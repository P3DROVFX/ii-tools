package dev.ii.tools.notify

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * The phone's notifications on the PC, over ii link (PROTOCOL.md, "Notifications").
 *
 * Runs in the notification listener, so it sees every notification with the app
 * closed. A new one opens the link for a burst (LinkHub keeps it 2 min after the last);
 * on every connect the PC gets the whole current list, so nothing needs queueing while
 * the link is down. The PC can dismiss, press an action or reply.
 *
 * Mirrored: what the user would see and could swipe away. Not ongoing ones (live
 * updates have their own path), group summaries, media, calls, local-only ones, or
 * this app's and KDE Connect's own.
 */
object NotifMirror {
    private const val PREFS = "notifs"
    private const val ENABLED = "enabled"
    private const val ICONS_SENT = "iconsSent"
    private const val IMAGE_MAX = 480
    private const val AVATAR_MAX = 128
    private const val ICON_SIZE = 96
    /** The system's own placeholders ("2 more notifications", media holders), never news. */
    private val SYSTEM = setOf("android", "com.android.systemui")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listener: NotificationListenerService? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Icons go one at a time (one file per phone on the link), in the order they are needed. */
    private val iconQueue = Channel<String>(Channel.UNLIMITED)
    private val iconsQueued = mutableSetOf<String>()

    init {
        scope.launch {
            for (pkg in iconQueue) sendIcon(pkg)
        }
    }

    fun isEnabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).apply()
        _running.value = on && listener != null
        LinkHub.announceCaps()
        if (on) LinkHub.onLinkReady { sendAll() }
    }

    fun start(service: NotificationListenerService) {
        listener = service
        _running.value = isEnabled(service)
        LinkHub.announceCaps()
        // The link may have come up first (the app just updated): its hello found no
        // listener, so the list goes now.
        if (LinkHub.isOnline()) sendAll()
    }

    fun onListenerGone(service: NotificationListenerService) {
        if (listener !== service) return
        listener = null
        _running.value = false
        LinkHub.announceCaps()
    }

    private fun active(): Boolean = _running.value

    fun shouldMirror(sbn: StatusBarNotification, ownPackage: String): Boolean {
        val n = sbn.notification
        val pkg = sbn.packageName
        if (pkg == ownPackage || pkg.startsWith("org.kde.kdeconnect") || pkg in SYSTEM) return false
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_LOCAL_ONLY) != 0) return false
        if (n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_TRANSPORT) return false
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return false
        val e = n.extras
        return !e.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank() || !e.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank()
    }

    // ── Phone → PC ────────────────────────────────────────────────────────

    fun onPosted(sbn: StatusBarNotification) {
        val l = listener ?: return
        if (!active() || !shouldMirror(sbn, l.packageName)) return
        if (LinkHub.isOnline()) {
            scope.launch { LinkHub.sendJson(JSONObject().put("t", "notif").put("n", describe(l, sbn))) }
            queueIcon(sbn.packageName)
        }
        // Up or not, the burst keeps (or brings) the link up; a new link sends everything.
        LinkHub.notifBurst()
    }

    fun onRemoved(sbn: StatusBarNotification) {
        val l = listener ?: return
        if (!active() || !shouldMirror(sbn, l.packageName) || !LinkHub.isOnline()) return
        scope.launch { LinkHub.sendJson(JSONObject().put("t", "notifGone").put("key", sbn.key)) }
    }

    /** The link came up: the whole current list, then any icon the PC has not had. */
    fun sendAll() {
        val l = listener ?: return
        if (!active()) return
        scope.launch {
            val list = runCatching { l.activeNotifications }.getOrNull().orEmpty()
                .filter { shouldMirror(it, l.packageName) }
            val arr = JSONArray()
            list.forEach { arr.put(describe(l, it)) }
            LinkHub.sendJson(JSONObject().put("t", "notifs").put("list", arr))
            list.map { it.packageName }.distinct().forEach(::queueIcon)
        }
    }

    private fun describe(ctx: Context, sbn: StatusBarNotification): JSONObject {
        val n = sbn.notification
        val e = n.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val o = JSONObject()
            .put("key", sbn.key)
            .put("pkg", sbn.packageName)
            .put("app", appLabel(ctx, sbn.packageName))
            .put("title", title)
            .put("text", text)
            .put("time", sbn.postTime)
        images(ctx, n).let { (image, avatar) ->
            image?.let { o.put("image", it) }
            avatar?.let { o.put("avatar", it) }
        }
        val actions = JSONArray()
        n.actions?.forEachIndexed { i, a ->
            val input = a.remoteInputs?.firstOrNull { it.allowFreeFormInput }
            actions.put(
                JSONObject().put("i", i).put("label", a.title?.toString().orEmpty())
                    .put("reply", input != null).put("hint", input?.label?.toString().orEmpty()),
            )
        }
        if (actions.length() > 0) o.put("actions", actions)
        return o
    }

    /**
     * The content picture and the sender's photo, apart (small JPEGs, base64): a big
     * picture is content; the large icon is a person (a chat's sender, a mail's
     * contact) when square, and content (a video thumbnail, a product) when not.
     */
    private fun images(ctx: Context, n: Notification): Pair<String?, String?> {
        val e = n.extras
        val picture = runCatching {
            e.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
                ?: e.getParcelable(Notification.EXTRA_PICTURE_ICON, Icon::class.java)?.loadDrawable(ctx)?.toBitmap()
        }.getOrNull()
        val large = runCatching { n.getLargeIcon()?.loadDrawable(ctx)?.toBitmap() }.getOrNull()
        val largeIsContent = large != null && kotlin.math.abs(large.width.toFloat() / large.height.coerceAtLeast(1) - 1f) > 0.08f
        val content = picture ?: large?.takeIf { largeIsContent }
        // Chat apps (Telegram) may leave the large icon out and put the photo on the
        // last message's sender.
        val person = large?.takeIf { !largeIsContent } ?: runCatching {
            Notification.MessagingStyle.Message
                .getMessagesFromBundleArray(e.getParcelableArray(Notification.EXTRA_MESSAGES, android.os.Parcelable::class.java))
                .lastOrNull()?.senderPerson?.icon?.loadDrawable(ctx)?.toBitmap()
        }.getOrNull()
        return encode(content, IMAGE_MAX) to encode(person, AVATAR_MAX)
    }

    private fun encode(bmp: Bitmap?, max: Int): String? = runCatching {
        if (bmp == null) return null
        val scale = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
        val small = if (scale < 1f) bmp.scale((bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1)) else bmp
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 78, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    private val labels = HashMap<String, String>()
    private fun appLabel(ctx: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    // ── Icons: once per app per PC, kept there for good ───────────────────

    private fun queueIcon(pkg: String) {
        val ctx = listener ?: return
        val sent = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(sentKey(), emptySet()).orEmpty()
        synchronized(iconsQueued) {
            if (pkg in sent || !iconsQueued.add(pkg)) return
        }
        iconQueue.trySend(pkg)
    }

    private fun sentKey() = "$ICONS_SENT:${LinkHub.pc.value?.id.orEmpty()}"

    private suspend fun sendIcon(pkg: String) {
        val ctx = listener ?: return
        try {
            val png = runCatching {
                val out = ByteArrayOutputStream()
                ctx.packageManager.getApplicationIcon(pkg).toBitmap(ICON_SIZE, ICON_SIZE).compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }.getOrNull() ?: return
            val why = LinkHub.upload("$pkg.png", png.size.toLong(), { png.inputStream() }, "appIcon", "", waitForLink = false) { }
            if (why == null) {
                val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                prefs.edit().putStringSet(sentKey(), prefs.getStringSet(sentKey(), emptySet()).orEmpty() + pkg).apply()
            }
        } finally {
            synchronized(iconsQueued) { iconsQueued.remove(pkg) }
        }
    }

    // ── PC → phone ────────────────────────────────────────────────────────

    fun command(m: JSONObject) {
        val l = listener ?: return
        if (!active()) return
        val key = m.optString("key")
        val sbn = runCatching { l.activeNotifications }.getOrNull()?.firstOrNull { it.key == key } ?: return
        runCatching {
            when (m.optString("a")) {
                "dismiss" -> l.cancelNotification(key)
                "action" -> sbn.notification.actions?.getOrNull(m.optInt("i", -1))?.actionIntent?.send()
                "reply" -> {
                    val action = sbn.notification.actions?.getOrNull(m.optInt("i", -1)) ?: return
                    val input = action.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: return
                    val intent = Intent()
                    RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply { putCharSequence(input.resultKey, m.optString("text")) })
                    action.actionIntent.send(l, 0, intent)
                }
            }
        }
    }
}
