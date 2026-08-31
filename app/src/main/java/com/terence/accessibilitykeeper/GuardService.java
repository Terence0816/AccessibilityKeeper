package com.terence.accessibilitykeeper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class GuardService extends Service {
    private static volatile boolean running = false;
    public static final String ACTION_CHECK_NOW = "com.terence.accessibilitykeeper.CHECK_NOW";
    private static final String CHANNEL_ID = "keeper_guard";
    private static final int NOTIFICATION_ID = 1001;

    // An accessibility service can be enabled in Settings but no longer bound to Android's
    // AccessibilityManager. HyperOS then often shows "無法運作". Wait for two consecutive
    // failed health checks before toggling it, so normal short binding transitions are ignored.
    private static final int UNBOUND_STRIKES_TO_RESTART = 2;
    private static final long RESTART_COOLDOWN_MS = 5 * 60 * 1000L;

    private ScheduledExecutorService scheduler;
    private ContentObserver observer;
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private final Map<String, Integer> unboundStrikes = new ConcurrentHashMap<>();
    private final Map<String, Long> lastRestartAt = new ConcurrentHashMap<>();

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createChannel();
        promoteToForeground(buildNotification());

        observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                checkAndRepair(false);
            }
        };
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                observer);

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> checkAndRepair(false), 10, 15, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Prefs.isGuardEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_CHECK_NOW.equals(intent.getAction())) {
            checkAndRepair(false);
        }
        updateNotification();
        return START_STICKY;
    }

    private void checkAndRepair(boolean forceHealthRestart) {
        if (!Prefs.isGuardEnabled(this)) return;
        if (!checking.compareAndSet(false, true)) return;
        try {
            Set<String> selected = Prefs.getSelected(this);
            AccessibilityUtils.RepairResult result = AccessibilityUtils.restoreSelected(this, selected);
            checkBoundHealth(selected, result, forceHealthRestart);
            updateNotification(result);
        } catch (Throwable ignored) {
            updateNotification();
        } finally {
            checking.set(false);
        }
    }

    private void checkBoundHealth(Set<String> selected, AccessibilityUtils.RepairResult result,
                                  boolean forceRestart) {
        if (selected == null || selected.isEmpty()) return;
        if (!AccessibilityUtils.hasWriteSecureSettings(this)) return;

        AccessibilityUtils.BoundState boundState = AccessibilityUtils.getBoundState(this);
        if (!boundState.reliable) return;

        Map<String, String> installed = AccessibilityUtils.installedLabels(this);
        List<String> enabled = AccessibilityUtils.splitEnabled(AccessibilityUtils.getEnabledRaw(this));
        long now = System.currentTimeMillis();

        for (String wanted : selected) {
            String installedId = AccessibilityUtils.findInstalledId(installed.keySet(), wanted);
            if (installedId == null) continue;

            // If it is disabled, restoreSelected() handles it. Don't treat that as an unbound crash.
            if (!AccessibilityUtils.containsComponent(enabled, installedId)) {
                unboundStrikes.remove(installedId);
                continue;
            }

            if (AccessibilityUtils.isBound(boundState, installedId)) {
                unboundStrikes.remove(installedId);
                continue;
            }

            int strikes = unboundStrikes.getOrDefault(installedId, 0) + 1;
            unboundStrikes.put(installedId, strikes);

            long last = lastRestartAt.getOrDefault(installedId, 0L);
            boolean cooldownOk = now - last >= RESTART_COOLDOWN_MS;
            boolean shouldRestart = forceRestart || strikes >= UNBOUND_STRIKES_TO_RESTART;
            if (!shouldRestart || !cooldownOk) continue;

            if (AccessibilityUtils.restartAccessibilityService(this, installedId)) {
                String label = installed.get(installedId);
                if (label == null || label.trim().isEmpty()) label = installedId;
                result.restartedIds.add(installedId);
                result.restartedLabels.add(label);
                lastRestartAt.put(installedId, System.currentTimeMillis());
                unboundStrikes.remove(installedId);
                Prefs.addRestart(this, java.util.Collections.singletonList(label));

                // The enabled list and bound list may change after each toggle; limit a single
                // automatic pass to one restart to avoid several services blinking at once.
                break;
            }
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Accessibility Keeper",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps selected accessibility services enabled and operational");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return buildNotification(null);
    }

    private Notification buildNotification(AccessibilityUtils.RepairResult result) {
        Set<String> selected = Prefs.getSelected(this);
        String content;
        if (!AccessibilityUtils.hasWriteSecureSettings(this)) {
            content = getString(R.string.notification_permission);
        } else if (result != null && !result.restartedLabels.isEmpty()) {
            content = getString(R.string.notification_restarted, String.join(", ", result.restartedLabels));
        } else if (result != null && result.repaired && !result.missingLabels.isEmpty()) {
            content = getString(R.string.notification_repaired, String.join(", ", result.missingLabels));
        } else {
            content = getString(R.string.notification_running, selected.size());
        }

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_keeper)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(content)
                .setContentIntent(pi)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void promoteToForeground(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification() {
        updateNotification(null);
    }

    private void updateNotification(AccessibilityUtils.RepairResult result) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(result));
    }

    @Override
    public void onDestroy() {
        running = false;
        if (observer != null) {
            try {
                getContentResolver().unregisterContentObserver(observer);
            } catch (Exception ignored) {}
        }
        if (scheduler != null) scheduler.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
