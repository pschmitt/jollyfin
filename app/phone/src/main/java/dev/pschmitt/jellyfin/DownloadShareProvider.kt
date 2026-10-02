package dev.pschmitt.jellyfin

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

/**
 * [FileProvider] for sharing downloaded episodes/movies with other apps. Older downloads are stored
 * as extension-less `<itemId>.<sourceId>` files (until DownloaderImpl.renameLegacyDownloads gets to
 * them), which would reach the receiving app as an opaque UUID with an `application/octet-stream`
 * type - so [uriFor] carries a human-readable display name and MIME type as query parameters, and
 * [query]/[getType] report those instead. File resolution itself only looks at the URI path, so the
 * query parameters don't affect which file is served.
 */
class DownloadShareProvider : FileProvider() {
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = super.query(uri, projection, selection, selectionArgs, sortOrder)
        val displayName = uri.getQueryParameter(PARAM_DISPLAY_NAME) ?: return cursor
        return cursor.use {
            val result = MatrixCursor(it.columnNames, 1)
            if (it.moveToFirst()) {
                result.addRow(
                    it.columnNames.mapIndexed { index, column ->
                        when {
                            column == OpenableColumns.DISPLAY_NAME -> displayName
                            it.getType(index) == Cursor.FIELD_TYPE_INTEGER -> it.getLong(index)
                            else -> it.getString(index)
                        }
                    }
                )
            }
            result
        }
    }

    override fun getType(uri: Uri): String? =
        uri.getQueryParameter(PARAM_MIME_TYPE) ?: super.getType(uri)

    companion object {
        private const val PARAM_DISPLAY_NAME = "displayName"
        private const val PARAM_MIME_TYPE = "mimeType"

        fun uriFor(context: Context, file: File, displayName: String, mimeType: String): Uri =
            getUriForFile(context, "${context.packageName}.downloadshare", file)
                .buildUpon()
                .appendQueryParameter(PARAM_DISPLAY_NAME, displayName)
                .appendQueryParameter(PARAM_MIME_TYPE, mimeType)
                .build()
    }
}
