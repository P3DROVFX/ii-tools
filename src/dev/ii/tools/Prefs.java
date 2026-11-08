package dev.ii.tools;

import android.content.Context;
import android.content.SharedPreferences;

/** Per-app override of the automatic live detection. */
final class Prefs {
    static final int AUTO = 0, ALWAYS = 1, NEVER = 2;

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("apps", Context.MODE_PRIVATE);
    }

    static int mode(Context c, String pkg) {
        return sp(c).getInt(pkg, AUTO);
    }

    static void setMode(Context c, String pkg, int mode) {
        if (mode == AUTO) sp(c).edit().remove(pkg).apply();
        else sp(c).edit().putInt(pkg, mode).apply();
    }
}
