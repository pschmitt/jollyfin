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
import dev.pschmitt.jellyfin.utils.VideoContainer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Overflow-menu entry that hands a downloaded file at [path] to the system share sheet, named
 * [title] plus the file's container extension (taken from the file name, or sniffed from the file
 * header for downloads whose extension isn't known).
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
    val container =
        VideoContainer.fromExtension(file.extension)
            ?: withContext(Dispatchers.IO) { VideoContainer.sniff(file) }
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
