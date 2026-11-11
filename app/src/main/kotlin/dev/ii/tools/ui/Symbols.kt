package dev.ii.tools.ui

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ii.tools.R
import dev.ii.tools.link.Symbols

/**
 * The shell names Material Symbols (actions, modes, deck keys). The app carries the
 * ones its own screens use as vectors (see AGENTS.md, *Design reference*); any other
 * is drawn by the PC from its font once and cached ([Symbols]).
 */
@DrawableRes
fun bundledSymbol(symbol: String): Int? = when (symbol) {
    "lock" -> R.drawable.rounded_lock
    "screen_lock_portrait" -> R.drawable.rounded_screen_lock_portrait
    "brightness_empty" -> R.drawable.rounded_brightness_empty
    "music_note" -> R.drawable.rounded_music_note
    "inventory_2" -> R.drawable.rounded_inventory_2
    "tune" -> R.drawable.rounded_tune
    "mic_off" -> R.drawable.rounded_mic_off
    "volume_up" -> R.drawable.rounded_volume_up
    "volume_off" -> R.drawable.rounded_volume_off
    "notifications_active" -> R.drawable.rounded_notifications_active
    "grid_view" -> R.drawable.rounded_grid_view
    "palette" -> R.drawable.rounded_palette
    else -> null
}

/** [symbol] as a painter: bundled, cached from the PC, or [fallback] until it arrives. */
@Composable
fun symbolPainter(symbol: String, @DrawableRes fallback: Int = R.drawable.rounded_bolt): Painter {
    bundledSymbol(symbol)?.let { return painterResource(it) }
    val paths by Symbols.paths.collectAsStateWithLifecycle()
    val d = paths[symbol]
    LaunchedEffect(symbol, d == null) { if (d == null) Symbols.want(symbol) }
    if (d == null) return painterResource(fallback)
    val vector = remember(d) { symbolVector(d) }
    return rememberVectorPainter(vector)
}

/** A mask, like the bundled drawables: white fill, tinted by whoever draws it. */
private fun symbolVector(pathData: String): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 960f, viewportHeight = 960f)
        .addPath(PathParser().parsePathString(pathData).toNodes(), fill = SolidColor(Color.White))
        .build()
