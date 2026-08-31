package com.terence.accessibilitykeeper;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class AccessibilityUtils {
    private AccessibilityUtils() {}

    public static final class ServiceEntry {
        public final String id;
        public final String label;
        public final String packageName;
        public final ResolveInfo resolveInfo;
        public final boolean systemApp;

        ServiceEntry(String id, String label, String packageName, ResolveInfo resolveInfo, boolean systemApp) {
            this.id = id;
            this.label = label;
            this.packageName = packageName;
            this.resolveInfo = resolveInfo;
            this.systemApp = systemApp;
        }
    }

    /**
     * Android's AccessibilityManager returns the services that are actually bound to
     * AccessibilityManagerService, not merely the services whose secure-setting switch is ON.
     * This lets us distinguish:
     *   enabled in Settings + bound   => actually working
     *   enabled in Settings + unbound => HyperOS "unable to operate" style failure
     */
    public static final class BoundState {
        public final boolean reliable;
        public final List<String> ids;

        BoundState(boolean reliable, List<String> ids) {
            this.reliable = reliable;
            this.ids = ids;
        }
    }

    public static boolean hasWriteSecureSettings(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Returns accessibility services from two independent sources and merges them:
     * 1) AccessibilityManager (normal Android API)
     * 2) PackageManager query for the AccessibilityService intent (OEM/package visibility fallback)
     */
    public static List<ServiceEntry> getInstalledServiceEntries(Context context) {
        PackageManager pm = context.getPackageManager();
        Map<String, ServiceEntry> merged = new LinkedHashMap<>();

        AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am != null) {
            try {
                for (AccessibilityServiceInfo info : am.getInstalledAccessibilityServiceList()) {
                    if (info == null || info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) continue;
                    addResolveInfo(pm, merged, info.getResolveInfo(), info.getId());
                }
            } catch (Throwable ignored) {
                // Continue with PackageManager fallback.
            }
        }

        try {
            Intent intent = new Intent(AccessibilityService.SERVICE_INTERFACE);
            List<ResolveInfo> results;
            long flags = PackageManager.GET_META_DATA | PackageManager.MATCH_DISABLED_COMPONENTS;
            if (Build.VERSION.SDK_INT >= 33) {
                results = pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(flags));
            } else {
                //noinspection deprecation
                results = pm.queryIntentServices(intent, (int) flags);
            }
            if (results != null) {
                for (ResolveInfo ri : results) {
                    addResolveInfo(pm, merged, ri, null);
                }
            }
        } catch (Throwable ignored) {
            // Keep results from AccessibilityManager if OEM blocks PackageManager query.
        }

        return new ArrayList<>(merged.values());
    }

    private static void addResolveInfo(PackageManager pm, Map<String, ServiceEntry> out,
                                       ResolveInfo ri, String preferredId) {
        if (ri == null || ri.serviceInfo == null) return;
        ServiceInfo si = ri.serviceInfo;
        if (si.packageName == null || si.name == null) return;

        ComponentName cn = new ComponentName(si.packageName, si.name);
        String id = (preferredId == null || preferredId.trim().isEmpty())
                ? cn.flattenToString() : preferredId;
        ComponentName normalized = ComponentName.unflattenFromString(id);
        if (normalized == null) normalized = cn;
        String key = normalized.flattenToString().toLowerCase(Locale.ROOT);

        CharSequence cs;
        try {
            cs = ri.loadLabel(pm);
        } catch (Throwable t) {
            cs = null;
        }
        String label = (cs == null || cs.toString().trim().isEmpty())
                ? si.packageName : cs.toString();

        boolean system = false;
        ApplicationInfo ai = si.applicationInfo;
        if (ai != null) {
            system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        }

        ServiceEntry current = out.get(key);
        if (current == null || current.resolveInfo == null) {
            out.put(key, new ServiceEntry(id, label, si.packageName, ri, system));
        }
    }

    public static String getEnabledRaw(Context context) {
        String value = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return value == null ? "" : value;
    }

    public static List<String> splitEnabled(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        String[] parts = raw.split(":");
        for (String part : parts) {
            String s = part.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    public static boolean sameComponent(String a, String b) {
        if (a == null || b == null) return false;
        ComponentName ca = ComponentName.unflattenFromString(a);
        ComponentName cb = ComponentName.unflattenFromString(b);
        if (ca != null && cb != null) return ca.equals(cb);
        return a.equalsIgnoreCase(b);
    }

    public static boolean containsComponent(List<String> list, String target) {
        for (String value : list) {
            if (sameComponent(value, target)) return true;
        }
        return false;
    }

    public static boolean isEnabled(Context context, String id) {
        return containsComponent(splitEnabled(getEnabledRaw(context)), id);
    }

    public static BoundState getBoundState(Context context) {
        AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return new BoundState(false, new ArrayList<>());

        try {
            List<AccessibilityServiceInfo> infos = am.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            List<String> ids = new ArrayList<>();
            if (infos != null) {
                for (AccessibilityServiceInfo info : infos) {
                    if (info == null) continue;
                    String id = info.getId();
                    if (id != null && !id.trim().isEmpty()) ids.add(id);
                }
            }
            return new BoundState(true, ids);
        } catch (Throwable ignored) {
            return new BoundState(false, new ArrayList<>());
        }
    }

    public static boolean isBound(BoundState state, String id) {
        return state != null && state.reliable && containsComponent(state.ids, id);
    }

    public static Map<String, String> installedLabels(Context context) {
        Map<String, String> labels = new HashMap<>();
        for (ServiceEntry entry : getInstalledServiceEntries(context)) {
            labels.put(entry.id, entry.label);
        }
        return labels;
    }

    /** Restore selected services if HyperOS removed them from enabled_accessibility_services. */
    public static RepairResult restoreSelected(Context context, Set<String> selected) {
        long now = System.currentTimeMillis();
        Prefs.setLastCheck(context, now);

        RepairResult result = new RepairResult();
        if (selected == null || selected.isEmpty()) return result;

        Map<String, String> installed = installedLabels(context);
        List<String> enabled = splitEnabled(getEnabledRaw(context));
        Set<String> additions = new LinkedHashSet<>();

        for (String wanted : selected) {
            String installedId = findInstalledId(installed.keySet(), wanted);
            if (installedId == null) continue;
            if (!containsComponent(enabled, installedId)) {
                additions.add(installedId);
                result.missingIds.add(installedId);
                result.missingLabels.add(safeLabel(installed.get(installedId), installedId));
            }
        }

        if (additions.isEmpty()) return result;
        if (!hasWriteSecureSettings(context)) {
            result.needsPermission = true;
            return result;
        }

        List<String> merged = new ArrayList<>(enabled);
        for (String addition : additions) {
            if (!containsComponent(merged, addition)) merged.add(addition);
        }

        boolean ok = Settings.Secure.putString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                String.join(":", merged));

        if (ok) {
            Settings.Secure.putInt(
                    context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    1);
            result.repaired = true;
            Prefs.addRestore(context, result.missingLabels);
        }
        return result;
    }

    /**
     * Perform the same effective action as the user manually toggling one accessibility
     * service OFF and ON. Other accessibility services are preserved exactly as they are.
     */
    public static boolean restartAccessibilityService(Context context, String wantedId) {
        if (!hasWriteSecureSettings(context)) return false;

        Map<String, String> installed = installedLabels(context);
        String target = findInstalledId(installed.keySet(), wantedId);
        if (target == null) return false;

        try {
            List<String> before = splitEnabled(getEnabledRaw(context));
            if (!containsComponent(before, target)) return false;

            List<String> withoutTarget = new ArrayList<>();
            for (String id : before) {
                if (!sameComponent(id, target)) withoutTarget.add(id);
            }

            boolean offOk = Settings.Secure.putString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    String.join(":", withoutTarget));
            if (!offOk) return false;

            // If this was the only enabled accessibility service, mirror the system's OFF state.
            if (withoutTarget.isEmpty()) {
                Settings.Secure.putInt(
                        context.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        0);
            }

            // Give AccessibilityManagerService time to unbind the stale/dead connection.
            SystemClock.sleep(900);

            // Re-read to preserve any changes made by the user or the OS during the delay.
            List<String> latest = splitEnabled(getEnabledRaw(context));
            if (!containsComponent(latest, target)) latest.add(target);

            boolean onOk = Settings.Secure.putString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    String.join(":", latest));
            if (!onOk) return false;

            Settings.Secure.putInt(
                    context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    1);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String safeLabel(String label, String fallback) {
        return (label == null || label.trim().isEmpty()) ? fallback : label;
    }

    public static String findInstalledId(Set<String> installedIds, String wanted) {
        for (String id : installedIds) {
            if (sameComponent(id, wanted)) return id;
        }
        return null;
    }

    public static final class RepairResult {
        public boolean repaired = false;
        public boolean needsPermission = false;
        public final List<String> missingIds = new ArrayList<>();
        public final List<String> missingLabels = new ArrayList<>();
        public final List<String> restartedIds = new ArrayList<>();
        public final List<String> restartedLabels = new ArrayList<>();

        public boolean didAnything() {
            return repaired || !restartedIds.isEmpty();
        }
    }
}
