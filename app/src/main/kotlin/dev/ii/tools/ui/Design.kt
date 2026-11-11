package dev.ii.tools.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ii.tools.R

/*
 * The app's layout vocabulary, taken from sameerasw/essentials (the design reference,
 * cloned at ~/Projects/reference/essentials):
 *
 *  - a surfaceContainer page under a LargeFlexibleTopAppBar of the same colour;
 *  - rows are M3 ListItems on surfaceBright, gathered in groups clipped to 24 dp with
 *    2 dp seams between rows and 12 dp between groups;
 *  - a row is its title, one line; a 40 dp tinted circle with a 24 dp Material Symbols
 *    Rounded glyph leads it; supporting text only where it says something the title
 *    cannot (a status, an address);
 *  - icons are the official Material Symbols Rounded vectors (res/drawable/rounded_*).
 */

/** A screen: large flexible bar that collapses into the page colour, then a list. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ToolScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val haptics = LocalHapticFeedback.current
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = cs.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                onBack()
                            },
                            colors = IconButtonDefaults.iconButtonColors(containerColor = cs.surfaceBright),
                        ) {
                            Icon(painterResource(R.drawable.rounded_arrow_back), stringResource(R.string.back))
                        }
                    }
                },
                expandedHeight = 120.dp,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = cs.surfaceContainer,
                    scrolledContainerColor = cs.surfaceContainer,
                ),
                modifier = Modifier.padding(horizontal = 8.dp),
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** A section title above a group, on detail screens. */
@Composable
fun GroupHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp),
    )
}

/** Rows that belong together: one rounded card, cut by thin seams. */
@Composable
fun CardGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

/** Which scheme family tints a row's icon. All of them follow the wallpaper. */
enum class Tint { PRIMARY, SECONDARY, TERTIARY, ERROR }

@Composable
private fun tintColors(tint: Tint): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    return when (tint) {
        Tint.PRIMARY -> cs.primaryContainer to cs.primary
        Tint.SECONDARY -> cs.secondaryContainer to cs.secondary
        Tint.TERTIARY -> cs.tertiaryContainer to cs.tertiary
        Tint.ERROR -> cs.errorContainer to cs.error
    }
}

/** A 24 dp glyph in a 40 dp tinted circle: what leads every row. */
@Composable
fun IconBadge(@DrawableRes icon: Int, tint: Tint) {
    val (container, content) = tintColors(tint)
    Box(Modifier.size(40.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, tint = content, modifier = Modifier.size(24.dp))
    }
}

/** One row of a [CardGroup]: an M3 ListItem, clickable when [onClick] is given. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ToolRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    tint: Tint = Tint.SECONDARY,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val leadingContent: (@Composable () -> Unit)? = when {
        leading != null -> leading
        icon != null -> { { IconBadge(icon, tint) } }
        else -> null
    }
    val supporting: (@Composable () -> Unit)? = summary?.takeIf { it.isNotEmpty() }?.let {
        { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
    }
    val trailingContent: (@Composable () -> Unit)? = trailing?.let {
        { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { it() } }
    }
    val colors = ListItemDefaults.colors(containerColor = cs.surfaceBright)
    val padding = PaddingValues(16.dp)
    val titleText: @Composable () -> Unit = {
        Text(title, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (onClick != null) {
        ListItem(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            },
            modifier = modifier.fillMaxWidth(),
            leadingContent = leadingContent,
            supportingContent = supporting,
            trailingContent = trailingContent,
            verticalAlignment = Alignment.CenterVertically,
            colors = colors,
            contentPadding = padding,
            content = titleText,
        )
    } else {
        ListItem(
            modifier = modifier.fillMaxWidth(),
            leadingContent = leadingContent,
            supportingContent = supporting,
            trailingContent = trailingContent,
            verticalAlignment = Alignment.CenterVertically,
            colors = colors,
            contentPadding = padding,
            content = titleText,
        )
    }
}

/** A row with a body under it (rules, previews): same surface as a [ToolRow]. */
@Composable
fun ToolBlock(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceBright)
            .padding(16.dp),
        content = content,
    )
}

/** Muted text for a hint, inside the page rhythm. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp),
    )
}
