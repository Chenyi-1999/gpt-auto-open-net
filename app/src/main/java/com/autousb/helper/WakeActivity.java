package com.autousb.helper;

import android.app.Activity;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A normal visible Activity can request screen-on only when no secure lock is configured. */
public final class WakeActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private GuardService guard;
    private long session, attempt;
    private boolean resumed, ending, attached;
    private final WakeDiagnostics.Lifecycle lifecycle = new WakeDiagnostics.Lifecycle();
    private final Runnable deadline = () -> clearWakeWindow(true);
    private final Runnable check = new Runnable() {
        @Override public void run() {
            if (ending) return;
            if (guard == null || !guard.mayUseWakeWindow(session, attempt)) { clearWakeWindow(true); return; }
            // A sleep-induced onPause does not revoke a still-valid window. While paused this
            // callback only enforces safety/expiry; it never drives policy evaluation.
            if (resumed) guard.wakeResumed(session, attempt);
            handler.postDelayed(this, 250);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        guard = ProbeStore.get(this).guard;
        session = getIntent().getLongExtra("session", 0); attempt = getIntent().getLongExtra("attempt", 0);
        boundary(WakeDiagnostics.Boundary.CREATED);
        if (guard == null || !guard.attachWakeWindow(this, session, attempt)) { clearWakeWindow(true); return; }
        attached = true;
        long remaining = guard.wakeDeadline(session, attempt) - SystemClock.elapsedRealtime();
        if (remaining <= 0) { clearWakeWindow(true); return; }
        // Independent of onResume/onPause and never extended by lifecycle changes.
        handler.postDelayed(deadline, remaining);
        handler.post(check);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        TextView text = new TextView(this); text.setTextSize(20);
        text.setText("USB共享辅助守护\n\n检测到USB重新连接，正在尝试亮屏并确认共享。\n仅无安全锁时尝试；不会操作锁屏。最多等待10秒，无法确认将暂停。\n\n随后可能打开系统共享设置。");
        layout.addView(text);
        Button stop = new Button(this); stop.setText("停止守护");
        stop.setOnClickListener(view -> { GuardService.stopFromActivity(this); clearWakeWindow(true); }); layout.addView(stop);
        // The manifest submits screen-on before client creation. Keep client gates for all later
        // operations; a system request already submitted cannot be guaranteed cancellable.
        try {
            if (!guard.mayUseWakeWindow(session, attempt)) { clearWakeWindow(true); return; }
            setTurnScreenOn(true);
            if (!guard.mayUseWakeWindow(session, attempt)) { clearWakeWindow(true); return; }
            setContentView(layout);
        } catch (RuntimeException unavailable) {
            ProbeStore.get(this).event("wake_window_request_unavailable");
            if (guard != null) guard.wakeRefused(session, attempt);
            clearWakeWindow(true);
        }
    }
    @Override protected void onResume() {
        super.onResume();
        boundary(WakeDiagnostics.Boundary.RESUMED);
        resumed = true;
        if (ending || guard == null || !guard.mayUseWakeWindow(session, attempt)
                || !guard.wakeResumed(session, attempt)) clearWakeWindow(true);
    }
    @Override protected void onPause() {
        resumed = false; boundary(WakeDiagnostics.Boundary.PAUSED); super.onPause();
    }
    @Override protected void onDestroy() {
        boundary(WakeDiagnostics.Boundary.DESTROYED); clearWakeWindow(false); super.onDestroy();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        boundary(focused ? WakeDiagnostics.Boundary.FOCUSED : WakeDiagnostics.Boundary.UNFOCUSED);
    }
    private void boundary(WakeDiagnostics.Boundary boundary) {
        if (!lifecycle.first(boundary)) return;
        // Use this Activity's actual Intent identity, never the guard's newer session/attempt.
        ProbeStore.get(this).wakeEvent(boundary.event(), boundary.name(), session, attempt,
                guard != null && guard.ownsWake(session, attempt), null);
    }
    void revokeWakeWindow(GuardService owner) {
        if (guard == owner) clearWakeWindow(true);
    }
    boolean wasAttachedTo(GuardService owner, long session, long attempt) {
        return attached && guard == owner && this.session == session && this.attempt == attempt;
    }
    private void clearWakeWindow(boolean finishActivity) {
        if (ending) return;
        ending = true; resumed = false; handler.removeCallbacksAndMessages(null);
        GuardService owner = guard;
        if (owner != null) owner.detachWakeWindow(this, session, attempt);
        // These requests may already have reached WMS; cancellation is best effort, never screen-off.
        try { setTurnScreenOn(false); }
        catch (RuntimeException unavailable) { ProbeStore.get(this).event("wake_window_cleanup_unavailable"); }
        if (finishActivity && !isFinishing() && owner != null && attached && isTaskRoot()
                && getIntent() != null && new ComponentName(this, WakeActivity.class).equals(getIntent().getComponent())) {
            owner.recordInternalWakeFinish(this, session, attempt);
        }
        guard = null; attached = false;
        if (finishActivity) finish(); // Finish this Activity instance only, not the Settings task.
    }
}
