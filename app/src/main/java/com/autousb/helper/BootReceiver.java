package com.autousb.helper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** BOOT_COMPLETED is system-protected; this receiver is also nonexported in the manifest. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!BootPreferences.enabled(context)) {
            BootPreferences.record(context, "开机自动守护未开启；未启动守护", "boot_option_off"); return;
        }
        if (!BootPreferences.claimBoot(context)) return;
        GuardService.startFromBoot(context);
    }
}
