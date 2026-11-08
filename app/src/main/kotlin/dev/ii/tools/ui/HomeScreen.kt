package dev.ii.tools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.Destination
import dev.ii.tools.R
import dev.ii.tools.live.LiveHub

/** The hub: the KDE Connect link the app rides on, then each shell integration. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(onOpen: (Destination) -> Unit) {
    val status = rememberDeviceStatus()
    val relayed by LiveHub.relayed.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                subtitle = { Text(stringResource(R.string.app_tagline)) },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { KdeConnectCard(status.kdeConnectInstalled) }
            item { SectionHeader(stringResource(R.string.section_integrations)) }
            item {
                val (label, on) = when {
                    !status.liveReady -> stringResource(R.string.status_needs_setup) to false
                    relayed.isNotEmpty() -> stringResource(R.string.status_relaying, relayed.size) to true
                    else -> stringResource(R.string.status_on) to true
                }
                FeatureCard(
                    icon = R.drawable.ic_live,
                    title = stringResource(R.string.live_title),
                    summary = stringResource(R.string.live_summary),
                    status = label,
                    active = on,
                    onClick = { onOpen(Destination.LIVE) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun KdeConnectCard(installed: Boolean) {
    val cs = MaterialTheme.colorScheme
    val container = if (installed) cs.secondaryContainer else cs.errorContainer
    val content = if (installed) cs.onSecondaryContainer else cs.onErrorContainer
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(32.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ShapedIcon(
                icon = if (installed) R.drawable.ic_devices else R.drawable.ic_warning,
                shape = MaterialShapes.Cookie9Sided.toShape(),
                container = if (installed) cs.secondary else cs.error,
                content = if (installed) cs.onSecondary else cs.onError,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (installed) R.string.kdeconnect_ok_title else R.string.kdeconnect_missing_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(if (installed) R.string.kdeconnect_ok_body else R.string.kdeconnect_missing_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FeatureCard(
    icon: Int,
    title: String,
    summary: String,
    status: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        color = if (active) cs.primaryContainer else cs.surfaceContainerHigh,
        contentColor = if (active) cs.onPrimaryContainer else cs.onSurface,
        shape = RoundedCornerShape(32.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapedIcon(
                    icon = icon,
                    shape = MaterialShapes.Sunny.toShape(),
                    container = if (active) cs.primary else cs.surfaceContainerHighest,
                    content = if (active) cs.onPrimary else cs.onSurfaceVariant,
                    size = 64.dp,
                )
                Spacer(Modifier.weight(1f))
                Pill(
                    status,
                    container = if (active) cs.primary else cs.tertiaryContainer,
                    content = if (active) cs.onPrimary else cs.onTertiaryContainer,
                )
                Icon(
                    painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = if (active) cs.onPrimaryContainer else cs.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(summary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
