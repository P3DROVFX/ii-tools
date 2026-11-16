@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.ii.tools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkStatus
import dev.ii.tools.link.PcEntry
import dev.ii.tools.ui.theme.Expressive

/**
 * The home's title when a PC is paired: the name of the PC the home controls, and an
 * arrow that opens the list of paired PCs.
 */
@Composable
fun PcSwitcherTitle(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val label = stringResource(R.string.pcs_switch)
    Box(modifier) {
        Row(
            Modifier
                .clip(CircleShape)
                .clickable(onClickLabel = label) {
                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    onClick()
                }
                .semantics { role = Role.DropdownList }
                .padding(end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(name, style = Expressive.xwide(28.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Box(Modifier.size(32.dp).clip(CircleShape).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.rounded_keyboard_arrow_down), null, tint = cs.onSecondaryContainer, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/**
 * Every paired PC, in a bottom sheet. All of them stay connected; the one picked here is
 * the one the home and the tools control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcSwitcherSheet(onDismiss: () -> Unit, onPairNew: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val state = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    val machines by LinkHub.machines.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = cs.surfaceContainerHigh) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.pcs_title), style = Expressive.wide(26.sp), modifier = Modifier.padding(start = 4.dp))
            CardGroup {
                machines.forEach { entry ->
                    PcRow(entry) {
                        LinkHub.select(entry.pc.fingerprint)
                        onDismiss()
                    }
                }
            }
            Hint(stringResource(R.string.pcs_hint), Modifier.fillMaxWidth())
            FilledPill(
                stringResource(R.string.pcs_pair_new),
                onClick = onPairNew,
                icon = R.drawable.rounded_add,
                height = 56.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One PC: the controlled one fills and scallops; each says whether it is connected. */
@Composable
private fun PcRow(entry: PcEntry, onPick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val on = entry.selected
    val status = entry.status
    val online = status is LinkStatus.Online
    val fg = if (on) cs.onSecondaryContainer else cs.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.join))
            .background(if (on) cs.secondaryContainer else Layer.field)
            .clickable(enabled = !on) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onPick()
            }
            .semantics { role = Role.RadioButton }
            .padding(start = 10.dp, end = 16.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StateShape(active = on, size = 46.dp, color = if (on) cs.onSecondaryContainer else if (online) cs.primaryContainer else Layer.onPane, on = MaterialShapes.Cookie9Sided) {
            if (status is LinkStatus.Connecting) {
                LoadingIndicator(Modifier.size(30.dp), color = if (on) cs.secondaryContainer else cs.primary)
            } else {
                Icon(
                    painterResource(if (online || status is LinkStatus.Idle) R.drawable.rounded_desktop_windows else R.drawable.rounded_desktop_access_disabled),
                    null,
                    tint = when {
                        on -> cs.secondaryContainer
                        online -> cs.onPrimaryContainer
                        else -> cs.onSurfaceVariant
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(entry.pc.name.ifEmpty { "PC" }, style = Expressive.title(16.sp), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (status) {
                    is LinkStatus.Online -> onlineLabel(status.host, withHost = false)
                    is LinkStatus.Offline -> problemText(status.problem, status.failure)
                    else -> statusLabel(status)
                },
                style = if (online) Expressive.live(14.sp) else MaterialTheme.typography.bodyMedium,
                color = if (on) cs.onSecondaryContainer else cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (on) Icon(painterResource(R.drawable.rounded_check), null, tint = cs.onSecondaryContainer)
    }
}
