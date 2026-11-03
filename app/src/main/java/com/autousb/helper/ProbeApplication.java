package com.autousb.helper;

import android.app.Application;

public final class ProbeApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        ProbeStore.get(this).start();
    }
}
