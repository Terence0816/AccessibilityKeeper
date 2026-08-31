package com.terence.accessibilitykeeper;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String GRANT_COMMAND =
            "adb shell pm grant com.terence.accessibilitykeeper android.permission.WRITE_SECURE_SETTINGS";
    private static final String GITHUB_URL =
            "https://github.com/Terence0816/AccessibilityKeeper";

    private LinearLayout servicesContainer;
    private TextView permissionStatusText;
    private TextView guardStatusText;
    private View guardStatusDot;
    private TextView repairCountText;
    private TextView repairLogText;
    private TextView lastCheckText;
    private Button guardButton;

    private TextView sysWriteSecureStatus;
    private TextView sysGuardServiceStatus;
    private TextView sysBatteryStatus;
    private TextView sysHyperOsBatteryStatus;
    private TextView sysAutoStartStatus;
    private TextView sysNotificationStatus;
    private CheckBox confirmHyperOsBatteryCheck;
    private CheckBox confirmAutoStartCheck;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        servicesContainer = findViewById(R.id.servicesContainer);
        permissionStatusText = findViewById(R.id.permissionStatusText);
        guardStatusText = findViewById(R.id.guardStatusText);
        guardStatusDot = findViewById(R.id.guardStatusDot);
        repairCountText = findViewById(R.id.repairCountText);
        repairLogText = findViewById(R.id.repairLogText);
        lastCheckText = findViewById(R.id.lastCheckText);
        guardButton = findViewById(R.id.guardButton);

        sysWriteSecureStatus = findViewById(R.id.sysWriteSecureStatus);
        sysGuardServiceStatus = findViewById(R.id.sysGuardServiceStatus);
        sysBatteryStatus = findViewById(R.id.sysBatteryStatus);
        sysHyperOsBatteryStatus = findViewById(R.id.sysHyperOsBatteryStatus);
        sysAutoStartStatus = findViewById(R.id.sysAutoStartStatus);
        sysNotificationStatus = findViewById(R.id.sysNotificationStatus);
        confirmHyperOsBatteryCheck = findViewById(R.id.confirmHyperOsBatteryCheck);
        confirmAutoStartCheck = findViewById(R.id.confirmAutoStartCheck);

        TextView grantCommand = findViewById(R.id.grantCommandText);
        grantCommand.setText(GRANT_COMMAND);

        findViewById(R.id.copyGrantButton).setOnClickListener(v -> copyGrantCommand());
        findViewById(R.id.refreshButton).setOnClickListener(v -> refreshAll());
        findViewById(R.id.checkNowButton).setOnClickListener(v -> checkNow());
        findViewById(R.id.openAccessibilityButton).setOnClickListener(v -> openAccessibilitySettings());
        findViewById(R.id.requestBatteryExemptionButton).setOnClickListener(v -> SystemSettingsHelper.requestIgnoreBatteryOptimizations(this));
        findViewById(R.id.openHyperOsBatteryButton).setOnClickListener(v -> SystemSettingsHelper.openHyperOsBatterySettings(this));
        findViewById(R.id.openAutoStartButton).setOnClickListener(v -> SystemSettingsHelper.openAutoStartSettings(this));
        findViewById(R.id.openNotificationButton).setOnClickListener(v -> SystemSettingsHelper.openNotificationSettings(this));
        findViewById(R.id.openGithubButton).setOnClickListener(v -> openGithub());
        confirmHyperOsBatteryCheck.setOnCheckedChangeListener((buttonView, checked) -> {
            Prefs.setHyperOsBatteryConfirmed(this, checked);
            updateSystemGuardSettings();
        });
        confirmAutoStartCheck.setOnCheckedChangeListener((buttonView, checked) -> {
            Prefs.setAutoStartConfirmed(this, checked);
            updateSystemGuardSettings();
        });
        guardButton.setOnClickListener(v -> toggleGuard());

        requestNotificationPermissionIfNeeded();
        refreshAll();

        if (Prefs.isGuardEnabled(this)) {
            startGuardService(null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
        }
    }

    private void refreshAll() {
        updatePermissionStatus();
        updateSystemGuardSettings();
        renderServices();
        updateGuardStatus();
        updateHistory();
    }

    private void updatePermissionStatus() {
        boolean granted = AccessibilityUtils.hasWriteSecureSettings(this);
        permissionStatusText.setText(granted ? R.string.permission_ok : R.string.permission_missing);
        permissionStatusText.setTextColor(getColor(granted ? R.color.success : R.color.danger));
    }

    private void updateSystemGuardSettings() {
        boolean writeSecure = AccessibilityUtils.hasWriteSecureSettings(this);
        setSystemStatus(sysWriteSecureStatus,
                writeSecure ? "✓ 已授權" : "✕ 尚未授權",
                writeSecure ? R.color.success : R.color.danger);

        boolean guardEnabled = Prefs.isGuardEnabled(this);
        boolean guardRunning = GuardService.isRunning();
        if (guardRunning) {
            setSystemStatus(sysGuardServiceStatus, "✓ 前景守護服務執行中", R.color.success);
        } else if (guardEnabled) {
            setSystemStatus(sysGuardServiceStatus, "⚠ 已啟用，服務正在啟動或曾被系統停止", R.color.warning);
        } else {
            setSystemStatus(sysGuardServiceStatus, "⚠ 守護目前停止", R.color.warning);
        }

        boolean batteryIgnored = SystemSettingsHelper.isIgnoringBatteryOptimizations(this);
        setSystemStatus(sysBatteryStatus,
                batteryIgnored ? "✓ 已排除 Android 電池最佳化" : "⚠ 尚未排除 Android 電池最佳化",
                batteryIgnored ? R.color.success : R.color.warning);

        // HyperOS/MIUI does not expose a reliable public API for reading its per-app
        // "No restrictions" / auto-start switches. These two statuses are therefore
        // user-confirmed rather than falsely reported as automatically detected.
        boolean hyperOsBatteryConfirmed = Prefs.isHyperOsBatteryConfirmed(this);
        boolean autoStartConfirmed = Prefs.isAutoStartConfirmed(this);

        confirmHyperOsBatteryCheck.setOnCheckedChangeListener(null);
        confirmHyperOsBatteryCheck.setChecked(hyperOsBatteryConfirmed);
        confirmHyperOsBatteryCheck.setOnCheckedChangeListener((buttonView, checked) -> {
            Prefs.setHyperOsBatteryConfirmed(this, checked);
            updateSystemGuardSettings();
        });

        confirmAutoStartCheck.setOnCheckedChangeListener(null);
        confirmAutoStartCheck.setChecked(autoStartConfirmed);
        confirmAutoStartCheck.setOnCheckedChangeListener((buttonView, checked) -> {
            Prefs.setAutoStartConfirmed(this, checked);
            updateSystemGuardSettings();
        });

        setSystemStatus(sysHyperOsBatteryStatus,
                hyperOsBatteryConfirmed ? "✓ 已確認設為「無限制」" : "⚠ 設定後請勾選「我已設定」",
                hyperOsBatteryConfirmed ? R.color.success : R.color.warning);
        setSystemStatus(sysAutoStartStatus,
                autoStartConfirmed ? "✓ 已確認開啟" : "⚠ 設定後請勾選「我已設定」",
                autoStartConfirmed ? R.color.success : R.color.warning);

        boolean notifications = SystemSettingsHelper.areNotificationsEnabled(this);
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications = false;
        }
        setSystemStatus(sysNotificationStatus,
                notifications ? "✓ 已允許" : "⚠ 尚未允許",
                notifications ? R.color.success : R.color.warning);
    }

    private void setSystemStatus(TextView view, String text, int colorRes) {
        if (view == null) return;
        view.setText(text);
        view.setTextColor(getColor(colorRes));
    }

    private void updateGuardStatus() {
        boolean enabled = Prefs.isGuardEnabled(this);
        guardStatusText.setText(enabled ? R.string.guard_on : R.string.guard_off);
        guardStatusText.setTextColor(getColor(enabled ? R.color.success : R.color.text_primary));
        guardStatusDot.setBackgroundResource(enabled ? R.drawable.status_dot_ok : R.drawable.status_dot_bad);
        guardButton.setText(enabled ? R.string.stop_guard : R.string.start_guard);

        long last = Prefs.getLastCheck(this);
        String when = last == 0L ? "--" : new SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault()).format(new Date(last));
        lastCheckText.setText(getString(R.string.last_check, when));
    }

    private void updateHistory() {
        int count = Prefs.getRepairCount(this);
        repairCountText.setText(getString(R.string.repair_count, count));
        String log = Prefs.getRepairLog(this);
        repairLogText.setText(log.isEmpty() ? getString(R.string.repair_none) : log);
    }

    private void renderServices() {
        servicesContainer.removeAllViews();
        List<AccessibilityUtils.ServiceEntry> services = new ArrayList<>(AccessibilityUtils.getInstalledServiceEntries(this));
        PackageManager pm = getPackageManager();

        // MyGesture first, then other user-installed apps, then system services.
        services.sort(Comparator.comparingInt((AccessibilityUtils.ServiceEntry entry) -> {
            if ("me.hisn.mygesture".equalsIgnoreCase(entry.packageName)) return 0;
            return entry.systemApp ? 2 : 1;
        }).thenComparing(entry -> entry.label, String.CASE_INSENSITIVE_ORDER));

        if (services.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.empty_services);
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setTextSize(14f);
            empty.setPadding(0, dp(12), 0, dp(12));
            servicesContainer.addView(empty);
            return;
        }

        Set<String> selected = Prefs.getSelected(this);
        if (selected.isEmpty()) {
            for (AccessibilityUtils.ServiceEntry entry : services) {
                if ("me.hisn.mygesture".equalsIgnoreCase(entry.packageName)) {
                    selected.add(entry.id);
                    Prefs.setSelected(this, selected);
                    break;
                }
            }
        }

        AccessibilityUtils.BoundState boundState = AccessibilityUtils.getBoundState(this);

        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < services.size(); i++) {
            AccessibilityUtils.ServiceEntry entry = services.get(i);
            if (entry.resolveInfo == null || entry.resolveInfo.serviceInfo == null) continue;

            View row = inflater.inflate(R.layout.service_row, servicesContainer, false);
            ImageView icon = row.findViewById(R.id.serviceIcon);
            TextView name = row.findViewById(R.id.serviceName);
            TextView pkg = row.findViewById(R.id.servicePackage);
            TextView status = row.findViewById(R.id.serviceStatus);
            CheckBox check = row.findViewById(R.id.serviceCheck);

            try {
                icon.setImageDrawable(entry.resolveInfo.loadIcon(pm));
            } catch (Exception e) {
                icon.setImageResource(R.drawable.ic_keeper);
            }

            String labelText = entry.label;
            name.setText(labelText);
            pkg.setText(entry.id);

            boolean isEnabled = AccessibilityUtils.isEnabled(this, entry.id);
            if (!isEnabled) {
                status.setText(R.string.status_disabled);
                status.setTextColor(getColor(R.color.warning));
            } else if (boundState.reliable && AccessibilityUtils.isBound(boundState, entry.id)) {
                status.setText(R.string.status_working);
                status.setTextColor(getColor(R.color.success));
            } else if (boundState.reliable) {
                status.setText(R.string.status_enabled_not_working);
                status.setTextColor(getColor(R.color.danger));
            } else {
                status.setText(R.string.status_enabled);
                status.setTextColor(getColor(R.color.success));
            }

            check.setOnCheckedChangeListener(null);
            check.setChecked(containsComponent(selected, entry.id));
            check.setContentDescription(labelText + " " + getString(R.string.status_guarded));
            check.setOnCheckedChangeListener((buttonView, isChecked) -> {
                Set<String> newSet = Prefs.getSelected(this);
                removeEquivalent(newSet, entry.id);
                if (isChecked) newSet.add(entry.id);
                Prefs.setSelected(this, newSet);
                if (Prefs.isGuardEnabled(this)) {
                    startGuardService(GuardService.ACTION_CHECK_NOW);
                }
                updateGuardStatus();
                updateSystemGuardSettings();
            });

            row.setOnClickListener(v -> check.setChecked(!check.isChecked()));
            servicesContainer.addView(row);

            if (i != services.size() - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(getColor(R.color.divider));
                servicesContainer.addView(divider, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            }
        }
    }

    private boolean containsComponent(Set<String> set, String target) {
        for (String value : set) {
            if (AccessibilityUtils.sameComponent(value, target)) return true;
        }
        return false;
    }

    private void removeEquivalent(Set<String> set, String target) {
        String found = null;
        for (String value : set) {
            if (AccessibilityUtils.sameComponent(value, target)) {
                found = value;
                break;
            }
        }
        if (found != null) set.remove(found);
    }

    private void toggleGuard() {
        boolean next = !Prefs.isGuardEnabled(this);
        Prefs.setGuardEnabled(this, next);
        if (next) {
            startGuardService(GuardService.ACTION_CHECK_NOW);
        } else {
            stopService(new Intent(this, GuardService.class));
        }
        refreshAll();
    }

    private void checkNow() {
        AccessibilityUtils.RepairResult result = AccessibilityUtils.restoreSelected(this, Prefs.getSelected(this));
        if (result.needsPermission) {
            Toast.makeText(this, "請先使用 ADB 授權 WRITE_SECURE_SETTINGS", Toast.LENGTH_LONG).show();
        } else {
            // Also check the real bound/running state. This catches HyperOS cases where the
            // switch is still ON but Settings shows the service as unable to operate.
            AccessibilityUtils.BoundState boundState = AccessibilityUtils.getBoundState(this);
            if (boundState.reliable && AccessibilityUtils.hasWriteSecureSettings(this)) {
                Map<String, String> installed = AccessibilityUtils.installedLabels(this);
                List<String> enabled = AccessibilityUtils.splitEnabled(AccessibilityUtils.getEnabledRaw(this));
                for (String wanted : Prefs.getSelected(this)) {
                    String installedId = AccessibilityUtils.findInstalledId(installed.keySet(), wanted);
                    if (installedId == null) continue;
                    if (!AccessibilityUtils.containsComponent(enabled, installedId)) continue;
                    if (AccessibilityUtils.isBound(boundState, installedId)) continue;

                    if (AccessibilityUtils.restartAccessibilityService(this, installedId)) {
                        String label = installed.get(installedId);
                        if (label == null || label.trim().isEmpty()) label = installedId;
                        result.restartedIds.add(installedId);
                        result.restartedLabels.add(label);
                        Prefs.addRestart(this, java.util.Collections.singletonList(label));
                        break;
                    }
                }
            }

            if (!result.restartedLabels.isEmpty()) {
                Toast.makeText(this, "偵測到失效，已自動關閉再開啟：" +
                        String.join(", ", result.restartedLabels), Toast.LENGTH_LONG).show();
            } else if (result.repaired) {
                Toast.makeText(this, "已恢復：" + String.join(", ", result.missingLabels), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "檢查完成，已守護項目目前正常", Toast.LENGTH_SHORT).show();
            }
        }
        if (Prefs.isGuardEnabled(this)) startGuardService(null);
        refreshAll();
    }

    private void startGuardService(String action) {
        Intent intent = new Intent(this, GuardService.class);
        if (action != null) intent.setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            Toast.makeText(this, "無法啟動守護服務：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void copyGrantCommand() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("ADB grant", GRANT_COMMAND));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            openAppSettings();
        }
    }

    private void openGithub() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.github_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void openAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
