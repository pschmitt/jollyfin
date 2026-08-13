package dev.pschmitt.jellyfin.work

import android.app.DownloadManager
import dev.pschmitt.jellyfin.utils.DownloadProgress
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Everything [VideoDownloadService] needs to transfer a single source. */
data class VideoDownloadRequest(
    val downloadId: Long,
    val sourceId: String,
    val sourceUrl: String,
    val destinationPath: String,
    val finalPath: String,
    val expectedSize: Long,
    val itemName: String,
)

/**
 * In-memory coordination point between [dev.pschmitt.jellyfin.utils.DownloaderImpl] (UI-facing
 * enqueue/cancel/progress calls) and [VideoDownloadService] (the actual transfer), replacing
 * WorkManager's WorkInfo/unique-work-name bookkeeping for video downloads specifically.
 *
 * WorkManager's own background rescheduling (retry backoff, PACKAGE_REPLACED, boot) is itself an
 * unpredictable-foreground-eligibility trigger - a worker retrying at 3am while the app is
 * backgrounded hits the exact same `ForegroundServiceStartNotAllowedException` as a cold start
 * would. This repository has no persistence of its own by design: the `sources` Room table (path
 * ending ".download") is what survives process death; [VideoDownloadService] and
 * [dev.pschmitt.jellyfin.utils.Downloader.reconcilePendingDownloads] re-derive pending requests
 * from that table and re-[enqueue] them here, only from moments guaranteed to be
 * foreground-eligible.
 */
@Singleton
class DownloadQueueRepository @Inject constructor() {
    private val requests = ConcurrentHashMap<String, VideoDownloadRequest>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())

    fun enqueue(request: VideoDownloadRequest) {
        requests[request.sourceId] = request
        setProgress(request.sourceId, DownloadProgress(status = DownloadManager.STATUS_PENDING))
    }

    fun pendingRequest(sourceId: String): VideoDownloadRequest? = requests[sourceId]

    /**
     * Requests that don't yet have a job tracked for them - what a (re)starting service should pick
     * up.
     */
    fun unclaimedRequests(): List<VideoDownloadRequest> =
        requests.values.filter { jobs[it.sourceId]?.isActive != true }

    fun trackJob(sourceId: String, job: Job) {
        jobs[sourceId] = job
    }

    fun hasActiveJob(sourceId: String): Boolean = jobs[sourceId]?.isActive == true

    fun hasWork(): Boolean = requests.isNotEmpty()

    /**
     * Used by pause/cancel - stops the transfer coroutine (if any) and drops the pending request.
     */
    fun cancel(sourceId: String, pausedByBatterySaver: Boolean = false) {
        jobs.remove(sourceId)?.cancel()
        requests.remove(sourceId)
        setProgress(
            sourceId,
            DownloadProgress(
                status = DownloadManager.STATUS_PAUSED,
                pausedByBatterySaver = pausedByBatterySaver,
            ),
        )
    }

    fun markFailed(sourceId: String) {
        jobs.remove(sourceId)
        requests.remove(sourceId)
        setProgress(sourceId, DownloadProgress(status = DownloadManager.STATUS_FAILED))
    }

    fun markSucceeded(sourceId: String) {
        jobs.remove(sourceId)
        requests.remove(sourceId)
        setProgress(
            sourceId,
            DownloadProgress(status = DownloadManager.STATUS_SUCCESSFUL, percent = 100),
        )
    }

    /**
     * The service couldn't get itself promoted to a foreground service right now (app is fully
     * backgrounded and there's no already-running instance to hand this off to) - leaves the
     * request pending so it resumes automatically the next time the app is foregrounded or the API
     * 34+ user-initiated job wakes us, but tells the UI to stop implying it's about to start.
     */
    fun markAwaitingForeground(sourceIds: Collection<String>) {
        sourceIds.forEach {
            setProgress(it, DownloadProgress(status = DownloadProgress.STATUS_AWAITING_FOREGROUND))
        }
    }

    fun updateProgress(sourceId: String, value: DownloadProgress) = setProgress(sourceId, value)

    fun progressFlow(sourceId: String): Flow<DownloadProgress> = progress.map {
        it[sourceId] ?: DownloadProgress(status = DownloadManager.STATUS_PAUSED)
    }

    private fun setProgress(sourceId: String, value: DownloadProgress) {
        progress.update { it + (sourceId to value) }
    }
}
