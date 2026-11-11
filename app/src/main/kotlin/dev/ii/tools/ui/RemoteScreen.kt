package dev.ii.tools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import dev.ii.tools.tiles.FavoritePreset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.launch

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

    ToolScaffold(title = stringResource(R.string.remote_title), onBack = onBack) {
        if (!linkGate(pc, status, shell != null)) return@ToolScaffold
        val info = shell ?: return@ToolScaffold
        if ("remote" !in info.caps) {
            item { Hint(stringResource(R.string.remote_off)) }
            return@ToolScaffold
        }

        if (info.actions.isNotEmpty()) {
            item { GroupHeader(stringResource(R.string.remote_actions)) }
            item { ActionGrid(info.actions, active) }
        }
        workspaces?.let { ws ->
            item { GroupHeader(stringResource(R.string.remote_workspaces)) }
            item { WorkspaceGrid(ws) }
        }
        modes?.let { m ->
            item { GroupHeader(stringResource(R.string.remote_modes)) }
            item { if (m.list.isEmpty()) Hint(stringResource(R.string.remote_modes_off)) else ModeGroup(m) }
        }
        presets?.takeIf { it.list.isNotEmpty() }?.let { p ->
            item { GroupHeader(stringResource(R.string.remote_presets)) }
            item { PresetGroup(p) }
        }
    }
}

/**
 * Two columns of toggle tiles. A tile shows the shell's real state, not the last tap:
 * locking from a keybind lights it up here too, and a tap that the shell refuses (unlocking
 * stays with the PC) says why for a moment instead of flipping.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionGrid(actions: List<ShellAction>, active: Set<String>) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val notes = remember { mutableStateMapOf<String, String>() }
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    val lockedNote = stringResource(R.string.action_locked)
    val failedNote = stringResource(R.string.action_failed)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { action ->
                    ActionTile(
                        action = action,
                        on = action.id in active,
                        note = notes[action.id],
                        modifier = Modifier.weight(1f),
                        onTap = {
                            if (busy[action.id] == true) return@ActionTile
                            busy[action.id] = true
                            haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                            scope.launch {
                                val why = LinkHub.run(action.id)
                                busy.remove(action.id)
                                if (why != null) {
                                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                    notes[action.id] = if (why == "locked") lockedNote else failedNote
                                    delay(1_800)
                                    notes.remove(action.id)
                                }
                            }
                        },
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionTile(action: ShellAction, on: Boolean, note: String?, modifier: Modifier, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    ToggleButton(
        checked = on,
        onCheckedChange = { onTap() },
        // Off: a rounded square. Pressed: squeezes. On: morphs to a pill.
        shapes = ToggleButtonShapes(
            shape = RoundedCornerShape(24.dp),
            pressedShape = RoundedCornerShape(12.dp),
            checkedShape = RoundedCornerShape(48.dp),
        ),
        colors = ToggleButtonDefaults.colors(
            containerColor = if (note != null) cs.errorContainer else cs.surfaceBright,
            contentColor = if (note != null) cs.onErrorContainer else cs.onSurface,
            checkedContainerColor = cs.primary,
            checkedContentColor = cs.onPrimary,
        ),
        contentPadding = PaddingValues(12.dp),
        modifier = modifier.height(96.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(symbolPainter(action.icon), contentDescription = null, modifier = Modifier.size(28.dp))
            Text(
                note ?: action.name,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Workspaces 1–10 always, plus any higher one in use. The active one is a filled circle;
 * one with windows is tonal, an empty one plain, so the grid doubles as a map.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
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
    CardGroup {
        ToolBlock {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 5,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ids.forEach { id ->
                    val used = (windows[id] ?: 0) > 0
                    ToggleButton(
                        checked = id == current,
                        onCheckedChange = {
                            if (id == current) return@ToggleButton
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            wanted = id
                            LinkHub.send("workspace", id)
                        },
                        shapes = ToggleButtonShapes(
                            shape = RoundedCornerShape(16.dp),
                            pressedShape = RoundedCornerShape(10.dp),
                            checkedShape = CircleShape,
                        ),
                        colors = ToggleButtonDefaults.colors(
                            containerColor = if (used) cs.secondaryContainer else cs.surfaceContainerHigh,
                            contentColor = if (used) cs.onSecondaryContainer else cs.onSurfaceVariant,
                            checkedContainerColor = cs.primary,
                            checkedContentColor = cs.onPrimary,
                        ),
                        contentPadding = PaddingValues(0.dp),
                        // Five to a row, square, whatever the screen width.
                        modifier = Modifier.weight(1f).aspectRatio(1f),
                    ) {
                        Text(id.toString(), style = MaterialTheme.typography.titleMedium)
                    }
                }
                // Keep a short last row on the same grid.
                repeat((5 - ids.size % 5) % 5) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** One row per mode, its switch showing whether it is the active one. */
@Composable
private fun ModeGroup(modes: Modes) {
    val haptics = LocalHapticFeedback.current
    var wanted by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(modes.active) { wanted = null }
    val current = wanted ?: modes.active
    val toggle = { id: String ->
        haptics.performHapticFeedback(if (id == current) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
        wanted = if (id == current) "" else id
        LinkHub.send("mode", id)
    }
    CardGroup {
        modes.list.forEach { mode ->
            ToolRow(
                title = mode.name,
                painter = symbolPainter(mode.icon, fallback = R.drawable.rounded_tune),
                tint = if (mode.id == current) Tint.PRIMARY else Tint.SECONDARY,
                onClick = { toggle(mode.id) },
                trailing = { Switch(checked = mode.id == current, onCheckedChange = { toggle(mode.id) }) },
            )
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
