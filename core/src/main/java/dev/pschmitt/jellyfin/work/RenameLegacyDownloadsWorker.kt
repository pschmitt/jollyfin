package dev.pschmitt.jellyfin.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pschmitt.jellyfin.utils.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Renames downloads stored under the old opaque `<itemId>.<sourceId>` names to readable ones (see
 * [Downloader.renameLegacyDownloads]). Enqueued on every app start: it's a cheap no-op once
 * everything is renamed, and re-running picks up downloads that were still in progress last time.
 */
@HiltWorker
class RenameLegacyDownloadsWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val downloader: Downloader,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val renamed = downloader.renameLegacyDownloads()
            if (renamed > 0) Timber.i("Renamed %d legacy download file(s)", renamed)
            Result.success()
        }

    companion object {
        private const val WORK_NAME = "renameLegacyDownloads"

        fun schedule(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<RenameLegacyDownloadsWorker>().build(),
                )
        }
    }
}
