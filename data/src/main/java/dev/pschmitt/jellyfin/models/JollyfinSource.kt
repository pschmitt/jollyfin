package dev.pschmitt.jellyfin.models

import dev.pschmitt.jellyfin.database.ServerDatabaseDao
import dev.pschmitt.jellyfin.repository.JellyfinRepository
import java.io.File
import java.util.UUID
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo

data class JollyfinSource(
    val id: String,
    val name: String,
    val type: JollyfinSourceType,
    val path: String,
    val size: Long,
    val mediaStreams: List<JollyfinMediaStream>,
    val downloadId: Long? = null,
    val checksum: String? = null,
    val pausedByBatterySaver: Boolean = false,
    val excludeFromAutoDelete: Boolean = false,
)

suspend fun MediaSourceInfo.toJollyfinSource(
    jellyfinRepository: JellyfinRepository,
    itemId: UUID,
    includePath: Boolean = false,
): JollyfinSource {
    val path =
        when (protocol) {
            MediaProtocol.FILE -> {
                try {
                    if (includePath) jellyfinRepository.getStreamUrl(itemId, id.orEmpty()) else ""
                } catch (e: Exception) {
                    ""
                }
            }
            MediaProtocol.HTTP -> this.path.orEmpty()
            else -> ""
        }
    return JollyfinSource(
        id = id.orEmpty(),
        name = name.orEmpty(),
        type = JollyfinSourceType.REMOTE,
        path = path,
        size = size ?: 0,
        mediaStreams =
            mediaStreams?.map { it.toJollyfinMediaStream(jellyfinRepository) } ?: emptyList(),
    )
}

fun JollyfinSourceDto.toJollyfinSource(serverDatabaseDao: ServerDatabaseDao): JollyfinSource {
    return toJollyfinSource(serverDatabaseDao.getMediaStreamsBySourceId(id))
}

/**
 * Same mapping as [toJollyfinSource], but takes an already-fetched media-stream list instead of
 * doing its own DB query - lets batch callers (see `toJollyfinMovies`/`toJollyfinEpisodes`) fetch
 * media streams for every source in one query instead of one query per source.
 */
fun JollyfinSourceDto.toJollyfinSource(mediaStreams: List<JollyfinMediaStreamDto>): JollyfinSource {
    return JollyfinSource(
        id = id,
        name = name,
        type = type,
        path = path,
        size = File(path).length(),
        mediaStreams = mediaStreams.map { it.toJollyfinMediaStream() },
        downloadId = downloadId,
        checksum = checksum,
        pausedByBatterySaver = pausedByBatterySaver,
        excludeFromAutoDelete = excludeFromAutoDelete,
    )
}

enum class JollyfinSourceType {
    REMOTE,
    LOCAL,
}
