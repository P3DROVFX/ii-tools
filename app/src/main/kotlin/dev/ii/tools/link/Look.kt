package dev.ii.tools.link

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The shell's look on the phone (PROTOCOL.md, "The shell's look"): its desktop wallpaper,
 * shown behind the home's PC card. The shell sends a small JPEG once per image, then
 * names it in the `look` topic; the last one stays on disk for when the PC is away.
 */
object Look {
    private const val PREFS = "look"
    private const val WALL = "wall"
    /** About the card's width at 3x density: no point decoding the full image. */
    private const val MAX_PX = 1080

    private val _wallpaper = MutableStateFlow<Bitmap?>(null)
    val wallpaper: StateFlow<Bitmap?> = _wallpaper.asStateFlow()
    @Volatile private var name = ""

    fun caps(): List<String> = listOf("look")

    private fun dir(c: Context) = File(c.filesDir, "look").apply { mkdirs() }

    /** The last wallpaper, from disk (the link's IO thread, at start). */
    fun load(c: Context) {
        val last = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(WALL, "").orEmpty()
        if (last.isNotEmpty()) show(c, last)
    }

    /** The image arrived (purpose `look`), named by its hash. */
    fun received(c: Context, file: File, wall: String): Boolean {
        val safe = wall.filter { it.isLetterOrDigit() }.ifEmpty { return false }
        val target = File(dir(c), safe)
        if (!file.renameTo(target) && runCatching { file.copyTo(target, overwrite = true) }.isFailure) return false
        if (safe == name || name.isEmpty()) show(c, safe)
        return true
    }

    /** The `look` topic: which image is the wallpaper now. */
    fun update(c: Context, wall: String) {
        val safe = wall.filter { it.isLetterOrDigit() }
        if (safe.isEmpty() || safe == name && _wallpaper.value != null) return
        show(c, safe)
    }

    private fun show(c: Context, wall: String) {
        name = wall
        val f = File(dir(c), wall)
        if (!f.isFile) return // the file comes first; it shows when it lands
        val bitmap = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { decoder, info, _ ->
                decoder.setTargetSampleSize((maxOf(info.size.width, info.size.height) / MAX_PX).coerceAtLeast(1))
            }
        }.getOrNull() ?: return
        _wallpaper.value = bitmap
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(WALL, wall).apply()
        // Only the one shown is kept.
        dir(c).listFiles()?.filter { it.name != wall }?.forEach { it.delete() }
    }
}
