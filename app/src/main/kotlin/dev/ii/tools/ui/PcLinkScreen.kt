package dev.ii.tools.ui

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.ii.tools.R
import dev.ii.tools.pclive.DndSync
import dev.ii.tools.pclive.PcLive

/** PC activity: its live updates here, and Do Not Disturb in step. */
@Composable
fun PcLinkScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status = rememberDeviceStatus()
    var live by remember { mutableStateOf(PcLive.isEnabled(ctx)) }
    var dnd by remember { mutableStateOf(DndSync.isEnabled(ctx)) }
    var media by remember { mutableStateOf(dev.ii.tools.media.PhoneMedia.isEnabled(ctx)) }
    var pcMedia by remember { mutableStateOf(dev.ii.tools.media.PcMedia.isEnabled(ctx)) }

    ToolScaffold(title = stringResource(R.string.pclink_title), onBack = onBack) {
        item { Hint(stringResource(R.string.pclink_hint)) }
        if (!status.listenerGranted) {
            item { GroupHeader(stringResource(R.string.setup_title)) }
            item {
                CardGroup {
                    PermissionRow(
                        title = stringResource(R.string.perm_listener),
                        body = stringResource(R.string.perm_listener_calls),
                        icon = R.drawable.rounded_notifications,
                        granted = false,
                        onGrant = { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    )
                }
            }
        }
        item {
            CardGroup {
                val toggleLive = { on: Boolean ->
                    live = on
                    PcLive.setEnabled(ctx, on)
                }
                ToolRow(
                    title = stringResource(R.string.pclink_live),
                    icon = R.drawable.rounded_timer,
                    tint = if (live) Tint.PRIMARY else Tint.SECONDARY,
                    onClick = { toggleLive(!live) },
                    trailing = { Switch(checked = live, onCheckedChange = toggleLive) },
                )
                val toggleMedia = { on: Boolean ->
                    media = on
                    dev.ii.tools.media.PhoneMedia.setEnabled(ctx, on)
                }
                ToolRow(
                    title = stringResource(R.string.pclink_media),
                    icon = R.drawable.rounded_music_note,
                    tint = if (media) Tint.PRIMARY else Tint.SECONDARY,
                    onClick = { toggleMedia(!media) },
                    trailing = { Switch(checked = media, onCheckedChange = toggleMedia) },
                )
                val togglePcMedia = { on: Boolean ->
                    pcMedia = on
                    dev.ii.tools.media.PcMedia.setEnabled(ctx, on)
                }
                ToolRow(
                    title = stringResource(R.string.pclink_pc_media),
                    icon = R.drawable.rounded_queue_music,
                    tint = if (pcMedia) Tint.PRIMARY else Tint.SECONDARY,
                    onClick = { togglePcMedia(!pcMedia) },
                    trailing = { Switch(checked = pcMedia, onCheckedChange = togglePcMedia) },
                )
                val toggleDnd = { on: Boolean ->
                    dnd = on
                    DndSync.setEnabled(ctx, on)
                }
                ToolRow(
                    title = stringResource(R.string.pclink_dnd),
                    icon = R.drawable.rounded_do_not_disturb_on,
                    tint = if (dnd) Tint.PRIMARY else Tint.SECONDARY,
                    onClick = { toggleDnd(!dnd) },
                    trailing = { Switch(checked = dnd, onCheckedChange = toggleDnd) },
                )
            }
        }
    }
}
