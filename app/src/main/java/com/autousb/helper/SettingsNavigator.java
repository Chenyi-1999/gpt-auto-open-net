package com.autousb.helper;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

final class SettingsNavigator {
    enum Result { OPENED, UNAVAILABLE, SECURITY_DENIED }
    static boolean openTether(Context context) { return openTetherResult(context) == Result.OPENED; }
    static Result openTetherResult(Context context) { return open(context, "android.settings.TETHER_SETTINGS"); }
    static boolean openAccessibility(Context context) { return open(context, Settings.ACTION_ACCESSIBILITY_SETTINGS) == Result.OPENED; }
    private static Result open(Context context, String action) {
        Intent intent = new Intent(action).setPackage("com.android.settings").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ResolveInfo info = context.getPackageManager().resolveActivity(intent, 0);
            if (info == null || info.activityInfo == null || !info.activityInfo.exported
                    || !"com.android.settings".equals(info.activityInfo.packageName)) return Result.UNAVAILABLE;
            int flags = info.activityInfo.applicationInfo.flags;
            if ((flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) return Result.UNAVAILABLE;
            context.startActivity(intent);
            return Result.OPENED;
        } catch (SecurityException denied) { return Result.SECURITY_DENIED; }
        catch (RuntimeException deniedOrUnavailable) { return Result.UNAVAILABLE; }
    }
}
