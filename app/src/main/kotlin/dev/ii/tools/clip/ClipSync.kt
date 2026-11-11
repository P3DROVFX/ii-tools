package dev.ii.tools.clip

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * The clipboard between the phone and the PC (PROTOCOL.md, `clip`).
 *
 * PC → phone: what is copied on the PC arrives while the link is up and goes straight
 * onto Android's clipboard (writing it is allowed from the background). An image is
 * kept in the cache and offered through the FileProvider.
 *
 * Phone → PC: Android lets only a focused app read the clipboard, so this side is
 * always a deliberate act: the tile (through [ClipActivity]), the share sheet, or the
 * Clipboard screen.
 */
object ClipSync {
    private const val PREFS = "clip"
    private const val RECEIVE = "receive"
    private const val AUTO_SEND = "autoSend"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun receives(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(RECEIVE, true)

    fun setReceives(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(RECEIVE, on).apply()
        LinkHub.announceCaps()
    }

    /** Send every copied text automatically (needs [ClipWatchService] enabled). */
    fun autoSend(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AUTO_SEND, true)

    fun setAutoSend(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(AUTO_SEND, on).apply()
    }

    /** The last text that crossed, either way: never sent back, never sent twice. */
    @Volatile private var lastText: String? = null

    fun isNew(text: String): Boolean = text != lastText

    // ── PC → phone ────────────────────────────────────────────────────────

    fun receive(ctx: Context, m: JSONObject) {
        if (!receives(ctx)) return
        scope.launch {
            val clip = runCatching {
                val data = m.optString("data")
                if (data.isEmpty()) {
                    lastText = m.optString("text")
                    ClipData.newPlainText(ctx.getString(R.string.clip_title), m.optString("text"))
                } else {
                    val dir = File(ctx.cacheDir, "clip").apply { mkdirs() }
                    // A new name each time: a reader that cached the last image must not
                    // show it for this one.
                    dir.listFiles()?.forEach { it.delete() }
                    val file = File(dir, "pc-${System.currentTimeMillis()}.png")
                    file.writeBytes(Base64.decode(data, Base64.DEFAULT))
                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
                    ClipData.newUri(ctx.contentResolver, ctx.getString(R.string.clip_title), uri)
                }
            }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                runCatching { ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip) }
            }
        }
    }

    // ── Phone → PC ────────────────────────────────────────────────────────

    /** Reads the clipboard (call with a focused window) and sends it. Null when sent, else why not. */
    suspend fun sendCurrent(ctx: Context): String? {
        val item = ctx.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            ?: return "empty"
        val uri = item.uri
        val mime = uri?.let { ctx.contentResolver.getType(it) }
        if (uri != null && mime?.startsWith("image/") == true) return sendImage(ctx, uri, mime)
        val text = item.coerceToText(ctx)?.toString().orEmpty()
        if (text.isBlank()) return "empty"
        return sendText(text)
    }

    suspend fun sendText(text: String): String? {
        lastText = text
        return LinkHub.runWhenReady("clip", JSONObject().put("text", text))
    }

    suspend fun sendImage(ctx: Context, uri: Uri, mime: String): String? {
        val size = withContext(Dispatchers.IO) {
            runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                }
            }.getOrNull() ?: runCatching { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: 0L
        }
        if (size <= 0) return "read"
        val ext = when (mime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        return LinkHub.upload("clip.$ext", size, { ctx.contentResolver.openInputStream(uri) }, "clip", "") { }
    }
}

/**
 * The clipboard tile's moment on screen: invisible, it waits for window focus (the only
 * time Android lets an app read the clipboard), sends, says so and leaves.
 */
class ClipActivity : Activity() {
    private var sent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || sent) return
        sent = true
        val app = applicationContext
        // Read here, while focused; send from the app's scope, after this window is gone.
        val text = runCatching {
            getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        }.getOrNull()
        val uri = text?.uri
        val mime = uri?.let { contentResolver.getType(it) }
        val plain = if (uri == null || mime?.startsWith("image/") != true) text?.coerceToText(this)?.toString() else null
        finish()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            val why = when {
                uri != null && mime?.startsWith("image/") == true -> ClipSync.sendImage(app, uri, mime)
                !plain.isNullOrBlank() -> ClipSync.sendText(plain)
                else -> "empty"
            }
            val message = when (why) {
                null -> R.string.clip_sent
                "empty" -> R.string.clip_empty
                "disabled" -> R.string.clip_off
                else -> R.string.clip_failed
            }
            Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun finish() {
        super.finish()
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }
}
