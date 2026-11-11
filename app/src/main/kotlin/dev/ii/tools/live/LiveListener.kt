package dev.ii.tools.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import dev.ii.tools.R

/**
 * Watches ongoing notifications and re-posts the live ones as a plain, silent,
 * dismissable notification. KDE Connect drops ongoing notifications but forwards this
 * copy, so the ii shell receives every update over the existing pairing.
 *
 * Wire format (read by the ii shell's KdeConnectService): title "<App> · <headline>",
 * text "iilive1:" + base64url(JSON from [LivePayload.toJson]).
 */
class LiveListener : NotificationListenerService() {
    companion object {
        const val TAG = "IiTools"
        const val CHANNEL = "live_relay"
        const val MIRROR_TAG = "live"
        const val MARKER = "iilive1:"
        private const val DEBOUNCE_MS = 700L
        private const val ONGOING_REFRESH_MS = 300L

        fun ensureChannel(ctx: Context) {
            val ch = NotificationChannel(
                CHANNEL, ctx.getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = ctx.getString(R.string.channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        private fun mirrorId(key: String) = key.hashCode()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val pending = HashMap<String, Runnable>()
    private val lastSent = HashMap<String, String>()
    private val icons = HashMap<String, Bitmap?>()
    private lateinit var nm: NotificationManager
    private val refreshOngoing = Runnable { publishOngoing() }

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        ensureChannel(this)
    }

    override fun onListenerConnected() {
        LiveHub.listener = this
        LiveHub.setConnected(true)
        rescan()
    }

    override fun onListenerDisconnected() {
        if (LiveHub.listener === this) LiveHub.listener = null
        LiveHub.setConnected(false)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (LiveHub.listener === this) {
            LiveHub.listener = null
            LiveHub.setConnected(false)
        }
        super.onDestroy()
    }

    private fun activeOrEmpty(): Array<StatusBarNotification> =
        runCatching { activeNotifications }.getOrNull() ?: emptyArray()

    /** Relays every live notification and drops copies that no longer qualify. */
    fun rescan() {
        val live = HashSet<Int>()
        val liveKeys = HashSet<String>()
        for (sbn in activeOrEmpty()) {
            if (shouldRelay(sbn)) {
                live += mirrorId(sbn.key)
                liveKeys += sbn.key
                schedule(sbn, 0)
            }
        }
        lastSent.keys.retainAll(liveKeys)
        LiveHub.retainRelayed(liveKeys)
        for (own in nm.activeNotifications) {
            if (own.tag == MIRROR_TAG && own.id !in live) nm.cancel(MIRROR_TAG, own.id)
        }
        publishOngoing()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (shouldRelay(sbn)) schedule(sbn, DEBOUNCE_MS)
        else if (sbn.key in lastSent) retire(sbn.key) // stopped being live
        scheduleOngoingRefresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        retire(sbn.key)
        scheduleOngoingRefresh()
    }

    private fun shouldRelay(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName
        if (pkg == packageName || pkg.startsWith("org.kde.kdeconnect")) return false
        if (!LiveExtractor.isOngoing(sbn.notification)) return false
        return when (LivePrefs.mode(this, pkg)) {
            RelayMode.NEVER -> false
            RelayMode.ALWAYS -> true
            RelayMode.AUTO -> LiveExtractor.looksLive(sbn)
        }
    }

    private fun schedule(sbn: StatusBarNotification, delay: Long) {
        val key = sbn.key
        pending.remove(key)?.let(handler::removeCallbacks)
        val r = Runnable {
            pending.remove(key)
            relay(sbn)
        }
        pending[key] = r
        handler.postDelayed(r, delay)
    }

    private fun retire(key: String) {
        pending.remove(key)?.let(handler::removeCallbacks)
        lastSent.remove(key)
        LiveHub.removeRelayed(key)
        nm.cancel(MIRROR_TAG, mirrorId(key))
    }

    private fun relay(sbn: StatusBarNotification) {
        try {
            val payload = LiveExtractor.build(this, sbn)
            val sig = payload.toJson(withPosted = false)
            if (sig == lastSent[sbn.key]) return
            lastSent[sbn.key] = sig
            LiveHub.putRelayed(sbn.key, payload)

            val headline = payload.title.ifEmpty { payload.text }
            val encoded = Base64.encodeToString(
                payload.toJson().toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING,
            )
            val b = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.rounded_notifications_active)
                .setContentTitle(if (headline.isEmpty()) payload.app else "${payload.app} · $headline")
                .setContentText(MARKER + encoded)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setLocalOnly(false)
            sbn.notification.contentIntent?.let(b::setContentIntent)
            appIcon(sbn.packageName)?.let(b::setLargeIcon)
            nm.notify(MIRROR_TAG, mirrorId(sbn.key), b.build())
            Log.i(TAG, "relayed ${sbn.packageName}: $headline")
        } catch (e: Exception) {
            Log.w(TAG, "relay failed for ${sbn.key}", e)
        }
    }

    private fun appIcon(pkg: String): Bitmap? = icons.getOrPut(pkg) {
        runCatching { packageManager.getApplicationIcon(pkg).toBitmap(128, 128) }.getOrNull()
    }

    private fun scheduleOngoingRefresh() {
        handler.removeCallbacks(refreshOngoing)
        handler.postDelayed(refreshOngoing, ONGOING_REFRESH_MS)
    }

    /** One row per app with an ongoing notification, for the per-app picker. */
    private fun publishOngoing() {
        val byPkg = LinkedHashMap<String, OngoingApp>()
        for (sbn in activeOrEmpty()) {
            if (sbn.packageName == packageName || !LiveExtractor.isOngoing(sbn.notification)) continue
            if (LiveExtractor.isMedia(sbn.notification)) continue
            val looksLive = LiveExtractor.looksLive(sbn)
            val prev = byPkg[sbn.packageName]
            if (prev == null || (!prev.looksLive && looksLive)) {
                byPkg[sbn.packageName] = OngoingApp(
                    pkg = sbn.packageName,
                    label = prev?.label ?: LiveExtractor.appLabel(this, sbn.packageName),
                    looksLive = looksLive,
                )
            }
        }
        LiveHub.setOngoing(byPkg.values.sortedWith(compareByDescending<OngoingApp> { it.looksLive }.thenBy { it.label }))
    }
}
