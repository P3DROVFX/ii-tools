package dev.ii.tools;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.RemoteViews;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Turns a live notification into the relay payload.
 *
 * Standard templates (Android 16 Live Updates / ProgressStyle, BigText…)
 * keep their content in extras. Apps such as iFood draw a custom layout
 * instead and leave every extra empty, so the layout is inflated off screen
 * and its visible TextViews/ProgressBars are read back.
 */
final class Extractor {
    /** Notification.FLAG_PROMOTED_ONGOING (API 36). */
    static final int FLAG_PROMOTED_ONGOING = 0x00040000;
    private static final int MAX_LINES = 8;

    private Extractor() {}

    static String template(Notification n) {
        CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TEMPLATE);
        return t == null ? "" : t.toString();
    }

    static boolean isMedia(Notification n) {
        return template(n).contains("MediaStyle")
                || Notification.CATEGORY_TRANSPORT.equals(n.category);
    }

    /** The automatic rule: does this ongoing notification look like a live update? */
    static boolean looksLive(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) == 0) return false;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return false;
        if (isMedia(n)) return false;
        if ((n.flags & FLAG_PROMOTED_ONGOING) != 0) return true;
        if (template(n).contains("ProgressStyle")) return true;
        return hasLiveWord(n.getChannelId()) || hasLiveWord(n.getGroup());
    }

    /** "LiveNotificationGroup", "live_updates" → true; "keep_alive", "Delivery" → false. */
    static boolean hasLiveWord(String id) {
        if (id == null) return false;
        String spaced = id.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        for (String tok : spaced.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (tok.equals("live")) return true;
        }
        return false;
    }

    static String appLabel(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    static JSONObject build(Context ctx, StatusBarNotification sbn) throws JSONException {
        Notification n = sbn.getNotification();
        Bundle x = n.extras;
        JSONObject o = new JSONObject();
        o.put("v", 1);
        o.put("id", Integer.toHexString(sbn.getKey().hashCode()));
        o.put("pkg", sbn.getPackageName());
        o.put("app", appLabel(ctx, sbn.getPackageName()));
        o.put("posted", sbn.getPostTime());
        if (n.color != 0) o.put("color", String.format("#%06x", n.color & 0xffffff));

        String title = str(x.getCharSequence(Notification.EXTRA_TITLE_BIG));
        if (title.isEmpty()) title = str(x.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (text.isEmpty()) text = str(x.getCharSequence(Notification.EXTRA_TEXT));
        String sub = str(x.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String crit = str(x.getCharSequence("android.shortCriticalText"));

        int progress = x.getInt(Notification.EXTRA_PROGRESS, 0);
        int max = x.getInt(Notification.EXTRA_PROGRESS_MAX, 0);
        boolean indeterminate = x.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false);

        List<String> lines = new ArrayList<>();
        RemoteViews rv = n.bigContentView != null ? n.bigContentView : n.contentView;
        if (rv != null) {
            Inflated inf = inflate(ctx, rv);
            lines.addAll(inf.texts);
            if (max <= 0 && inf.max > 0) {
                max = 1000;
                progress = (int) (inf.progress * 1000 / inf.max);
            }
            if (inf.indeterminate && max <= 0) indeterminate = true;
        }
        if (title.isEmpty() && !lines.isEmpty()) title = lines.remove(0);
        if (text.isEmpty() && !lines.isEmpty()) text = lines.remove(0);

        o.put("title", title);
        o.put("text", text);
        if (!sub.isEmpty()) o.put("sub", sub);
        if (!crit.isEmpty()) o.put("crit", crit);
        JSONArray arr = new JSONArray();
        for (String l : lines) arr.put(l);
        o.put("lines", arr);
        if (max > 0) {
            o.put("progress", progress);
            o.put("max", max);
        }
        if (indeterminate) o.put("indeterminate", true);
        if (x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)) {
            o.put("chrono", n.when);
            o.put("countdown", x.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false));
        }
        return o;
    }

    private static final class Inflated {
        final LinkedHashSet<String> texts = new LinkedHashSet<>();
        // Segmented steppers draw one bar per step; their sum is the real progress.
        long progress, max;
        boolean indeterminate;
    }

    private static Inflated inflate(Context ctx, RemoteViews rv) {
        Inflated out = new Inflated();
        try {
            FrameLayout parent = new FrameLayout(ctx);
            View v = rv.apply(ctx, parent);
            walk(v, out);
        } catch (Throwable t) {
            // A layout that refuses to inflate here just yields no lines.
        }
        return out;
    }

    private static void walk(View v, Inflated out) {
        if (v.getVisibility() == View.GONE) return;
        // Action labels ("Close", "Track"): buttons or click targets.
        if (v instanceof Button || v.hasOnClickListeners()) return;
        if (v instanceof TextView) {
            String s = str(((TextView) v).getText());
            if (!s.isEmpty() && out.texts.size() < MAX_LINES) out.texts.add(s);
        } else if (v instanceof ProgressBar) {
            ProgressBar p = (ProgressBar) v;
            if (p.isIndeterminate()) {
                out.indeterminate = true;
            } else if (p.getMax() > 0) {
                out.progress += Math.min(p.getProgress(), p.getMax());
                out.max += p.getMax();
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), out);
        }
    }

    static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }
}
