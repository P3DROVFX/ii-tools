package dev.ii.tools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Watches ongoing notifications and re-posts the live ones as a plain,
 * silent, dismissable notification. KDE Connect drops ongoing notifications
 * but forwards this copy, so the desktop shell receives every update over
 * the existing pairing.
 *
 * Wire format (read by the ii shell): title "<App> · <headline>", text
 * "iilive1:" + base64url(JSON payload from {@link Extractor#build}).
 */
public class LiveListener extends NotificationListenerService {
    static final String TAG = "IiTools";
    static final String CHANNEL = "live_relay";
    static final String MIRROR_TAG = "live";
    static final String MARKER = "iilive1:";
    private static final long DEBOUNCE_MS = 700;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Runnable> pending = new HashMap<>();
    private final Map<String, String> lastSent = new HashMap<>();
    private NotificationManager nm;

    @Override
    public void onCreate() {
        super.onCreate();
        nm = getSystemService(NotificationManager.class);
        ensureChannel(this);
    }

    static void ensureChannel(Context c) {
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                c.getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN);
        ch.setDescription(c.getString(R.string.channel_desc));
        ch.setShowBadge(false);
        ch.setSound(null, null);
        ch.enableVibration(false);
        c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    @Override
    public void onListenerConnected() {
        ActiveCache.listener = this;
        rescan();
    }

    @Override
    public void onListenerDisconnected() {
        if (ActiveCache.listener == this) ActiveCache.listener = null;
    }

    @Override
    public void onDestroy() {
        if (ActiveCache.listener == this) ActiveCache.listener = null;
        super.onDestroy();
    }

    /** Relays every live notification and drops copies that are no longer live. */
    void rescan() {
        Set<Integer> live = new HashSet<>();
        Set<String> liveKeys = new HashSet<>();
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (Exception e) {
            active = new StatusBarNotification[0];
        }
        for (StatusBarNotification sbn : active) {
            if (shouldRelay(sbn)) {
                live.add(mirrorId(sbn.getKey()));
                liveKeys.add(sbn.getKey());
                schedule(sbn, 0);
            }
        }
        lastSent.keySet().retainAll(liveKeys);
        // Drop copies whose source vanished or stopped qualifying.
        for (StatusBarNotification own : nm.getActiveNotifications()) {
            if (MIRROR_TAG.equals(own.getTag()) && !live.contains(own.getId())) {
                nm.cancel(MIRROR_TAG, own.getId());
            }
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (getPackageName().equals(sbn.getPackageName())) return;
        if (shouldRelay(sbn)) {
            schedule(sbn, DEBOUNCE_MS);
        } else if (lastSent.containsKey(sbn.getKey())) {
            // It stopped being live (e.g. no longer ongoing): retire the copy.
            retire(sbn.getKey());
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (getPackageName().equals(sbn.getPackageName())) return;
        retire(sbn.getKey());
    }

    private boolean shouldRelay(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (pkg.equals(getPackageName()) || pkg.startsWith("org.kde.kdeconnect")) return false;
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) == 0) return false;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return false;
        switch (Prefs.mode(this, pkg)) {
            case Prefs.NEVER: return false;
            case Prefs.ALWAYS: return true;
            default: return Extractor.looksLive(sbn);
        }
    }

    private void schedule(StatusBarNotification sbn, long delay) {
        final String key = sbn.getKey();
        Runnable old = pending.remove(key);
        if (old != null) handler.removeCallbacks(old);
        Runnable r = () -> {
            pending.remove(key);
            relay(sbn);
        };
        pending.put(key, r);
        handler.postDelayed(r, delay);
    }

    private void retire(String key) {
        Runnable old = pending.remove(key);
        if (old != null) handler.removeCallbacks(old);
        lastSent.remove(key);
        nm.cancel(MIRROR_TAG, mirrorId(key));
    }

    private static int mirrorId(String key) {
        return key.hashCode();
    }

    private void relay(StatusBarNotification sbn) {
        try {
            JSONObject payload = Extractor.build(this, sbn);
            // "posted" changes on every repost; compare content only.
            JSONObject cmp = new JSONObject(payload.toString());
            cmp.remove("posted");
            String sig = cmp.toString();
            if (sig.equals(lastSent.get(sbn.getKey()))) return;
            lastSent.put(sbn.getKey(), sig);

            String app = payload.optString("app");
            String headline = payload.optString("title");
            if (headline.isEmpty()) headline = payload.optString("text");
            String encoded = Base64.encodeToString(
                    payload.toString().getBytes(StandardCharsets.UTF_8),
                    Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);

            Notification src = sbn.getNotification();
            Notification.Builder b = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_live)
                    .setContentTitle(headline.isEmpty() ? app : app + " · " + headline)
                    .setContentText(MARKER + encoded)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setLocalOnly(false);
            if (src.contentIntent != null) b.setContentIntent(src.contentIntent);
            Bitmap icon = appIcon(sbn.getPackageName());
            if (icon != null) b.setLargeIcon(icon);
            nm.notify(MIRROR_TAG, mirrorId(sbn.getKey()), b.build());
            Log.i(TAG, "relayed " + sbn.getPackageName() + ": " + headline);
        } catch (Exception e) {
            Log.w(TAG, "relay failed for " + sbn.getKey(), e);
        }
    }

    private final Map<String, Bitmap> icons = new HashMap<>();

    private Bitmap appIcon(String pkg) {
        if (icons.containsKey(pkg)) return icons.get(pkg);
        Bitmap bmp = null;
        try {
            Drawable d = getPackageManager().getApplicationIcon(pkg);
            int size = 128;
            bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, size, size);
            d.draw(c);
        } catch (Exception ignored) {
        }
        icons.put(pkg, bmp);
        return bmp;
    }
}
