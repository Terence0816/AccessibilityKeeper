package com.terence.accessibilitykeeper;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class Prefs {
    private static final String NAME = "keeper_prefs";
    private static final String KEY_SELECTED = "selected_services";
    private static final String KEY_GUARD_ENABLED = "guard_enabled";
    private static final String KEY_REPAIR_COUNT = "repair_count";
    private static final String KEY_REPAIR_LOG = "repair_log";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_HYPEROS_BATTERY_CONFIRMED = "hyperos_battery_confirmed";
    private static final String KEY_AUTOSTART_CONFIRMED = "autostart_confirmed";

    private Prefs() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static Set<String> getSelected(Context c) {
        return new HashSet<>(p(c).getStringSet(KEY_SELECTED, new HashSet<>()));
    }

    public static void setSelected(Context c, Set<String> values) {
        p(c).edit().putStringSet(KEY_SELECTED, new HashSet<>(values)).apply();
    }

    public static boolean isGuardEnabled(Context c) {
        return p(c).getBoolean(KEY_GUARD_ENABLED, false);
    }

    public static void setGuardEnabled(Context c, boolean enabled) {
        p(c).edit().putBoolean(KEY_GUARD_ENABLED, enabled).apply();
    }

    public static int getRepairCount(Context c) {
        return p(c).getInt(KEY_REPAIR_COUNT, 0);
    }

    public static String getRepairLog(Context c) {
        return p(c).getString(KEY_REPAIR_LOG, "");
    }

    public static long getLastCheck(Context c) {
        return p(c).getLong(KEY_LAST_CHECK, 0L);
    }

    public static void setLastCheck(Context c, long when) {
        p(c).edit().putLong(KEY_LAST_CHECK, when).apply();
    }

    public static boolean isHyperOsBatteryConfirmed(Context c) {
        return p(c).getBoolean(KEY_HYPEROS_BATTERY_CONFIRMED, false);
    }

    public static void setHyperOsBatteryConfirmed(Context c, boolean confirmed) {
        p(c).edit().putBoolean(KEY_HYPEROS_BATTERY_CONFIRMED, confirmed).apply();
    }

    public static boolean isAutoStartConfirmed(Context c) {
        return p(c).getBoolean(KEY_AUTOSTART_CONFIRMED, false);
    }

    public static void setAutoStartConfirmed(Context c, boolean confirmed) {
        p(c).edit().putBoolean(KEY_AUTOSTART_CONFIRMED, confirmed).apply();
    }

    public static synchronized void addRestore(Context c, List<String> labels) {
        addRepairEvent(c, "恢復開關", labels);
    }

    public static synchronized void addRestart(Context c, List<String> labels) {
        addRepairEvent(c, "失效重啟", labels);
    }

    // Backward-compatible entry point used by older code/data.
    public static synchronized void addRepair(Context c, List<String> labels) {
        addRestore(c, labels);
    }

    private static void addRepairEvent(Context c, String action, List<String> labels) {
        if (labels == null || labels.isEmpty()) return;
        int count = getRepairCount(c) + labels.size();
        String time = new SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault()).format(new Date());
        String entry = time + "  " + action + "：" + String.join(", ", labels);

        String old = getRepairLog(c);
        List<String> lines = new ArrayList<>();
        lines.add(entry);
        if (!old.isEmpty()) {
            String[] oldLines = old.split("\\n");
            for (String line : oldLines) {
                if (!line.trim().isEmpty() && lines.size() < 12) {
                    lines.add(line);
                }
            }
        }

        p(c).edit()
                .putInt(KEY_REPAIR_COUNT, count)
                .putString(KEY_REPAIR_LOG, String.join("\n", lines))
                .apply();
    }
}
