package dev.ii.tools;

import android.service.notification.StatusBarNotification;

/** Lets the activity reach the bound listener (only it can list notifications). */
final class ActiveCache {
    static volatile LiveListener listener;

    private ActiveCache() {}

    static StatusBarNotification[] snapshot() {
        LiveListener l = listener;
        if (l == null) return new StatusBarNotification[0];
        try {
            StatusBarNotification[] a = l.getActiveNotifications();
            return a == null ? new StatusBarNotification[0] : a;
        } catch (Exception e) {
            return new StatusBarNotification[0];
        }
    }

    /** Re-evaluates every active notification after a per-app mode change. */
    static void rescan() {
        LiveListener l = listener;
        if (l != null) l.rescan();
    }
}
