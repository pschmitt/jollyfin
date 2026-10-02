package dev.pschmitt.jellyfin.utils

import dev.pschmitt.jellyfin.models.originalFileExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFileNamingTest {
    @Test
    fun movieGetsYearAndMoviesFolder() {
        assertEquals("Movies/Heat (1995)", DownloadFileNaming.movieBasePath("Heat", 1995))
        assertEquals("Movies/Heat", DownloadFileNaming.movieBasePath("Heat", null))
    }

    @Test
    fun episodeUsesJellyfinLayout() {
        assertEquals(
            "Shows/Severance/Season 01/Severance - S01E02 - Half Loop",
            DownloadFileNaming.episodeBasePath("Severance", 1, 2, null, "Half Loop"),
        )
    }

    @Test
    fun multiEpisodeFileGetsRange() {
        assertEquals(
            "Shows/Show/Season 00/Show - S00E01-E03",
            DownloadFileNaming.episodeBasePath("Show", 0, 1, 3, " "),
        )
    }

    @Test
    fun sanitizeReplacesForbiddenCharsAndTrailingDots() {
        assertEquals("AC_DC_ Live_", DownloadFileNaming.sanitize("AC/DC: Live?"))
        assertEquals("Mr. Robot", DownloadFileNaming.sanitize("  Mr. Robot... "))
        assertEquals("_", DownloadFileNaming.sanitize("..."))
        assertEquals(80, DownloadFileNaming.sanitize("x".repeat(300)).length)
    }

    @Test
    fun candidatesTryVersionLabelThenNumbers() {
        assertEquals(
            listOf("Movies/Heat.mkv", "Movies/Heat - 4K.mkv", "Movies/Heat (2).mkv"),
            DownloadFileNaming.uniqueCandidates("Movies/Heat", "mkv", "4K").take(3).toList(),
        )
    }

    @Test
    fun candidatesSkipVersionLabelThatIsJustTheName() {
        assertEquals(
            listOf("Movies/Heat", "Movies/Heat (2)"),
            DownloadFileNaming.uniqueCandidates("Movies/Heat", null, "Heat").take(2).toList(),
        )
    }

    @Test
    fun subtitleSitsNextToVideo() {
        assertEquals(
            "Movies/Heat (1995).eng",
            DownloadFileNaming.subtitleBasePath("Movies/Heat (1995)", "eng"),
        )
        assertEquals("Movies/Heat", DownloadFileNaming.subtitleBasePath("Movies/Heat", ""))
    }

    @Test
    fun subtitleExtensionPrefersDeliveryUrl() {
        assertEquals(
            "ass",
            DownloadFileNaming.subtitleExtension(
                "https://jf/Videos/x/y/Subtitles/2/0/Stream.ass?api_key=k",
                "subrip",
            ),
        )
        assertEquals("srt", DownloadFileNaming.subtitleExtension(null, "subrip"))
        assertEquals("vtt", DownloadFileNaming.subtitleExtension(null, "WebVTT"))
    }

    @Test
    fun legacyNamesAreDetected() {
        val itemId = "3f2a0000-0000-0000-0000-000000000e91"
        assertTrue(DownloadFileNaming.isLegacyFileName("$itemId.abc123", itemId))
        assertFalse(DownloadFileNaming.isLegacyFileName("Heat (1995).mkv", itemId))
    }

    @Test
    fun finalPathOnlyStripsTrailingSuffix() {
        assertEquals(
            "/d/Movies/The .download Story.mkv",
            DownloadFileNaming.finalPath("/d/Movies/The .download Story.mkv.download"),
        )
    }

    @Test
    fun originalExtensionPrefersServerPath() {
        assertEquals("mov", originalFileExtension("/media/Movies/Heat/Heat.MOV", "mov,mp4,m4a"))
        assertEquals("mp4", originalFileExtension("", "mov,mp4,m4a,3gp,3g2,mj2"))
        assertEquals("mkv", originalFileExtension(null, "mkv"))
        assertEquals("ts", originalFileExtension("/media/no-extension", "mpegts"))
        assertNull(originalFileExtension(null, null))
    }
}
