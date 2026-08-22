package dev.pschmitt.jellyfin.player.local.domain

import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import dev.pschmitt.jellyfin.models.JollyfinChapter
import dev.pschmitt.jellyfin.models.JollyfinEpisode
import dev.pschmitt.jellyfin.models.JollyfinItem
import dev.pschmitt.jellyfin.models.JollyfinMovie
import dev.pschmitt.jellyfin.models.JollyfinSource
import dev.pschmitt.jellyfin.models.JollyfinSourceType
import dev.pschmitt.jellyfin.models.JollyfinSources
import dev.pschmitt.jellyfin.player.core.domain.models.ExternalSubtitle
import dev.pschmitt.jellyfin.player.core.domain.models.PlayerChapter
import dev.pschmitt.jellyfin.player.core.domain.models.PlayerItem
import dev.pschmitt.jellyfin.player.core.domain.models.TrickplayInfo
import dev.pschmitt.jellyfin.repository.JellyfinRepository
import java.io.File
import java.util.UUID
import javax.inject.Inject
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber

class PlaylistManager @Inject internal constructor(private val repository: JellyfinRepository) {
    private var startItem: JollyfinItem? = null
    private var items: List<JollyfinItem> = emptyList()
    private val playerItems: MutableList<PlayerItem> = mutableListOf()
    var currentItemIndex: Int = 0

    suspend fun getInitialItem(
        itemId: UUID,
        itemKind: BaseItemKind,
        mediaSourceIndex: Int? = null,
        startFromBeginning: Boolean = false,
    ): PlayerItem? {
        Timber.d("Retrieving initial player item")

        val initialItem =
            when (itemKind) {
                BaseItemKind.MOVIE -> {
                    val movie = repository.getMovie(itemId)

                    items = listOf(movie)
                    movie
                }
                BaseItemKind.SERIES -> {
                    val nextUpEpisode = repository.getNextUp(itemId).firstOrNull()

                    val season =
                        if (nextUpEpisode != null) {
                            repository.getSeason(nextUpEpisode.seasonId)
                        } else {
                            val seasons = repository.getSeasons(itemId)
                            if (seasons.isEmpty()) {
                                return null
                            }
                            seasons.first()
                        }

                    val episodes =
                        repository
                            .getEpisodes(
                                seriesId = itemId,
                                seasonId = season.id,
                                fields = listOf(ItemFields.CHAPTERS, ItemFields.TRICKPLAY),
                            )
                            .filter { !it.missing }

                    if (episodes.isEmpty()) {
                        return null
                    }

                    val episode = nextUpEpisode ?: episodes.first()

                    items = episodes
                    episode
                }
                BaseItemKind.SEASON -> {
                    val season = repository.getSeason(itemId)
                    val episodes =
                        repository
                            .getEpisodes(
                                seriesId = season.seriesId,
                                seasonId = season.id,
                                fields = listOf(ItemFields.CHAPTERS, ItemFields.TRICKPLAY),
                            )
                            .filter { !it.missing }

                    if (episodes.isEmpty()) {
                        return null
                    }

                    val episode = episodes.first()

                    items = episodes
                    episode
                }
                BaseItemKind.EPISODE -> {
                    val episode = repository.getEpisode(itemId)

                    val episodes =
                        repository
                            .getEpisodes(
                                seriesId = episode.seriesId,
                                seasonId = episode.seasonId,
                                fields = listOf(ItemFields.CHAPTERS, ItemFields.TRICKPLAY),
                            )
                            .filter { !it.missing }

                    items = episodes
                    episode
                }
                else -> null
            }

        if (initialItem == null) {
            return null
        }

        startItem = initialItem

        currentItemIndex = items.indexOfFirst { it.id == initialItem.id }

        val playbackPosition =
            if (!startFromBeginning) initialItem.playbackPositionTicks.div(10000) else 0
        val playerItem = initialItem.toPlayerItem(mediaSourceIndex, playbackPosition)
        playerItems.add(playerItem)

        return playerItem
    }

    suspend fun getPreviousPlayerItem(): PlayerItem? {
        Timber.d("Retrieving previous player item")

        val itemIndex = currentItemIndex - 1
        val playerItem =
            when (startItem) {
                is JollyfinMovie -> null
                is JollyfinEpisode -> {
                    if (currentItemIndex == 0) {
                        null
                    } else {
                        val item = items[itemIndex]
                        if (playerItems.firstOrNull { it.itemId == item.id } == null) {
                            try {
                                item.toPlayerItem(null, 0L)
                            } catch (e: Exception) {
                                Timber.e("Failed to retrieve previous player item: $e")
                                null
                            }
                        } else {
                            null
                        }
                    }
                }
                else -> null
            }

        if (playerItem != null) {
            playerItems.add(playerItem)
        }

        return playerItem
    }

    suspend fun getNextPlayerItem(): PlayerItem? {
        Timber.d("Retrieving next player item")

        val itemIndex = currentItemIndex + 1
        val playerItem =
            when (startItem) {
                is JollyfinMovie -> null
                is JollyfinEpisode -> {
                    if (currentItemIndex == items.lastIndex) {
                        null
                    } else {
                        val item = items[itemIndex]
                        if (playerItems.firstOrNull { it.itemId == item.id } == null) {
                            try {
                                item.toPlayerItem(null, 0L)
                            } catch (e: Exception) {
                                Timber.e("Failed to retrieve next player item: $e")
                                null
                            }
                        } else {
                            null
                        }
                    }
                }
                else -> null
            }

        if (playerItem != null) {
            playerItems.add(playerItem)
        }

        return playerItem
    }

    fun setCurrentMediaItemIndex(itemId: UUID) {
        currentItemIndex = items.indexOfFirst { it.id == itemId }
    }

    private suspend fun JollyfinItem.toPlayerItem(
        mediaSourceIndex: Int?,
        playbackPosition: Long,
    ): PlayerItem {
        Timber.d("Converting JollyfinItem ${this.id} to PlayerItem")

        val mediaSources = repository.getMediaSources(id, true)
        val mediaSource = selectPlayableMediaSource(mediaSources, mediaSourceIndex)
        val externalSubtitles =
            mediaSource.mediaStreams
                .filter { mediaStream ->
                    mediaStream.isExternal &&
                        mediaStream.type == MediaStreamType.SUBTITLE &&
                        !mediaStream.path.isNullOrBlank()
                }
                .map { mediaStream ->
                    ExternalSubtitle(
                        mediaStream.title,
                        mediaStream.language,
                        mediaStream.path!!.toUri(),
                        when (mediaStream.codec) {
                            "subrip" -> MimeTypes.APPLICATION_SUBRIP
                            "webvtt" -> MimeTypes.APPLICATION_SUBRIP
                            "ass" -> MimeTypes.TEXT_SSA
                            else -> MimeTypes.TEXT_UNKNOWN
                        },
                    )
                }
        val trickplayInfo =
            when (this) {
                is JollyfinSources -> {
                    this.trickplayInfo?.get(mediaSource.id)?.let {
                        TrickplayInfo(
                            width = it.width,
                            height = it.height,
                            tileWidth = it.tileWidth,
                            tileHeight = it.tileHeight,
                            thumbnailCount = it.thumbnailCount,
                            interval = it.interval,
                            bandwidth = it.bandwidth,
                        )
                    }
                }
                else -> null
            }
        return PlayerItem(
            name = name,
            itemId = id,
            mediaSourceId = mediaSource.id,
            mediaSourceUri = mediaSource.path,
            playbackPosition = playbackPosition,
            parentIndexNumber = if (this is JollyfinEpisode) parentIndexNumber else null,
            indexNumber = if (this is JollyfinEpisode) indexNumber else null,
            indexNumberEnd = if (this is JollyfinEpisode) indexNumberEnd else null,
            externalSubtitles = externalSubtitles,
            chapters = chapters.toPlayerChapters(),
            trickplayInfo = trickplayInfo,
        )
    }

    private fun List<JollyfinChapter>.toPlayerChapters(): List<PlayerChapter> {
        return this.map { chapter ->
            PlayerChapter(startPosition = chapter.startPosition, name = chapter.name)
        }
    }
}

internal fun selectPlayableMediaSource(
    mediaSources: List<JollyfinSource>,
    mediaSourceIndex: Int?,
): JollyfinSource {
    val localSource = mediaSources.firstOrNull {
        it.type == JollyfinSourceType.LOCAL && it.isPlayable()
    }
    val remoteSource = mediaSources.firstOrNull {
        it.type == JollyfinSourceType.REMOTE && it.isPlayable()
    }

    return if (mediaSourceIndex != null) {
        mediaSources.getOrNull(mediaSourceIndex)?.takeIf { it.isPlayable() }
            ?: localSource
            ?: remoteSource
            ?: error("No playable media source")
    } else {
        localSource ?: remoteSource ?: error("No playable media source")
    }
}

private fun JollyfinSource.isPlayable(): Boolean =
    when (type) {
        JollyfinSourceType.LOCAL ->
            !path.endsWith(".download") && File(path).isFile && File(path).length() > 0L
        JollyfinSourceType.REMOTE -> path.isNotBlank()
    }
