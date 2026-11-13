package dev.ii.tools.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.clip.ClipSync
import dev.ii.tools.clip.ClipWatchService
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Clipboard: receiving the PC's, and sending this phone's (the app is in focus here). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ClipboardScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by LinkHub.status.collectAsStateWithLifecycle()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    var receives by remember { mutableStateOf(ClipSync.receives(ctx)) }
    var autoSend by remember { mutableStateOf(ClipSync.autoSend(ctx)) }
    // Re-read on every return from Settings, where both are changed.
    var watching by remember { mutableStateOf(ClipWatchService.isEnabled(ctx)) }
    var restricted by remember { mutableStateOf(ClipWatchService.restricted(ctx)) }
    LifecycleResumeEffect(Unit) {
        watching = ClipWatchService.isEnabled(ctx)
        restricted = ClipWatchService.restricted(ctx)
        onPauseOrDispose { }
    }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(result) {
        if (result != null) {
            delay(2_500)
            result = null
        }
    }

    ToolScaffold(title = stringResource(R.string.clip_title), onBack = onBack) {
        item { Hint(stringResource(R.string.clip_hint)) }
        // Automatic sending is on but its service is not: the steps, each with its button.
        if (autoSend && !watching) {
            item { GroupHeader(stringResource(R.string.setup_title)) }
            item {
                CardGroup {
                    if (restricted) {
                        PermissionRow(
                            title = stringResource(R.string.clip_step_restricted),
                            body = stringResource(R.string.clip_step_restricted_body),
                            icon = R.drawable.rounded_lock,
                            granted = false,
                            onGrant = { ctx.startActivity(ClipWatchService.appInfoIntent(ctx)) },
                        )
                    }
                    PermissionRow(
                        title = stringResource(R.string.clip_step_service),
                        body = stringResource(R.string.clip_step_service_body),
                        icon = R.drawable.rounded_content_paste,
                        granted = false,
                        onGrant = { ClipWatchService.openSettings(ctx) },
                    )
                }
            }
        }
        val ready = linkGate(pc, status, shell != null)
        if (ready && shell?.caps?.contains("clip") != true) {
            item { Hint(stringResource(R.string.clip_off)) }
            return@ToolScaffold
        }
        item {
            CardGroup {
                ToolRow(
                    title = stringResource(R.string.clip_send),
                    summary = result?.let { stringResource(it) },
                    icon = R.drawable.rounded_content_paste,
                    tint = if (result == R.string.clip_sent) Tint.PRIMARY else Tint.SECONDARY,
                    onClick = if (sending) null else {
                        {
                            sending = true
                            scope.launch {
                                result = when (ClipSync.sendCurrent(ctx)) {
                                    null -> R.string.clip_sent
                                    "empty" -> R.string.clip_empty
                                    else -> R.string.clip_failed
                                }
                                sending = false
                            }
                        }
                    },
                    trailing = when {
                        sending -> {
                            { LoadingIndicator(Modifier.size(32.dp)) }
                        }
                        result == R.string.clip_sent -> {
                            { Icon(painterResource(R.drawable.rounded_check), null, tint = MaterialTheme.colorScheme.primary) }
                        }
                        else -> null
                    },
                )
                // On needs the accessibility service: off in Settings, the switch takes you there.
                // Switching on only shows the setup steps above; nothing opens by surprise.
                val toggleAuto = { on: Boolean ->
                    autoSend = on
                    ClipSync.setAutoSend(ctx, on)
                }
                SwitchRow(
                    title = stringResource(R.string.clip_auto),
                    checked = autoSend,
                    onCheckedChange = toggleAuto,
                    icon = R.drawable.rounded_bolt,
                    summary = if (autoSend && !watching) stringResource(R.string.status_needs_setup) else null,
                )
                val toggle = { on: Boolean ->
                    receives = on
                    ClipSync.setReceives(ctx, on)
                }
                SwitchRow(
                    title = stringResource(R.string.clip_receive),
                    checked = receives,
                    onCheckedChange = toggle,
                    icon = R.drawable.rounded_download,
                )
            }
        }
    }
}
