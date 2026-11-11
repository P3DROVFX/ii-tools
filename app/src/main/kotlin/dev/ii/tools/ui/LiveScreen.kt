package dev.ii.tools.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.live.LiveHub
import dev.ii.tools.live.LivePayload
import dev.ii.tools.live.LivePrefs
import dev.ii.tools.live.OngoingApp
import dev.ii.tools.live.RelayMode
import dev.ii.tools.live.view

/** Live notifications: setup, what the shell shows right now, and the per-app rule. */
@Composable
fun LiveScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status = rememberDeviceStatus()
    val relayed by LiveHub.relayed.collectAsStateWithLifecycle()
    val ongoing by LiveHub.ongoing.collectAsStateWithLifecycle()
    val modes by LivePrefs.modes.collectAsStateWithLifecycle()
    val postLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    ToolScaffold(title = stringResource(R.string.live_title), onBack = onBack) {
        item { Hint(stringResource(R.string.live_explainer)) }

        if (!status.liveReady) {
            item { GroupHeader(stringResource(R.string.setup_title)) }
            item {
                CardGroup {
                    PermissionRow(
                        title = stringResource(R.string.perm_listener),
                        body = stringResource(R.string.perm_listener_body),
                        icon = R.drawable.rounded_notifications,
                        granted = status.listenerGranted,
                        onGrant = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    )
                    PermissionRow(
                        title = stringResource(R.string.perm_post),
                        body = stringResource(R.string.perm_post_body),
                        icon = R.drawable.rounded_notifications_active,
                        granted = status.postGranted,
                        onGrant = { postLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    )
                }
            }
        }

        item { GroupHeader(stringResource(R.string.now_title)) }
        if (relayed.isEmpty()) {
            item { CardGroup { ToolRow(title = stringResource(R.string.now_empty), icon = R.drawable.rounded_notifications_active, tint = Tint.SECONDARY) } }
        } else {
            items(relayed.values.sortedByDescending { it.posted }, key = { it.id }) {
                LivePreviewCard(it)
            }
        }

        item { GroupHeader(stringResource(R.string.apps_title)) }
        item { Hint(stringResource(R.string.apps_hint)) }
        item {
            CardGroup {
                if (ongoing.isEmpty()) {
                    ToolRow(title = stringResource(R.string.apps_empty), icon = R.drawable.rounded_notifications, tint = Tint.SECONDARY)
                } else {
                    ongoing.forEach { app -> AppRuleRow(app, modes[app.pkg] ?: RelayMode.AUTO) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PermissionRow(title: String, body: String, icon: Int, granted: Boolean, onGrant: () -> Unit) {
    ToolRow(
        title = title,
        summary = body,
        icon = icon,
        tint = if (granted) Tint.PRIMARY else Tint.TERTIARY,
        trailing = {
            if (granted) {
                Icon(painterResource(R.drawable.rounded_check), stringResource(R.string.granted), tint = MaterialTheme.colorScheme.primary)
            } else {
                Button(onClick = onGrant, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.grant)) }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LivePreviewCard(p: LivePayload) {
    val cs = MaterialTheme.colorScheme
    val v = p.view()
    Surface(
        color = cs.primaryContainer,
        contentColor = cs.onPrimaryContainer,
        shape = RoundedCornerShape(32.dp),
        modifier = Modifier.animateContentSize(),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppIcon(p.pkg, 36.dp)
                Text(p.app, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Pill(stringResource(R.string.status_on), container = cs.primary, content = cs.onPrimary)
            }
            Text(
                v.status,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (v.chipValue.isNotEmpty() || v.detail.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (v.chipValue.isNotEmpty()) {
                        Surface(color = cs.primary, contentColor = cs.onPrimary, shape = RoundedCornerShape(50)) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (v.chipLabel.isNotEmpty()) Text(v.chipLabel, style = MaterialTheme.typography.labelLarge)
                                Text(v.chipValue, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                if (v.detail.isNotEmpty()) Text(v.detail, style = MaterialTheme.typography.bodyMedium)
            }
            p.fraction?.let { f ->
                LinearWavyProgressIndicator(
                    progress = { f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    color = cs.primary,
                    trackColor = cs.onPrimaryContainer.copy(alpha = 0.18f),
                )
            }
        }
    }
}

/** One app with an ongoing notification and its Auto/Always/Never rule. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppRuleRow(app: OngoingApp, mode: RelayMode) {
    val ctx = LocalContext.current
    ToolBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AppIcon(app.pkg)
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (app.looksLive) {
                    Text(stringResource(R.string.looks_live), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            val entries = RelayMode.entries
            entries.forEachIndexed { i, m ->
                ToggleButton(
                    checked = mode == m,
                    onCheckedChange = { LivePrefs.setMode(ctx, app.pkg, m) },
                    shapes = when (i) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                ) {
                    Text(
                        stringResource(
                            when (m) {
                                RelayMode.AUTO -> R.string.mode_auto
                                RelayMode.ALWAYS -> R.string.mode_always
                                RelayMode.NEVER -> R.string.mode_never
                            },
                        ),
                    )
                }
            }
        }
    }
}
