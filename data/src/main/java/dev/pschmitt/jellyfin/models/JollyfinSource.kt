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
    // File extension of the original file on the server (e.g. "mkv"), used to name downloads.
    // Only known for remote sources fresh from the server - see [originalFileExtension].
    val container: String? = null,
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
        container = originalFileExtension(this.path, container),
    )
}

/**
 * Best guess at the original file's extension: the extension of the server-side [path] when it has
 * a plausible one, otherwise derived from Jellyfin's [container] string, which can be a
 * comma-separated list of aliases for the same demuxer (e.g. `mov,mp4,m4a,3gp,3g2,mj2`).
 */
fun originalFileExtension(path: String?, container: String?): String? {
    val plausible = Regex("^[a-z0-9]{1,5}$")
    path
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.substringAfterLast('.', "")
        ?.lowercase()
        ?.takeIf { plausible.matches(it) }
        ?.let {
            return it
        }
    val aliases = container?.lowercase()?.split(',')?.map { it.trim() }.orEmpty()
    return when {
        "mp4" in aliases -> "mp4"
        "matroska" in aliases -> "mkv"
        "mpegts" in aliases -> "ts"
        else -> aliases.firstOrNull()?.takeIf { plausible.matches(it) }
    }
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
