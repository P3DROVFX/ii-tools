package dev.ii.tools;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/** Status, permission shortcuts and the per-app Auto/Always/Never picker. */
public class MainActivity extends Activity {
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LiveListener.ensureChannel(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private boolean listenerEnabled() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        return nm.isNotificationListenerAccessGranted(new ComponentName(this, LiveListener.class));
    }

    private boolean canPost() {
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void render() {
        root.removeAllViews();
        TextView title = text(getString(R.string.app_name), 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        boolean listening = listenerEnabled();
        boolean posting = canPost();
        String status = !listening ? getString(R.string.status_off)
                : !posting ? getString(R.string.status_no_post)
                : getString(R.string.status_on);
        TextView st = text(status, 16);
        st.setPadding(0, dp(8), 0, dp(12));
        root.addView(st);

        if (!listening) {
            root.addView(button(getString(R.string.grant_access), v ->
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
        }
        if (!posting) {
            root.addView(button(getString(R.string.grant_post), v ->
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1)));
        }

        TextView help = text(getString(R.string.help), 13);
        help.setAlpha(0.7f);
        help.setPadding(0, dp(12), 0, dp(16));
        root.addView(help);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = text(getString(R.string.active_header), 18);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(h, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(button(getString(R.string.refresh), v -> render()));
        root.addView(header);

        Map<String, StatusBarNotification> byPkg = ongoingByPackage();
        if (byPkg.isEmpty()) {
            TextView e = text(getString(R.string.active_empty), 14);
            e.setAlpha(0.7f);
            e.setPadding(0, dp(8), 0, 0);
            root.addView(e);
        }
        for (StatusBarNotification sbn : byPkg.values()) root.addView(appRow(sbn));
    }

    private Map<String, StatusBarNotification> ongoingByPackage() {
        Map<String, StatusBarNotification> out = new LinkedHashMap<>();
        for (StatusBarNotification sbn : ActiveCache.snapshot()) {
            Notification n = sbn.getNotification();
            if ((n.flags & Notification.FLAG_ONGOING_EVENT) == 0) continue;
            if (sbn.getPackageName().equals(getPackageName())) continue;
            StatusBarNotification prev = out.get(sbn.getPackageName());
            // Prefer the notification that the auto rule would pick.
            if (prev == null || (!Extractor.looksLive(prev) && Extractor.looksLive(sbn))) {
                out.put(sbn.getPackageName(), sbn);
            }
        }
        return out;
    }

    private View appRow(StatusBarNotification sbn) {
        final String pkg = sbn.getPackageName();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        ImageView icon = new ImageView(this);
        try {
            icon.setImageDrawable(getPackageManager().getApplicationIcon(pkg));
        } catch (Exception ignored) {
        }
        row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), 0, dp(8), 0);
        labels.addView(text(Extractor.appLabel(this, pkg), 16));
        TextView sub = text(Extractor.looksLive(sbn) ? getString(R.string.mode_auto_live) : pkg, 12);
        sub.setAlpha(0.6f);
        labels.addView(sub);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        int mode = Prefs.mode(this, pkg);
        String[] names = {getString(R.string.mode_auto), getString(R.string.mode_on), getString(R.string.mode_off)};
        Button b = button(names[mode], null);
        b.setOnClickListener(v -> {
            int next = (Prefs.mode(this, pkg) + 1) % 3;
            Prefs.setMode(this, pkg, next);
            ActiveCache.rescan();
            render();
        });
        row.addView(b);
        return row;
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
