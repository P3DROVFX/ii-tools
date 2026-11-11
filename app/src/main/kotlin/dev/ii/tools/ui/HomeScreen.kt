package dev.ii.tools.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
import dev.ii.tools.live.LiveHub

/**
 * The hub, as Essentials lays out its own: groups of one-line rows, no headers, no
 * descriptions. What a row needs to say beyond its name is one word of status.
 */
@Composable
fun HomeScreen(onOpenLink: () -> Unit, onOpenRemote: () -> Unit, onOpenAudio: () -> Unit, onOpenDeck: () -> Unit, onOpenWallpaper: () -> Unit, onOpenLive: () -> Unit) {
    val status = rememberDeviceStatus()
    val relayed by LiveHub.relayed.collectAsStateWithLifecycle()
    val link by LinkHub.status.collectAsStateWithLifecycle()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()

    ToolScaffold(title = stringResource(R.string.app_name)) {
        item {
            CardGroup {
                ToolRow(
                    title = pc?.name?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.link_title),
                    summary = if (pc == null) null else statusLabel(link),
                    icon = R.drawable.rounded_desktop_windows,
                    tint = if (link is LinkStatus.Online) Tint.PRIMARY else Tint.SECONDARY,
                    onClick = onOpenLink,
                )
                ToolRow(
                    title = stringResource(R.string.remote_title),
                    icon = R.drawable.rounded_settings_remote,
                    tint = Tint.PRIMARY,
                    onClick = onOpenRemote,
                )
                ToolRow(
                    title = stringResource(R.string.audio_title),
                    icon = R.drawable.rounded_volume_up,
                    tint = Tint.TERTIARY,
                    onClick = onOpenAudio,
                )
                ToolRow(
                    title = stringResource(R.string.deck_title),
                    icon = R.drawable.rounded_dashboard,
                    tint = Tint.SECONDARY,
                    onClick = onOpenDeck,
                )
                ToolRow(
                    title = stringResource(R.string.wallpaper_title),
                    icon = R.drawable.rounded_wallpaper,
                    tint = Tint.TERTIARY,
                    onClick = onOpenWallpaper,
                )
            }
        }
        item {
            CardGroup {
                ToolRow(
                    title = stringResource(R.string.live_title),
                    icon = R.drawable.rounded_notifications_active,
                    tint = Tint.TERTIARY,
                    onClick = onOpenLive,
                    trailing = {
                        when {
                            !status.liveReady -> Status(stringResource(R.string.status_needs_setup))
                            relayed.isNotEmpty() -> Status(relayed.size.toString())
                        }
                    },
                )
            }
        }
        item {
            CardGroup {
                ToolRow(
                    title = stringResource(R.string.kdeconnect),
                    summary = if (status.kdeConnectInstalled) null else stringResource(R.string.kdeconnect_missing_short),
                    icon = if (status.kdeConnectInstalled) R.drawable.rounded_devices else R.drawable.rounded_warning,
                    tint = if (status.kdeConnectInstalled) Tint.SECONDARY else Tint.ERROR,
                )
            }
        }
    }
}

@Composable
private fun Status(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}
