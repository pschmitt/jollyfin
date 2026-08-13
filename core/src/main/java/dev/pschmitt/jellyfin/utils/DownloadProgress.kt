package dev.pschmitt.jellyfin.utils

import android.app.DownloadManager

/**
 * A continuous snapshot of a single download's transfer state, emitted by
 * [Downloader.getProgressFlow]. [status] uses the [DownloadManager].STATUS_* vocabulary for
 * consistency with the older one-shot [Downloader.getProgress] - note that here CANCELLED
 * WorkManager jobs are reported as STATUS_PAUSED rather than STATUS_FAILED, since a "pause" is
 * implemented as a plain WorkManager cancellation that leaves the partial file and DB row intact.
 */
data class DownloadProgress(
    val status: Int,
    val percent: Int = -1,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSecond: Long = 0L,
    val etaSeconds: Long = -1L,
    val pausedByBatterySaver: Boolean = false,
) {
    companion object {
        // Synthetic status, not a real DownloadManager constant (those are 1/2/4/8/16) - reported
        // once the file finishes downloading and is being re-read to compute/verify its checksum.
        const val STATUS_VERIFYING = 32

        // Synthetic status - the download service couldn't promote itself to the foreground right
        // now (app is fully backgrounded, Android refused the start) and is waiting for the app to
        // be foregrounded again (or, on API 34+, for the user-initiated-job backstop to wake it).
        const val STATUS_AWAITING_FOREGROUND = 33
    }
}
