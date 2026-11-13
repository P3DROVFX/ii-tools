package dev.ii.tools.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.live.LiveHub
import dev.ii.tools.notify.NotifMirror
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Notifications on the PC: the switch, and the access the mirror needs. */
@Composable
fun NotifsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status = rememberDeviceStatus()
    val listening by LiveHub.connected.collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf(NotifMirror.isEnabled(ctx)) }

    ToolScaffold(title = stringResource(R.string.notifs_title), onBack = onBack) {
        item { Hint(stringResource(R.string.notifs_hint)) }
        if (!status.listenerGranted) {
            item { GroupHeader(stringResource(R.string.setup_title)) }
            item {
                CardGroup {
                    PermissionRow(
                        title = stringResource(R.string.perm_listener),
                        body = stringResource(R.string.perm_listener_notifs),
                        icon = R.drawable.rounded_notifications,
                        granted = false,
                        onGrant = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    )
                }
            }
        }
        item {
            CardGroup {
                val toggle = { on: Boolean ->
                    enabled = on
                    NotifMirror.setEnabled(ctx, on)
                }
                SwitchRow(
                    title = stringResource(R.string.notifs_mirror),
                    checked = enabled,
                    onCheckedChange = toggle,
                    icon = R.drawable.rounded_notifications,
                )
            }
        }
    }
}
