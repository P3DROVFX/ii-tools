package dev.ii.tools.ui

import android.app.Activity
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.vitals.HealthConnectSource
import dev.ii.tools.vitals.SamsungSource
import dev.ii.tools.vitals.Vitals
import kotlinx.coroutines.launch

/** ii Vitals: the two health sources, and the switches for what goes to the PC. */
@Composable
fun VitalsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var shared by remember { mutableStateOf(Vitals.isShared(ctx)) }
    var routes by remember { mutableStateOf(Vitals.routesOn(ctx)) }
    val state by Vitals.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        Vitals.onPermissionChanged(ctx)
        onPauseOrDispose { }
    }
    val askHc = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        Vitals.onPermissionChanged(ctx)
    }
    val hc = state.healthConnect
    val samsung = state.samsung
    val readable = samsung == "on" || hc == "granted" || hc == "partial"

    ToolScaffold(title = stringResource(R.string.vitals_title), onBack = onBack, subtitle = vitalsStatus(shared, readable, state)) {
        item { Hint(stringResource(R.string.vitals_hint)) }
        if (shared && readable && state.pcs.isEmpty()) item { Hint(stringResource(R.string.vitals_waiting_hint)) }
        item { GroupHeader(stringResource(R.string.vitals_sources)) }
        item {
            CardGroup {
                val hcBody = stringResource(
                    when (hc) {
                        "granted" -> R.string.vitals_hc_granted
                        "partial" -> R.string.vitals_hc_partial
                        "unavailable" -> R.string.vitals_hc_unavailable
                        else -> R.string.vitals_hc_denied
                    },
                )
                if (hc == "unavailable") {
                    ToolRow(title = stringResource(R.string.vitals_hc), summary = hcBody, icon = R.drawable.rounded_shield)
                } else {
                    PermissionRow(stringResource(R.string.vitals_hc), hcBody, R.drawable.rounded_shield, hc == "granted") {
                        askHc.launch(HealthConnectSource.permissions(ctx))
                    }
                }
                val samsungBody = stringResource(
                    when (samsung) {
                        "on" -> R.string.vitals_samsung_on
                        "notBuiltIn" -> R.string.vitals_samsung_not_built
                        "notInstalled" -> R.string.vitals_samsung_not_installed
                        "devModeOff" -> R.string.vitals_samsung_dev_mode
                        "noPermission" -> R.string.vitals_samsung_permission
                        else -> R.string.vitals_samsung_error
                    },
                )
                if (samsung == "notBuiltIn" || samsung == "notInstalled" || samsung == "devModeOff") {
                    ToolRow(title = stringResource(R.string.vitals_samsung), summary = samsungBody, icon = R.drawable.rounded_watch)
                } else {
                    PermissionRow(stringResource(R.string.vitals_samsung), samsungBody, R.drawable.rounded_watch, samsung == "on") {
                        val activity = ctx as? Activity ?: return@PermissionRow
                        scope.launch {
                            SamsungSource.requestPermissions(activity)
                            Vitals.onPermissionChanged(ctx)
                        }
                    }
                }
            }
        }
        item {
            CardGroup {
                SwitchRow(
                    title = stringResource(R.string.vitals_share),
                    checked = shared,
                    onCheckedChange = {
                        shared = it
                        Vitals.setShared(ctx, it)
                    },
                    icon = R.drawable.rounded_share,
                    enabled = readable,
                    summary = if (!readable) stringResource(R.string.vitals_status_perm) else null,
                )
                SwitchRow(
                    title = stringResource(R.string.vitals_routes),
                    checked = routes,
                    onCheckedChange = {
                        routes = it
                        Vitals.setRoutes(ctx, it)
                        // Health Connect asks for routes separately; Samsung's sheet already listed them.
                        if (it && hc != "unavailable") askHc.launch(setOf(HealthConnectSource.ROUTES))
                    },
                    icon = R.drawable.rounded_location_on,
                    enabled = shared,
                    summary = stringResource(R.string.vitals_routes_body),
                )
            }
        }
    }
}

/** One line on what the PCs get: off, waiting for one to ask, or what went out last. */
@Composable
private fun vitalsStatus(shared: Boolean, readable: Boolean, state: Vitals.State): String {
    val ctx = LocalContext.current
    return when {
        !readable -> stringResource(R.string.vitals_status_perm)
        !shared -> stringResource(R.string.vitals_status_off)
        state.pcs.isEmpty() -> stringResource(R.string.vitals_status_waiting)
        state.sentAt == 0L -> stringResource(R.string.vitals_status_asked, state.pcs.joinToString(", "))
        else -> stringResource(
            R.string.vitals_status_sent,
            state.pcs.joinToString(", "),
            DateFormat.getTimeFormat(ctx).format(java.util.Date(state.sentAt)),
        )
    }
}
