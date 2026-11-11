package dev.ii.tools.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.link.AudioNode
import dev.ii.tools.link.EasyEffectsState
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.PcAudio
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** The PC's audio: volume and mic, which device plays, each app, EasyEffects. */
@Composable
fun AudioScreen(onBack: () -> Unit) {
    val status by LinkHub.status.collectAsStateWithLifecycle()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    val audio by LinkHub.audio.collectAsStateWithLifecycle()

    ToolScaffold(title = stringResource(R.string.audio_title), onBack = onBack) {
        if (!linkGate(pc, status, shell != null)) return@ToolScaffold
        if (shell?.caps?.contains("audio") != true) {
            item { Hint(stringResource(R.string.audio_off)) }
            return@ToolScaffold
        }
        val a = audio ?: return@ToolScaffold

        item { VolumeGroup(a) }

        item { GroupHeader(stringResource(R.string.audio_output)) }
        item { DeviceGroup(a.outputs, a.out, R.drawable.rounded_speaker) }

        item { GroupHeader(stringResource(R.string.audio_input)) }
        item { DeviceGroup(a.inputs, a.input, R.drawable.rounded_mic) }

        item { GroupHeader(stringResource(R.string.audio_apps)) }
        item {
            if (a.apps.isEmpty()) {
                Hint(stringResource(R.string.audio_no_apps))
            } else {
                CardGroup { a.apps.forEach { VolumeBlock(it, it.name, R.drawable.rounded_volume_up, R.drawable.rounded_volume_off) } }
            }
        }

        a.ee?.let { ee ->
            item { GroupHeader(stringResource(R.string.easyeffects)) }
            item { EffectsGroup(ee) }
        }
    }
}

/** The two sliders that matter most: what plays, and the mic. */
@Composable
private fun VolumeGroup(a: PcAudio) {
    CardGroup {
        a.output?.let { VolumeBlock(it, stringResource(R.string.audio_volume), R.drawable.rounded_volume_up, R.drawable.rounded_volume_off) }
        a.mic?.let { VolumeBlock(it, stringResource(R.string.audio_mic), R.drawable.rounded_mic, R.drawable.rounded_mic_off) }
    }
}

/**
 * A title over a mute toggle and a slider. The slider follows the finger, not the PC,
 * while it is held, and for a moment after, until the PC's push catches up; sends are
 * throttled to ~20/s and the last value always goes out.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VolumeBlock(node: AudioNode, title: String, @DrawableRes icon: Int, @DrawableRes mutedIcon: Int) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var held by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(node.vol) }
    var settling by remember { mutableStateOf(false) }
    var lastSent by remember { mutableLongStateOf(0L) }

    LaunchedEffect(node.vol, held, settling) {
        if (!held && (!settling || abs(node.vol - local) < 0.015f)) {
            settling = false
            local = node.vol
        }
    }
    LaunchedEffect(settling) {
        if (settling) {
            delay(800)
            settling = false
        }
    }
    val send = { v: Float -> LinkHub.send("volume", JSONObject().put("id", node.id).put("vol", v.toDouble())) }

    ToolBlock {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${(local * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalIconToggleButton(
                checked = node.mute,
                onCheckedChange = {
                    haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    LinkHub.send("mute", JSONObject().put("id", node.id).put("mute", it))
                },
                colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                    checkedContainerColor = cs.errorContainer,
                    checkedContentColor = cs.onErrorContainer,
                ),
            ) {
                Icon(painterResource(if (node.mute) mutedIcon else icon), stringResource(R.string.audio_mute))
            }
            Slider(
                value = local,
                onValueChange = { v ->
                    held = true
                    local = v
                    val now = System.currentTimeMillis()
                    if (now - lastSent >= 50) {
                        lastSent = now
                        send(v)
                    }
                },
                onValueChangeFinished = {
                    send(local)
                    held = false
                    settling = true
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** One row per device; the default one carries the check. */
@Composable
private fun DeviceGroup(devices: List<AudioNode>, selected: Int, @DrawableRes icon: Int) {
    val haptics = LocalHapticFeedback.current
    var wanted by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(selected) { wanted = null }
    val current = wanted ?: selected
    CardGroup {
        devices.forEach { dev ->
            val on = dev.id == current
            ToolRow(
                title = dev.name,
                icon = icon,
                tint = if (on) Tint.PRIMARY else Tint.SECONDARY,
                onClick = if (on) null else {
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        wanted = dev.id
                        LinkHub.send("default", dev.id)
                    }
                },
                trailing = if (on) {
                    { Icon(painterResource(R.drawable.rounded_check), null, tint = MaterialTheme.colorScheme.primary) }
                } else null,
            )
        }
    }
}

/** EasyEffects: effects on or bypassed, and which output preset. */
@Composable
private fun EffectsGroup(ee: EasyEffectsState) {
    var wanted by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ee.preset) { wanted = null }
    val current = wanted ?: ee.preset
    CardGroup {
        ToolRow(
            title = stringResource(R.string.audio_effects),
            icon = R.drawable.rounded_graphic_eq,
            tint = if (ee.on) Tint.PRIMARY else Tint.SECONDARY,
            onClick = { LinkHub.send("eeBypass", ee.on) },
            trailing = { Switch(checked = ee.on, onCheckedChange = { LinkHub.send("eeBypass", !it) }) },
        )
        ee.presets.forEach { name ->
            val on = name == current
            ToolRow(
                title = name,
                icon = R.drawable.rounded_tune,
                tint = if (on) Tint.PRIMARY else Tint.SECONDARY,
                onClick = if (on) null else {
                    {
                        wanted = name
                        LinkHub.send("eePreset", name)
                    }
                },
                trailing = if (on) {
                    { Icon(painterResource(R.drawable.rounded_check), null, tint = MaterialTheme.colorScheme.primary) }
                } else null,
            )
        }
    }
}
