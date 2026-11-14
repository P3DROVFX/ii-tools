package dev.ii.tools.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.routines.PcAction
import dev.ii.tools.ui.theme.Expressive
import dev.ii.tools.routines.Routine
import dev.ii.tools.routines.Routines
import dev.ii.tools.routines.RunActivity
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private class RoutineInfo(
    val routine: Routine,
    @StringRes val title: Int,
    @StringRes val body: Int,
    @DrawableRes val icon: Int,
)

private val ready = listOf(
    RoutineInfo(Routine.LEAVE, R.string.routine_leave, R.string.routine_leave_body, R.drawable.rounded_directions_walk),
    RoutineInfo(Routine.BEDTIME, R.string.routine_bedtime, R.string.routine_bedtime_body, R.drawable.rounded_bedtime),
    RoutineInfo(Routine.ARRIVE, R.string.routine_arrive, R.string.routine_arrive_body, R.drawable.rounded_home),
    RoutineInfo(Routine.CALL, R.string.routine_call, R.string.routine_call_body, R.drawable.rounded_call),
)

private class ShortcutInfoRow(val action: PcAction, @StringRes val label: Int, @DrawableRes val icon: Int)

private val shortcuts = listOf(
    ShortcutInfoRow(PcAction.LOCK, R.string.shortcut_lock, R.drawable.rounded_lock),
    ShortcutInfoRow(PcAction.LOCK_AOD, R.string.shortcut_lock_aod, R.drawable.rounded_bedtime),
    ShortcutInfoRow(PcAction.PAUSE_MEDIA, R.string.shortcut_pause_media, R.drawable.rounded_pause),
    ShortcutInfoRow(PcAction.WAKE, R.string.shortcut_wake, R.drawable.rounded_power_settings_new),
)

/** The ready-made routines of the phone that act on the PC, and shortcuts for the phone's own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val last by Routines.last.collectAsStateWithLifecycle()
    val enabled = remember { mutableStateOf(Routine.entries.associateWith { Routines.isEnabled(ctx, it) }) }
    var bed by remember { mutableStateOf(Routines.bedtime(ctx)) }
    var duck by remember { mutableStateOf(Routines.duckTo(ctx) / 100f) }
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = from, false = to

    ToolScaffold(title = stringResource(R.string.routines_title), onBack = onBack) {
        item { Hint(stringResource(R.string.routines_hint)) }
        item { GroupHeader(stringResource(R.string.routines_ready)) }
        item {
            CardGroup {
                ready.forEach { info ->
                    val ran = last[info.routine]
                    val state = when {
                        ran == null -> null
                        ran.failed == null -> stringResource(R.string.routine_ran, ran.at)
                        else -> stringResource(R.string.routine_failed, ran.at, ran.failed)
                    }
                    SwitchRow(
                        title = stringResource(info.title),
                        checked = enabled.value[info.routine] == true,
                        onCheckedChange = {
                            Routines.setEnabled(ctx, info.routine, it)
                            enabled.value = enabled.value + (info.routine to it)
                        },
                        icon = info.icon,
                        summary = listOfNotNull(stringResource(info.body), state).joinToString("\n"),
                    )
                }
            }
        }
        item {
            CardGroup {
                ToolRow(
                    title = stringResource(R.string.routines_bed_window),
                    summary = "${clock(bed.first)} – ${clock(bed.second)}",
                    icon = R.drawable.rounded_schedule,
                    onClick = { picking = true },
                )
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${stringResource(R.string.routines_call_volume)} · ${(duck * 100).roundToInt()}%",
                        style = Expressive.title(14.sp),
                    )
                    BigSlider(
                        value = duck,
                        onValueChange = { duck = it },
                        onValueChangeFinished = { Routines.setDuckTo(ctx, (duck * 100).roundToInt()) },
                        label = stringResource(R.string.routines_call_volume),
                        height = 36.dp,
                        icon = R.drawable.rounded_volume_down,
                    )
                }
            }
        }

        item { GroupHeader(stringResource(R.string.routines_shortcuts)) }
        item { Hint(stringResource(R.string.routines_shortcuts_hint)) }
        item {
            CardGroup {
                shortcuts.forEach { row ->
                    ToolRow(
                        title = stringResource(row.label),
                        icon = row.icon,
                        onClick = { scope.launch { Routines.exec(ctx, row.action) } },
                        trailing = {
                            IconButton(onClick = { pin(ctx, row, ctx.getString(row.label)) }) {
                                M3Icon(painterResource(R.drawable.rounded_add_to_home_screen), stringResource(R.string.routines_pin))
                            }
                        },
                    )
                }
            }
        }
    }

    picking?.let { from ->
        val minutes = if (from) bed.first else bed.second
        val state = rememberTimePickerState(minutes / 60, minutes % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(stringResource(if (from) R.string.routines_bed_from else R.string.routines_bed_to)) },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    val m = state.hour * 60 + state.minute
                    bed = if (from) m to bed.second else bed.first to m
                    Routines.setBedtime(ctx, bed.first, bed.second)
                    // From, then To: the second pick follows the first.
                    picking = if (from) false else null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun clock(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

/** The launcher offers to put the shortcut on the home screen. */
private fun pin(ctx: Context, row: ShortcutInfoRow, label: String) {
    val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return
    if (!sm.isRequestPinShortcutSupported) return
    val intent = Intent(Intent.ACTION_VIEW).setClassName(ctx, RunActivity::class.java.name).putExtra("a", row.action.id)
    val info = ShortcutInfo.Builder(ctx, "pin_${row.action.id}")
        .setShortLabel(label)
        .setIcon(Icon.createWithResource(ctx, row.icon))
        .setIntent(intent)
        .build()
    sm.requestPinShortcut(info, null)
}
