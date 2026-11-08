package dev.ii.tools.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LiveScreen(onBack: () -> Unit) {
    val status = rememberDeviceStatus()
    val relayed by LiveHub.relayed.collectAsStateWithLifecycle()
    val ongoing by LiveHub.ongoing.collectAsStateWithLifecycle()
    val modes by LivePrefs.modes.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.live_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.live_explainer),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            if (!status.liveReady) {
                item { SectionHeader(stringResource(R.string.setup_title)) }
                item { SetupCard(status) }
            }

            item { SectionHeader(stringResource(R.string.now_title)) }
            if (relayed.isEmpty()) {
                item { EmptyNote(stringResource(R.string.now_empty)) }
            } else {
                items(relayed.values.sortedByDescending { it.posted }, key = { it.id }) { LivePreviewCard(it) }
            }

            item { SectionHeader(stringResource(R.string.apps_title)) }
            item {
                Text(
                    stringResource(R.string.apps_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
                )
            }
            if (ongoing.isEmpty()) {
                item { EmptyNote(stringResource(R.string.apps_empty)) }
            } else {
                items(ongoing, key = { it.pkg }) { app ->
                    AppRuleRow(app, modes[app.pkg] ?: RelayMode.AUTO)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SetupCard(status: DeviceStatus) {
    val ctx = LocalContext.current
    val postLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(32.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            PermissionRow(
                title = stringResource(R.string.perm_listener),
                body = stringResource(R.string.perm_listener_body),
                granted = status.listenerGranted,
                onGrant = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            )
            PermissionRow(
                title = stringResource(R.string.perm_post),
                body = stringResource(R.string.perm_post_body),
                granted = status.postGranted,
                onGrant = { postLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PermissionRow(title: String, body: String, granted: Boolean, onGrant: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
        if (granted) {
            FilledIconButton(
                onClick = {},
                enabled = false,
                shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.filledIconButtonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.tertiary,
                    disabledContentColor = MaterialTheme.colorScheme.onTertiary,
                ),
            ) {
                Icon(painterResource(R.drawable.ic_check), stringResource(R.string.granted))
            }
        } else {
            Button(onClick = onGrant, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.grant))
            }
        }
    }
}

/** The payload as the shell's live card will read it. */
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
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainerHigh, shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIcon(app.pkg)
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(app.pkg, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (app.looksLive) {
                    ShapedIcon(
                        icon = R.drawable.ic_live,
                        shape = MaterialShapes.Cookie6Sided.toShape(),
                        container = cs.primary,
                        content = cs.onPrimary,
                        size = 32.dp,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
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
}

@Composable
private fun EmptyNote(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(20.dp),
        )
    }
}
