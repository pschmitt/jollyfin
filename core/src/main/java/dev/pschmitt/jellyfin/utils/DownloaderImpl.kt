package dev.pschmitt.jellyfin.utils

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.database.ServerDatabaseDao
import dev.pschmitt.jellyfin.models.JollyfinEpisode
import dev.pschmitt.jellyfin.models.JollyfinItem
import dev.pschmitt.jellyfin.models.JollyfinMovie
import dev.pschmitt.jellyfin.models.JollyfinSource
import dev.pschmitt.jellyfin.models.JollyfinSourceDto
import dev.pschmitt.jellyfin.models.JollyfinSourceType
import dev.pschmitt.jellyfin.models.JollyfinSources
import dev.pschmitt.jellyfin.models.JollyfinTrickplayInfo
import dev.pschmitt.jellyfin.models.UiText
import dev.pschmitt.jellyfin.models.toJollyfinEpisode
import dev.pschmitt.jellyfin.models.toJollyfinEpisodeDto
import dev.pschmitt.jellyfin.models.toJollyfinMediaStreamDto
import dev.pschmitt.jellyfin.models.toJollyfinMovie
import dev.pschmitt.jellyfin.models.toJollyfinMovieDto
import dev.pschmitt.jellyfin.models.toJollyfinSeasonDto
import dev.pschmitt.jellyfin.models.toJollyfinSegmentsDto
import dev.pschmitt.jellyfin.models.toJollyfinShowDto
import dev.pschmitt.jellyfin.models.toJollyfinSource
import dev.pschmitt.jellyfin.models.toJollyfinSourceDto
import dev.pschmitt.jellyfin.models.toJollyfinTrickplayInfoDto
import dev.pschmitt.jellyfin.models.toJollyfinUserDataDto
import dev.pschmitt.jellyfin.repository.JellyfinRepository
import dev.pschmitt.jellyfin.settings.domain.AppPreferences
import dev.pschmitt.jellyfin.work.DeleteDownloadsWorker
import dev.pschmitt.jellyfin.work.DownloadNotificationCoordinator
import dev.pschmitt.jellyfin.work.DownloadQueueRepository
import dev.pschmitt.jellyfin.work.DownloadSlotLimiter
import dev.pschmitt.jellyfin.work.ImagesDownloaderWorker
import dev.pschmitt.jellyfin.work.MigrateDownloadsWorker
import dev.pschmitt.jellyfin.work.ResumeDownloadsJobService
import dev.pschmitt.jellyfin.work.VideoDownloadRequest
import dev.pschmitt.jellyfin.work.VideoDownloadService
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.Exception
import kotlin.math.ceil
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import timber.log.Timber

class DownloaderImpl(
    private val context: Context,
    private val database: ServerDatabaseDao,
    private val jellyfinRepository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val workManager: WorkManager,
    private val downloadQueueRepository: DownloadQueueRepository,
) : Downloader {
    private val downloadManager = context.getSystemService(DownloadManager::class.java)

    // TODO: We should probably move most (if not all) code to a worker.
    //  At this moment it is possible that some things are not downloaded due to the user leaving
    //  the current screen
    override suspend fun downloadItem(
        item: JollyfinItem,
        sourceId: String,
        storageIndex: Int,
    ): Pair<Long, UiText?> = coroutineScope {
        try {
            val source =
                jellyfinRepository.getMediaSources(item.id, true).first { it.id == sourceId }
            val segments = jellyfinRepository.getSegments(item.id)
            val trickplayInfo =
                if (item is JollyfinSources) {
                    item.trickplayInfo?.get(sourceId)
                } else {
                    null
                }
            val storageLocation = context.getExternalFilesDirs(null)[storageIndex]
            if (
                storageLocation == null ||
                    Environment.getExternalStorageState(storageLocation) !=
                        Environment.MEDIA_MOUNTED
            ) {
                return@coroutineScope Pair(
                    -1,
                    UiText.StringResource(CoreR.string.storage_unavailable),
                )
            }
            val path =
                Uri.fromFile(File(storageLocation, "downloads/${item.id}.${source.id}.download"))
            val stats = StatFs(storageLocation.path)
            if (stats.availableBytes < source.size) {
                return@coroutineScope Pair(
                    -1,
                    UiText.StringResource(
                        CoreR.string.not_enough_storage,
                        formatBinaryFileSize(source.size),
                        formatBinaryFileSize(stats.availableBytes),
                    ),
                )
            }
            // The primary source is streamed by our own VideoDownloadService rather than
            // DownloadManager - see the class doc on VideoDownloadService for why. downloadId is
            // now a synthetic, locally-unique 64-bit id used purely as a Room lookup key; it no
            // longer comes from DownloadManager.enqueue().
            val downloadId = UUID.randomUUID().mostSignificantBits
            val finalPath = path.path.orEmpty().replace(".download", "")

            when (item) {
                is JollyfinMovie -> {
                    database.insertMovie(
                        item.toJollyfinMovieDto(
                            appPreferences.getValue(appPreferences.currentServer)
                        )
                    )
                }
                is JollyfinEpisode -> {
                    val show = jellyfinRepository.getShow(item.seriesId)
                    database.insertShow(
                        show.toJollyfinShowDto(
                            appPreferences.getValue(appPreferences.currentServer)
                        )
                    )
                    val season = jellyfinRepository.getSeason(item.seasonId)
                    database.insertSeason(season.toJollyfinSeasonDto())
                    database.insertEpisode(
                        item.toJollyfinEpisodeDto(
                            appPreferences.getValue(appPreferences.currentServer)
                        )
                    )

                    startImagesDownloader(show)
                    startImagesDownloader(season)
                }
            }

            val sourceDto = source.toJollyfinSourceDto(item.id, path.path.orEmpty())

            database.insertSource(sourceDto.copy(downloadId = downloadId))
            database.insertUserData(item.toJollyfinUserDataDto(jellyfinRepository.getUserId()))

            // Enqueue only after the sources row exists - VideoDownloadService updates that row by
            // id on completion, so it must not race the insert above.
            enqueueVideoDownload(
                downloadId = downloadId,
                sourceId = source.id,
                sourceUrl = source.path,
                destinationPath = path.path.orEmpty(),
                finalPath = finalPath,
                expectedSize = source.size,
                itemName = downloadDisplayName(item),
            )

            downloadExternalMediaStreams(item, source, storageIndex)

            segments.forEach { database.insertSegment(it.toJollyfinSegmentsDto(item.id)) }

            if (trickplayInfo != null) {
                downloadTrickplayData(item.id, sourceId, trickplayInfo)
            }

            startImagesDownloader(item)
            return@coroutineScope Pair(downloadId, null)
        } catch (e: Exception) {
            try {
                val source = jellyfinRepository.getMediaSources(item.id).first { it.id == sourceId }
                deleteItem(item, source)
            } catch (_: Exception) {}
            Timber.e(e)
            return@coroutineScope Pair(
                -1,
                if (e.message != null) UiText.DynamicString(e.message!!)
                else UiText.StringResource(CoreR.string.unknown_error),
            )
        }
    }

    override suspend fun cancelDownload(downloadId: Long) {
        val sourceDto = database.getSourceByDownloadId(downloadId) ?: return
        downloadQueueRepository.cancel(sourceDto.id)

        val item = findJollyfinItem(sourceDto.itemId)
        if (item == null) {
            // The movie/episode row is already gone - fall back to just cleaning up the source
            // row and the partial file directly, since deleteItem() needs the item's type to
            // cascade into season/show cleanup.
            Timber.e(
                "cancelDownload: no JollyfinItem found for source ${sourceDto.id}, cleaning up source only"
            )
            database.deleteSource(sourceDto.id)
            File(sourceDto.path).delete()
            return
        }
        deleteItem(item, sourceDto.toJollyfinSource(database))
    }

    override suspend fun pauseDownload(downloadId: Long) {
        val sourceDto = database.getSourceByDownloadId(downloadId) ?: return
        downloadQueueRepository.cancel(sourceDto.id)
    }

    override suspend fun pauseAllForBatterySaver() {
        for (sourceDto in database.getAllSources()) {
            if (sourceDto.downloadId == null) continue
            if (downloadQueueRepository.pendingRequest(sourceDto.id) != null) {
                downloadQueueRepository.cancel(sourceDto.id, pausedByBatterySaver = true)
                database.setSourcePausedByBatterySaver(sourceDto.id, true)
            }
        }
    }

    override suspend fun resumeBatterySaverPausedDownloads() {
        for (sourceDto in database.getAllSources()) {
            val downloadId = sourceDto.downloadId
            if (!sourceDto.pausedByBatterySaver) continue
            if (downloadId == null) {
                database.setSourcePausedByBatterySaver(sourceDto.id, false)
                continue
            }
            if (resumeDownload(downloadId) == null) {
                database.setSourcePausedByBatterySaver(sourceDto.id, false)
            }
        }
    }

    override suspend fun forceDownload(downloadId: Long) {
        forceStart(downloadId)
    }

    override suspend fun forceDownloadGroup(downloadIds: List<Long>) {
        if (downloadIds.isEmpty()) return
        val sourceIds = downloadIds.mapNotNull { database.getSourceByDownloadId(it)?.id }
        DownloadSlotLimiter.prioritize(sourceIds)
        // The rest just got moved to the front of the queue and will be picked up next as slots
        // free naturally; only the first one is force-started immediately.
        forceStart(downloadIds.first())
    }

    private suspend fun forceStart(downloadId: Long) {
        val sourceDto = database.getSourceByDownloadId(downloadId) ?: return
        val promoted = DownloadSlotLimiter.forcePromote(sourceDto.id)
        if (promoted) {
            DownloadNotificationCoordinator.runningDownloadIds()
                .firstOrNull { it != downloadId }
                ?.let { victimDownloadId -> pauseDownload(victimDownloadId) }
        }
    }

    override suspend fun resumeDownload(downloadId: Long): UiText? {
        val sourceDto =
            database.getSourceByDownloadId(downloadId)
                ?: return UiText.StringResource(CoreR.string.unknown_error)
        return try {
            val remoteSource =
                jellyfinRepository.getMediaSources(sourceDto.itemId, true).firstOrNull {
                    it.id == sourceDto.id
                } ?: return UiText.StringResource(CoreR.string.unknown_error)

            val itemName =
                findJollyfinItem(sourceDto.itemId)?.let { downloadDisplayName(it) }
                    ?: sourceDto.name
            val finalPath = sourceDto.path.replace(".download", "")

            enqueueVideoDownload(
                downloadId = downloadId,
                sourceId = remoteSource.id,
                sourceUrl = remoteSource.path,
                destinationPath = sourceDto.path,
                finalPath = finalPath,
                expectedSize = remoteSource.size,
                itemName = itemName,
            )
            null
        } catch (e: Exception) {
            Timber.e(e)
            if (e.message != null) UiText.DynamicString(e.message!!)
            else UiText.StringResource(CoreR.string.unknown_error)
        }
    }

    // For episodes, the episode title alone doesn't say which show/season it's from - show that
    // instead so concurrent downloads are distinguishable in the notification and Downloads page.
    private fun downloadDisplayName(item: JollyfinItem): String =
        when (item) {
            is JollyfinEpisode ->
                "${item.seriesName} • S${item.parentIndexNumber}E${item.indexNumber}"
            else -> item.name
        }

    private fun findJollyfinItem(itemId: UUID): JollyfinItem? {
        val userId = jellyfinRepository.getUserId()
        return try {
            database.getMovie(itemId).toJollyfinMovie(database, userId)
        } catch (_: Exception) {
            try {
                database.getEpisode(itemId).toJollyfinEpisode(database, userId)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun enqueueVideoDownload(
        downloadId: Long,
        sourceId: String,
        sourceUrl: String,
        destinationPath: String,
        finalPath: String,
        expectedSize: Long,
        itemName: String,
    ) {
        downloadQueueRepository.enqueue(
            VideoDownloadRequest(
                downloadId = downloadId,
                sourceId = sourceId,
                sourceUrl = sourceUrl,
                destinationPath = destinationPath,
                finalPath = finalPath,
                expectedSize = expectedSize,
                itemName = itemName,
            )
        )
        ensureDownloadServiceStarted()
    }

    // Only ever called from moments guaranteed to be foreground-eligible - a direct UI action
    // (downloadItem/resumeDownload/forceDownload), or ForegroundDownloadResumer's ON_START check.
    // If Android still refuses (e.g. the app was backgrounded in the split second between the
    // trigger and this call), leave the request queued and let the API 34+ user-initiated job
    // backstop or the next app foreground pick it up - see the "Real fix for stuck background
    // downloads" plan for why VideoDownloadService/this call exist at all.
    private fun ensureDownloadServiceStarted() {
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VideoDownloadService::class.java),
            )
        } catch (e: Exception) {
            Timber.w(e, "Could not start VideoDownloadService right now")
            ResumeDownloadsJobService.schedule(context)
        }
    }

    override suspend fun deleteItem(item: JollyfinItem, source: JollyfinSource) {
        when (item) {
            is JollyfinMovie -> {
                database.deleteMovie(item.id)
            }
            is JollyfinEpisode -> {
                database.deleteEpisode(item.id)
                val remainingEpisodes = database.getEpisodesBySeasonId(item.seasonId)
                if (remainingEpisodes.isEmpty()) {
                    database.deleteSeason(item.seasonId)
                    database.deleteUserData(item.seasonId)
                    File(context.filesDir, "trickplay/${item.seasonId}").deleteRecursively()
                    File(context.filesDir, "images/${item.seasonId}").deleteRecursively()
                    val remainingSeasons = database.getSeasonsByShowId(item.seriesId)
                    if (remainingSeasons.isEmpty()) {
                        database.deleteShow(item.seriesId)
                        database.deleteUserData(item.seriesId)
                        File(context.filesDir, "trickplay/${item.seriesId}").deleteRecursively()
                        File(context.filesDir, "images/${item.seriesId}").deleteRecursively()
                    }
                }
            }
        }

        database.deleteSource(source.id)
        File(source.path).delete()

        val mediaStreams = database.getMediaStreamsBySourceId(source.id)
        for (mediaStream in mediaStreams) {
            File(mediaStream.path).delete()
        }
        database.deleteMediaStreamsBySourceId(source.id)

        database.deleteUserData(item.id)

        File(context.filesDir, "trickplay/${item.id}").deleteRecursively()
        File(context.filesDir, "images/${item.id}").deleteRecursively()
    }

    override suspend fun moveDownloads(
        fromStorageIndex: Int,
        toStorageIndex: Int,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ) {
        val storageLocations = context.getExternalFilesDirs(null)
        val fromDir = storageLocations.getOrNull(fromStorageIndex) ?: return
        val toDir = storageLocations.getOrNull(toStorageIndex) ?: return
        if (fromDir.path == toDir.path) return

        val sources =
            database.getAllSources().filter {
                it.type == JollyfinSourceType.LOCAL && it.path.startsWith(fromDir.path)
            }

        sources.forEachIndexed { index, sourceDto ->
            try {
                moveSourceFiles(sourceDto, fromDir, toDir)
            } catch (e: Exception) {
                Timber.e(e, "Failed to move download ${sourceDto.id} to new storage location")
            }
            onProgress(index + 1, sources.size)
        }
    }

    override suspend fun moveItems(
        itemIds: List<UUID>,
        toStorageIndex: Int,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ) {
        if (itemIds.isEmpty()) return
        val storageLocations = context.getExternalFilesDirs(null)
        val toDir = storageLocations.getOrNull(toStorageIndex) ?: return
        val mountedDirs = storageLocations.filterNotNull()

        val sources =
            database.getSourcesForItems(itemIds).filter {
                it.type == JollyfinSourceType.LOCAL && !it.path.startsWith(toDir.path)
            }

        sources.forEachIndexed { index, sourceDto ->
            try {
                // A selected item could in principle already be on a *different* volume than the
                // one most downloads happen to live on, so this is resolved per-source rather than
                // assuming a single shared fromDir the way the whole-volume moveDownloads() can.
                val fromDir = mountedDirs.firstOrNull { sourceDto.path.startsWith(it.path) }
                if (fromDir != null) {
                    moveSourceFiles(sourceDto, fromDir, toDir)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to migrate download ${sourceDto.id} to new storage location")
            }
            onProgress(index + 1, sources.size)
        }
    }

    override suspend fun migrateItems(itemIds: List<UUID>, toStorageIndex: Int) {
        if (itemIds.isEmpty()) return
        val request =
            OneTimeWorkRequestBuilder<MigrateDownloadsWorker>()
                .setInputData(
                    workDataOf(
                        MigrateDownloadsWorker.KEY_ITEM_IDS to
                            itemIds.map { it.toString() }.toTypedArray(),
                        MigrateDownloadsWorker.KEY_TO_STORAGE_INDEX to toStorageIndex,
                    )
                )
                .build()
        // APPEND (not KEEP/REPLACE) for the same reason as deleteItems(): a migrate triggered
        // while an earlier batch is still running queues after it instead of being dropped.
        workManager.enqueueUniqueWork(
            MIGRATE_DOWNLOADS_WORK_NAME,
            ExistingWorkPolicy.APPEND,
            request,
        )
    }

    override fun getMigrateProgressFlow(): Flow<MigrateProgress?> {
        return workManager.getWorkInfosForUniqueWorkFlow(MIGRATE_DOWNLOADS_WORK_NAME).map { infos ->
            val active = infos.firstOrNull { !it.state.isFinished } ?: return@map null
            MigrateProgress(
                done = active.progress.getInt(MigrateDownloadsWorker.KEY_DONE, 0),
                total = active.progress.getInt(MigrateDownloadsWorker.KEY_TOTAL, 0),
            )
        }
    }

    /** Shared per-source move: the file itself plus any external media stream files. */
    private fun moveSourceFiles(sourceDto: JollyfinSourceDto, fromDir: File, toDir: File) {
        moveFile(File(sourceDto.path), fromDir, toDir, expectedChecksum = sourceDto.checksum)
            ?.let { newPath -> database.setSourcePath(sourceDto.id, newPath) }
        for (mediaStream in database.getMediaStreamsBySourceId(sourceDto.id)) {
            moveFile(File(mediaStream.path), fromDir, toDir)?.let { newPath ->
                database.setMediaStreamPath(mediaStream.id, newPath)
            }
        }
    }

    override suspend fun clearDownloads(
        fromStorageIndex: Int,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ) {
        val fromDir = context.getExternalFilesDirs(null).getOrNull(fromStorageIndex) ?: return

        val sources =
            database.getAllSources().filter {
                it.type == JollyfinSourceType.LOCAL && it.path.startsWith(fromDir.path)
            }

        sources.forEachIndexed { index, sourceDto ->
            try {
                val item = findJollyfinItem(sourceDto.itemId)
                if (item != null) {
                    deleteItem(item, sourceDto.toJollyfinSource(database))
                } else {
                    // No JollyfinItem left for this source (e.g. orphaned row) - deleteItem()
                    // needs the item's type to cascade into season/show cleanup, so fall back to
                    // just cleaning up the source row and its files directly, same as the
                    // equivalent fallback in cancelDownload().
                    Timber.e(
                        "clearDownloads: no JollyfinItem found for source ${sourceDto.id}, cleaning up source only"
                    )
                    database.deleteSource(sourceDto.id)
                    File(sourceDto.path).delete()
                    val mediaStreams = database.getMediaStreamsBySourceId(sourceDto.id)
                    for (mediaStream in mediaStreams) {
                        File(mediaStream.path).delete()
                    }
                    database.deleteMediaStreamsBySourceId(sourceDto.id)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to clear download ${sourceDto.id}")
            }
            onProgress(index + 1, sources.size)
        }
    }

    /**
     * Copies [oldFile] (which must live under [fromDir]) to the equivalent relative path under
     * [toDir], verifies the copy, deletes the original, and returns the new path. Uses copy+delete
     * rather than [File.renameTo] since the two storage volumes here are typically different
     * filesystems, and renameTo silently fails (returns false) across filesystems on some platforms
     * rather than falling back to a copy.
     *
     * When [expectedChecksum] is available (the primary video file, once downloaded with a checksum
     * recorded - see VideoDownloadService), the copy is verified by SHA-256 computed in the same
     * pass as the copy, not just a length check. Media stream files and sources downloaded before
     * checksums existed fall back to the length-only check.
     */
    private fun moveFile(
        oldFile: File,
        fromDir: File,
        toDir: File,
        expectedChecksum: String? = null,
    ): String? {
        if (!oldFile.exists()) return null
        val relativePath = oldFile.path.removePrefix(fromDir.path).trimStart(File.separatorChar)
        val newFile = File(toDir, relativePath)
        newFile.parentFile?.mkdirs()

        if (expectedChecksum != null) {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(oldFile).use { input ->
                FileOutputStream(newFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                }
            }
            val actualChecksum = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualChecksum != expectedChecksum) {
                newFile.delete()
                throw IOException("Checksum mismatch after moving ${oldFile.path}")
            }
        } else {
            oldFile.copyTo(newFile, overwrite = true)
            if (newFile.length() != oldFile.length()) {
                newFile.delete()
                throw IOException("Copied file size mismatch for ${oldFile.path}")
            }
        }
        oldFile.delete()
        return newFile.path
    }

    override suspend fun deleteItems(itemIds: List<UUID>) {
        if (itemIds.isEmpty()) return
        val request =
            OneTimeWorkRequestBuilder<DeleteDownloadsWorker>()
                .setInputData(
                    workDataOf(
                        DeleteDownloadsWorker.KEY_ITEM_IDS to
                            itemIds.map { it.toString() }.toTypedArray()
                    )
                )
                .build()
        // APPEND (not KEEP/REPLACE) so a delete triggered while an earlier batch is still running
        // queues after it instead of being dropped or clobbering the in-flight one.
        workManager.enqueueUniqueWork(
            DELETE_DOWNLOADS_WORK_NAME,
            ExistingWorkPolicy.APPEND,
            request,
        )
    }

    override fun getDeleteProgressFlow(): Flow<DeleteProgress?> {
        return workManager.getWorkInfosForUniqueWorkFlow(DELETE_DOWNLOADS_WORK_NAME).map { infos ->
            val active = infos.firstOrNull { !it.state.isFinished } ?: return@map null
            DeleteProgress(
                done = active.progress.getInt(DeleteDownloadsWorker.KEY_DONE, 0),
                total = active.progress.getInt(DeleteDownloadsWorker.KEY_TOTAL, 0),
            )
        }
    }

    override fun getAllStorageStats(): List<DeviceStorageStats> {
        // No Environment.getExternalStorageState() gate here, unlike downloadItem()'s pre-write
        // check above - that API is meant for the classic public external storage root, and is
        // unreliable for an app-specific getExternalFilesDirs() subdirectory (spuriously reports
        // not-mounted here on some devices). ItemButtonsBar's storage-picker calls StatFs the same
        // direct way for the same reason.
        return context.getExternalFilesDirs(null).mapNotNull { storageLocation ->
            if (storageLocation == null) return@mapNotNull null
            try {
                val stats = StatFs(storageLocation.path)
                DeviceStorageStats(
                    path = storageLocation.path,
                    totalBytes = stats.blockCountLong * stats.blockSizeLong,
                    availableBytes = stats.availableBlocksLong * stats.blockSizeLong,
                    isRemovable = Environment.isExternalStorageRemovable(storageLocation),
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to read device storage stats for %s", storageLocation.path)
                null
            }
        }
    }

    override fun getTotalDownloadedBytes(): Long {
        return database
            .getAllSources()
            .filter { it.type == JollyfinSourceType.LOCAL }
            .sumOf { File(it.path).length() }
    }

    override fun resolvePreferredStorageIndex(): Int {
        val preference = appPreferences.getValue(appPreferences.downloadLocation)
        val resolved = resolveDownloadStorageIndex(context, preference)
        return if (resolved >= 0) resolved else 0
    }

    override fun getProgressFlow(downloadId: Long): Flow<DownloadProgress> {
        val sourceId =
            database.getSourceByDownloadId(downloadId)?.id
                ?: return flowOf(DownloadProgress(status = DownloadManager.STATUS_FAILED))
        return downloadQueueRepository.progressFlow(sourceId)
    }

    // Re-adopts every LOCAL source still mid-transfer (path ending ".download") that doesn't have
    // an active transfer job right now - the app was killed mid-download, VideoDownloadService
    // itself was killed while backgrounded with no live job for it, or (the case this exists to
    // catch) the service never even got to start in the first place - e.g. its
    // startForegroundService() call was silently disallowed while backgrounded, leaving the
    // request sitting unclaimed in downloadQueueRepository forever with nothing consuming it.
    // Checking hasActiveJob() rather than pendingRequest() != null matters here: a request can
    // exist in that in-memory map with no job ever having claimed it, which is exactly this stuck
    // state - checking mere existence would wrongly treat it as already being handled. Only ever
    // called from ForegroundDownloadResumer's ON_START check (a guaranteed foreground-eligible
    // moment) - see the "Real fix for stuck background downloads" plan.
    override suspend fun reconcilePendingDownloads() {
        for (sourceDto in database.getAllSources()) {
            val downloadId = sourceDto.downloadId ?: continue
            if (sourceDto.pausedByBatterySaver) continue
            if (!sourceDto.path.endsWith(".download")) continue
            if (downloadQueueRepository.hasActiveJob(sourceDto.id)) continue
            resumeDownload(downloadId)
        }
    }

    private fun downloadExternalMediaStreams(
        item: JollyfinItem,
        source: JollyfinSource,
        storageIndex: Int = 0,
    ) {
        val storageLocation = context.getExternalFilesDirs(null)[storageIndex]
        for (mediaStream in source.mediaStreams.filter { it.isExternal }) {
            val id = UUID.randomUUID()
            val streamPath =
                Uri.fromFile(
                    File(storageLocation, "downloads/${item.id}.${source.id}.$id.download")
                )
            database.insertMediaStream(
                mediaStream.toJollyfinMediaStreamDto(id, source.id, streamPath.path.orEmpty())
            )
            val request =
                DownloadManager.Request(mediaStream.path!!.toUri())
                    .setTitle(mediaStream.title)
                    .setAllowedOverMetered(
                        appPreferences.getValue(appPreferences.downloadOverMobileData)
                    )
                    .setAllowedOverRoaming(
                        appPreferences.getValue(appPreferences.downloadWhenRoaming)
                    )
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                    .setDestinationUri(streamPath)
            val downloadId = downloadManager.enqueue(request)
            database.setMediaStreamDownloadId(id, downloadId)
        }
    }

    private suspend fun downloadTrickplayData(
        itemId: UUID,
        sourceId: String,
        trickplayInfo: JollyfinTrickplayInfo,
    ) {
        val maxIndex =
            ceil(
                    trickplayInfo.thumbnailCount
                        .toDouble()
                        .div(trickplayInfo.tileWidth * trickplayInfo.tileHeight)
                )
                .toInt()
        val byteArrays = mutableListOf<ByteArray>()
        for (i in 0..maxIndex) {
            jellyfinRepository.getTrickplayData(itemId, trickplayInfo.width, i)?.let { byteArray ->
                byteArrays.add(byteArray)
            }
        }
        saveTrickplayData(itemId, sourceId, trickplayInfo, byteArrays)
    }

    private fun saveTrickplayData(
        itemId: UUID,
        sourceId: String,
        trickplayInfo: JollyfinTrickplayInfo,
        byteArrays: List<ByteArray>,
    ) {
        val basePath = "trickplay/$itemId/$sourceId"
        database.insertTrickplayInfo(trickplayInfo.toJollyfinTrickplayInfoDto(sourceId))
        File(context.filesDir, basePath).mkdirs()
        for ((i, byteArray) in byteArrays.withIndex()) {
            val file = File(context.filesDir, "$basePath/$i")
            file.writeBytes(byteArray)
        }
    }

    private fun startImagesDownloader(item: JollyfinItem) {
        val downloadImagesRequest =
            OneTimeWorkRequestBuilder<ImagesDownloaderWorker>()
                .setInputData(workDataOf(ImagesDownloaderWorker.KEY_ITEM_ID to item.id.toString()))
                .build()

        workManager.enqueue(downloadImagesRequest)
    }

    companion object {
        private const val DELETE_DOWNLOADS_WORK_NAME = "deleteDownloads"
        private const val MIGRATE_DOWNLOADS_WORK_NAME = "migrateDownloads"
    }
}
