package dev.pschmitt.jellyfin.presentation.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.presentation.theme.JollyfinTheme
import dev.pschmitt.jellyfin.presentation.theme.spacings

@Composable
fun ErrorDialog(exception: Throwable, onDismissRequest: () -> Unit) {
    val context = LocalContext.current

    Dialog(onDismissRequest = { onDismissRequest() }) {
        Card(
            modifier = Modifier.fillMaxWidth().heightIn(200.dp, max = 540.dp),
            shape = RoundedCornerShape(28.dp),
        ) {
            Column {
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.default))
                Text(
                    text = exception.message ?: stringResource(CoreR.string.unknown_error),
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(horizontal = MaterialTheme.spacings.default),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
                HorizontalDivider()
                Column(
                    modifier =
                        Modifier.weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .verticalScroll(rememberScrollState())
                ) {
                    Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
                    Text(
                        text = exception.stackTraceToString(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                HorizontalDivider()
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(
                                start = MaterialTheme.spacings.medium,
                                top = MaterialTheme.spacings.extraSmall,
                                end = MaterialTheme.spacings.medium,
                                bottom = MaterialTheme.spacings.small,
                            ),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            val sendIntent =
                                Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "${exception.message}\n ${exception.stackTraceToString()}",
                                    )
                                    type = "text/plain"
                                }
                            val shareIntent = Intent.createChooser(sendIntent, null)
                            context.startActivity(shareIntent)
                        }
                    ) {
                        Text(stringResource(CoreR.string.share))
                    }
                    TextButton(onClick = { onDismissRequest() }) {
                        Text(stringResource(CoreR.string.close))
                    }
                }
            }
        }
    }
}

/**
 * Full, selectable/copyable view of a plain error message - e.g. a Sonarr/Radarr API failure body
 * that only fits a line or two inline before it's clipped. Unlike [ErrorDialog] this has no
 * exception/stack trace to show, just the message text itself, so it skips the headline+trace split
 * in favor of one scrollable block.
 */
@Composable
fun MessageDetailsDialog(
    message: String,
    onDismissRequest: () -> Unit,
    title: String? = null,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Dialog(onDismissRequest = onDismissRequest) {
        Card(
            modifier = Modifier.fillMaxWidth().heightIn(120.dp, max = 540.dp),
            shape = RoundedCornerShape(28.dp),
        ) {
            Column {
                if (title != null) {
                    Spacer(modifier = Modifier.height(MaterialTheme.spacings.default))
                    Text(
                        text = title,
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(horizontal = MaterialTheme.spacings.default),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
                    HorizontalDivider()
                }
                Column(
                    modifier =
                        Modifier.weight(1f, fill = false)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .verticalScroll(rememberScrollState())
                            .padding(MaterialTheme.spacings.default)
                ) {
                    SelectionContainer {
                        Text(
                            text = message,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                HorizontalDivider()
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(
                                start = MaterialTheme.spacings.medium,
                                top = MaterialTheme.spacings.extraSmall,
                                end = MaterialTheme.spacings.medium,
                                bottom = MaterialTheme.spacings.small,
                            ),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { clipboardManager.setText(AnnotatedString(message)) }) {
                        Text(stringResource(CoreR.string.copy))
                    }
                    TextButton(
                        onClick = {
                            val sendIntent =
                                Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, message)
                                    type = "text/plain"
                                }
                            context.startActivity(Intent.createChooser(sendIntent, null))
                        }
                    ) {
                        Text(stringResource(CoreR.string.share))
                    }
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(CoreR.string.close))
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun ErrorDialogPreview() {
    JollyfinTheme {
        ErrorDialog(exception = Exception("Error loading data"), onDismissRequest = {})
    }
}

@Preview
@Composable
private fun MessageDetailsDialogPreview() {
    JollyfinTheme {
        MessageDetailsDialog(
            title = "Manual import failed",
            message =
                "Sonarr request to https://sonarr.example.com/api/v3/command failed with HTTP 400: " +
                    "[{\"propertyName\":\"Name\",\"errorMessage\":\"'Name' must not be empty.\"}]",
            onDismissRequest = {},
        )
    }
}
