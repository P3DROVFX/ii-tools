package dev.ii.tools.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import dev.ii.tools.mic.Mic
import dev.ii.tools.mic.MicPhase

/**
 * The microphone: the phone as the PC's microphone over the link, kept alive by a service so it
 * goes on with the screen off. While this screen is up a request from the PC starts at once.
 */
@Composable
fun MicScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status by Mic.status.collectAsStateWithLifecycle()
    val muted by Mic.muted.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(Mic.hasPermission(ctx)) }
    LifecycleResumeEffect(Unit) {
        granted = Mic.hasPermission(ctx)
        Mic.screenOpen = true
        // The "start" notification opened the screen: go live now that it is up.
        if (Mic.pendingStart && granted) {
            Mic.pendingStart = false
            Mic.start()
        }
        onPauseOrDispose { Mic.screenOpen = false }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok && Mic.pendingStart) {
            Mic.pendingStart = false
            Mic.start()
        }
    }
    val pcReady = shell?.caps?.contains("mic") == true
    val live = status.phase == MicPhase.LIVE
    val busy = status.phase == MicPhase.STARTING
    val cs = MaterialTheme.colorScheme

    ToolScaffold(title = stringResource(R.string.mic_title), onBack = onBack) {
        item { Hint(stringResource(R.string.mic_hint)) }
        if (!granted) {
            item {
                CardGroup {
                    PermissionRow(
                        title = stringResource(R.string.perm_mic),
                        body = stringResource(R.string.perm_mic_body),
                        icon = R.drawable.rounded_mic,
                        granted = false,
                        onGrant = { ask.launch(Manifest.permission.RECORD_AUDIO) },
                    )
                }
            }
        }
        item {
            CardGroup {
                ToolRow(
                    title = stringResource(
                        when {
                            live -> R.string.webcam_live
                            busy -> R.string.webcam_starting
                            status.phase == MicPhase.ASKING -> R.string.mic_asking
                            else -> R.string.mic_not_live
                        },
                    ),
                    summary = when {
                        status.phase == MicPhase.FAILED -> micError(status.error)
                        pc == null -> stringResource(R.string.webcam_not_paired)
                        !pcReady -> stringResource(R.string.mic_pc_off)
                        status.reconnecting -> stringResource(R.string.mic_reconnecting)
                        live -> stringResource(R.string.mic_stats, status.kbps)
                        else -> null
                    },
                    icon = if (live && muted) R.drawable.rounded_mic_off else if (live) R.drawable.rounded_mic_fill1 else R.drawable.rounded_mic,
                    tint = if (live) Tint.PRIMARY else Tint.SECONDARY,
                )
                if (live) {
                    val level by Mic.level.collectAsStateWithLifecycle()
                    LinearProgressIndicator(
                        progress = { if (muted) 0f else level },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (live || busy) {
                        DashedPill(
                            stringResource(R.string.webcam_stop),
                            onClick = { Mic.stop() },
                            icon = R.drawable.rounded_stop,
                            outline = cs.error,
                            content = cs.error,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        FilledPill(
                            stringResource(if (status.phase == MicPhase.FAILED) R.string.retry else R.string.webcam_start),
                            onClick = {
                                Mic.retry()
                                if (granted) Mic.start() else ask.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            icon = R.drawable.rounded_play_arrow_fill1,
                            enabled = pc != null && pcReady,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        item {
            CardGroup {
                SwitchRow(
                    title = stringResource(R.string.mic_mute),
                    summary = stringResource(R.string.mic_mute_body),
                    checked = muted,
                    onCheckedChange = Mic::setMuted,
                    icon = R.drawable.rounded_mic_off,
                )
            }
        }
    }
}

@Composable
private fun micError(code: String): String = stringResource(
    when (code) {
        "permission" -> R.string.mic_err_permission
        "mic" -> R.string.mic_err_mic
        "network" -> R.string.webcam_err_network
        "offline" -> R.string.webcam_err_offline
        "timeout" -> R.string.webcam_err_timeout
        "device" -> R.string.mic_err_device
        "busy" -> R.string.mic_err_busy
        "disabled" -> R.string.mic_pc_off
        else -> R.string.mic_err_unknown
    },
)
