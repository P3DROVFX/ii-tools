package dev.ii.tools.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.LinkProblem
import dev.ii.tools.link.LinkStatus
import dev.ii.tools.link.PairedPc
import dev.ii.tools.link.PairingUri
import dev.ii.tools.link.ShellAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Connect to PC: pairing and the link's state. The remote and the audio have their own screens. */
@Composable
fun LinkScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status by LinkHub.status.collectAsStateWithLifecycle()
    val pc by LinkHub.pc.collectAsStateWithLifecycle()

    var permitted by remember { mutableStateOf(LinkHub.hasNetworkPermission(ctx)) }
    LifecycleResumeEffect(Unit) {
        permitted = LinkHub.hasNetworkPermission(ctx)
        onPauseOrDispose { }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        if (granted) LinkHub.onPermissionGranted()
    }

    var invalidCode by remember { mutableStateOf(false) }
    val scan = {
        invalidCode = false
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(ctx, options).startScan()
            .addOnSuccessListener { code ->
                val uri = PairingUri.parse(code.rawValue)
                if (uri != null) LinkHub.pair(uri) else invalidCode = true
            }
        Unit
    }
    var confirmForget by remember { mutableStateOf(false) }
    var stays by remember { mutableStateOf(LinkHub.staysConnected()) }
    val wakeScope = rememberCoroutineScope()
    var woke by remember { mutableStateOf<Boolean?>(null) }
    var unrestricted by remember { mutableStateOf(LinkHub.batteryUnrestricted()) }
    LifecycleResumeEffect(Unit) {
        unrestricted = LinkHub.batteryUnrestricted()
        onPauseOrDispose { }
    }

    val current = pc
    ToolScaffold(
        title = current?.name?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.link_title),
        onBack = onBack,
    ) {
        if (!permitted) {
            item {
                CardGroup {
                    ToolRow(
                        title = stringResource(R.string.perm_network),
                        summary = stringResource(R.string.perm_network_body),
                        icon = R.drawable.rounded_devices,
                        tint = Tint.TERTIARY,
                        trailing = {
                            Button(
                                onClick = { permissionLauncher.launch(LinkHub.LOCAL_NETWORK_PERMISSION) },
                                shapes = ButtonDefaults.shapes(),
                            ) { Text(stringResource(R.string.grant)) }
                        },
                    )
                }
            }
        }

        if (current == null) {
            item { PairGroup(status, invalidCode, onScan = scan) }
            return@ToolScaffold
        }

        item { CardGroup { StatusRow(current, status) } }

        // Kept open in the background; Doze needs letting off for that.
        if (stays && !unrestricted) {
            item { GroupHeader(stringResource(R.string.setup_title)) }
            item {
                CardGroup {
                    PermissionRow(
                        title = stringResource(R.string.link_battery),
                        body = stringResource(R.string.link_battery_body),
                        icon = R.drawable.rounded_battery_full,
                        granted = false,
                        onGrant = {
                            ctx.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.fromParts("package", ctx.packageName, null),
                                ),
                            )
                        },
                    )
                }
            }
        }

        item { GroupHeader(stringResource(R.string.section_this_pc)) }
        item {
            CardGroup {
                val toggleStay = { on: Boolean ->
                    stays = on
                    LinkHub.setStaysConnected(on)
                }
                ToolRow(
                    title = stringResource(R.string.link_stay),
                    icon = R.drawable.rounded_sync,
                    tint = if (stays) Tint.PRIMARY else Tint.SECONDARY,
                    onClick = { toggleStay(!stays) },
                    trailing = { androidx.compose.material3.Switch(checked = stays, onCheckedChange = toggleStay) },
                )
                // Wake-on-LAN, once the PC has said its adapters (any hello).
                if (dev.ii.tools.link.Wake.available(ctx) && status !is LinkStatus.Online) {
                    ToolRow(
                        title = stringResource(R.string.wake_pc),
                        summary = woke?.let { stringResource(if (it) R.string.wake_sent else R.string.wake_failed) },
                        icon = R.drawable.rounded_power_settings_new,
                        tint = Tint.TERTIARY,
                        onClick = { wakeScope.launch { woke = LinkHub.wake() } },
                    )
                }
                ToolRow(
                    title = stringResource(R.string.link_address),
                    summary = "${current.hosts.firstOrNull().orEmpty()}:${current.port}",
                    icon = R.drawable.rounded_lan,
                    tint = Tint.SECONDARY,
                )
                ToolRow(
                    title = stringResource(R.string.forget_pc),
                    icon = R.drawable.rounded_delete,
                    tint = Tint.ERROR,
                    onClick = { confirmForget = true },
                )
            }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.forget_pc)) },
            text = { Text(stringResource(R.string.forget_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    LinkHub.forget()
                }) { Text(stringResource(R.string.forget_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Not paired yet: how, and the one button that does it. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PairGroup(status: LinkStatus, invalidCode: Boolean, onScan: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardGroup {
        ToolRow(
            title = stringResource(R.string.pair_title),
            icon = R.drawable.rounded_qr_code_scanner,
            tint = Tint.PRIMARY,
        )
        ToolBlock {
            Text(stringResource(R.string.pair_body), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            if (status is LinkStatus.Pairing) {
                LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
            } else {
                Button(onClick = onScan, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(painterResource(R.drawable.rounded_qr_code_scanner), null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.pair_scan))
                }
            }
            val problem = when {
                invalidCode -> stringResource(R.string.pair_invalid)
                status is LinkStatus.Offline -> problemText(status.problem)
                else -> null
            }
            AnimatedVisibility(problem != null) {
                Text(
                    problem.orEmpty(),
                    color = cs.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/** The link in one row: its state, where it goes, and a retry when it is down. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StatusRow(pc: PairedPc, status: LinkStatus) {
    ToolRow(
        title = statusLabel(status),
        summary = when (status) {
            is LinkStatus.Online -> status.host
            is LinkStatus.Offline -> problemText(status.problem)
            LinkStatus.Connecting -> pc.hosts.firstOrNull().orEmpty()
            else -> stringResource(R.string.link_hero_paired)
        },
        icon = R.drawable.rounded_desktop_windows,
        tint = when (status) {
            is LinkStatus.Online -> Tint.PRIMARY
            is LinkStatus.Offline -> Tint.ERROR
            else -> Tint.SECONDARY
        },
        trailing = {
            when (status) {
                LinkStatus.Connecting -> LoadingIndicator(Modifier.size(36.dp))
                is LinkStatus.Offline -> FilledTonalButton(onClick = { LinkHub.retry() }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.retry))
                }
                else -> {}
            }
        },
    )
}

@Composable
fun statusLabel(status: LinkStatus): String = stringResource(
    when (status) {
        LinkStatus.Unpaired -> R.string.status_unpaired
        LinkStatus.Idle -> R.string.status_idle
        LinkStatus.Pairing -> R.string.pair_working
        LinkStatus.Connecting -> R.string.status_connecting
        is LinkStatus.Online -> R.string.status_online
        is LinkStatus.Offline -> R.string.status_offline
    },
)

@Composable
internal fun problemText(problem: LinkProblem): String = stringResource(
    when (problem) {
        LinkProblem.UNREACHABLE -> R.string.problem_unreachable
        LinkProblem.REJECTED -> R.string.problem_rejected
        LinkProblem.PAIRING_FAILED -> R.string.problem_pairing
        LinkProblem.NO_PERMISSION -> R.string.problem_permission
    },
)

/**
 * The top of every screen that needs the PC: nothing while connected, else the link's
 * state with its retry. Returns whether the shell is there to talk to.
 */
internal fun LazyListScope.linkGate(pc: PairedPc?, status: LinkStatus, shellReady: Boolean): Boolean {
    if (pc == null) {
        item { Hint(stringResource(R.string.link_needed)) }
        return false
    }
    if (status is LinkStatus.Online && shellReady) return true
    item { CardGroup { StatusRow(pc, status) } }
    return false
}
