package dev.pschmitt.jellyfin.presentation.film.components

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.pschmitt.jellyfin.api.pvr.PvrService
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.presentation.pvr.displayName
import dev.pschmitt.jellyfin.presentation.pvr.icon

/** One "Open in Sonarr/Radarr/Seerr" overflow entry per service in [services] (JF-94). */
@Composable
fun OpenInWebUiMenuItems(
    services: List<PvrService>,
    closeMenu: () -> Unit,
    onClick: (PvrService) -> Unit,
) {
    services.forEach { service ->
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(
                        CoreR.string.pvr_web_ui_open_in,
                        stringResource(service.displayName),
                    )
                )
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(service.icon),
                    contentDescription = null,
                    tint = Color.Unspecified,
                )
            },
            onClick = {
                closeMenu()
                onClick(service)
            },
        )
    }
}
