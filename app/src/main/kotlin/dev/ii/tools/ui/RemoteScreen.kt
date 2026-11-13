@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.ii.tools.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialShapes
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.sp
import dev.ii.tools.ui.theme.Expressive
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.ii.tools.tiles.FavoritePreset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.Modes
import dev.ii.tools.link.Presets
import dev.ii.tools.link.ShellAction
import dev.ii.tools.link.Workspaces
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Remote control: the shell's toggles, workspaces, modes and presets, all live. */
@Composable
fun RemoteScreen(onBack: () -> Unit) {
    val status by LinkHub.status.collectAsStateWithLifecycle()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    val active by LinkHub.active.collectAsStateWithLifecycle()
    val workspaces by LinkHub.workspaces.collectAsStateWithLifecycle()
    val modes by LinkHub.modes.collectAsStateWithLifecycle()
    val presets by LinkHub.presets.collectAsStateWithLifecycle()
    val subtitle = listOfNotNull(
        pc?.name?.takeIf { it.isNotEmpty() },
        workspaces?.active?.takeIf { it > 0 }?.let { stringResource(R.string.home_workspace, it) },
        modes?.let { m -> m.list.firstOrNull { it.id == m.active }?.name },
    ).joinToString(" · ")

    ToolScaffold(title = stringResource(R.string.remote_short), onBack = onBack, subtitle = subtitle) {
        if (!linkGate(pc, status, shell != null)) return@ToolScaffold
        val info = shell ?: return@ToolScaffold
        if ("remote" !in info.caps) {
            item { Hint(stringResource(R.string.remote_off)) }
            return@ToolScaffold
        }

        if (info.actions.isNotEmpty()) item { ActionBento(info.actions, active) }
        workspaces?.let { ws ->
            item { GroupHeader(stringResource(R.string.remote_workspaces)) }
            item { WorkspaceGrid(ws) }
        }
        modes?.let { m ->
            item { GroupHeader(stringResource(R.string.remote_modes)) }
            item { if (m.list.isEmpty()) Hint(stringResource(R.string.remote_modes_off)) else ModeGrid(m) }
        }
        presets?.takeIf { it.list.isNotEmpty() }?.let { p ->
            item { GroupHeader(stringResource(R.string.remote_presets)) }
            item { PresetGroup(p) }
        }
    }
}

/**
 * The shell's toggles as a bento: the lock tall on the left, the next two beside it, the
 * rest wide below. Each shows the shell's real state, not the last tap: locking from a
 * keybind lights it up here too, and a refusal (unlocking stays with the PC) says why for
 * a moment instead of flipping.
 */
@Composable
private fun ActionBento(actions: List<ShellAction>, active: Set<String>) {
    val runner = rememberActionRunner()
    val lock = actions.firstOrNull { it.id == "lock" }
    val rest = actions.filter { it != lock }
    val beside = if (lock != null) rest.take(2) else emptyList()
    val below = rest.drop(beside.size)
    val shapes = listOf(MaterialShapes.Sunny, MaterialShapes.Cookie9Sided, MaterialShapes.Cookie7Sided, MaterialShapes.Clover4Leaf, MaterialShapes.SoftBurst, MaterialShapes.Flower)
    val tile = @Composable { a: ShellAction, style: TileStyle, modifier: Modifier ->
        ActionTile(a, a.id in active, runner.notes[a.id], style, shapes[actions.indexOf(a) % shapes.size], modifier) { runner.run(a.id) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (lock != null) {
            Row(Modifier.height(if (beside.isEmpty()) 200.dp else 372.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                tile(lock, TileStyle.TALL, Modifier.weight(1f).fillMaxHeight())
                if (beside.isNotEmpty()) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        beside.forEach { tile(it, TileStyle.SQUARE, Modifier.weight(1f).fillMaxWidth()) }
                    }
                }
            }
        }
        below.forEach { tile(it, TileStyle.WIDE, Modifier.fillMaxWidth()) }
    }
}

private enum class TileStyle { TALL, SQUARE, WIDE }

@Composable
private fun ActionTile(action: ShellAction, on: Boolean, note: String?, style: TileStyle, onShape: androidx.graphics.shapes.RoundedPolygon, modifier: Modifier, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val (bg, fg) = when {
        note != null -> cs.errorContainer to cs.onErrorContainer
        on -> cs.primaryContainer to cs.onPrimaryContainer
        else -> Layer.pane to cs.onSurface
    }
    val shapeColor = if (on) cs.primary else Layer.field
    val glyph = if (on) cs.onPrimary else fg
    ExpressiveCard(bg, fg, modifier, onClick = {
        haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
        onTap()
    }) {
        val label = note ?: action.name
        when (style) {
            TileStyle.TALL -> Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
                StateShape(on, 132.dp, shapeColor, Modifier.align(Alignment.CenterHorizontally).padding(top = 24.dp), on = onShape) {
                    Icon(symbolPainter(action.icon), null, tint = glyph, modifier = Modifier.size(56.dp))
                }
                Column {
                    Text(label, style = Expressive.xwide(26.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(if (on) R.string.status_on else R.string.remote_tap_to_toggle), style = Expressive.live(13.sp))
                }
            }
            TileStyle.SQUARE -> Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                StateShape(on, 60.dp, shapeColor, on = onShape) {
                    Icon(symbolPainter(action.icon), null, tint = glyph, modifier = Modifier.size(28.dp))
                }
                Text(label, style = Expressive.title(17.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TileStyle.WIDE -> Row(Modifier.fillMaxWidth().heightIn(min = 104.dp).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StateShape(on, 68.dp, shapeColor, on = onShape) {
                    Icon(symbolPainter(action.icon), null, tint = glyph, modifier = Modifier.size(30.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(label, style = Expressive.wide(22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(if (on) R.string.status_on else R.string.status_off), style = Expressive.live(14.sp))
                }
            }
        }
    }
}

/**
 * Workspaces 1–10 always, plus any higher one in use. The current one is a filled rounded
 * square; one with windows is tonal with a dot per window, an empty one plain, so the
 * grid doubles as a map.
 */
@Composable
private fun WorkspaceGrid(ws: Workspaces) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val windows = remember(ws) { ws.list.associate { it.id to it.windows } }
    val ids = remember(ws) { ((1..10) + ws.list.map { it.id }.filter { it > 10 }).distinct() }
    // The tap shows at once; the shell's push confirms it a moment later.
    var wanted by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(ws.active) { wanted = null }
    val current = wanted ?: ws.active
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ids.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { id ->
                    val count = windows[id] ?: 0
                    val on = id == current
                    val (bg, fg) = when {
                        on -> cs.primary to cs.onPrimary
                        count > 0 -> cs.secondaryContainer to cs.onSecondaryContainer
                        else -> Layer.pane to cs.onSurfaceVariant
                    }
                    val radius by animateDpAsState(if (on) 20.dp else 38.dp, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "ws")
                    Column(
                        Modifier
                            .weight(1f)
                            .height(76.dp)
                            .clip(RoundedCornerShape(radius))
                            .background(bg)
                            .clickable {
                                if (on) return@clickable
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                wanted = id
                                LinkHub.send("workspace", id)
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(id.toString(), style = if (count > 0 || on) Expressive.digits(34.sp) else Expressive.digitsSoft(34.sp), color = fg)
                        if (count > 0) {
                            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                repeat(count.coerceAtMost(5)) { Box(Modifier.size(5.dp).clip(CircleShape).background(fg)) }
                            }
                        }
                    }
                }
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The shell's modes, two to a row; tapping the active one turns it off. */
@Composable
private fun ModeGrid(modes: Modes) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var wanted by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(modes.active) { wanted = null }
    val current = wanted ?: modes.active
    val toggle = { id: String ->
        haptics.performHapticFeedback(if (id == current) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
        wanted = if (id == current) "" else id
        LinkHub.send("mode", id)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        modes.list.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { mode ->
                    val on = mode.id == current
                    val fill by animateColorAsState(if (on) cs.primaryContainer else Layer.pane, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "mode")
                    Row(
                        Modifier
                            .weight(1f)
                            .height(72.dp)
                            .clip(RoundedCornerShape(Radius.row))
                            .background(fill)
                            .clickable { toggle(mode.id) }
                            .semantics { role = Role.Switch; stateDescription = if (on) "on" else "off" }
                            .padding(start = 10.dp, end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StateShape(on, 48.dp, if (on) cs.primary else Layer.field) {
                            Icon(symbolPainter(mode.icon, fallback = R.drawable.rounded_tune), null, tint = if (on) cs.onPrimary else cs.onSurface, modifier = Modifier.size(22.dp))
                        }
                        Text(mode.name, style = Expressive.title(16.sp), color = if (on) cs.onPrimaryContainer else cs.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** The saved looks. Applying one reloads the shell, so the link drops and comes back. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PresetGroup(presets: Presets) {
    val ctx = LocalContext.current
    val favorite by FavoritePreset.name.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { FavoritePreset.load(ctx) }
    var applying by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(presets.active) { applying = null }
    LaunchedEffect(applying) {
        if (applying != null) {
            delay(15_000)
            applying = null
        }
    }
    CardGroup {
        presets.list.forEach { name ->
            val on = name == presets.active
            ToolRow(
                title = name,
                icon = R.drawable.rounded_palette,
                tint = if (on) Tint.PRIMARY else Tint.SECONDARY,
                onClick = if (on || applying != null) null else {
                    {
                        applying = name
                        LinkHub.send("preset", name)
                    }
                },
                trailing = {
                    when {
                        applying == name -> LoadingIndicator(Modifier.size(32.dp))
                        on -> Icon(painterResource(R.drawable.rounded_check), null, tint = MaterialTheme.colorScheme.primary)
                    }
                    // The star picks the preset the Quick Settings tile applies.
                    val starred = favorite == name
                    IconButton(onClick = { FavoritePreset.set(ctx, if (starred) null else name) }) {
                        Icon(
                            painterResource(if (starred) R.drawable.rounded_star_fill1 else R.drawable.rounded_star),
                            stringResource(R.string.preset_favorite),
                            tint = if (starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
    }
}
