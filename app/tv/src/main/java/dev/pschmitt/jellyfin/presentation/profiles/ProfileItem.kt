package dev.pschmitt.jellyfin.presentation.profiles

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.models.Profile
import dev.pschmitt.jellyfin.models.ProfileWithUserAndServer
import dev.pschmitt.jellyfin.presentation.theme.JollyfinTheme
import dev.pschmitt.jellyfin.presentation.theme.spacings
import java.util.UUID

/**
 * TV row/card for one [ProfileWithUserAndServer] - style-matched to
 * [dev.pschmitt.jellyfin.presentation.setup.components.ServerItem]'s Surface + rounded-card look.
 * The whole card is a single D-pad focus target: clicking it switches to this profile (see
 * [ProfilesScreen] for the sibling "Manage" button that opens [ProfileDetailScreen] instead,
 * matching TV's convention of keeping clickable surfaces flat rather than nesting a second
 * focusable control inside this one).
 */
@Composable
fun ProfileItem(
    profile: ProfileWithUserAndServer,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    serverVersion: String? = null,
) {
    Surface(
        onClick = onClick,
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor =
                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else Color(0xFF132026),
                focusedContainerColor =
                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else Color(0xFF132026),
            ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
        border =
            ClickableSurfaceDefaults.border(
                focusedBorder =
                    Border(BorderStroke(4.dp, Color.White), shape = RoundedCornerShape(16.dp))
            ),
        modifier = modifier.width(260.dp),
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
            }
            Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.profile.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.serverName,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFBDBDBD),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (profile.profile.isMain) {
                        Spacer(modifier = Modifier.width(MaterialTheme.spacings.extraSmall))
                        Text(
                            text = stringResource(CoreR.string.profile_main_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier =
                                Modifier.background(
                                        MaterialTheme.colorScheme.tertiaryContainer,
                                        RoundedCornerShape(4.dp),
                                    )
                                    .padding(horizontal = 4.dp),
                        )
                    }
                }
                if (!serverVersion.isNullOrBlank()) {
                    Text(
                        text = stringResource(CoreR.string.profile_server_version, serverVersion),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFBDBDBD),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.extraSmall))
                Icon(
                    painter = painterResource(CoreR.drawable.ic_check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Preview
@Composable
private fun ProfileItemPreview() {
    JollyfinTheme {
        ProfileItem(profile = dummyProfileWithUserAndServer, selected = false, onClick = {})
    }
}

@Preview
@Composable
private fun ProfileItemSelectedMainPreview() {
    JollyfinTheme {
        ProfileItem(
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
        profile = Profile(id = UUID.randomUUID(), name = "jelly", userId = UUID.randomUUID()),
        userName = "jelly",
        serverId = "server-1",
        serverName = "Home Server",
    )
