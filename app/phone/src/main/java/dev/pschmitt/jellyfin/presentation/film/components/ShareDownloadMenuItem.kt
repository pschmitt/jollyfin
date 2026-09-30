package dev.pschmitt.jellyfin.presentation.film.components

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.pschmitt.jellyfin.DownloadShareProvider
import dev.pschmitt.jellyfin.core.R as CoreR
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Overflow-menu entry that hands a downloaded file at [path] to the system share sheet, named
 * [title] (plus a container extension sniffed from the file header, since downloads are stored
 * without one).
 */
@Composable
fun ShareDownloadMenuItem(path: String, title: String, closeMenu: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    DropdownMenuItem(
        text = { Text(stringResource(CoreR.string.share)) },
        leadingIcon = {
            Icon(painter = painterResource(CoreR.drawable.ic_share), contentDescription = null)
        },
        onClick = {
            closeMenu()
            scope.launch { shareDownload(context, File(path), title) }
        },
    )
}

private suspend fun shareDownload(context: Context, file: File, title: String) {
    val container = withContext(Dispatchers.IO) { sniffContainer(file) }
    val displayName =
        title.replace(Regex("""[\\/:*?"<>|]"""), "_").trim() +
            container?.let { ".${it.extension}" }.orEmpty()
    val mimeType = container?.mimeType ?: "video/*"
    val uri =
        try {
            DownloadShareProvider.uriFor(context, file, displayName, mimeType)
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "Cannot share download outside of configured paths: %s", file)
            return
        }
    val sendIntent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, displayName)
            clipData = ClipData.newRawUri(displayName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(sendIntent, null))
}

private enum class Container(val extension: String, val mimeType: String) {
    MKV("mkv", "video/x-matroska"),
    MP4("mp4", "video/mp4"),
    AVI("avi", "video/x-msvideo"),
    TS("ts", "video/mp2t"),
}

private fun sniffContainer(file: File): Container? {
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
            header[3] == 0xA3.toByte() -> Container.MKV
        ascii(4, 8) == "ftyp" -> Container.MP4
        ascii(0, 4) == "RIFF" && ascii(8, 12) == "AVI " -> Container.AVI
        header[0] == 0x47.toByte() -> Container.TS
        else -> null
    }
}
