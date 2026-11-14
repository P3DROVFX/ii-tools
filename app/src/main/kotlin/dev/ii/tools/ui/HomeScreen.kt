@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.ii.tools.ui

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.clip.ClipSync
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
import dev.ii.tools.link.Look
import dev.ii.tools.link.PairedPc
import dev.ii.tools.link.PcAudio
import dev.ii.tools.link.PcPerf
import dev.ii.tools.link.ShellInfo
import dev.ii.tools.link.Wake
import dev.ii.tools.media.PcMedia
import dev.ii.tools.pclive.PcLive
import dev.ii.tools.pclive.PcLiveItem
import dev.ii.tools.ui.theme.Expressive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * The home: a short dashboard of the PC. Not paired, it is the pairing screen; paired
 * but away, the PC's state with a way to wake it. Every card opens its own screen, and
 * everything else is one tap away in Tools.
 */
@Composable
fun HomeScreen(onOpen: (Dest) -> Unit) {
    val status by LinkHub.status.collectAsStateWithLifecycle()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    val scanner = rememberPairScanner()
    val network = rememberNetworkPermission()
    val online = status is LinkStatus.Online && shell != null
    // The PC samples its load only while a screen shows it.
    val showsPerf = online && shell?.caps?.contains("perf") == true
    LifecycleResumeEffect(showsPerf) {
        if (showsPerf) LinkHub.subscribePerf(true)
        onPauseOrDispose { if (showsPerf) LinkHub.subscribePerf(false) }
    }

    Scaffold(containerColor = Layer.page) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "bar") { HomeBar(paired = pc != null, onOpen = onOpen) }
            val current = pc
            when {
                current == null -> item(key = "pair") { PairContent(status, scanner, network) }
                online -> dashboard(current, shell!!, onOpen)
                else -> away(current, status, onOpen)
            }
        }
    }
}

@Composable
private fun HomeBar(paired: Boolean, onOpen: (Dest) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.app_name), style = Expressive.xwide(28.sp), modifier = Modifier.weight(1f))
        if (paired) {
            BarButton(R.drawable.rounded_content_paste_go, stringResource(R.string.clip_send)) {
                scope.launch {
                    val msg = when (ClipSync.sendCurrent(ctx)) {
                        null -> R.string.clip_sent
                        "empty" -> R.string.clip_empty
                        else -> R.string.clip_failed
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
        BarButton(R.drawable.rounded_settings, stringResource(R.string.settings_title)) { onOpen(Dest.SETTINGS) }
    }
}

@Composable
private fun BarButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    FilledIconButton(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onClick()
        },
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Layer.pane, contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.size(48.dp),
    ) { Icon(painterResource(icon), label, Modifier.size(22.dp)) }
}

// ── Connected ─────────────────────────────────────────────────────────────

private fun LazyListScope.dashboard(pc: PairedPc, shell: ShellInfo, onOpen: (Dest) -> Unit) {
    item(key = "hero") { PcHero(pc, shell, onOpen) }
    item(key = "media") { MediaCard(onOpen) }
    if ("audio" in shell.caps) item(key = "audio") { AudioCard(onOpen) }
    item(key = "live") { LiveCards() }
    item(key = "toolsHeader") { SectionHeader(stringResource(R.string.tools_title), stringResource(R.string.tools_all)) { onOpen(Dest.TOOLS) } }
    item(key = "tools") { ToolsStrip(onOpen) }
    item(key = "monitor") { SecondScreenCard(onOpen) }
    if ("remote" in shell.caps && shell.actions.any { it.id != "lock" }) {
        item(key = "controlsHeader") { SectionHeader(stringResource(R.string.controls_title), stringResource(R.string.remote_short)) { onOpen(Dest.REMOTE) } }
        item(key = "controls") { ControlsRow(shell) }
    }
    item(key = "webcam") { WebcamCard(onOpen) }
    if ("perf" in shell.caps) item(key = "perf") { PerfCard(onOpen) }
}

/** The PC, big: where the shell's wallpaper will go, its name and the lock. */
@Composable
private fun PcHero(pc: PairedPc, shell: ShellInfo, onOpen: (Dest) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val status by LinkHub.status.collectAsStateWithLifecycle()
    val active by LinkHub.active.collectAsStateWithLifecycle()
    val workspaces by LinkHub.workspaces.collectAsStateWithLifecycle()
    val modes by LinkHub.modes.collectAsStateWithLifecycle()
    val runner = rememberActionRunner()
    val lock = shell.actions.firstOrNull { it.id == "lock" }
    val subtitle = listOfNotNull(
        workspaces?.active?.takeIf { it > 0 }?.let { stringResource(R.string.home_workspace, it) },
        modes?.let { m -> m.list.firstOrNull { it.id == m.active }?.name },
    ).joinToString(" · ")
    val wall by Look.wallpaper.collectAsStateWithLifecycle()
    val wallImage = remember(wall) { wall?.asImageBitmap() }
    ExpressiveCard(cs.primaryContainer, cs.onPrimaryContainer, Modifier.height(260.dp)) {
        if (wallImage != null) {
            Image(wallImage, stringResource(R.string.home_wallpaper), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            // Until the shell has sent its wallpaper (`look`), its shapes stand in.
            Ornament(MaterialShapes.Cookie12Sided, 400.dp, cs.onPrimaryContainer, Alignment.TopEnd, x = 180.dp, y = (-120).dp, alpha = 0.14f)
            Ornament(MaterialShapes.Flower, 260.dp, cs.onPrimaryContainer, Alignment.BottomStart, x = (-110).dp, y = 150.dp, alpha = 0.08f)
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(CircleShape).background(cs.surfaceContainerHigh).padding(start = 12.dp, end = 14.dp).height(36.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(cs.primary))
                Text(
                    listOfNotNull(stringResource(R.string.status_online), (status as? LinkStatus.Online)?.host).joinToString(" · "),
                    style = Expressive.title(14.sp),
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { onOpen(Dest.WALLPAPER) },
                colors = IconButtonDefaults.iconButtonColors(containerColor = cs.surfaceContainerHigh, contentColor = cs.onSurface),
            ) { Icon(painterResource(R.drawable.rounded_wallpaper), stringResource(R.string.wallpaper_title), Modifier.size(20.dp)) }
        }
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp).height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerHigh).clickable { onOpen(Dest.SETTINGS) }.padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text(pc.name.ifEmpty { shell.name }, style = Expressive.xwide(30.sp), color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (lock != null) {
                val locked = lock.id in active
                val note = runner.notes[lock.id]
                Column(
                    Modifier
                        .width(88.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (note != null) cs.errorContainer else cs.primary)
                        .clickable { runner.run(lock.id) }
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val content = if (note != null) cs.onErrorContainer else cs.onPrimary
                    Icon(painterResource(if (locked) R.drawable.rounded_lock_fill1 else R.drawable.rounded_lock), null, tint = content, modifier = Modifier.size(28.dp))
                    Text(note ?: stringResource(if (locked) R.string.home_locked else R.string.home_lock), style = Expressive.title(13.sp), color = content, maxLines = 2)
                }
            }
        }
    }
}

/** What plays on the PC, compact like Android's own media player. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MediaCard(onOpen: (Dest) -> Unit) {
    val player by PcMedia.player.collectAsStateWithLifecycle()
    val audio by LinkHub.audio.collectAsStateWithLifecycle()
    val p = player ?: return
    val cs = MaterialTheme.colorScheme
    var picking by remember { mutableStateOf(false) }
    val now = rememberClock(p.playing)
    val fraction = if (p.length > 0) (p.positionAt(now).toFloat() / p.length).coerceIn(0f, 1f) else 0f
    ExpressiveCard(cs.secondaryContainer, cs.onSecondaryContainer, Modifier.heightIn(min = 164.dp), radius = 28.dp) {
        val art = remember(p.art) { p.art?.asImageBitmap() }
        if (art != null) {
            Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            // The cover tinted by the card's own colour, so the text keeps its contrast.
            Box(Modifier.matchParentSize().background(cs.secondaryContainer.copy(alpha = 0.74f)))
        } else {
            Ornament(MaterialShapes.SoftBurst, 300.dp, cs.onSecondaryContainer, Alignment.TopEnd, x = 60.dp, y = (-120).dp, alpha = 0.1f)
            Ornament(MaterialShapes.Cookie6Sided, 180.dp, cs.onSecondaryContainer, Alignment.BottomStart, x = (-60).dp, y = 110.dp, alpha = 0.07f)
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(cs.onSecondaryContainer), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.rounded_music_note), null, tint = cs.secondaryContainer, modifier = Modifier.size(18.dp))
                }
                Text(p.app, style = Expressive.caption, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                audio?.output?.let { out ->
                    Row(
                        Modifier.clip(CircleShape).background(cs.onSecondaryContainer.copy(alpha = 0.12f)).clickable { picking = true }.padding(start = 8.dp, end = 12.dp).height(30.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(painterResource(R.drawable.rounded_speaker), null, Modifier.size(16.dp))
                        Text(out.name, style = Expressive.title(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = Expressive.wide(19.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (p.artist.isNotEmpty()) Text(p.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val haptics = LocalHapticFeedback.current
                Box(
                    Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(if (p.playing) 20.dp else 30.dp))
                        .background(cs.primary)
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            PcMedia.command(if (p.playing) "pause" else "play")
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(if (p.playing) R.drawable.rounded_pause_fill1 else R.drawable.rounded_play_arrow_fill1),
                        stringResource(if (p.playing) R.string.pcmedia_pause else R.string.pcmedia_play),
                        tint = cs.onPrimary,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { PcMedia.command("previous") }, enabled = p.canPrevious, modifier = Modifier.size(32.dp)) {
                    Icon(painterResource(R.drawable.rounded_skip_previous_fill1), stringResource(R.string.pcmedia_previous), Modifier.size(22.dp))
                }
                LinearWavyProgressIndicator(
                    progress = { fraction },
                    color = cs.onSecondaryContainer,
                    trackColor = cs.onSecondaryContainer.copy(alpha = 0.3f),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { PcMedia.command("next") }, enabled = p.canNext, modifier = Modifier.size(32.dp)) {
                    Icon(painterResource(R.drawable.rounded_skip_next_fill1), stringResource(R.string.pcmedia_next), Modifier.size(22.dp))
                }
            }
        }
    }
    val a = audio
    if (picking && a != null) DevicePickerSheet(a, startOnInput = false, onDismiss = { picking = false }, onOpenAudio = {
        picking = false
        onOpen(Dest.AUDIO)
    })
}

/** The PC's volume in big digits, its slider, and output and input as split buttons. */
@Composable
private fun AudioCard(onOpen: (Dest) -> Unit) {
    val audio by LinkHub.audio.collectAsStateWithLifecycle()
    val a: PcAudio = audio ?: return
    val out = a.output ?: return
    val cs = MaterialTheme.colorScheme
    val volume = rememberLiveVolume(out)
    var sheet by remember { mutableIntStateOf(-1) }
    ExpressiveCard(Layer.pane, cs.onSurface) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onOpen(Dest.AUDIO) }, verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Caption(stringResource(R.string.home_pc_volume), color = cs.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${(volume.value * 100).roundToInt()}", style = Expressive.digits(96.sp))
                        Text("%", style = Expressive.digitsSoft(28.sp), color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
                    }
                }
                Icon(painterResource(R.drawable.rounded_arrow_forward), stringResource(R.string.audio_title), tint = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 14.dp))
            }
            BigSlider(
                value = volume.value,
                onValueChange = volume.change,
                onValueChangeFinished = volume.finish,
                label = stringResource(R.string.audio_volume),
                icon = if (out.mute) R.drawable.rounded_volume_off else R.drawable.rounded_volume_up,
                enabled = !out.mute,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeviceSplitButton(out, R.drawable.rounded_speaker, R.drawable.rounded_volume_off, stringResource(R.string.audio_pick_output), onPick = { sheet = 0 }, modifier = Modifier.weight(1f))
                a.mic?.let { mic ->
                    DeviceSplitButton(
                        mic, R.drawable.rounded_mic, R.drawable.rounded_mic_off, stringResource(R.string.audio_pick_input), onPick = { sheet = 1 },
                        modifier = Modifier.weight(1f), container = cs.tertiaryContainer, content = cs.onTertiaryContainer,
                    )
                }
            }
        }
    }
    if (sheet >= 0) DevicePickerSheet(a, startOnInput = sheet == 1, onDismiss = { sheet = -1 }, onOpenAudio = {
        sheet = -1
        onOpen(Dest.AUDIO)
    })
}

/** What the PC is doing live (pomodoro, recording, a countdown…), each with its button. */
@Composable
private fun LiveCards() {
    val items by PcLive.items.collectAsStateWithLifecycle()
    if (items.isEmpty()) return
    val now = rememberClock(enabled = true, read = System::currentTimeMillis)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { LiveCard(it, now) }
    }
}

@Composable
private fun LiveCard(item: PcLiveItem, now: Long) {
    val cs = MaterialTheme.colorScheme
    val counting = item.end > 0 && !item.paused
    val left = when {
        item.paused -> item.remaining
        counting -> (item.end - now) / 1000
        else -> -1L
    }
    val big = when {
        left >= 0 -> clockText(left)
        item.start > 0 -> clockText((now - item.start) / 1000)
        item.kind == "progress" && item.progress >= 0 -> "${item.progress}%"
        else -> ""
    }
    val fraction = when {
        item.kind == "progress" && item.progress >= 0 -> item.progress / 100f
        left >= 0 && item.total > 0 -> (left.toFloat() / item.total).coerceIn(0f, 1f)
        else -> 1f
    }
    val icon = when (item.kind) {
        "record" -> R.drawable.rounded_screen_record
        "alarm" -> R.drawable.rounded_alarm
        "progress" -> R.drawable.rounded_download
        else -> R.drawable.rounded_timer_fill1
    }
    ExpressiveCard(Layer.pane, cs.onSurface) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                val track = cs.tertiaryContainer
                val fill = cs.tertiary
                Canvas(Modifier.fillMaxSize()) {
                    val w = 8.dp.toPx()
                    drawArc(track, 0f, 360f, false, style = Stroke(w), topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w))
                    drawArc(fill, -90f, 360f * fraction, false, style = Stroke(w, cap = StrokeCap.Round), topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w))
                }
                Icon(painterResource(icon), null, tint = cs.tertiary, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f)) {
                Caption(stringResource(R.string.home_live, item.title), color = cs.tertiary)
                if (big.isNotEmpty()) {
                    Text(big, style = Expressive.digits(54.sp), autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = 54.sp), maxLines = 1, softWrap = false)
                }
                val line = when {
                    item.text.isNotEmpty() -> item.text
                    item.paused -> stringResource(R.string.home_paused)
                    else -> ""
                }
                if (line.isNotEmpty()) Text(line, style = Expressive.live(14.sp), color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item.actions.forEach { (id, label) ->
                    FilledIconButton(
                        onClick = { PcLive.act(item.id, id) },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Layer.onPane, contentColor = cs.onSurface),
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(
                            painterResource(
                                when (id) {
                                    "pause" -> R.drawable.rounded_pause_fill1
                                    "resume" -> R.drawable.rounded_play_arrow_fill1
                                    "snooze" -> R.drawable.rounded_alarm
                                    else -> R.drawable.rounded_stop
                                },
                            ),
                            label,
                            Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The four tools used most, each in its own shape. */
@Composable
private fun ToolsStrip(onOpen: (Dest) -> Unit) {
    val cs = MaterialTheme.colorScheme
    ExpressiveCard(Layer.pane, cs.onSurface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 18.dp)) {
            listOf(
                Triple(Dest.TOUCHPAD, R.drawable.rounded_touch_app, R.string.touchpad_title) to MaterialShapes.Cookie9Sided,
                Triple(Dest.DECK, R.drawable.rounded_dashboard, R.string.deck_title) to MaterialShapes.Cookie12Sided,
                Triple(Dest.DRAW, R.drawable.rounded_draw, R.string.draw_short) to MaterialShapes.Flower,
                Triple(Dest.PRESENTER, R.drawable.rounded_co_present, R.string.present_short) to MaterialShapes.Clover4Leaf,
            ).forEach { (t, shape) ->
                val (dest, icon, label) = t
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).clickable { onOpen(dest) }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ShapeBox(shape, 66.dp, cs.secondaryContainer) {
                        Icon(painterResource(icon), null, tint = cs.onSecondaryContainer, modifier = Modifier.size(28.dp))
                    }
                    Text(stringResource(label), style = Expressive.title(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The phone as a monitor (roadmap 3.1): Extend and Mirror start it from the home. */
@Composable
private fun SecondScreenCard(onOpen: (Dest) -> Unit) {
    val cs = MaterialTheme.colorScheme
    ExpressiveCard(cs.tertiaryContainer, cs.onTertiaryContainer, onClick = { onOpen(Dest.SECOND_SCREEN) }) {
        Ornament(MaterialShapes.Sunny, 330.dp, cs.tertiary, Alignment.TopEnd, x = 130.dp, y = (-110).dp, alpha = 0.16f)
        // Grows with its text: a larger display size makes it taller, never cut.
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Caption(stringResource(R.string.second_title))
                    Text(
                        stringResource(R.string.second_headline),
                        style = Expressive.xwide(30.sp),
                        autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = 30.sp),
                        maxLines = 2,
                    )
                }
                MonitorDrawing(scale = 0.7f)
            }
            Text(stringResource(R.string.second_card_body), style = Expressive.live(14.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledPill(stringResource(R.string.second_extend), onClick = { dev.ii.tools.screen.SecondScreen.pendingMode = "extend"; onOpen(Dest.SECOND_SCREEN) }, icon = R.drawable.rounded_add_to_queue, container = cs.tertiary, content = cs.onTertiary, modifier = Modifier.weight(1f))
                DashedPill(stringResource(R.string.second_mirror), onClick = { dev.ii.tools.screen.SecondScreen.pendingMode = "mirror"; onOpen(Dest.SECOND_SCREEN) }, icon = R.drawable.rounded_screen_share, outline = cs.onTertiaryContainer, content = cs.onTertiaryContainer, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** A monitor and a phone beside it, drawn from the scheme's own colours, at [scale]. */
@Composable
internal fun MonitorDrawing(modifier: Modifier = Modifier, scale: Float = 1f) {
    val cs = MaterialTheme.colorScheme
    val u = { v: Int -> (v * scale).dp }
    Box(modifier.size(width = u(150), height = u(120))) {
        Box(Modifier.size(u(128), u(82)).clip(RoundedCornerShape(u(14))).background(cs.tertiary))
        Box(Modifier.padding(start = u(54), top = u(82)).size(u(20), u(14)).background(cs.tertiary))
        Box(Modifier.padding(start = u(38), top = u(94)).size(u(52), u(8)).clip(CircleShape).background(cs.tertiary))
        Box(
            Modifier.padding(start = u(104), top = u(34)).size(u(46), u(82)).clip(RoundedCornerShape(u(12))).background(cs.onTertiaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(R.drawable.rounded_screen_share), null, tint = cs.tertiaryContainer, modifier = Modifier.size(u(22))) }
    }
}

/** The shell's toggles (all but the lock, which the hero holds), round, showing the real state. */
@Composable
private fun ControlsRow(shell: ShellInfo) {
    val active by LinkHub.active.collectAsStateWithLifecycle()
    val runner = rememberActionRunner()
    val actions = shell.actions.filter { it.id != "lock" }
    val shapes = listOf(MaterialShapes.Sunny, MaterialShapes.Cookie9Sided, MaterialShapes.Cookie7Sided, MaterialShapes.Clover4Leaf, MaterialShapes.SoftBurst)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { a ->
                    RoundAction(
                        label = a.name,
                        icon = symbolPainter(a.icon),
                        active = a.id in active,
                        onClick = { runner.run(a.id) },
                        onShape = shapes[actions.indexOf(a) % shapes.size],
                        note = runner.notes[a.id],
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The phone as the PC's webcam (roadmap 4.6): its place on the home, ahead of the feature. */
@Composable
private fun WebcamCard(onOpen: (Dest) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var lens by remember { mutableIntStateOf(2) }
    ExpressiveCard(Layer.pane, cs.onSurface, onClick = { onOpen(Dest.CAMERA) }) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Caption(stringResource(R.string.webcam_title), color = cs.onSurfaceVariant)
                    Text(
                        stringResource(R.string.webcam_headline),
                        style = Expressive.xwide(30.sp),
                        autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = 30.sp),
                        maxLines = 2,
                    )
                }
                ShapeBox(MaterialShapes.Cookie9Sided, 104.dp, cs.primaryContainer) {
                    Icon(painterResource(R.drawable.rounded_videocam_fill1), null, tint = cs.onPrimaryContainer, modifier = Modifier.size(46.dp))
                }
            }
            Text(stringResource(R.string.coming_soon_body), style = Expressive.live(14.sp), color = cs.onSurfaceVariant)
            LensPicker(lens, onPick = { lens = it }, idle = Layer.onPane, chosen = cs.secondaryContainer, onChosen = cs.onSecondaryContainer, size = 44.dp)
            FilledPill(stringResource(R.string.webcam_start), onClick = { onOpen(Dest.CAMERA) }, icon = R.drawable.rounded_play_arrow_fill1, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** The PC's load, all three in a row; the PC samples it only while this shows. */
@Composable
private fun PerfCard(onOpen: (Dest) -> Unit) {
    val perf by LinkHub.perf.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val p: PcPerf? = perf
    val gb = { bytes: Long -> "%.1f".format(bytes / 1_073_741_824.0) }
    ExpressiveCard(Layer.pane, cs.onSurface, onClick = { onOpen(Dest.PERF) }) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption(stringResource(R.string.home_pc_load), color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                Icon(painterResource(R.drawable.rounded_arrow_forward), null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LoadColumn(
                    p?.let { "${(it.cpuUsage * 100).roundToInt()}" } ?: "—", "%",
                    listOfNotNull(stringResource(R.string.perf_cpu), p?.cpuTemp?.let { "${it.roundToInt()}°" }).joinToString(" · "),
                    p?.cpuUsage, Modifier.weight(1f),
                )
                LoadColumn(
                    when {
                        p == null -> "—"
                        p.gpuSuspended -> "0"
                        else -> p.gpuUsage?.let { "${(it * 100).roundToInt()}" } ?: "—"
                    },
                    "%",
                    listOfNotNull(stringResource(R.string.perf_gpu), p?.gpuTemp?.let { "${it.roundToInt()}°" }).joinToString(" · "),
                    p?.gpuUsage, Modifier.weight(1f),
                )
                LoadColumn(
                    p?.takeIf { it.ramTotal > 0 }?.let { gb(it.ramUsed) } ?: "—", "GB",
                    p?.takeIf { it.ramTotal > 0 }?.let { stringResource(R.string.home_ram_of, gb(it.ramTotal)) } ?: stringResource(R.string.perf_ram),
                    p?.takeIf { it.ramTotal > 0 }?.let { it.ramUsed.toFloat() / it.ramTotal }, Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LoadColumn(value: String, unit: String, label: String, fraction: Float?, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The number shrinks to its column rather than wrapping on a large display size; the
        // row keeps one height so the three columns line up whatever their numbers' size.
        Row(Modifier.height(50.dp), verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = Expressive.digits(46.sp),
                autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 46.sp),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(unit, style = Expressive.digitsSoft(17.sp), color = cs.onSurfaceVariant, maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = 6.dp, start = 1.dp))
        }
        Text(label, style = Expressive.title(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(cs.secondaryContainer)) {
            Box(Modifier.fillMaxWidth((fraction ?: 0f).coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(cs.primary))
        }
    }
}

// ── Away ──────────────────────────────────────────────────────────────────

private fun LazyListScope.away(pc: PairedPc, status: LinkStatus, onOpen: (Dest) -> Unit) {
    item(key = "awayHero") { AwayHero(pc, status) }
    item(key = "awayTiles") { AwayTiles(onOpen) }
    item(key = "toolsHeader") { SectionHeader(stringResource(R.string.tools_title), stringResource(R.string.tools_all)) { onOpen(Dest.TOOLS) } }
    item(key = "tools") { ToolsStrip(onOpen) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AwayHero(pc: PairedPc, status: LinkStatus) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var woke by remember { mutableStateOf<Boolean?>(null) }
    val connecting = status is LinkStatus.Connecting || status is LinkStatus.Idle || status is LinkStatus.Online
    ExpressiveCard(Layer.pane, cs.onSurface) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            StateShape(active = !connecting, size = 180.dp, color = Layer.field, on = MaterialShapes.Cookie12Sided) {
                if (connecting) LoadingIndicator(Modifier.size(72.dp))
                else Icon(painterResource(R.drawable.rounded_desktop_access_disabled), null, tint = cs.onSurfaceVariant, modifier = Modifier.size(72.dp))
            }
            Spacer(Modifier.height(22.dp))
            Caption(stringResource(R.string.home_paired_pc), color = cs.onSurfaceVariant)
            Text(pc.name, style = Expressive.xwide(40.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Text(
                when {
                    woke == true -> stringResource(R.string.wake_sent)
                    woke == false -> stringResource(R.string.wake_failed)
                    status is LinkStatus.Offline -> problemText(status.problem)
                    else -> stringResource(R.string.status_connecting)
                },
                style = Expressive.live(16.sp),
                color = cs.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (Wake.available(ctx)) {
                    FilledPill(stringResource(R.string.wake_pc), onClick = { scope.launch { woke = LinkHub.wake() } }, icon = R.drawable.rounded_power_settings_new, height = 60.dp, modifier = Modifier.weight(1.4f))
                }
                DashedPill(stringResource(R.string.retry), onClick = { LinkHub.retry() }, icon = R.drawable.rounded_refresh, height = 60.dp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AwayTiles(onOpen: (Dest) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val device = rememberDeviceStatus()
    var stays by remember { mutableStateOf(LinkHub.staysConnected()) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ExpressiveCard(cs.tertiaryContainer, cs.onTertiaryContainer, Modifier.weight(1f), onClick = { onOpen(Dest.LIVE) }) {
            Column(Modifier.fillMaxWidth().heightIn(min = 168.dp).padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Icon(painterResource(R.drawable.rounded_notifications_active_fill1), null, Modifier.size(26.dp))
                Column {
                    Text(stringResource(R.string.home_live_relay), style = Expressive.title(18.sp))
                    Text(
                        stringResource(
                            when {
                                !device.kdeConnectInstalled -> R.string.kdeconnect_missing_short
                                !device.liveReady -> R.string.status_needs_setup
                                else -> R.string.home_live_relay_on
                            },
                        ),
                        style = Expressive.live(14.sp),
                    )
                }
            }
        }
        ExpressiveCard(Layer.pane, cs.onSurface, Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth().heightIn(min = 168.dp).padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(painterResource(R.drawable.rounded_link), null, Modifier.size(26.dp))
                    Spacer(Modifier.weight(1f))
                    Switch(checked = stays, onCheckedChange = {
                        stays = it
                        LinkHub.setStaysConnected(it)
                    })
                }
                Column {
                    Text(stringResource(R.string.link_stay), style = Expressive.title(18.sp))
                    Text(stringResource(R.string.home_stay_body), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}
