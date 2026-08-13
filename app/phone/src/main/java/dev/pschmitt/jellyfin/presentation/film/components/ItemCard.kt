package dev.pschmitt.jellyfin.presentation.film.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.pschmitt.jellyfin.core.R
import dev.pschmitt.jellyfin.core.presentation.dummy.dummyEpisode
import dev.pschmitt.jellyfin.core.presentation.dummy.dummyMovie
import dev.pschmitt.jellyfin.models.JollyfinEpisode
import dev.pschmitt.jellyfin.models.JollyfinItem
import dev.pschmitt.jellyfin.models.QueueStatus
import dev.pschmitt.jellyfin.models.isDownloaded
import dev.pschmitt.jellyfin.models.isRecentlyAdded
import dev.pschmitt.jellyfin.presentation.theme.JollyfinTheme
import dev.pschmitt.jellyfin.presentation.theme.spacings

@Composable
fun ItemCard(
    item: JollyfinItem,
    direction: Direction,
    onClick: (JollyfinItem) -> Unit,
    modifier: Modifier = Modifier,
    queueStatus: QueueStatus? = null,
) {
    val width =
        when (direction) {
            Direction.HORIZONTAL -> 260
            Direction.VERTICAL -> 150
        }
    Column(
        modifier =
            modifier
                .width(width.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = { onClick(item) })
                .testTag("e2e-item-card")
    ) {
        Surface(shape = MaterialTheme.shapes.small) {
            Box {
                ItemPoster(item = item, direction = direction)
                Row(
                    modifier =
                        Modifier.align(Alignment.TopEnd).padding(MaterialTheme.spacings.small),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                ) {
                    if (!item.isDownloaded() && queueStatus != null) {
                        QueueBadge(status = queueStatus)
                    }
                    if (item.played) PlayedBadge()
                    item.unplayedItemCount?.takeIf { it > 0 }?.let { ItemCountBadge(it) }
                    if (!item.played && item.isRecentlyAdded()) NewBadge()
                }
                if (item.isDownloaded()) {
                    DownloadedBadge(
                        modifier =
                            Modifier.align(Alignment.BottomEnd)
                                .padding(MaterialTheme.spacings.small)
                    )
                }
                if (direction == Direction.HORIZONTAL) {
                    ProgressBar(
                        item = item,
                        width = width,
                        modifier =
                            Modifier.align(Alignment.BottomStart)
                                .padding(MaterialTheme.spacings.small),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
        Text(
            text = if (item is JollyfinEpisode) item.seriesName else item.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (item is JollyfinEpisode) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (item is JollyfinEpisode) {
            Text(
                text =
                    stringResource(
                        id = R.string.episode_name_extended,
                        item.parentIndexNumber,
                        item.indexNumber,
                        item.name,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun ItemCardPreviewMovie() {
    JollyfinTheme { ItemCard(item = dummyMovie, direction = Direction.HORIZONTAL, onClick = {}) }
}

@Preview(showBackground = true)
@Composable
private fun ItemCardPreviewMovieVertical() {
    JollyfinTheme { ItemCard(item = dummyMovie, direction = Direction.VERTICAL, onClick = {}) }
}

@Preview(showBackground = true)
@Composable
private fun ItemCardPreviewEpisode() {
    JollyfinTheme { ItemCard(item = dummyEpisode, direction = Direction.HORIZONTAL, onClick = {}) }
}

@Preview(showBackground = true)
@Composable
private fun ItemCardPreviewEpisodeVertical() {
    JollyfinTheme { ItemCard(item = dummyEpisode, direction = Direction.VERTICAL, onClick = {}) }
}
