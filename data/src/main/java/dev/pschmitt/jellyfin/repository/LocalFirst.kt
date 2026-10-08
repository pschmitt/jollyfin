package dev.pschmitt.jellyfin.repository

import dev.pschmitt.jellyfin.models.JollyfinEpisode
import dev.pschmitt.jellyfin.models.JollyfinItem
import dev.pschmitt.jellyfin.models.JollyfinMovie
import dev.pschmitt.jellyfin.models.JollyfinSourceType
import java.util.UUID

/*
 * Local-first reads for downloaded items while *not* in offline mode. The online repository always
 * asks the server first, which on a poor connection (train, tunnel) means waiting out network
 * timeouts before showing - or playing - something that's already on disk. Callers use these to
 * render/play the local copy immediately and treat the server as an optional refresh.
 *
 * Room's non-null DAO getters throw when the row is missing, hence the runCatching - "not
 * downloaded" is an expected answer here, not an error.
 */

private fun JollyfinItem.hasLocalSource(): Boolean = sources.any {
    it.type == JollyfinSourceType.LOCAL
}

/** The downloaded copy of episode [itemId], or null when it isn't downloaded. */
suspend fun JellyfinRepositoryOfflineImpl.getDownloadedEpisodeOrNull(
    itemId: UUID
): JollyfinEpisode? = runCatching { getEpisode(itemId) }.getOrNull()?.takeIf { it.hasLocalSource() }

/** The downloaded copy of movie [itemId], or null when it isn't downloaded. */
suspend fun JellyfinRepositoryOfflineImpl.getDownloadedMovieOrNull(itemId: UUID): JollyfinMovie? =
    runCatching {
        getMovie(itemId)
    }
    .getOrNull()
    ?.takeIf { it.hasLocalSource() }
