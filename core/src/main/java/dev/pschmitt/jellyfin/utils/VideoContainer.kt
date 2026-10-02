package dev.pschmitt.jellyfin.utils

import java.io.File
import timber.log.Timber

/** Video container formats recognizable from a downloaded file's header - see [sniff]. */
enum class VideoContainer(val extension: String, val mimeType: String) {
    MKV("mkv", "video/x-matroska"),
    MP4("mp4", "video/mp4"),
    AVI("avi", "video/x-msvideo"),
    TS("ts", "video/mp2t");

    companion object {
        /**
         * Detects the container from [file]'s magic bytes, for files whose original extension is
         * unknown (downloads from before they were stored with one). Null if unrecognized.
         */
        fun sniff(file: File): VideoContainer? {
            val header = ByteArray(12)
            val read =
                try {
                    file.inputStream().use { it.read(header) }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to read download header: %s", file)
                    return null
                }
            if (read < header.size) return null
            fun ascii(from: Int, to: Int) = String(header, from, to - from, Charsets.US_ASCII)
            return when {
                header[0] == 0x1A.toByte() &&
                    header[1] == 0x45.toByte() &&
                    header[2] == 0xDF.toByte() &&
                    header[3] == 0xA3.toByte() -> MKV
                ascii(4, 8) == "ftyp" -> MP4
                ascii(0, 4) == "RIFF" && ascii(8, 12) == "AVI " -> AVI
                header[0] == 0x47.toByte() -> TS
                else -> null
            }
        }

        fun fromExtension(extension: String): VideoContainer? = entries.firstOrNull {
            it.extension.equals(extension, ignoreCase = true)
        }
    }
}
