package dev.ii.tools.ui

import android.app.Activity
import android.content.Intent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.SecondScreenViewActivity
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
import dev.ii.tools.screen.ScreenPrefs
import dev.ii.tools.screen.ScreenState
import dev.ii.tools.screen.ScreenStream
import dev.ii.tools.screen.SecondScreen
import dev.ii.tools.ui.theme.Expressive
import kotlin.math.roundToInt

/**
 * The phone as a monitor (ROADMAP 3.1): extend or mirror, over USB, and how the PC sends
 * it. Once the PC says the output is served, the picture opens full screen
 * ([SecondScreenView]).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SecondScreenScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val status by LinkHub.status.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    val state by SecondScreen.state.collectAsStateWithLifecycle()
    val prefs by SecondScreen.prefs(ctx).collectAsStateWithLifecycle()
    val online = status is LinkStatus.Online && shell != null
    val supported = shell?.caps?.contains("screen") == true
    val ready = online && supported

    DisposableEffect(Unit) {
        LinkHub.hold("screen")
        onDispose { LinkHub.release("screen") }
    }
    // The home card's Extend and Mirror start it straight away.
    LaunchedEffect(ready) {
        if (ready) SecondScreen.pendingMode?.let {
            SecondScreen.pendingMode = null
            SecondScreen.start(ctx, it)
        }
    }
    LaunchedEffect(state) {
        if (state is ScreenState.Ready) ctx.startActivity(Intent(ctx, SecondScreenViewActivity::class.java))
    }
    val monitors by SecondScreen.monitors.collectAsStateWithLifecycle()
    LaunchedEffect(ready) {
        if (ready) SecondScreen.requestMonitors()
    }

    val s = state
    val p = prefs ?: ScreenPrefs()
    fun set(v: ScreenPrefs) = SecondScreen.setPrefs(ctx, v)
    ToolScaffold(title = stringResource(R.string.second_title), onBack = onBack) {
        item {
            ExpressiveCard(cs.tertiaryContainer, cs.onTertiaryContainer, Modifier.height(220.dp)) {
                Ornament(MaterialShapes.Sunny, 360.dp, cs.tertiary, Alignment.TopEnd, x = 140.dp, y = (-120).dp, alpha = 0.16f)
                if (s is ScreenState.Starting) LoadingIndicator(Modifier.align(Alignment.Center).size(72.dp))
                else MonitorDrawing(Modifier.align(Alignment.Center))
            }
        }
        item {
            Text(stringResource(R.string.second_headline), style = Expressive.xwide(32.sp), modifier = Modifier.padding(horizontal = 4.dp))
        }
        item {
            Hint(
                when {
                    !online -> stringResource(R.string.touchpad_offline)
                    !supported -> stringResource(R.string.second_off)
                    s is ScreenState.Starting -> stringResource(R.string.second_starting)
                    s is ScreenState.Failed -> failure(s.code)
                    else -> stringResource(R.string.second_body)
                },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val busy = s is ScreenState.Starting || s is ScreenState.Ready
                FilledPill(
                    stringResource(R.string.second_extend),
                    onClick = { SecondScreen.start(ctx, "extend") },
                    enabled = ready && !busy,
                    icon = R.drawable.rounded_add_to_queue,
                    modifier = Modifier.weight(1f),
                )
                DashedPill(
                    stringResource(R.string.second_mirror),
                    onClick = { if (ready && !busy) SecondScreen.start(ctx, "mirror") },
                    icon = R.drawable.rounded_screen_share,
                    outline = cs.outline,
                    content = if (ready && !busy) cs.onSurface else cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (s is ScreenState.Starting || s is ScreenState.Ready) {
            item {
                DashedPill(stringResource(R.string.second_stop), onClick = { SecondScreen.stop() }, icon = R.drawable.rounded_stop, outline = cs.error, content = cs.error)
            }
        }

        // ── How the PC sends it ────────────────────────────────────────────
        item { GroupHeader(stringResource(R.string.second_stream)) }
        item {
            CardGroup {
                ToolBlock {
                    Choice(
                        stringResource(R.string.second_resolution),
                        listOf(1f to "100%", 0.75f to "75%", 0.5f to "50%"),
                        p.res,
                    ) { set(p.copy(res = it)) }
                    Choice(
                        stringResource(R.string.second_fps),
                        listOf(30 to "30", 60 to "60", 90 to "90", 120 to "120"),
                        p.fps,
                    ) { set(p.copy(fps = it)) }
                    Choice(
                        stringResource(R.string.second_scale),
                        listOf(1f to "1×", 1.25f to "1.25×", 1.5f to "1.5×", 2f to "2×"),
                        p.scale,
                    ) { set(p.copy(scale = it)) }
                    Choice(
                        stringResource(R.string.second_side),
                        listOf(
                            "left" to stringResource(R.string.second_side_left),
                            "right" to stringResource(R.string.second_side_right),
                            "above" to stringResource(R.string.second_side_above),
                            "below" to stringResource(R.string.second_side_below),
                        ),
                        p.side,
                    ) { set(p.copy(side = it)) }
                    if (monitors.size > 1) {
                        Choice(
                            stringResource(R.string.second_mirror_which),
                            listOf("" to stringResource(R.string.second_mirror_focused)) + monitors.map { it.name to it.name },
                            if (monitors.any { it.name == p.mirror }) p.mirror else "",
                        ) { set(p.copy(mirror = it)) }
                    }
                    Choice(
                        stringResource(R.string.second_quality),
                        listOf(8 to "8 Mbps", 16 to "16 Mbps", 30 to "30 Mbps"),
                        p.bitrate,
                    ) { set(p.copy(bitrate = it)) }
                }
            }
        }
        item { Hint(stringResource(R.string.second_stream_hint)) }
        item { Hint(stringResource(R.string.second_usb_hint)) }
    }
}

/** A label over connected toggle buttons: one of [options]. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> Choice(label: String, options: List<Pair<T, String>>, value: T, onPick: (T) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = Expressive.title(14.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            options.forEachIndexed { i, (v, text) ->
                ToggleButton(
                    checked = v == value,
                    onCheckedChange = {
                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                        onPick(v)
                    },
                    shapes = when (i) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                ) { Text(text, maxLines = 1, style = Expressive.title(13.sp)) }
            }
        }
    }
}

@Composable
private fun failure(code: String): String = stringResource(
    when (code) {
        "disabled" -> R.string.second_off
        "adb" -> R.string.second_err_adb
        "usb" -> R.string.second_err_usb
        "unauthorized" -> R.string.second_err_unauthorized
        "busy" -> R.string.second_err_busy
        "timeout" -> R.string.second_err_timeout
        "encoder", "encoder_exit" -> R.string.second_err_encoder
        else -> R.string.second_err_stream
    },
)

/**
 * The PC's output, full screen and sideways: the picture fitted to the display, every
 * finger on it going back as the PC's touchscreen, and a floating toolbar (keyboard,
 * live draw, stop) that can be folded to a dot and dragged aside. Leaving ends it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SecondScreenView(onDone: () -> Unit) {
    val view = LocalView.current
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val state by SecondScreen.state.collectAsStateWithLifecycle()
    val ready = state as? ScreenState.Ready

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        val window = (view.context as? Activity)?.window
        window?.attributes = window?.attributes?.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            view.keepScreenOn = false
            SecondScreen.stop()
        }
    }
    LaunchedEffect(ready) {
        if (ready == null) onDone()
    }
    val r = ready ?: return

    val stream = remember { arrayOfNulls<ScreenStream>(1) }
    // Picture ids → the PC's finger ids (0–9), kept while the finger is down.
    val ids = remember { HashMap<PointerId, Int>() }
    var typing by remember { mutableStateOf(false) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    val mods = remember { mutableStateListOf<String>() }
    val focus = remember { FocusRequester() }
    val ime = LocalSoftwareKeyboardController.current

    LaunchedEffect(typing) {
        if (typing) {
            focus.requestFocus()
            ime?.show()
        } else {
            ime?.hide()
        }
    }
    fun sendKey(key: String) {
        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        stream[0]?.key(key, mods.toList())
        mods.clear()
    }

    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { area = it }) {
        Box(Modifier.align(Alignment.Center).aspectRatio(r.width.toFloat() / r.height.coerceAtLeast(1))) {
            AndroidView(
                factory = { c ->
                    SurfaceView(c).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) {
                                stream[0]?.stop()
                                stream[0] = ScreenStream(r.port, r.token, r.width, r.height, h.surface) { failed ->
                                    if (failed) SecondScreen.failed("stream") else SecondScreen.stop()
                                }.also { it.start() }
                            }

                            override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}

                            override fun surfaceDestroyed(h: SurfaceHolder) {
                                stream[0]?.stop()
                                stream[0] = null
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        do {
                            val event = awaitPointerEvent()
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            event.changes.forEach { it.consume() }
                            val down = event.changes.filter { it.pressed }
                            ids.keys.retainAll(down.map { it.id }.toSet())
                            val fingers = down.mapNotNull { c ->
                                val id = ids.getOrPut(c.id) { (0..9).firstOrNull { it !in ids.values } ?: return@mapNotNull null }
                                Triple(id, c.position.x / w, c.position.y / h)
                            }
                            stream[0]?.touches(fingers)
                        } while (event.changes.any { it.pressed })
                    }
                },
            )
        }

        // ── The floating toolbar: docked on an edge, or anywhere ────────────
        FloatingTools(
            area = area,
            typing = typing,
            onKeyboard = { typing = !typing },
            onDraw = { SecondScreen.draw() },
            onSwitch = if (r.mode == "mirror" && r.screens > 1) ({ SecondScreen.switchNext() }) else null,
            onStop = onDone,
        )

        // Which PC screen this is, for a moment after mirror switched to it.
        var shown by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(r.output) {
            if (r.mode != "mirror" || r.output.isEmpty()) return@LaunchedEffect
            shown = r.output
            kotlinx.coroutines.delay(1600)
            shown = null
        }
        AnimatedVisibility(shown != null, Modifier.align(Alignment.TopCenter).padding(top = 16.dp)) {
            Surface(shape = CircleShape, color = cs.inverseSurface, contentColor = cs.inverseOnSurface) {
                Text(shown ?: "", style = Expressive.title(14.sp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }

        // ── The keyboard: keys the IME lacks, over it ───────────────────────
        if (typing) {
            Surface(color = cs.surfaceContainer.copy(alpha = 0.94f), modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding()) {
                Box(Modifier.padding(8.dp)) {
                    KeysRow(mods = mods, onKey = ::sendKey, onMod = { m ->
                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                        if (m in mods) mods.remove(m) else mods.add(m)
                    })
                    // Never seen: it only takes the IME's text, which goes out as it comes.
                    var field by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(SENTINEL.length))) }
                    BasicTextField(
                        value = field,
                        onValueChange = { v ->
                            if (v.composition != null) {
                                field = v
                                return@BasicTextField
                            }
                            when {
                                v.text.isEmpty() -> sendKey("BackSpace")
                                v.text.startsWith(SENTINEL) && v.text.length > SENTINEL.length -> {
                                    val typed = v.text.substring(SENTINEL.length)
                                    if (mods.isNotEmpty() && typed.length == 1) sendKey(typed) else stream[0]?.text(typed)
                                }
                                v.text != SENTINEL -> stream[0]?.text(v.text.trim())
                            }
                            field = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Send,
                        ),
                        keyboardActions = KeyboardActions(onSend = { sendKey("Return") }),
                        modifier = Modifier.size(1.dp).alpha(0f).focusRequester(focus),
                    )
                }
            }
        }
    }
}

private enum class Edge { LEFT, RIGHT, TOP, BOTTOM }

/**
 * The viewer's tools. Near an edge it docks: a half circle against it with an arrow,
 * and the tools grow out of that edge. Dragged away it floats as a dot that opens into a
 * pill. Open, it is no thicker than the dot: the buttons fill its 48 dp exactly.
 */
@Composable
private fun FloatingTools(area: IntSize, typing: Boolean, onKeyboard: () -> Unit, onDraw: () -> Unit, onSwitch: (() -> Unit)?, onStop: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var open by remember { mutableStateOf(false) }
    var dock by remember { mutableStateOf<Edge?>(Edge.RIGHT) }
    // The bar's centre in the viewer, in px; along the edge while docked.
    var center by remember { mutableStateOf<Offset?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    if (area == IntSize.Zero) return

    val areaW = area.width.toFloat()
    val areaH = area.height.toFloat()
    val c = center ?: Offset(areaW, areaH * 0.3f)
    val x = when (dock) {
        Edge.LEFT -> 0f
        Edge.RIGHT -> areaW - size.width
        else -> (c.x - size.width / 2f).coerceIn(0f, (areaW - size.width).coerceAtLeast(0f))
    }
    val y = when (dock) {
        Edge.TOP -> 0f
        Edge.BOTTOM -> areaH - size.height
        else -> (c.y - size.height / 2f).coerceIn(0f, (areaH - size.height).coerceAtLeast(0f))
    }
    val vertical = dock == Edge.LEFT || dock == Edge.RIGHT
    val shape = when (dock) {
        Edge.LEFT -> RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
        Edge.RIGHT -> RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
        Edge.TOP -> RoundedCornerShape(bottomStartPercent = 50, bottomEndPercent = 50)
        Edge.BOTTOM -> RoundedCornerShape(topStartPercent = 50, topEndPercent = 50)
        null -> CircleShape
    }
    // The arrow points where the bar goes: in to open it, out to fold it.
    val inward = when (dock) {
        Edge.LEFT -> 0f
        Edge.RIGHT -> 180f
        Edge.TOP -> 90f
        Edge.BOTTOM -> 270f
        null -> 0f
    }

    // The gesture outlives this composition: it reads where the bar is now through this.
    val barCenter by rememberUpdatedState(Offset(x + size.width / 2f, y + size.height / 2f))
    val handle = Modifier.pointerInput(area) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            var moved = false
            var travel = Offset.Zero
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val delta = change.positionChange()
                change.consume()
                travel += delta
                if (!moved && travel.getDistance() > viewConfiguration.touchSlop) {
                    moved = true
                    center = barCenter
                    dock = null
                }
                if (moved) center = (center ?: Offset.Zero) + delta
            }
            if (moved) {
                // Let go near an edge: it docks there.
                val p = center ?: return@awaitEachGesture
                val snap = with(density) { 72.dp.toPx() }
                val near = listOf(Edge.LEFT to p.x, Edge.RIGHT to areaW - p.x, Edge.TOP to p.y, Edge.BOTTOM to areaH - p.y).minBy { it.second }
                if (near.second < snap) {
                    dock = near.first
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
            } else {
                haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                open = !open
            }
        }
    }

    Surface(
        shape = shape,
        color = cs.surfaceContainerHigh.copy(alpha = if (open) 0.95f else 0.78f),
        contentColor = cs.onSurface,
        modifier = Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .onSizeChanged { size = it }
            .animateContentSize(),
    ) {
        val handleSize = when {
            dock == null || open -> DpSize(48.dp, 48.dp)
            vertical -> DpSize(28.dp, 56.dp)
            else -> DpSize(56.dp, 28.dp)
        }
        val handleBox = @Composable {
            Box(handle.size(handleSize), contentAlignment = Alignment.Center) {
                when {
                    dock == null -> Icon(painterResource(if (open) R.drawable.rounded_drag_indicator else R.drawable.rounded_tune), stringResource(R.string.second_tools))
                    else -> Icon(
                        painterResource(R.drawable.rounded_chevron_right),
                        stringResource(R.string.second_tools),
                        Modifier.size(22.dp).rotate(if (open) inward + 180f else inward),
                    )
                }
            }
        }
        val tools = @Composable {
            ToolButton(if (typing) R.drawable.rounded_keyboard_hide else R.drawable.rounded_keyboard, stringResource(R.string.touchpad_keyboard), typing, onKeyboard)
            if (onSwitch != null) ToolButton(R.drawable.rounded_swap_horiz, stringResource(R.string.second_switch), false, onSwitch)
            ToolButton(R.drawable.rounded_draw, stringResource(R.string.second_draw), false, onDraw)
            FilledIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    onStop()
                },
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = cs.errorContainer, contentColor = cs.onErrorContainer),
            ) { Icon(painterResource(R.drawable.rounded_stop), stringResource(R.string.second_stop)) }
        }
        if (vertical) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                handleBox()
                if (open) tools()
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                handleBox()
                if (open) tools()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ToolButton(@DrawableRes icon: Int, label: String, active: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    FilledIconButton(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onClick()
        },
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (active) cs.primaryContainer else cs.surfaceBright,
            contentColor = if (active) cs.onPrimaryContainer else cs.onSurface,
        ),
    ) { Icon(painterResource(icon), label) }
}
