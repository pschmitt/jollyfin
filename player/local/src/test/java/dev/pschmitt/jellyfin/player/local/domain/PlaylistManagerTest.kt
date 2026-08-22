package dev.pschmitt.jellyfin.player.local.domain

import dev.pschmitt.jellyfin.models.JollyfinSource
import dev.pschmitt.jellyfin.models.JollyfinSourceType
import java.io.File
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Test

class PlaylistManagerTest {
    private val temporaryFiles = mutableListOf<File>()

    @After
    fun tearDown() {
        temporaryFiles.forEach(File::delete)
    }

    @Test
    fun `incomplete local source falls back to remote source`() {
        val incompleteFile = File.createTempFile("jollyfin", ".download")
        incompleteFile.delete()
        temporaryFiles += incompleteFile
        val remoteSource = source(JollyfinSourceType.REMOTE, "https://example.test/video")

        val selected =
            selectPlayableMediaSource(
                listOf(source(JollyfinSourceType.LOCAL, incompleteFile.path), remoteSource),
                mediaSourceIndex = null,
            )

        assertSame(remoteSource, selected)
    }

    @Test
    fun `completed local source remains preferred`() {
        val completedFile = File.createTempFile("jollyfin", ".mkv")
        completedFile.writeBytes(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()))
        temporaryFiles += completedFile
        val localSource = source(JollyfinSourceType.LOCAL, completedFile.path)

        val selected =
            selectPlayableMediaSource(
                listOf(
                    localSource,
                    source(JollyfinSourceType.REMOTE, "https://example.test/video"),
                ),
                mediaSourceIndex = null,
            )

        assertSame(localSource, selected)
    }

    private fun source(type: JollyfinSourceType, path: String) =
        JollyfinSource(
            id = path,
            name = "Test source",
            type = type,
            path = path,
            size = 1L,
            mediaStreams = emptyList(),
        )
}
