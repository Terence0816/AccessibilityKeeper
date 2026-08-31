package com.terence.accessibilitykeeper;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

public final class SystemSettingsHelper {
    private SystemSettingsHelper() {}

    public static boolean isIgnoringBatteryOptimizations(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    public static boolean areNotificationsEnabled(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm == null || nm.areNotificationsEnabled();
    }

    public static void openNotificationSettings(Context context) {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
        } else {
            intent = appDetailsIntent(context);
        }
        safeStart(context, intent, appDetailsIntent(context));
    }

    public static void requestIgnoreBatteryOptimizations(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        Intent fallback = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        safeStart(context, intent, fallback);
    }

    public static void openHyperOsBatterySettings(Context context) {
        String label = context.getApplicationInfo().loadLabel(context.getPackageManager()).toString();

        // HyperOS changes this private activity between releases. v0.1.3 only checked
        // resolveActivity() and then started it directly; some builds still throw a
        // SecurityException at launch. v0.1.5 wraps every launch and safely falls back.
        Intent perApp = new Intent();
        perApp.setComponent(new ComponentName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"));
        perApp.putExtra("package_name", context.getPackageName());
        perApp.putExtra("package_label", label);
        if (tryStart(context, perApp)) return;

        Intent powerSettings = new Intent();
        powerSettings.setComponent(new ComponentName(
                "com.miui.securitycenter",
                "com.miui.powercenter.PowerSettings"));
        if (tryStart(context, powerSettings)) return;

        // Stable fallback: never crash the keeper if Xiaomi blocks its private page.
        if (tryStart(context, appDetailsIntent(context))) {
            Toast.makeText(context,
                    "此 HyperOS 無法直接開啟電池策略，請在此頁進入電池/省電並設為「無限制」",
                    Toast.LENGTH_LONG).show();
            return;
        }
        safeStart(context, new Intent(Settings.ACTION_SETTINGS), new Intent(Settings.ACTION_SETTINGS));
    }

    public static void openAutoStartSettings(Context context) {
        Intent autoStart = new Intent();
        autoStart.setComponent(new ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"));
        if (tryStart(context, autoStart)) return;

        Intent permissionsEditor = new Intent();
        permissionsEditor.setComponent(new ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity"));
        permissionsEditor.putExtra("extra_pkgname", context.getPackageName());
        if (tryStart(context, permissionsEditor)) return;

        safeStart(context, appDetailsIntent(context), new Intent(Settings.ACTION_SETTINGS));
    }

    public static Intent appDetailsIntent(Context context) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + context.getPackageName()));
    }

    private static boolean tryStart(Context context, Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void safeStart(Context context, Intent primary, Intent fallback) {
        if (tryStart(context, primary)) return;
        tryStart(context, fallback);
    }
}
