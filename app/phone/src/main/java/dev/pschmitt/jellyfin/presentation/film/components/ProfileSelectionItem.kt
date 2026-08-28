package dev.pschmitt.jellyfin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.models.Profile
import dev.pschmitt.jellyfin.models.ProfileWithUserAndServer
import dev.pschmitt.jellyfin.presentation.theme.JollyfinTheme
import dev.pschmitt.jellyfin.presentation.theme.spacings
import java.util.UUID
import org.jellyfin.sdk.model.api.ImageType

/**
 * A single row in the Profiles switcher (home header bottom sheet + Settings > Profiles list) -
 * Material 3, tonal surface + checkmark for the selected state rather than an outline, matching a
 * modern M3 "selectable card" look.
 */
@Composable
fun ProfileSelectionItem(
    profile: ProfileWithUserAndServer,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    baseUrl: String = "",
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 1.dp else 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier.size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primaryContainer
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = profile.profile.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color =
                        if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                )
                // Layered on top of the initials once it loads - Coil renders nothing over them
                // for a user with no picture set, so no separate "has an image" check is needed.
                if (baseUrl.isNotBlank()) {
                    AsyncImage(
                        model =
                            "$baseUrl/users/${profile.profile.userId}/Images/${ImageType.PRIMARY}",
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(modifier = Modifier.width(MaterialTheme.spacings.medium))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (profile.profile.isMain) {
                        Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = {
                                Text(text = stringResource(CoreR.string.profile_main_badge))
                            },
                            colors =
                                AssistChipDefaults.assistChipColors(
                                    disabledContainerColor =
                                        MaterialTheme.colorScheme.tertiaryContainer,
                                    disabledLabelColor =
                                        MaterialTheme.colorScheme.onTertiaryContainer,
                                ),
                            border = null,
                        )
                    }
                }
                Text(
                    text = profile.serverName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                Icon(
                    painter = painterResource(CoreR.drawable.ic_check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
@Preview
private fun ProfileSelectionItemPreview() {
    JollyfinTheme {
        ProfileSelectionItem(
            profile = dummyProfileWithUserAndServer,
            selected = false,
            onClick = {},
        )
    }
}

@Composable
@Preview
private fun ProfileSelectionItemSelectedMainPreview() {
    JollyfinTheme {
        ProfileSelectionItem(
            profile =
                dummyProfileWithUserAndServer.copy(
                    profile = dummyProfileWithUserAndServer.profile.copy(isMain = true)
                ),
            selected = true,
            onClick = {},
        )
    }
}

private val dummyProfileWithUserAndServer =
    ProfileWithUserAndServer(
        profile =
            Profile(id = UUID.randomUUID(), name = "jelly@Home Server", userId = UUID.randomUUID()),
        userName = "jelly",
        serverId = "server-1",
        serverName = "Home Server",
    )
