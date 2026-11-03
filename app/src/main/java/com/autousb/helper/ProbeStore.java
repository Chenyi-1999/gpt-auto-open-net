package com.autousb.helper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.KeyguardManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/** Process-only observations: no saved USB state is ever used to authorize an action. */
final class ProbeStore {
    static final String USB = "android.hardware.usb.action.USB_STATE";
    static final String TETHER = "android.net.conn.TETHER_STATE_CHANGED";
    static final long FRESH_MS = 120_000;
    enum Truth { YES, NO, UNKNOWN }
    private static ProbeStore instance;
    static ProbeStore get(Context context) {
        if (instance == null) instance = new ProbeStore(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    final DiagnosticLog log;
    final Set<Runnable> listeners = new LinkedHashSet<>();
    final OneShotPolicy policy = new OneShotPolicy();
    final VerificationAttempt.Slot attempts = new VerificationAttempt.Slot();
    final WakeDiagnostics wake = new WakeDiagnostics();
    final PageReadyEvidence pageReady = new PageReadyEvidence();
    final UsbReconnectEvidence usbCycles = new UsbReconnectEvidence();
    ProbeAccessibilityService service;
    GuardService guard;
    boolean guardEnabled;
    String guardState = "STOPPED", guardStatus = "守护未启用";
    String guardSource = "OFF";
    long guardSessionId, guardAttemptId, guardPausedAt, guardConsumedCycle;
    String guardPauseKind = "NONE";
    int guardAttempts, guardPollSeconds = 60;
    Truth connected = Truth.UNKNOWN, configured = Truth.UNKNOWN;
    Truth rndis = Truth.UNKNOWN, ncm = Truth.UNKNOWN;
    long usbAt, tetherAt, usbWall, tetherWall;
    String usbSource = "未观察", tetherSource = "未观察";
    String[] tethered, available, errored;
    Boolean switchChecked;
    Boolean switchCheckable, switchRawChecked, switchEnabled;
    String switchClass = "", switchSummary = "";
    long switchWall, switchAt;
    boolean switchReadable;
    String switchAdapter = "标准checkable节点";
    String switchId = "";
    String result = "尚未验证";
    boolean snapshotWritten = true;
    private boolean started;
    private final Handler expiryHandler = new Handler(Looper.getMainLooper());
    private final Runnable usbExpired = this::changed, tetherExpired = this::changed, switchExpired = this::changed;
    private ProbeStore(Context context) { this.context = context; log = new DiagnosticLog(context); }
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                event("screen_off");
                invalidateSwitch();
                cancel(VerificationAttempt.Outcome.WAIT_UNLOCK, "息屏，验证已取消；守护等待解锁");
            } else if (Intent.ACTION_USER_PRESENT.equals(intent.getAction()) || Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                event(Intent.ACTION_USER_PRESENT.equals(intent.getAction()) ? "user_present" : "screen_on");
                changed();
            } else {
                observe(intent, "动态系统广播（协议待验证）", !isInitialStickyBroadcast());
            }
        }
    };
    void start() {
        if (started) return;
        started = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(USB); filter.addAction(TETHER); filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            else context.registerReceiver(receiver, filter);
        } catch (SecurityException e) { securityDenied(); result = "系统拒绝注册状态广播；状态未知"; event("receiver_denied"); }
        refresh();
    }
    void refresh() {
        // Do not relabel old sticky evidence as post-click evidence during a transaction.
        if (policy.phase() != OneShotPolicy.Phase.IDLE) return;
        Intent usb = readSticky(USB), tether = readSticky(TETHER);
        // Publish only after both reads are applied; listeners never see a half-refreshed pair.
        applyObservation(USB, usb, "系统粘性广播读取（协议待验证）");
        usbCycles.observe(UsbReconnectEvidence.Connection.valueOf(connected.name()),
                SystemClock.elapsedRealtime(), false);
        applyObservation(TETHER, tether, "系统粘性广播读取（协议待验证）");
        if (!reliableConnected()) switchReadable = false;
        event("diagnostics_refreshed"); changed();
    }
    void securityDenied() {
        if (guard != null) guard.hardPause("系统拒绝访问；权限恢复后需停止并重新启用守护");
    }
    private Intent readSticky(String action) {
        try {
            return Build.VERSION.SDK_INT >= 33
                    ? context.registerReceiver(null, new IntentFilter(action), Context.RECEIVER_EXPORTED)
                    : context.registerReceiver(null, new IntentFilter(action));
        } catch (SecurityException e) {
            securityDenied(); return null;
        }
    }
    private void observe(Intent intent, String source, boolean realDynamicBroadcast) {
        applyObservation(intent.getAction(), intent, source);
        if (USB.equals(intent.getAction())) {
            usbCycles.observe(UsbReconnectEvidence.Connection.valueOf(connected.name()),
                    SystemClock.elapsedRealtime(), realDynamicBroadcast);
        }
        if (!reliableConnected()) {
            invalidateSwitch(); cancel(VerificationAttempt.Outcome.WAIT_USB, "USB断开或连接状态未知，验证已取消；守护等待连接");
        }
        event(USB.equals(intent.getAction()) ? "usb_observed" : "tether_observed");
        changed();
    }
    private void applyObservation(String action, Intent intent, String source) {
        Bundle extras;
        try { extras = intent == null ? null : intent.getExtras(); }
        catch (SecurityException denied) { securityDenied(); extras = null; }
        catch (RuntimeException e) { extras = null; }
        if (USB.equals(action)) {
            connected = bool(extras, "connected"); configured = bool(extras, "configured");
            rndis = bool(extras, "rndis"); ncm = bool(extras, "ncm");
            usbAt = extras == null ? 0 : SystemClock.elapsedRealtime();
            usbWall = extras == null ? 0 : System.currentTimeMillis(); usbSource = extras == null ? "广播不可用；状态未知" : source;
            expiryHandler.removeCallbacks(usbExpired); expiryHandler.postDelayed(usbExpired, FRESH_MS + 1);
        } else if (TETHER.equals(action)) {
            String[][] groups = InterfaceState.normalize(interfaces(extras, "tetherArray"),
                    interfaces(extras, "availableArray"), interfaces(extras, "erroredArray"));
            tethered = groups[0]; available = groups[1]; errored = groups[2];
            tetherAt = extras == null ? 0 : SystemClock.elapsedRealtime();
            tetherWall = extras == null ? 0 : System.currentTimeMillis(); tetherSource = extras == null ? "广播不可用；状态未知" : source;
            expiryHandler.removeCallbacks(tetherExpired); expiryHandler.postDelayed(tetherExpired, FRESH_MS + 1);
        }
    }
    private Truth bool(Bundle bundle, String key) {
        try {
            if (bundle == null || !bundle.containsKey(key)) return Truth.UNKNOWN;
            Object value = bundle.get(key);
            return value instanceof Boolean ? ((Boolean) value ? Truth.YES : Truth.NO) : Truth.UNKNOWN;
        } catch (SecurityException denied) { securityDenied(); return Truth.UNKNOWN; }
        catch (RuntimeException e) { return Truth.UNKNOWN; }
    }
    private String[] interfaces(Bundle bundle, String key) {
        try {
            if (bundle == null || !bundle.containsKey(key)) return null;
            return InterfaceState.parse(bundle.get(key));
        } catch (SecurityException denied) { securityDenied(); return null; }
        catch (RuntimeException e) { return null; }
    }
    boolean fresh(long at) {
        long now = SystemClock.elapsedRealtime();
        return at > 0 && now >= at && now - at <= FRESH_MS;
    }
    boolean reliableConnected() { return fresh(usbAt) && connected == Truth.YES; }
    boolean sharingConflict() {
        return InterfaceState.sharingConflict(fresh(usbAt), rndis == Truth.YES, ncm == Truth.YES, fresh(tetherAt), tethered);
    }
    String usbInterfaces() {
        if (!fresh(tetherAt) || tethered == null) return "未知";
        ArrayList<String> usb = new ArrayList<>();
        for (String name : tethered) if (name.matches("(?:usb|rndis)[0-9]+")) usb.add(name);
        return usb.isEmpty() ? "未观察到（不代表已关闭）" : "USB共享接口已观察到：" + String.join(", ", usb);
    }
    void cancel(String reason) {
        cancel(VerificationAttempt.Outcome.REFUSED, reason);
    }
    void cancel(VerificationAttempt.Outcome outcome, String reason) {
        if (policy.phase() != OneShotPolicy.Phase.IDLE || attempts.current() != null) {
            complete(outcome, reason, "verification_cancelled");
        } else { changed(); }
    }
    void complete(VerificationAttempt.Outcome outcome, String message, String eventName) {
        VerificationAttempt attempt = attempts.current();
        attempts.release(attempt); policy.cancel();
        if (service != null) service.attemptEnded();
        pageReady.end(); // Keep immutable terminal facts available to snapshot below and later diagnostics.
        result = message;
        JSONObject row = snapshot();
        try {
            row.put("event", eventName); row.put("attemptOutcome", outcome.name());
            if (attempt != null) {
                row.put("attemptOwner", attempt.owner.name()); row.put("attemptSessionId", attempt.sessionId);
                row.put("attemptId", attempt.attemptId);
            }
        } catch (Exception ignored) { }
        log.append(row); changed();
        if (attempt != null && attempt.listener != null) attempt.listener.finished(attempt, outcome);
    }
    void report(String message, String event) { result = message; event(event); changed(); }
    void observeSwitch(boolean checkable, boolean checked, boolean enabled, String nodeClass, String id, String safeSummary,
                       SwitchStatePolicy.State resolved, boolean adapted) {
        String safeId = id != null && id.matches("[A-Za-z0-9_.]+:id/[A-Za-z0-9_]{1,100}") ? id : "";
        boolean nextReadable = resolved != SwitchStatePolicy.State.UNKNOWN;
        Boolean nextValue = nextReadable ? resolved == SwitchStatePolicy.State.ON : null;
        String nextAdapter = adapted ? SwitchStatePolicy.ADAPTER_NOTE : "标准checkable节点 / 未知节点";
        boolean changedValue = switchCheckable == null || switchCheckable != checkable || switchRawChecked != checked
                || switchEnabled != enabled || !safeId.equals(switchId) || !safeSummary.equals(switchSummary)
                || !java.util.Objects.equals(nextValue, switchChecked) || !nextAdapter.equals(switchAdapter);
        switchCheckable = checkable; switchRawChecked = checked; switchEnabled = enabled;
        switchClass = "android.widget.Switch".equals(nodeClass) || "android.widget.CompoundButton".equals(nodeClass) ? nodeClass : "UNKNOWN";
        switchSummary = safeSummary;
        switchReadable = nextReadable; switchChecked = nextValue; switchAdapter = nextAdapter;
        switchId = safeId; switchWall = System.currentTimeMillis(); switchAt = SystemClock.elapsedRealtime();
        expiryHandler.removeCallbacks(switchExpired); expiryHandler.postDelayed(switchExpired, FRESH_MS + 1);
        if (changedValue) event("settings_switch_observed");
        changed();
    }
    Boolean currentSwitchChecked() {
        return SwitchStatePolicy.currentValue(switchChecked, switchReadable, switchAt, SystemClock.elapsedRealtime(), FRESH_MS);
    }
    void invalidateSwitch() {
        if (switchReadable) { switchReadable = false; changed(); }
    }
    JSONObject snapshot() {
        JSONObject row = new JSONObject();
        try {
            row.put("time", System.currentTimeMillis()); row.put("sdk", Build.VERSION.SDK_INT);
            row.put("elapsedRealtime", SystemClock.elapsedRealtime());
            row.put("overlayPermission", OverlayAccess.status(context).name());
            PowerSnapshot power = powerSnapshot();
            row.put("interactive", nullable(power.interactive)); row.put("deviceSecure", nullable(power.deviceSecure));
            row.put("deviceLocked", nullable(power.deviceLocked)); row.put("keyguardLocked", nullable(power.keyguardLocked));
            row.put("wakeSessionId", wake.sessionId); row.put("wakeAttemptId", wake.attemptId);
            row.put("wakeStage", wake.stage); row.put("wakeCreatedObserved", wake.created);
            row.put("wakeResumedObserved", wake.resumed); row.put("wakeTimeoutFact", wake.timeoutFact);
            row.put("prototype", true); row.put("guardEnabled", guardEnabled);
            row.put("guardState", guardState); row.put("guardStatus", guardStatus);
            row.put("guardSource", guardSource);
            PageReadyEvidence.Facts page = pageReady.facts();
            row.put("pageEvidenceSessionId", page.sessionId); row.put("pageEvidenceAttemptId", page.attemptId);
            row.put("pageDeadline", page.pageDeadline); row.put("pageSampleElapsed", page.sampleAt);
            row.put("lastPageCategory", page.lastPage == null ? "NONE" : page.lastPage.name());
            row.put("pageEverConfirmed", page.everConfirmed); row.put("pagePossibleClick", page.possibleClick);
            row.put("pageEvidenceActive", page.active);
            row.put("guardSessionId", guardSessionId); row.put("guardAttemptId", guardAttemptId);
            row.put("guardPauseKind", guardPauseKind); row.put("guardPausedAt", guardPausedAt);
            row.put("guardConsumedCycle", guardConsumedCycle);
            row.put("usbCycle", usbCycles.generation()); row.put("usbCycleStartedAt", usbCycles.cycleStartedAt());
            row.put("usbCycleCompletedAt", usbCycles.cycleCompletedAt());
            row.put("guardAttempts", guardAttempts); row.put("guardPollSeconds", guardPollSeconds);
            row.put("usbConnected", fresh(usbAt) ? connected.name() : "UNKNOWN");
            row.put("usbConfigured", fresh(usbAt) ? configured.name() : "UNKNOWN");
            row.put("rndis", fresh(usbAt) ? rndis.name() : "UNKNOWN");
            row.put("ncm", fresh(usbAt) ? ncm.name() : "UNKNOWN");
            row.put("usbObservedAt", usbWall); row.put("usbStale", !fresh(usbAt)); row.put("usbSource", usbSource);
            row.put("tetherObservedAt", tetherWall); row.put("tetherStale", !fresh(tetherAt)); row.put("tetherSource", tetherSource);
            row.put("tetheredInterfaces", tethered == null ? JSONObject.NULL : new JSONArray(tethered));
            row.put("availableInterfaces", available == null ? JSONObject.NULL : new JSONArray(available));
            row.put("erroredInterfaces", errored == null ? JSONObject.NULL : new JSONArray(errored));
            Boolean currentSwitch = currentSwitchChecked();
            row.put("switchChecked", currentSwitch == null ? JSONObject.NULL : currentSwitch);
            row.put("switchHistoricalChecked", switchChecked == null ? JSONObject.NULL : switchChecked);
            row.put("switchCheckable", switchCheckable == null ? JSONObject.NULL : switchCheckable);
            row.put("switchRawChecked", switchRawChecked == null ? JSONObject.NULL : switchRawChecked);
            row.put("switchEnabled", switchEnabled == null ? JSONObject.NULL : switchEnabled);
            row.put("switchClass", switchClass); row.put("switchSummary", switchSummary);
            row.put("switchObservedAt", switchWall); row.put("switchNodeId", switchId);
            row.put("switchSource", "系统设置无障碍节点（原始元数据为历史观测）"); row.put("switchAdapter", switchAdapter);
            row.put("switchStale", !fresh(switchAt)); row.put("switchCurrentlyReadable", switchReadable && fresh(switchAt));
            row.put("accessibilityConnected", service != null); row.put("phase", policy.phase().name());
            row.put("result", result); row.put("computerInternet", "UNVERIFIED");
        } catch (Exception ignored) { }
        return row;
    }
    void event(String name) {
        JSONObject row = snapshot();
        try { row.put("event", name); } catch (Exception ignored) { }
        log.append(row);
    }
    /** Fixed-schema read-only diagnostics; never changes result, snapshot state or listeners. */
    void usbModeDialogEvent(VerificationAttempt attempt, UsbModeDialogProbe.Result probe) {
        JSONObject row = snapshot(), detail = new JSONObject();
        try {
            row.put("event", "usb_mode_dialog_probe");
            row.put("attemptOwner", attempt.owner.name()); row.put("attemptSessionId", attempt.sessionId);
            row.put("attemptId", attempt.attemptId);
            detail.put("schema", 2); detail.put("status", probe.status.name());
            detail.put("rootWindowId", probe.rootWindowId); detail.put("rootClass", probe.rootClass);
            detail.put("nodeLimit", UsbModeDialogProbe.NODE_LIMIT);
            if (probe.complete()) {
                JSONObject counts = new JSONObject();
                counts.put("USB连接方式", probe.counts[0]); counts.put("传输照片", probe.counts[1]);
                counts.put("传输文件", probe.counts[2]); counts.put("仅充电", probe.counts[3]); counts.put("取消", probe.counts[4]);
                detail.put("labelCounts", counts);
                JSONObject visible = new JSONObject();
                visible.put("USB连接方式", probe.visibleTextCounts[0]); visible.put("传输照片", probe.visibleTextCounts[1]);
                visible.put("传输文件", probe.visibleTextCounts[2]); visible.put("仅充电", probe.visibleTextCounts[3]);
                visible.put("取消", probe.visibleTextCounts[4]); detail.put("visibleTextCounts", visible);
                detail.put("titleCandidates", usbModeCandidates(probe.titles));
                detail.put("cancelCandidates", usbModeCandidates(probe.cancels));
                detail.put("titleAmbiguous", probe.counts[0] > 1); detail.put("cancelAmbiguous", probe.counts[4] > 1);
            }
            row.put("usbModeDialogProbe", detail);
        } catch (Exception ignored) { return; }
        log.append(row);
    }
    private JSONArray usbModeCandidates(ArrayList<UsbModeDialogProbe.Candidate> candidates) throws org.json.JSONException {
        JSONArray out = new JSONArray();
        for (UsbModeDialogProbe.Candidate candidate : candidates) {
            JSONObject item = new JSONObject();
            item.put("resourceId", candidate.resourceId); item.put("class", candidate.nodeClass);
            item.put("windowId", candidate.windowId);
            item.put("visible", candidate.visible); item.put("enabled", candidate.enabled);
            item.put("clickable", candidate.clickable); item.put("actionClick", candidate.actionClick);
            out.put(item);
        }
        return out;
    }
    /** Cancellation request/return facts only; a returned true never means tethering recovered. */
    void usbModeCancelEvent(VerificationAttempt attempt, UsbModeDialogCancelPolicy.Stage stage,
                            UsbModeDialogCancelPolicy.Reason reason, Boolean returned) {
        JSONObject row = snapshot();
        try {
            row.put("event", "usb_mode_dialog_cancel"); row.put("attemptOwner", attempt.owner.name());
            row.put("attemptSessionId", attempt.sessionId); row.put("attemptId", attempt.attemptId);
            row.put("cancelStage", stage.name()); row.put("cancelReason", reason.name());
            if (stage == UsbModeDialogCancelPolicy.Stage.RETURNED && returned != null) row.put("cancelReturned", returned);
        } catch (Exception ignored) { return; }
        log.append(row);
    }
    /** Fixed-field diagnostic boundaries persist without waking the state-machine listeners. */
    void wakeEvent(String name, String stage, long session, long attempt, boolean ownsWake, String exceptionType) {
        if (ownsWake) wake.observe(session, attempt, stage);
        JSONObject row = snapshot();
        try {
            row.put("event", name); row.put("wakeEventSessionId", session); row.put("wakeEventAttemptId", attempt);
            row.put("ownsWake", ownsWake); row.put("wakeEventStage", stage);
            if (exceptionType != null) row.put("wakeExceptionType", exceptionType);
        } catch (Exception ignored) { }
        log.append(row); writeSnapshot();
    }
    static final class PowerSnapshot {
        final Boolean interactive, deviceSecure, deviceLocked, keyguardLocked;
        PowerSnapshot(Boolean interactive, Boolean deviceSecure, Boolean deviceLocked, Boolean keyguardLocked) {
            this.interactive = interactive; this.deviceSecure = deviceSecure;
            this.deviceLocked = deviceLocked; this.keyguardLocked = keyguardLocked;
        }
    }
    private static Boolean readBoolean(java.util.function.Supplier<Boolean> read) {
        try { return read.get(); } catch (RuntimeException unavailable) { return null; }
    }
    PowerSnapshot powerSnapshot() {
        // Each read is independent: an unavailable service/property remains JSON null, never false.
        return new PowerSnapshot(
                readBoolean(() -> { PowerManager manager = context.getSystemService(PowerManager.class);
                    return manager == null ? null : manager.isInteractive(); }),
                readBoolean(() -> { KeyguardManager manager = context.getSystemService(KeyguardManager.class);
                    return manager == null ? null : manager.isDeviceSecure(); }),
                readBoolean(() -> { KeyguardManager manager = context.getSystemService(KeyguardManager.class);
                    return manager == null ? null : manager.isDeviceLocked(); }),
                readBoolean(() -> { KeyguardManager manager = context.getSystemService(KeyguardManager.class);
                    return manager == null ? null : manager.isKeyguardLocked(); }));
    }
    private static Object nullable(Boolean value) { return value == null ? JSONObject.NULL : value; }
    void changed() {
        writeSnapshot();
        for (Runnable listener : new ArrayList<>(listeners)) listener.run();
    }
    private void writeSnapshot() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "snapshot.json"))) {
            out.write(snapshot().toString(2).getBytes(StandardCharsets.UTF_8)); snapshotWritten = true;
        } catch (Exception ignored) { snapshotWritten = false; }
    }
}
