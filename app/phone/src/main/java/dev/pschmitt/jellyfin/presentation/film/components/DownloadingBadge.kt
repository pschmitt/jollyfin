package dev.pschmitt.jellyfin.presentation.film.components

import android.app.DownloadManager
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.presentation.theme.JollyfinTheme
import dev.pschmitt.jellyfin.utils.DownloadProgress

/** Shown over an episode's poster while it's queued, downloading, or paused. */
@Composable
fun DownloadingBadge(
    progress: DownloadProgress,
    modifier: Modifier = Modifier,
    isPausedByBatterySaver: Boolean = false,
) {
    BaseBadge(modifier = modifier) {
        when (progress.status) {
            DownloadManager.STATUS_PENDING ->
                Text(
                    text = stringResource(CoreR.string.download_queued),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            DownloadManager.STATUS_PAUSED ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(CoreR.string.download_paused),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (isPausedByBatterySaver || progress.pausedByBatterySaver) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_battery),
                            contentDescription =
                                stringResource(CoreR.string.download_paused_by_battery_saver),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(start = 4.dp).size(14.dp),
                        )
                    }
                }
            else ->
                if (progress.percent >= 0) {
                    Text(
                        text = "${progress.percent}%",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                } else {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_download),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(4.dp),
                    )
                }
        }
    }
}

@Composable
@Preview
private fun DownloadingBadgeQueuedPreview() {
    JollyfinTheme {
        DownloadingBadge(progress = DownloadProgress(status = DownloadManager.STATUS_PENDING))
    }
}

@Composable
@Preview
private fun DownloadingBadgeProgressPreview() {
    JollyfinTheme {
        DownloadingBadge(
            progress = DownloadProgress(status = DownloadManager.STATUS_RUNNING, percent = 42)
        )
    }
}
