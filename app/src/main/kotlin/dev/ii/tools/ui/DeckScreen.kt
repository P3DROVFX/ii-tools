package dev.ii.tools.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.deck.DeckKey
import dev.ii.tools.deck.DeckStore
import dev.ii.tools.link.DeckAction
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val Gap = 8.dp
private val MinKey = 88.dp

/**
 * The phone as a stream deck: keys the user picked from the shell's catalog, cut into
 * pages that fit the screen. Toggle keys show the shell's real state. Edit mode adds,
 * removes and (long press, drag) reorders; dock mode turns the phone sideways, keeps
 * the screen on and dims it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckScreen(onBack: () -> Unit, dock: Boolean, onDock: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val status by LinkHub.status.collectAsStateWithLifecycle()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()
    val shell by LinkHub.shell.collectAsStateWithLifecycle()
    val catalog by LinkHub.deck.collectAsStateWithLifecycle()
    val keys by DeckStore.keys.collectAsStateWithLifecycle()

    var editing by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    BackHandler(dock) { onDock(false) }
    BackHandler(editing && !dock) { editing = false }

    LaunchedEffect(catalog) {
        catalog?.let { list -> DeckStore.seed(list.associate { it.id to (it.name to it.icon) }) }
    }
    val byId = remember(catalog) { catalog.orEmpty().associateBy { it.id } }
    val ready = pc != null && status is LinkStatus.Online && shell != null

    Scaffold(
        containerColor = cs.surfaceContainer,
        topBar = {
            if (!dock) {
                TopAppBar(
                    title = { Text(stringResource(R.string.deck_title)) },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                onBack()
                            },
                            colors = IconButtonDefaults.iconButtonColors(containerColor = cs.surfaceBright),
                        ) { Icon(painterResource(R.drawable.rounded_arrow_back), stringResource(R.string.back)) }
                    },
                    actions = {
                        if (ready) {
                            IconButton(onClick = { editing = !editing }) {
                                Icon(
                                    painterResource(if (editing) R.drawable.rounded_check else R.drawable.rounded_edit),
                                    stringResource(if (editing) R.string.deck_done else R.string.deck_edit),
                                )
                            }
                            IconButton(onClick = {
                                editing = false
                                onDock(true)
                            }) {
                                Icon(painterResource(R.drawable.rounded_stay_current_landscape), stringResource(R.string.deck_dock))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainer),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val info = shell
            when {
                pc == null -> Hint(stringResource(R.string.link_needed), Modifier.padding(top = 16.dp))
                !ready -> Box(Modifier.padding(16.dp)) { CardGroup { StatusRow(pc!!, status) } }
                info == null || "deck" !in info.caps -> Hint(stringResource(R.string.deck_off), Modifier.padding(top = 16.dp))
                keys.isEmpty() && !editing -> Hint(stringResource(R.string.deck_empty), Modifier.padding(top = 16.dp))
                else -> DeckPages(keys, byId, editing, onAdd = { picking = true })
            }
            if (dock) {
                IconButton(
                    onClick = { onDock(false) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.rounded_close),
                        stringResource(R.string.deck_exit_dock),
                        tint = cs.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }

    if (picking) {
        DeckPicker(
            catalog = catalog.orEmpty(),
            onDeck = remember(keys) { keys.map { it.key }.toSet() },
            onPick = { DeckStore.add(it) },
            onDismiss = { picking = false },
        )
    }
}

/** The keys cut into pages of whatever grid fits; the add key closes the last page in edit mode. */
@Composable
private fun DeckPages(keys: List<DeckKey>, catalog: Map<String, DeckAction>, editing: Boolean, onAdd: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val cols = ((maxWidth + Gap) / (MinKey + Gap)).toInt().coerceIn(3, 8)
        val cell = (maxWidth - Gap * (cols - 1)) / cols
        val dots = 24.dp
        val rows = ((maxHeight - dots + Gap) / (cell + Gap)).toInt().coerceAtLeast(1)
        val capacity = cols * rows
        val slots: List<DeckKey?> = if (editing) keys + null else keys
        val pages = slots.chunked(capacity).ifEmpty { listOf(emptyList()) }
        val pager = rememberPagerState { pages.size }
        var dragging by remember { mutableStateOf<String?>(null) }

        Column {
            HorizontalPager(
                state = pager,
                userScrollEnabled = dragging == null,
                modifier = Modifier.weight(1f),
            ) { page ->
                DeckPage(
                    slots = pages[page],
                    first = page * capacity,
                    pageEnd = minOf(keys.size, (page + 1) * capacity),
                    cols = cols,
                    cell = cell,
                    catalog = catalog,
                    editing = editing,
                    dragging = dragging,
                    onDragging = { dragging = it },
                    onAdd = onAdd,
                )
            }
            Row(
                Modifier.fillMaxWidth().height(dots),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pages.size > 1) repeat(pages.size) { i ->
                    val cs = MaterialTheme.colorScheme
                    Box(
                        Modifier.size(if (i == pager.currentPage) 8.dp else 6.dp).clip(CircleShape)
                            .background(if (i == pager.currentPage) cs.primary else cs.outlineVariant),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DeckPage(
    slots: List<DeckKey?>,
    first: Int,
    pageEnd: Int,
    cols: Int,
    cell: androidx.compose.ui.unit.Dp,
    catalog: Map<String, DeckAction>,
    editing: Boolean,
    dragging: String?,
    onDragging: (String?) -> Unit,
    onAdd: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val active by LinkHub.active.collectAsStateWithLifecycle()
    val modes by LinkHub.modes.collectAsStateWithLifecycle()
    val presets by LinkHub.presets.collectAsStateWithLifecycle()
    val workspaces by LinkHub.workspaces.collectAsStateWithLifecycle()
    val notes = remember { mutableStateMapOf<String, String>() }
    val lockedNote = stringResource(R.string.action_locked)
    val unavailableNote = stringResource(R.string.deck_unavailable)
    val stepPx = with(LocalDensity.current) { (cell + Gap).toPx() }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    // One parent for every key of the page, placed by index: a key keeps its node (and
    // its drag) while the order changes under it, and the others slide to their places.
    Box(Modifier.fillMaxSize()) {
        slots.forEachIndexed { i, slot ->
            key(slot?.key ?: "+add") {
                val target = IntOffset(((i % cols) * stepPx).roundToInt(), ((i / cols) * stepPx).roundToInt())
                val animated by animateIntOffsetAsState(target, label = "key")
                val held = slot != null && slot.key == dragging
                val tileModifier = Modifier
                    .zIndex(if (held) 1f else 0f)
                    .offset { if (held) target + IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) else animated }
                    .size(cell)
                    .graphicsLayer {
                        val s = if (held) 1.08f else 1f
                        scaleX = s
                        scaleY = s
                    }
                if (slot == null) {
                    AddKey(tileModifier, onAdd)
                } else {
                val drag = if (editing) Modifier.pointerInput(slot.key, cols, first, pageEnd) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            dragOffset = Offset.Zero
                            onDragging(slot.key)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragOffset += amount
                            val from = DeckStore.keys.value.indexOfFirst { it.key == slot.key }
                            if (from < 0) return@detectDragGesturesAfterLongPress
                            val dx = (dragOffset.x / stepPx).roundToInt()
                            val dy = (dragOffset.y / stepPx).roundToInt()
                            if (dx == 0 && dy == 0) return@detectDragGesturesAfterLongPress
                            val to = (from + dx + dy * cols).coerceIn(first, pageEnd - 1)
                            if (to == from) return@detectDragGesturesAfterLongPress
                            DeckStore.move(from, to)
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            val (fc, fr) = (from - first) % cols to (from - first) / cols
                            val (tc, tr) = (to - first) % cols to (to - first) / cols
                            dragOffset -= Offset((tc - fc) * stepPx, (tr - fr) * stepPx)
                        },
                        onDragEnd = { onDragging(null) },
                        onDragCancel = { onDragging(null) },
                    )
                } else Modifier

                val view = keyView(slot, catalog, modes?.list?.map { it.id to it.name }, presets?.list)
                val on = when (slot.kind) {
                    "action" -> slot.ref in active
                    "mode" -> modes?.active == slot.ref
                    "preset" -> presets?.active == slot.ref
                    "workspace" -> workspaces?.active?.toString() == slot.ref
                    else -> false
                }
                DeckTile(
                    slot = slot,
                    label = notes[slot.key] ?: view.label,
                    icon = view.icon,
                    on = on,
                    available = view.available,
                    warning = notes[slot.key] != null,
                    editing = editing,
                    modifier = tileModifier.then(drag),
                    onRemove = { DeckStore.remove(slot) },
                    onTap = {
                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                        when (slot.kind) {
                            "mode" -> LinkHub.send("mode", slot.ref)
                            "preset" -> LinkHub.send("preset", slot.ref)
                            "workspace" -> slot.ref.toIntOrNull()?.let { LinkHub.send("workspace", it) }
                            else -> scope.launch {
                                val id = if (slot.kind == "app") "app:${slot.ref}" else slot.ref
                                val why = LinkHub.run("deck", id) ?: return@launch
                                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                notes[slot.key] = if (why == "locked") lockedNote else unavailableNote
                                delay(1_800)
                                notes.remove(slot.key)
                            }
                        }
                    },
                )
                }
            }
        }
    }
}

private data class KeyView(val label: String, val icon: String, val available: Boolean)

/** What a key reads as now: the live catalog when it has the key, else what it was saved as. */
private fun keyView(k: DeckKey, catalog: Map<String, DeckAction>, modes: List<Pair<String, String>>?, presets: List<String>?): KeyView =
    when (k.kind) {
        "action" -> catalog[k.ref]?.let { KeyView(it.name, it.icon, true) } ?: KeyView(k.label, k.icon, false)
        "mode" -> KeyView(modes?.firstOrNull { it.first == k.ref }?.second ?: k.label, k.icon, modes?.any { it.first == k.ref } != false)
        "preset" -> KeyView(k.label, k.icon, presets?.contains(k.ref) != false)
        else -> KeyView(k.label, k.icon, true)
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DeckTile(
    slot: DeckKey,
    label: String,
    icon: String,
    on: Boolean,
    available: Boolean,
    warning: Boolean,
    editing: Boolean,
    modifier: Modifier,
    onRemove: () -> Unit,
    onTap: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(modifier) {
        if (editing) {
            // Still while editing: a plain card, so a long press drags instead of firing.
            Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceBright, modifier = Modifier.fillMaxSize()) {
                KeyFace(slot, label, icon, Modifier.padding(8.dp))
            }
            Box(
                Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp).size(28.dp).clip(CircleShape)
                    .background(cs.error).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.rounded_close), stringResource(R.string.deck_remove), tint = cs.onError, modifier = Modifier.size(18.dp))
            }
        } else {
            ToggleButton(
                checked = on,
                onCheckedChange = { onTap() },
                enabled = available,
                shapes = ToggleButtonShapes(
                    shape = RoundedCornerShape(24.dp),
                    pressedShape = RoundedCornerShape(12.dp),
                    checkedShape = RoundedCornerShape(40.dp),
                ),
                colors = ToggleButtonDefaults.colors(
                    containerColor = if (warning) cs.errorContainer else cs.surfaceBright,
                    contentColor = if (warning) cs.onErrorContainer else cs.onSurface,
                    checkedContainerColor = cs.primary,
                    checkedContentColor = cs.onPrimary,
                ),
                contentPadding = PaddingValues(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                KeyFace(slot, label, icon, Modifier)
            }
        }
    }
}

/** Icon over label; a workspace shows its number, an app its initial. */
@Composable
private fun KeyFace(slot: DeckKey, label: String, icon: String, modifier: Modifier) {
    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        when (slot.kind) {
            "workspace" -> Text(slot.ref, style = MaterialTheme.typography.headlineMedium)
            "app" -> Text(label.take(1).uppercase(), style = MaterialTheme.typography.headlineMedium)
            else -> Icon(symbolPainter(icon), contentDescription = null, modifier = Modifier.size(30.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AddKey(modifier: Modifier, onAdd: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onAdd,
        shape = RoundedCornerShape(24.dp),
        color = cs.secondaryContainer,
        contentColor = cs.onSecondaryContainer,
        modifier = modifier,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.rounded_add), stringResource(R.string.deck_add), modifier = Modifier.size(32.dp))
        }
    }
}

/** Everything the deck can hold, grouped and searchable; what is already on it is left out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeckPicker(catalog: List<DeckAction>, onDeck: Set<String>, onPick: (DeckKey) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val modes by LinkHub.modes.collectAsStateWithLifecycle()
    val presets by LinkHub.presets.collectAsStateWithLifecycle()
    val apps by LinkHub.apps.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { LinkHub.requestApps() }
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val match = { name: String -> q.isEmpty() || name.lowercase().contains(q) }
    val pick = { k: DeckKey ->
        onPick(k)
        onDismiss()
    }
    val workspaceLabel = stringResource(R.string.remote_workspaces)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = cs.surfaceContainer) {
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.deck_search)) },
            leadingIcon = { Icon(painterResource(R.drawable.rounded_search), null) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = cs.surfaceBright,
                unfocusedContainerColor = cs.surfaceBright,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val groups = listOf(
                "shell" to R.string.deck_group_shell,
                "capture" to R.string.deck_group_capture,
                "media" to R.string.deck_group_media,
                "windows" to R.string.deck_group_windows,
                "look" to R.string.deck_group_look,
                "discord" to R.string.deck_group_discord,
                "custom" to R.string.deck_group_custom,
            )
            for ((group, title) in groups) {
                val list = catalog.filter { it.group == group && "action:${it.id}" !in onDeck && match(it.name) }
                if (list.isEmpty()) continue
                item(key = "h-$group") { GroupHeader(stringResource(title)) }
                item(key = "g-$group") {
                    CardGroup {
                        list.forEach { a ->
                            ToolRow(
                                title = a.name,
                                painter = symbolPainter(a.icon),
                                tint = if (a.toggle) Tint.PRIMARY else Tint.SECONDARY,
                                onClick = { pick(DeckKey("action", a.id, a.name, a.icon)) },
                            )
                        }
                    }
                }
            }
            modes?.list?.filter { "mode:${it.id}" !in onDeck && match(it.name) }?.takeIf { it.isNotEmpty() }?.let { list ->
                item(key = "h-modes") { GroupHeader(stringResource(R.string.remote_modes)) }
                item(key = "g-modes") {
                    CardGroup {
                        list.forEach { m ->
                            ToolRow(
                                title = m.name,
                                painter = symbolPainter(m.icon, fallback = R.drawable.rounded_tune),
                                tint = Tint.TERTIARY,
                                onClick = { pick(DeckKey("mode", m.id, m.name, m.icon)) },
                            )
                        }
                    }
                }
            }
            presets?.list?.filter { "preset:$it" !in onDeck && match(it) }?.takeIf { it.isNotEmpty() }?.let { list ->
                item(key = "h-presets") { GroupHeader(stringResource(R.string.remote_presets)) }
                item(key = "g-presets") {
                    CardGroup {
                        list.forEach { name ->
                            ToolRow(
                                title = name,
                                icon = R.drawable.rounded_palette,
                                tint = Tint.TERTIARY,
                                onClick = { pick(DeckKey("preset", name, name, "palette")) },
                            )
                        }
                    }
                }
            }
            (1..10).filter { "workspace:$it" !in onDeck && match("$workspaceLabel $it") }.takeIf { it.isNotEmpty() }?.let { list ->
                item(key = "h-ws") { GroupHeader(workspaceLabel) }
                item(key = "g-ws") {
                    CardGroup {
                        list.forEach { n ->
                            ToolRow(
                                title = "$workspaceLabel $n",
                                leading = { Monogram(n.toString(), Tint.SECONDARY) },
                                onClick = { pick(DeckKey("workspace", n.toString(), n.toString(), "grid_view")) },
                            )
                        }
                    }
                }
            }
            apps?.filter { "app:${it.id}" !in onDeck && match(it.name) }?.takeIf { it.isNotEmpty() }?.let { list ->
                item(key = "h-apps") { GroupHeader(stringResource(R.string.deck_group_apps)) }
                item(key = "g-apps") {
                    CardGroup {
                        list.forEach { app ->
                            ToolRow(
                                title = app.name,
                                leading = { Monogram(app.name.take(1).uppercase(), Tint.PRIMARY) },
                                onClick = { pick(DeckKey("app", app.id, app.name, "apps")) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A letter or number in the row badge, where an icon would go. */
@Composable
private fun Monogram(text: String, tint: Tint) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (tint) {
        Tint.PRIMARY -> cs.primaryContainer to cs.onPrimaryContainer
        Tint.TERTIARY -> cs.tertiaryContainer to cs.onTertiaryContainer
        Tint.ERROR -> cs.errorContainer to cs.onErrorContainer
        Tint.SECONDARY -> cs.secondaryContainer to cs.onSecondaryContainer
    }
    Box(Modifier.size(40.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = fg)
    }
}
