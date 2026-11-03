package com.autousb.helper;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

/** Only opt-in, poll interval and our boot diagnosis are persisted; never USB or screen state. */
final class BootPreferences {
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("boot_guard", Context.MODE_PRIVATE);
    }
    static boolean enabled(Context context) {
        try { return prefs(context).getBoolean("enabled", false); }
        catch (RuntimeException unavailable) { return false; }
    }
    static int pollSeconds(Context context) {
        try { return BootWaitPolicy.normalizePoll(prefs(context).getInt("poll_seconds", 60)); }
        catch (RuntimeException unavailable) { return 60; }
    }
    static boolean setEnabled(Context context, boolean enabled) {
        boolean saved;
        try { saved = prefs(context).edit().putBoolean("enabled", enabled).commit(); }
        catch (RuntimeException unavailable) { saved = false; }
        // A failed synchronous save must never leave a newly enabled in-memory permission.
        if (!saved && enabled) {
            try { prefs(context).edit().putBoolean("enabled", false).commit(); }
            catch (RuntimeException ignored) { /* Caller is told the save failed. */ }
        }
        // Cancel even if storage threw before changing its in-memory value.
        if (!enabled || !saved) GuardService.cancelPendingBoot(context);
        return saved;
    }
    static boolean setPollSeconds(Context context, int seconds) {
        try { return prefs(context).edit().putInt("poll_seconds", BootWaitPolicy.normalizePoll(seconds)).commit(); }
        catch (RuntimeException unavailable) { return false; }
    }
    static String lastResult(Context context) {
        try { return prefs(context).getString("last_result", "尚未收到开机启动结果"); }
        catch (RuntimeException unavailable) { return "开机记录不可读取"; }
    }
    static void record(Context context, String result, String event) {
        boolean saved;
        try { saved = prefs(context).edit().putString("last_result", result).commit(); }
        catch (RuntimeException unavailable) { saved = false; }
        ProbeStore.get(context).report(result + (saved ? "" : "；开机结果保存失败"), event);
    }
    /** Claim before launch. Missing, malformed, reset or duplicate counters fail closed. */
    static synchronized boolean claimBoot(Context context) {
        try {
            int current = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT);
            long claimed = prefs(context).getLong("claimed_boot", -1);
            if (!BootWaitPolicy.mayClaimBoot(current, claimed)) {
                record(context, "本次开机已处理或开机计数异常；不重复启动", "boot_duplicate_or_invalid");
                return false;
            }
            if (!prefs(context).edit().putLong("claimed_boot", current).commit()) {
                GuardService.reportBootFailure(context, "开机标记保存失败；未启动守护", "boot_claim_failed"); return false;
            }
            return true;
        } catch (Settings.SettingNotFoundException | RuntimeException unavailable) {
            GuardService.reportBootFailure(context, "无法读取可靠开机计数或保存记录；未启动守护", "boot_count_unavailable");
            return false;
        }
    }
}
