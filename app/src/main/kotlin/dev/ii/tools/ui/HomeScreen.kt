package dev.ii.tools.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import dev.ii.tools.R
import dev.ii.tools.clip.ClipSync
import dev.ii.tools.clip.ClipWatchService
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
import dev.ii.tools.live.LiveHub

/**
 * The hub, as Essentials lays out its own: groups of one-line rows, no headers, no
 * descriptions. What a row needs to say beyond its name is one word of status.
 */
@Composable
fun HomeScreen(onOpenLink: () -> Unit, onOpenRemote: () -> Unit, onOpenAudio: () -> Unit, onOpenDeck: () -> Unit, onOpenWallpaper: () -> Unit, onOpenCalls: () -> Unit, onOpenNotifs: () -> Unit, onOpenClipboard: () -> Unit, onOpenShots: () -> Unit, onOpenPhoneData: () -> Unit, onOpenPcLink: () -> Unit, onOpenPerf: () -> Unit, onOpenLive: () -> Unit, onOpenTouchpad: () -> Unit) {
    val status = rememberDeviceStatus()
    val relayed by LiveHub.relayed.collectAsStateWithLifecycle()
    val link by LinkHub.status.collectAsStateWithLifecycle()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var clipNeedsSetup by remember { mutableStateOf(false) }
    var linkNeedsSetup by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        linkNeedsSetup = LinkHub.staysConnected() && !LinkHub.batteryUnrestricted()
        clipNeedsSetup = ClipSync.autoSend(ctx) && !ClipWatchService.isEnabled(ctx)
        onPauseOrDispose { }
    }

    ToolScaffold(title = stringResource(R.string.app_name)) {
        item {
            CardGroup {
                ToolRow(
                    title = pc?.name?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.link_title),
                    summary = if (pc == null) null else statusLabel(link),
                    icon = R.drawable.rounded_desktop_windows,
                    tint = if (link is LinkStatus.Online) Tint.PRIMARY else Tint.SECONDARY,
                    onClick = onOpenLink,
                    trailing = if (pc != null && linkNeedsSetup) {
                        { Status(stringResource(R.string.status_needs_setup)) }
                    } else null,
                )
                ToolRow(
                    title = stringResource(R.string.remote_title),
                    icon = R.drawable.rounded_settings_remote,
                    tint = Tint.PRIMARY,
                    onClick = onOpenRemote,
                )
                ToolRow(
                    title = stringResource(R.string.touchpad_title),
                    icon = R.drawable.rounded_touch_app,
                    tint = Tint.PRIMARY,
                    onClick = onOpenTouchpad,
                )
                ToolRow(
                    title = stringResource(R.string.audio_title),
                    icon = R.drawable.rounded_volume_up,
                    tint = Tint.TERTIARY,
                    onClick = onOpenAudio,
                )
                ToolRow(
                    title = stringResource(R.string.perf_title),
                    icon = R.drawable.rounded_speed,
                    tint = Tint.TERTIARY,
                    onClick = onOpenPerf,
                )
                ToolRow(
                    title = stringResource(R.string.deck_title),
                    icon = R.drawable.rounded_dashboard,
                    tint = Tint.SECONDARY,
                    onClick = onOpenDeck,
                )
                ToolRow(
                    title = stringResource(R.string.clip_title),
                    icon = R.drawable.rounded_content_paste,
                    tint = Tint.SECONDARY,
                    onClick = onOpenClipboard,
                    trailing = if (clipNeedsSetup) {
                        { Status(stringResource(R.string.status_needs_setup)) }
                    } else null,
                )
                ToolRow(
                    title = stringResource(R.string.shots_title),
                    icon = R.drawable.rounded_screenshot,
                    tint = Tint.TERTIARY,
                    onClick = onOpenShots,
                )
                ToolRow(
                    title = stringResource(R.string.wallpaper_title),
                    icon = R.drawable.rounded_wallpaper,
                    tint = Tint.TERTIARY,
                    onClick = onOpenWallpaper,
                )
                ToolRow(
                    title = stringResource(R.string.pclink_title),
                    icon = R.drawable.rounded_timer,
                    tint = Tint.TERTIARY,
                    onClick = onOpenPcLink,
                )
                ToolRow(
                    title = stringResource(R.string.data_title),
                    icon = R.drawable.rounded_contacts,
                    tint = Tint.SECONDARY,
                    onClick = onOpenPhoneData,
                )
                ToolRow(
                    title = stringResource(R.string.calls_title),
                    icon = R.drawable.rounded_call,
                    tint = Tint.PRIMARY,
                    onClick = onOpenCalls,
                )
            }
        }
        item {
            CardGroup {
                ToolRow(
                    title = stringResource(R.string.notifs_title),
                    icon = R.drawable.rounded_notifications,
                    tint = Tint.PRIMARY,
                    onClick = onOpenNotifs,
                )
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
