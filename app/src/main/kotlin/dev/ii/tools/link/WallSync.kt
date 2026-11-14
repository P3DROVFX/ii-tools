package dev.ii.tools.link

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.File

/**
 * The PC's wallpaper as this phone's (PROTOCOL.md, "Wallpaper sync"). Off by default:
 * it replaces the home and lock wallpapers. The shell sends the image once per change
 * (up to 3840 px, the original's detail is what a portrait crop is cut from), the phone
 * centre-crops it to its screen and sets it; Material You then draws its colours from
 * the same picture the PC's does.
 */
object WallSync {
    private const val PREFS = "wallsync"
    private const val ENABLED = "enabled"
    private const val APPLIED = "applied"

    private val lock = Any()

    fun isEnabled(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).apply()
        // The PC sends the current wallpaper to a phone that starts asking for it.
        if (on) c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(APPLIED).apply()
        LinkHub.announceCaps()
    }

    fun caps(c: Context): List<String> = if (isEnabled(c)) listOf("wallSync") else emptyList()

    /** The image arrived (purpose `wallSync`), named by its hash; true when it needs no resend. */
    fun received(c: Context, file: File, name: String): Boolean = synchronized(lock) {
        if (!isEnabled(c)) return true
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(APPLIED, "") == name) return true
        val screen = c.resources.displayMetrics
        val w = minOf(screen.widthPixels, screen.heightPixels)
        val h = maxOf(screen.widthPixels, screen.heightPixels)
        val bitmap = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                // The screen's aspect, cut from the middle, at the image's own resolution.
                val iw = info.size.width
                val ih = info.size.height
                val ratio = w.toFloat() / h
                val cw = minOf(iw, (ih * ratio).toInt())
                val ch = minOf(ih, (iw / ratio).toInt())
                decoder.setCrop(android.graphics.Rect((iw - cw) / 2, (ih - ch) / 2, (iw + cw) / 2, (ih + ch) / 2))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: return false
        val ok = runCatching {
            WallpaperManager.getInstance(c).setBitmap(
                bitmap, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
            )
        }.isSuccess
        bitmap.recycle()
        if (ok) prefs.edit().putString(APPLIED, name).apply()
        ok
    }
}
