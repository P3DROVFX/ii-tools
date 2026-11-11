package dev.ii.tools.ui

import androidx.annotation.DrawableRes
import dev.ii.tools.R

/**
 * The shell names Material Symbols (actions, modes); the app carries only the ones it
 * draws, as vectors (see AGENTS.md, *Design reference*). Anything else falls back.
 */
@DrawableRes
fun symbolIcon(symbol: String, @DrawableRes fallback: Int = R.drawable.rounded_bolt): Int = when (symbol) {
    "lock" -> R.drawable.rounded_lock
    "screen_lock_portrait" -> R.drawable.rounded_screen_lock_portrait
    "brightness_empty" -> R.drawable.rounded_brightness_empty
    "music_note" -> R.drawable.rounded_music_note
    "inventory_2" -> R.drawable.rounded_inventory_2
    "bedtime" -> R.drawable.rounded_bedtime
    "work" -> R.drawable.rounded_work
    "center_focus_strong" -> R.drawable.rounded_center_focus_strong
    "sports_esports" -> R.drawable.rounded_sports_esports
    "theaters" -> R.drawable.rounded_theaters
    "co_present" -> R.drawable.rounded_co_present
    "spa" -> R.drawable.rounded_spa
    "self_improvement" -> R.drawable.rounded_self_improvement
    "headphones" -> R.drawable.rounded_headphones
    "headset_mic" -> R.drawable.rounded_headset_mic
    "monitor" -> R.drawable.rounded_monitor
    "speed" -> R.drawable.rounded_speed
    "tune" -> R.drawable.rounded_tune
    "notifications_active" -> R.drawable.rounded_notifications_active
    "mic_off" -> R.drawable.rounded_mic_off
    else -> fallback
}
