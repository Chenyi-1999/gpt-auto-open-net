package com.autousb.helper;

import android.content.Context;
import android.provider.Settings;

/** Read-only diagnosis; never an authorization to start a guard or create a window. */
final class OverlayAccess {
    enum Status { GRANTED, DENIED, UNKNOWN }
    static Status status(Context context) {
        try { return Settings.canDrawOverlays(context) ? Status.GRANTED : Status.DENIED; }
        catch (RuntimeException unavailable) { return Status.UNKNOWN; }
    }
}
