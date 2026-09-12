package dev.pschmitt.jellyfin.repository

import dev.pschmitt.jellyfin.api.pvr.PvrQualityDetail
import dev.pschmitt.jellyfin.api.pvr.PvrQualityInfo
import dev.pschmitt.jellyfin.api.pvr.PvrRejection
import dev.pschmitt.jellyfin.api.pvr.PvrStatusMessage
import dev.pschmitt.jellyfin.api.pvr.RadarrManualImportItem
import dev.pschmitt.jellyfin.api.pvr.RadarrMovie
import dev.pschmitt.jellyfin.api.pvr.RadarrQueueItem
import dev.pschmitt.jellyfin.api.pvr.SonarrEpisode
import dev.pschmitt.jellyfin.api.pvr.SonarrManualImportEpisode
import dev.pschmitt.jellyfin.api.pvr.SonarrManualImportItem
import dev.pschmitt.jellyfin.api.pvr.SonarrManualImportSeriesRef
import dev.pschmitt.jellyfin.api.pvr.SonarrQueueItem
import dev.pschmitt.jellyfin.api.pvr.SonarrSeries
import dev.pschmitt.jellyfin.models.JollyfinEpisode
import dev.pschmitt.jellyfin.models.JollyfinImages
import dev.pschmitt.jellyfin.models.JollyfinMovie
import dev.pschmitt.jellyfin.models.JollyfinShow
import dev.pschmitt.jellyfin.models.PvrSource
import dev.pschmitt.jellyfin.models.QueueItemStatus
import dev.pschmitt.jellyfin.models.QueueStatus
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueStatusMatchingTest {

    // region matchSonarr

    @Test
    fun `matchSonarr resolves an exact match through tvdbId then season-episode number`() {
        val showId = UUID.randomUUID()
        val episodeId = UUID.randomUUID()
        val show = testShow(id = showId, tvdbId = "1000")
        val episode = testEpisode(id = episodeId, seriesId = showId, season = 1, index = 5)

        val series = listOf(SonarrSeries(id = 1, tvdbId = 1000))
        val queue =
            listOf(
                SonarrQueueItem(
                    id = 42,
                    seriesId = 1,
                    seasonNumber = 1,
                    episode = SonarrEpisode(episodeNumber = 5),
                    status = "downloading",
                    size = 1000,
                    sizeleft = 250,
                )
            )

        val result =
            matchSonarr(
                series = series,
                queue = queue,
                jellyfinShows = listOf(show),
                episodesByShowId = mapOf(showId to listOf(episode)),
            )

        assertEquals(1, result.size)
        assertEquals(episodeId, result.single().item?.id)
        val status = result.toQueueStatusMap()[episodeId]
        assertEquals(PvrSource.SONARR, status?.source)
        assertEquals(QueueItemStatus.DOWNLOADING, status?.status)
        assertEquals(75, status?.percent)
    }

    @Test
    fun `matchSonarr yields an unmatched entry when neither side has a tvdbId`() {
        val showId = UUID.randomUUID()
        val show = testShow(id = showId, tvdbId = null)
        val episode = testEpisode(id = UUID.randomUUID(), seriesId = showId, season = 1, index = 5)

        // SonarrSeries.tvdbId defaults to 0, the DTO's "unset" sentinel.
        val series = listOf(SonarrSeries(id = 1, title = "Some Show"))
        val queue =
            listOf(
                SonarrQueueItem(id = 42, seriesId = 1, seasonNumber = 1, episode = SonarrEpisode(5))
            )

        val result = matchSonarr(series, queue, listOf(show), mapOf(showId to listOf(episode)))

        assertEquals(1, result.size)
        assertNull(result.single().item)
        // The unmatched entry is still titled from Sonarr's own series metadata.
        assertEquals("Some Show - S1E5", result.single().title)
        assertTrue(result.toQueueStatusMap().isEmpty())
    }

    @Test
    fun `matchSonarr yields an unmatched entry titled from the release when the seriesId is unknown`() {
        val showId = UUID.randomUUID()
        val show = testShow(id = showId, tvdbId = "1000")
        val episode = testEpisode(id = UUID.randomUUID(), seriesId = showId, season = 1, index = 5)

        val series = listOf(SonarrSeries(id = 1, tvdbId = 1000))
        // References seriesId 999, which doesn't exist in `series` - an orphaned reference.
        val queue =
            listOf(
                SonarrQueueItem(
                    id = 42,
                    seriesId = 999,
                    seasonNumber = 1,
                    episode = SonarrEpisode(5),
                    title = "Some.Show.S01E05.1080p.WEB.h264-GROUP",
                )
            )

        val result = matchSonarr(series, queue, listOf(show), mapOf(showId to listOf(episode)))

        assertEquals(1, result.size)
        assertNull(result.single().item)
        assertEquals("Some.Show.S01E05.1080p.WEB.h264-GROUP", result.single().title)
        assertTrue(result.toQueueStatusMap().isEmpty())
    }

    @Test
    fun `matchSonarr yields an unmatched entry when the episode is not yet in Jellyfin's library`() {
        val showId = UUID.randomUUID()
        val show = testShow(id = showId, tvdbId = "1000")

        val series = listOf(SonarrSeries(id = 1, tvdbId = 1000))
        val queue =
            listOf(
                SonarrQueueItem(id = 42, seriesId = 1, seasonNumber = 1, episode = SonarrEpisode(5))
            )

        // episodesByShowId has no entry at all for showId - show exists, but nothing has synced
        // yet.
        val result = matchSonarr(series, queue, listOf(show), emptyMap())

        assertEquals(1, result.size)
        assertNull(result.single().item)
        assertTrue(result.toQueueStatusMap().isEmpty())

        // Also covers the case where the show's episode list exists but simply doesn't (yet)
        // contain the queued episode.
        val otherEpisode =
            testEpisode(id = UUID.randomUUID(), seriesId = showId, season = 1, index = 1)
        val result2 =
            matchSonarr(series, queue, listOf(show), mapOf(showId to listOf(otherEpisode)))
        assertNull(result2.single().item)
        assertTrue(result2.toQueueStatusMap().isEmpty())
    }

    @Test
    fun `matchSonarr titles a season-pack grab without a per-episode number`() {
        // A whole-season grab has no nested episode object - it can't be matched to a single
        // Jellyfin episode, but the row should still read as "show + season", not a raw release.
        val series = listOf(SonarrSeries(id = 1, tvdbId = 1000, title = "Some Show"))
        val queue = listOf(SonarrQueueItem(id = 42, seriesId = 1, seasonNumber = 2))

        val result = matchSonarr(series, queue, emptyList(), emptyMap())

        assertEquals("Some Show - Season 2", result.single().title)
        assertNull(result.single().item)
    }

    @Test
    fun `matchSonarr last queue entry wins when two entries resolve to the same episode`() {
        val showId = UUID.randomUUID()
        val episodeId = UUID.randomUUID()
        val show = testShow(id = showId, tvdbId = "1000")
        val episode = testEpisode(id = episodeId, seriesId = showId, season = 1, index = 5)

        val series = listOf(SonarrSeries(id = 1, tvdbId = 1000))
        val queue =
            listOf(
                SonarrQueueItem(
                    id = 1,
                    seriesId = 1,
                    seasonNumber = 1,
                    episode = SonarrEpisode(5),
                    status = "downloading",
                ),
                // A retried download for the same episode - a second queue row before Sonarr
                // cleans up the first one.
                SonarrQueueItem(
                    id = 2,
                    seriesId = 1,
                    seasonNumber = 1,
                    episode = SonarrEpisode(5),
                    status = "queued",
                ),
            )

        val result = matchSonarr(series, queue, listOf(show), mapOf(showId to listOf(episode)))

        // Both queue rows are kept in the list (a queue view should show both downloads), but the
        // per-item badge map collapses them with the later entry winning.
        assertEquals(2, result.size)
        val statusMap = result.toQueueStatusMap()
        assertEquals(1, statusMap.size)
        assertEquals(QueueItemStatus.QUEUED, statusMap[episodeId]?.status)
    }

    // endregion

    // region matchRadarr

    @Test
    fun `matchRadarr resolves an exact match through tmdbId`() {
        val movieId = UUID.randomUUID()
        val movie = testMovie(id = movieId, tmdbId = "2000")

        val movies = listOf(RadarrMovie(id = 7, tmdbId = 2000))
        val queue =
            listOf(
                RadarrQueueItem(
                    id = 1,
                    movieId = 7,
                    status = "downloading",
                    size = 1000,
                    sizeleft = 100,
                )
            )

        val result = matchRadarr(movies, queue, listOf(movie))

        assertEquals(1, result.size)
        assertEquals(movieId, result.single().item?.id)
        val status = result.toQueueStatusMap()[movieId]
        assertEquals(PvrSource.RADARR, status?.source)
        assertEquals(QueueItemStatus.DOWNLOADING, status?.status)
        assertEquals(90, status?.percent)
        assertEquals(90, result.toRadarrQueueStatusMap()[2000]?.percent)
    }

    @Test
    fun `matchRadarr yields an unmatched entry when neither side has a tmdbId`() {
        val movie = testMovie(id = UUID.randomUUID(), tmdbId = null)
        // RadarrMovie.tmdbId defaults to 0, the DTO's "unset" sentinel.
        val movies = listOf(RadarrMovie(id = 7, title = "Some Movie"))
        val queue = listOf(RadarrQueueItem(id = 1, movieId = 7))

        val result = matchRadarr(movies, queue, listOf(movie))

        assertEquals(1, result.size)
        assertNull(result.single().item)
        assertNull(result.single().tmdbId)
        assertEquals(0, result.toRadarrQueueStatusMap().size)
        // The unmatched entry is still titled from Radarr's own movie metadata.
        assertEquals("Some Movie", result.single().title)
        assertTrue(result.toQueueStatusMap().isEmpty())
    }

    @Test
    fun `matchRadarr yields an unmatched entry titled from the release when the movieId is unknown`() {
        val movie = testMovie(id = UUID.randomUUID(), tmdbId = "2000")
        val movies = listOf(RadarrMovie(id = 7, tmdbId = 2000))
        // References movieId 999, which doesn't exist in `movies` - an orphaned reference.
        val queue =
            listOf(RadarrQueueItem(id = 1, movieId = 999, title = "Some.Movie.2024.2160p.WEB-DL"))

        val result = matchRadarr(movies, queue, listOf(movie))

        assertEquals(1, result.size)
        assertNull(result.single().item)
        assertNull(result.single().tmdbId)
        assertEquals("Some.Movie.2024.2160p.WEB-DL", result.single().title)
        assertTrue(result.toQueueStatusMap().isEmpty())
    }

    @Test
    fun `matchRadarr yields an unmatched entry when the movie is not yet in Jellyfin's library`() {
        val movies = listOf(RadarrMovie(id = 7, tmdbId = 2000, title = "Some Movie"))
        val queue = listOf(RadarrQueueItem(id = 1, movieId = 7))

        val result = matchRadarr(movies, queue, emptyList())

        assertEquals(1, result.size)
        assertNull(result.single().item)
        assertEquals(2000, result.single().tmdbId)
        assertEquals(QueueItemStatus.QUEUED, result.toRadarrQueueStatusMap()[2000]?.status)
        assertEquals("Some Movie", result.single().title)
        assertTrue(result.toQueueStatusMap().isEmpty())
    }

    @Test
    fun `matchRadarr last queue entry wins when two entries resolve to the same movie`() {
        val movieId = UUID.randomUUID()
        val movie = testMovie(id = movieId, tmdbId = "2000")
        val movies = listOf(RadarrMovie(id = 7, tmdbId = 2000))
        val queue =
            listOf(
                RadarrQueueItem(id = 1, movieId = 7, status = "downloading"),
                RadarrQueueItem(
                    id = 2,
                    movieId = 7,
                    status = "warning",
                    trackedDownloadStatus = "warning",
                ),
            )

        val result = matchRadarr(movies, queue, listOf(movie))

        // Both queue rows are kept in the list; the per-item badge map collapses them with the
        // later entry winning.
        assertEquals(2, result.size)
        val statusMap = result.toQueueStatusMap()
        assertEquals(1, statusMap.size)
        assertEquals(QueueItemStatus.WARNING, statusMap[movieId]?.status)
    }

    // endregion

    // region one-service-down independence (documents the try/catch structure covered at the
    // repository level - QueueStatusRepositoryImpl.fetchSonarrStatus()/fetchRadarrStatus() each
    // wrap their own fetch+match in try/catch, so an exception thrown while calling Sonarr/Radarr
    // never affects the other service's contribution to the merged map. The matching functions
    // under test here are pure and never throw regardless of input, by construction - every
    // lookup that fails is a `continue`, never a `!!` or an index that can be missing.)

    // endregion

    // region status mapping

    @Test
    fun `mapQueueItemStatus maps trackedDownloadStatus error to FAILED regardless of status`() {
        assertEquals(
            QueueItemStatus.FAILED,
            mapQueueItemStatus(
                status = "downloading",
                trackedDownloadStatus = "error",
                trackedDownloadState = null,
            ),
        )
    }

    @Test
    fun `mapQueueItemStatus maps trackedDownloadStatus warning to WARNING regardless of status`() {
        assertEquals(
            QueueItemStatus.WARNING,
            mapQueueItemStatus(
                status = "downloading",
                trackedDownloadStatus = "warning",
                trackedDownloadState = null,
            ),
        )
    }

    @Test
    fun `mapQueueItemStatus maps queued-ish statuses to QUEUED`() {
        for (status in listOf("queued", "delay", "paused", null)) {
            assertEquals(
                "status=$status",
                QueueItemStatus.QUEUED,
                mapQueueItemStatus(
                    status = status,
                    trackedDownloadStatus = null,
                    trackedDownloadState = null,
                ),
            )
        }
    }

    @Test
    fun `mapQueueItemStatus maps downloading to DOWNLOADING`() {
        assertEquals(
            QueueItemStatus.DOWNLOADING,
            mapQueueItemStatus(
                status = "downloading",
                trackedDownloadStatus = "ok",
                trackedDownloadState = "downloading",
            ),
        )
    }

    @Test
    fun `mapQueueItemStatus maps completed or import tracked states to IMPORTING`() {
        assertEquals(
            QueueItemStatus.IMPORTING,
            mapQueueItemStatus(
                status = "completed",
                trackedDownloadStatus = "ok",
                trackedDownloadState = null,
            ),
        )
        assertEquals(
            QueueItemStatus.IMPORTING,
            mapQueueItemStatus(
                status = "downloading",
                trackedDownloadStatus = "ok",
                trackedDownloadState = "importPending",
            ),
        )
    }

    @Test
    fun `mapQueueItemStatus maps failed status or tracked state to FAILED`() {
        assertEquals(
            QueueItemStatus.FAILED,
            mapQueueItemStatus(
                status = "failed",
                trackedDownloadStatus = null,
                trackedDownloadState = null,
            ),
        )
        assertEquals(
            QueueItemStatus.FAILED,
            mapQueueItemStatus(
                status = "downloading",
                trackedDownloadStatus = "ok",
                trackedDownloadState = "failedPending",
            ),
        )
    }

    @Test
    fun `matchSonarr surfaces the bare top-level statusMessages reason when errorMessage is absent`() {
        // Real-world case: a season-pack release where Sonarr imported every file it found, but
        // considers the release itself incomplete (fewer episodes than expected for the season).
        // errorMessage is null in this scenario - the actual reason only lives in statusMessages,
        // mixed in with a noisy per-file "already imported" confirmation for every episode.
        val series = listOf(SonarrSeries(id = 1, tvdbId = 1000, title = "Some Show"))
        val queue =
            listOf(
                SonarrQueueItem(
                    id = 42,
                    seriesId = 1,
                    seasonNumber = 1,
                    status = "completed",
                    trackedDownloadStatus = "warning",
                    trackedDownloadState = "importBlocked",
                    errorMessage = null,
                    statusMessages =
                        listOf(
                            PvrStatusMessage(
                                title =
                                    "One or more episodes expected in this release were not " +
                                        "imported or missing from the release",
                                messages = emptyList(),
                            ),
                            PvrStatusMessage(
                                title = "S01E01-Episode.mkv",
                                messages = listOf("Episode file already imported"),
                            ),
                        ),
                )
            )

        val result = matchSonarr(series, queue, emptyList(), emptyMap())

        assertEquals(QueueItemStatus.WARNING, result.single().status.status)
        assertEquals(
            "One or more episodes expected in this release were not imported or missing from the release",
            result.single().status.errorMessage,
        )
    }

    @Test
    fun `matchRadarr falls back to the first per-file statusMessage when no bare reason exists`() {
        val movies = listOf(RadarrMovie(id = 7, tmdbId = 2000, title = "Some Movie"))
        val queue =
            listOf(
                RadarrQueueItem(
                    id = 1,
                    movieId = 7,
                    status = "warning",
                    trackedDownloadStatus = "warning",
                    errorMessage = null,
                    statusMessages =
                        listOf(
                            PvrStatusMessage(
                                title = "Some.Movie.2024.mkv",
                                messages = listOf("Not a preferred word score"),
                            )
                        ),
                )
            )

        val result = matchRadarr(movies, queue, emptyList())

        assertEquals(
            "Some.Movie.2024.mkv: Not a preferred word score",
            result.single().status.errorMessage,
        )
    }

    // endregion

    // region manual import candidate mapping

    @Test
    fun `SonarrManualImportItem toCandidate formats the episode label and flags importable files`() {
        // Grounded in a real Sonarr v3 GET /api/v3/manualimport response (Mushoku Tensei S01
        // season-pack) - a file Sonarr mapped to a specific episode, already imported once.
        val item =
            SonarrManualImportItem(
                id = 1068051129,
                path = "/downloads/S01E06-A Day Off in Roa [E8841F59].mkv",
                name = "S01E06-A Day Off in Roa [E8841F59]",
                size = 914265058,
                seasonNumber = 1,
                series = SonarrManualImportSeriesRef(id = 68, title = "Mushoku Tensei"),
                episodes = listOf(SonarrManualImportEpisode(id = 4602, episodeNumber = 6)),
                quality = PvrQualityInfo(quality = PvrQualityDetail(id = 7, name = "Bluray-1080p")),
                downloadId = "ADE418CD87072DDF0E2513500FD39C3282EAE073",
                rejections =
                    listOf(
                        PvrRejection(
                            reason = "Episode file already imported at 7/18/2026 4:19:29 AM"
                        )
                    ),
            )

        val candidate = item.toCandidate()

        assertEquals(1068051129, candidate.id)
        assertEquals("S1E6", candidate.episodeLabel)
        assertEquals("Bluray-1080p", candidate.qualityName)
        assertEquals(914265058L, candidate.sizeBytes)
        assertTrue(candidate.canImport)
        assertEquals(1, candidate.rejections.size)
    }

    @Test
    fun `SonarrManualImportItem toCandidate cannot import a file with no episode guess at all`() {
        // Sonarr couldn't map this file to any episode - a manual episode assignment (not yet
        // supported) would be needed, so it must not be selectable for import as-is.
        val item =
            SonarrManualImportItem(
                id = 2,
                name = "Unrecognized.File.mkv",
                series = null,
                episodes = emptyList(),
            )

        val candidate = item.toCandidate()

        assertNull(candidate.episodeLabel)
        assertTrue(!candidate.canImport)
    }

    @Test
    fun `RadarrManualImportItem toCandidate has no episode label and is importable once matched to a movie`() {
        val matched =
            RadarrManualImportItem(
                id = 1,
                name = "Some.Movie.mkv",
                movie = RadarrMovie(id = 7, tmdbId = 2000),
            )
        val unmatched =
            RadarrManualImportItem(id = 2, name = "Unrecognized.Movie.mkv", movie = null)

        assertNull(matched.toCandidate().episodeLabel)
        assertTrue(matched.toCandidate().canImport)
        assertTrue(!unmatched.toCandidate().canImport)
    }

    // endregion

    // region season clustering

    @Test
    fun `seasonClusterKey is null for anything but a healthy per-episode Sonarr entry`() {
        val healthy =
            seasonClusterKey(
                source = PvrSource.SONARR,
                status = QueueItemStatus.DOWNLOADING,
                tmdbId = 1000,
                seasonNumber = 3,
                episodeNumber = 5,
            )
        assertEquals(1000 to 3, healthy)

        assertNull(
            "Radarr has no season concept",
            seasonClusterKey(PvrSource.RADARR, QueueItemStatus.DOWNLOADING, 1000, 3, 5),
        )
        assertNull(
            "a problem entry stays individually actionable",
            seasonClusterKey(PvrSource.SONARR, QueueItemStatus.WARNING, 1000, 3, 5),
        )
        assertNull(
            "a season-pack row (no per-episode number) already reads as a season on its own",
            seasonClusterKey(PvrSource.SONARR, QueueItemStatus.DOWNLOADING, 1000, 3, null),
        )
        assertNull(
            "nothing safe to cluster by without a resolved series/season",
            seasonClusterKey(PvrSource.SONARR, QueueItemStatus.DOWNLOADING, null, null, 5),
        )
    }

    @Test
    fun `aggregateQueueStatuses sums sizes and speed, recomputes percent, and picks the most in-progress status`() {
        val statuses =
            listOf(
                QueueStatus(
                    source = PvrSource.SONARR,
                    status = QueueItemStatus.QUEUED,
                    sizeBytes = 1000,
                    remainingBytes = 1000,
                ),
                QueueStatus(
                    source = PvrSource.SONARR,
                    status = QueueItemStatus.DOWNLOADING,
                    sizeBytes = 1000,
                    remainingBytes = 250,
                    speedBytesPerSecond = 50,
                ),
            )

        val aggregate = aggregateQueueStatuses(statuses)

        assertEquals(PvrSource.SONARR, aggregate.source)
        assertEquals(QueueItemStatus.DOWNLOADING, aggregate.status)
        assertEquals(2000L, aggregate.sizeBytes)
        assertEquals(1250L, aggregate.remainingBytes)
        assertEquals(50L, aggregate.speedBytesPerSecond)
        assertEquals(37, aggregate.percent)
        assertEquals(25L, aggregate.etaSeconds)
    }

    @Test
    fun `seasonClusterTitle strips the per-episode suffix`() {
        assertEquals("Some Show - Season 3", seasonClusterTitle("Some Show - S3E5", 3))
    }

    @Test
    fun `clusterSeasonsForDisplay merges distinct healthy episodes of the same season into one entry`() {
        val series = listOf(SonarrSeries(id = 1, tvdbId = 1000, tmdbId = 2000, title = "Some Show"))
        val queue =
            listOf(
                SonarrQueueItem(
                    id = 1,
                    seriesId = 1,
                    seasonNumber = 3,
                    episode = SonarrEpisode(5),
                    status = "downloading",
                    size = 1000,
                    sizeleft = 250,
                ),
                SonarrQueueItem(
                    id = 2,
                    seriesId = 1,
                    seasonNumber = 3,
                    episode = SonarrEpisode(6),
                    status = "queued",
                    size = 1000,
                    sizeleft = 1000,
                ),
                // Different season - must not be folded into the season-3 cluster.
                SonarrQueueItem(
                    id = 3,
                    seriesId = 1,
                    seasonNumber = 4,
                    episode = SonarrEpisode(1),
                    status = "downloading",
                ),
            )

        val result = matchSonarr(series, queue, emptyList(), emptyMap()).clusterSeasonsForDisplay()

        assertEquals(2, result.size)
        val cluster = result.single { it.title == "Some Show - Season 3" }
        assertNull(cluster.item)
        assertEquals(2000L, cluster.status.sizeBytes)
        assertEquals(QueueItemStatus.DOWNLOADING, cluster.status.status)
        assertTrue(result.any { it.title == "Some Show - S4E1" })
    }

    @Test
    fun `clusterSeasonsForDisplay dedupes retries of the same episode before clustering by season`() {
        val series = listOf(SonarrSeries(id = 1, tvdbId = 1000, tmdbId = 2000, title = "Some Show"))
        val queue =
            listOf(
                // Two retries of the very same episode (same episodeId - what groupDuplicates
                // actually keys on) - must count as one episode, not two.
                SonarrQueueItem(
                    id = 1,
                    seriesId = 1,
                    episodeId = 55,
                    seasonNumber = 3,
                    episode = SonarrEpisode(5),
                    status = "queued",
                ),
                SonarrQueueItem(
                    id = 2,
                    seriesId = 1,
                    episodeId = 55,
                    seasonNumber = 3,
                    episode = SonarrEpisode(5),
                    status = "downloading",
                ),
            )

        val result = matchSonarr(series, queue, emptyList(), emptyMap()).clusterSeasonsForDisplay()

        // A single distinct episode never clusters with itself.
        assertEquals(1, result.size)
        assertEquals("Some Show - S3E5", result.single().title)
    }

    @Test
    fun `clusterSeasonsForDisplay leaves a WARNING episode out of its season's cluster`() {
        val series = listOf(SonarrSeries(id = 1, tvdbId = 1000, tmdbId = 2000, title = "Some Show"))
        val queue =
            listOf(
                SonarrQueueItem(
                    id = 1,
                    seriesId = 1,
                    seasonNumber = 3,
                    episode = SonarrEpisode(5),
                    status = "downloading",
                ),
                SonarrQueueItem(
                    id = 2,
                    seriesId = 1,
                    seasonNumber = 3,
                    episode = SonarrEpisode(6),
                    status = "warning",
                    trackedDownloadStatus = "warning",
                ),
            )

        val result = matchSonarr(series, queue, emptyList(), emptyMap()).clusterSeasonsForDisplay()

        assertEquals(2, result.size)
        assertTrue(result.any { it.status.status == QueueItemStatus.WARNING })
        assertTrue(result.none { it.title.contains("Season") })
    }

    // endregion

    @Test
    fun `parseTimeleftSeconds parses HH-MM-SS and day-prefixed durations, and blanks as unknown`() {
        assertEquals(3661L, parseTimeleftSeconds("01:01:01"))
        assertEquals(90_061L, parseTimeleftSeconds("1.01:01:01"))
        assertEquals(-1L, parseTimeleftSeconds(null))
        assertEquals(-1L, parseTimeleftSeconds(""))
        assertEquals(-1L, parseTimeleftSeconds("not-a-duration"))
    }

    // endregion

    private fun testShow(id: UUID, tvdbId: String?): JollyfinShow =
        JollyfinShow(
            id = id,
            name = "Show $id",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            seasons = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            unplayedItemCount = null,
            genres = emptyList(),
            people = emptyList(),
            runtimeTicks = 0L,
            communityRating = null,
            officialRating = null,
            status = "Continuing",
            productionYear = null,
            endDate = null,
            trailer = null,
            images = JollyfinImages(),
            tvdbId = tvdbId,
        )

    private fun testEpisode(id: UUID, seriesId: UUID, season: Int, index: Int): JollyfinEpisode =
        JollyfinEpisode(
            id = id,
            name = "Episode $id",
            originalTitle = null,
            overview = "",
            indexNumber = index,
            indexNumberEnd = null,
            parentIndexNumber = season,
            sources = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            seriesId = seriesId,
            seriesName = "Show",
            seasonId = UUID.randomUUID(),
            seasonName = null,
            communityRating = null,
            people = emptyList(),
            images = JollyfinImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )

    private fun testMovie(id: UUID, tmdbId: String?): JollyfinMovie =
        JollyfinMovie(
            id = id,
            name = "Movie $id",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            people = emptyList(),
            genres = emptyList(),
            communityRating = null,
            officialRating = null,
            status = "Released",
            productionYear = null,
            endDate = null,
            trailer = null,
            images = JollyfinImages(),
            chapters = emptyList(),
            trickplayInfo = null,
            tmdbId = tmdbId,
        )
}
