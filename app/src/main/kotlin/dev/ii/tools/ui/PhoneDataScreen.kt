package dev.ii.tools.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import dev.ii.tools.phonedata.PhoneData
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Phone data: the switch, and the contacts permission. */
@Composable
fun PhoneDataScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status = rememberDeviceStatus()
    var enabled by remember { mutableStateOf(PhoneData.isEnabled(ctx)) }
    var contacts by remember { mutableStateOf(PhoneData.canReadContacts(ctx)) }
    LifecycleResumeEffect(Unit) {
        contacts = PhoneData.canReadContacts(ctx)
        onPauseOrDispose { }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        contacts = granted
        // Tell the PC it can ask for contacts now.
        LinkHub.announceCaps()
    }

    ToolScaffold(title = stringResource(R.string.data_title), onBack = onBack) {
        item { Hint(stringResource(R.string.data_hint)) }
        if (!contacts || !status.listenerGranted) {
            item { GroupHeader(stringResource(R.string.setup_title)) }
            item {
                CardGroup {
                    PermissionRow(
                        title = stringResource(R.string.perm_contacts),
                        body = stringResource(R.string.perm_contacts_body),
                        icon = R.drawable.rounded_contacts,
                        granted = contacts,
                        onGrant = { ask.launch(PhoneData.CONTACTS) },
                    )
                    PermissionRow(
                        title = stringResource(R.string.perm_listener),
                        body = stringResource(R.string.perm_listener_calls),
                        icon = R.drawable.rounded_notifications,
                        granted = status.listenerGranted,
                        onGrant = { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    )
                }
            }
        }
        item {
            CardGroup {
                val toggle = { on: Boolean ->
                    enabled = on
                    PhoneData.setEnabled(ctx, on)
                }
                SwitchRow(
                    title = stringResource(R.string.data_share),
                    checked = enabled,
                    onCheckedChange = toggle,
                    icon = R.drawable.rounded_battery_full,
                )
            }
        }
    }
}
