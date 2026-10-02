package dev.pschmitt.jellyfin.utils

/**
 * Human-readable, Jellyfin-library-style relative paths for downloaded files (below the
 * `downloads/` directory of a storage volume), so they're recognizable when browsing the device
 * over USB/MTP, and the folder can be dropped straight into a Jellyfin library:
 * - `Movies/Name (2019).mkv`
 * - `Shows/Series/Season 01/Series - S01E02 - Title.mkv` (`S01E02-E03` for multi-episode files)
 * - external subtitles next to their video: `<video name>.<language>.srt`
 *
 * Name collisions (two versions of the same item, two shows with the same name, stray files) are
 * resolved by [uniqueCandidates]. Downloads from before this naming existed were stored as
 * extension-less `<itemId>.<sourceId>` files - see [isLegacyFileName] and
 * DownloaderImpl.renameLegacyDownloads for their one-time migration.
 */
object DownloadFileNaming {
    const val DOWNLOADS_DIR = "downloads"
    const val PARTIAL_SUFFIX = ".download"

    // Keeps whole names comfortably below the 255-byte filename limit of ext4/exFAT/FAT, even for
    // multi-byte UTF-8 titles and with a version/counter suffix plus extension appended.
    private const val MAX_SEGMENT_LENGTH = 80

    private val forbiddenChars = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
    private val whitespace = Regex("""\s+""")

    /** Makes [name] safe as a single path segment on every filesystem Android storage may use. */
    fun sanitize(name: String): String {
        val cleaned =
            name
                .replace(forbiddenChars, "_")
                .replace(whitespace, " ")
                .trim()
                .take(MAX_SEGMENT_LENGTH)
                // Windows (and so MTP clients) can't handle names ending in dots or spaces, and a
                // leading dot would hide the file.
                .trim(' ', '.')
        return cleaned.ifEmpty { "_" }
    }

    fun movieBasePath(name: String, productionYear: Int?): String {
        val title = sanitize(if (productionYear != null) "$name ($productionYear)" else name)
        return "Movies/$title"
    }

    fun episodeBasePath(
        seriesName: String,
        seasonNumber: Int,
        episodeNumber: Int,
        episodeNumberEnd: Int?,
        episodeName: String,
    ): String {
        val series = sanitize(seriesName)
        val season = "%02d".format(seasonNumber)
        val episodes =
            "S${season}E%02d".format(episodeNumber) +
                (episodeNumberEnd?.takeIf { it > episodeNumber }?.let { "-E%02d".format(it) } ?: "")
        val title = episodeName.trim().takeIf { it.isNotEmpty() }?.let { " - ${sanitize(it)}" }
        return "Shows/$series/Season $season/${sanitize("$series - $episodes")}${title.orEmpty()}"
    }

    /**
     * Candidate paths for [basePath] in order of preference, ending in [extension] (if any): the
     * plain name, then (if given) tagged with [versionLabel] the way Jellyfin names multiple
     * versions of the same item (`Name - 1080p.mkv`), then numbered (`Name (2).mkv`, ...). Callers
     * take the first candidate that isn't taken yet.
     */
    fun uniqueCandidates(
        basePath: String,
        extension: String?,
        versionLabel: String? = null,
    ): Sequence<String> {
        val suffix = extension?.let { ".$it" }.orEmpty()
        val label =
            versionLabel
                ?.let { sanitize(it) }
                ?.takeIf { it != "_" && !basePath.endsWith(it, ignoreCase = true) }
        return sequence {
            yield(basePath + suffix)
            if (label != null) yield("$basePath - $label$suffix")
            for (n in 2..Int.MAX_VALUE) yield("$basePath ($n)$suffix")
        }
    }

    /**
     * Subtitle name next to its video ([videoBasePath] is the video's path without extension):
     * `<video name>.<language>`, the sidecar convention both Jellyfin and most players pick up.
     */
    fun subtitleBasePath(videoBasePath: String, language: String): String =
        videoBasePath +
            language.trim().takeIf { it.isNotEmpty() }?.let { ".${sanitize(it)}" }.orEmpty()

    /**
     * Extension for an external subtitle: from its delivery URL (Jellyfin serves them as
     * `.../Stream.<format>`), falling back to the codec name.
     */
    fun subtitleExtension(deliveryUrl: String?, codec: String): String? {
        val plausible = Regex("^[a-z0-9]{1,5}$")
        deliveryUrl
            ?.substringBefore('?')
            ?.substringAfterLast('/')
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { plausible.matches(it) }
            ?.let {
                return it
            }
        return when (val c = codec.lowercase()) {
            "subrip" -> "srt"
            "webvtt" -> "vtt"
            else -> c.takeIf { plausible.matches(it) }
        }
    }

    /** Whether [fileName] is a pre-readable-naming `<itemId>.<sourceId>[.<streamId>]` download. */
    fun isLegacyFileName(fileName: String, itemId: String): Boolean =
        fileName.startsWith("$itemId.", ignoreCase = true)

    /** Final path of a partial download, i.e. without its [PARTIAL_SUFFIX]. */
    fun finalPath(partialPath: String): String = partialPath.removeSuffix(PARTIAL_SUFFIX)
}
