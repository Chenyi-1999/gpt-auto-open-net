package com.autousb.helper;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.Context;
import android.content.ComponentName;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

/** Process-only guard with an opt-in boot entry. Only an Activity may request screen-on; no unlock. */
public final class GuardService extends Service {
    private static final String START = "com.autousb.helper.GUARD_START", BOOT_START = "com.autousb.helper.GUARD_BOOT_START",
            STOP = "com.autousb.helper.GUARD_STOP";
    private static final String CHANNEL = "usb_guard";
    private static final int NOTIFICATION_ID = 31;
    private static final int BOOT_FAILURE_ID = 32;
    private static final StartAuthorization starts = new StartAuthorization(SystemClock.elapsedRealtimeNanos());
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final GuardPolicy policy = new GuardPolicy();
    private final BootWaitPolicy bootWait = new BootWaitPolicy();
    private final WakeWindowSlot<WakeActivity> wakeWindow = new WakeWindowSlot<>();
    private final InternalTaskRemoval internalTaskRemoval = new InternalTaskRemoval();
    private ProbeStore store;
    private long pollMillis = 60_000;
    private boolean evaluating, evaluationPosted, foreground, stopping;
    private String detail = "等待状态检查";
    private String pageCooldownDetail;
    private final Runnable changed = () -> {
        closeInvalidWakeWindow();
        if (!evaluating) requestEvaluation();
    };
    private final Runnable evaluateTask = () -> { evaluationPosted = false; evaluate(); };
    private final Runnable periodic = new Runnable() {
        @Override public void run() {
            if (!policy.enabled()) return;
            requestEvaluation(); handler.postDelayed(this, pollMillis);
        }
    };
    private final Runnable retry = () -> requestEvaluation();
    private final Runnable bootCheck = this::checkBootWait;

    static boolean supported() {
        return Build.VERSION.SDK_INT == 31 && SwitchStatePolicy.supportedHuawei(
                Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT, Build.DISPLAY);
    }
    static boolean notificationsVisible(android.content.Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }
    static boolean startFromActivity(Activity activity, int seconds, boolean resumed) {
        ProbeStore store = ProbeStore.get(activity);
        if (!resumed || !supported() || !notificationsVisible(activity) || store.guardEnabled
                || starts.pending() || store.attempts.current() != null
                || store.policy.phase() != OneShotPolicy.Phase.IDLE) {
            store.report("无法启动守护：需本机已验证的API31、可见应用、通知已开启，且无其他验证占用", "guard_start_refused");
            return false;
        }
        long request = starts.issue(StartAuthorization.Origin.MANUAL);
        try {
            activity.startForegroundService(new Intent(activity, GuardService.class).setAction(START)
                    .putExtra("request", request).putExtra("pollSeconds", seconds));
            return true;
        } catch (RuntimeException denied) {
            starts.revoke();
            store.report("系统拒绝启动前台守护；守护未启用，请保持应用可见并检查通知设置", "guard_start_failed");
            return false;
        }
    }
    static boolean hasPendingStart() { return starts.pending(); }
    /** Only BootReceiver calls this after a durable claim of a protected system broadcast. */
    static void startFromBoot(Context context) {
        ProbeStore store = ProbeStore.get(context);
        if (!BootPreferences.enabled(context) || !supported() || !notificationsVisible(context)
                || store.guardEnabled || store.guard != null || starts.pending()
                || store.policy.phase() != OneShotPolicy.Phase.IDLE || store.attempts.current() != null) {
            reportBootFailure(context, "开机启动条件不满足或已有守护/验证；未启动", "boot_start_refused"); return;
        }
        long request = starts.issue(StartAuthorization.Origin.BOOT);
        // Reserve the UI/verification slot before asynchronous service delivery.
        store.guardEnabled = true; store.guardState = "BOOT_QUEUED";
        store.guardSource = "BOOT";
        store.guardStatus = "已请求开机守护，等待前台服务；可随时停止";
        store.guardPollSeconds = BootPreferences.pollSeconds(context);
        BootPreferences.record(context, store.guardStatus, "boot_start_requested");
        try {
            context.startForegroundService(new Intent(context, GuardService.class).setAction(BOOT_START)
                    .putExtra("request", request));
        } catch (RuntimeException denied) {
            starts.revoke(); clearQueuedBoot(store);
            reportBootFailure(context, "系统拒绝开机前台服务；未启动守护", "boot_start_failed");
        }
    }
    static void onBootOptionChanged(Context context) {
        if (BootPreferences.enabled(context)) return;
        cancelPendingBoot(context);
    }
    static void cancelPendingBoot(Context context) {
        clearBootFailure(context);
        ProbeStore store = ProbeStore.get(context);
        boolean queued = starts.pendingBoot();
        starts.revokeBoot();
        if (queued) {
            clearQueuedBoot(store);
            BootPreferences.record(context, "开机选项已关闭；待启动请求已撤销", "boot_option_cancelled");
        }
        if (store.guard != null && store.guard.bootWait.waiting()) {
            store.guard.shutdown("开机选项已关闭；等待已撤销");
        }
    }
    private static void clearQueuedBoot(ProbeStore store) {
        if (!"BOOT_QUEUED".equals(store.guardState)) return;
        store.guardEnabled = false; store.guardState = "STOPPED"; store.guardStatus = "守护未启用";
        store.guardSource = "OFF";
        store.changed();
    }
    static void stopFromActivity(android.content.Context context) {
        stopByUser(context, "用户从应用停止守护；开机自动守护已关闭");
    }
    private static void stopByUser(Context context, String reason) {
        starts.revoke();
        boolean saved = BootPreferences.setEnabled(context, false);
        ProbeStore store = ProbeStore.get(context);
        clearQueuedBoot(store);
        if (!saved) reason = "守护已停止；开机选项关闭保存失败，请检查后再次关闭";
        GuardService active = ProbeStore.get(context).guard;
        // Both Activity buttons run on the main thread: revoke queued clicks before lifecycle cleanup.
        if (active != null) active.shutdown(reason);
        clearBootFailure(context);
        BootPreferences.record(context, reason, saved ? "guard_user_stopped" : "boot_option_save_failed");
        // stopService does not create a service when it is already stopped.
        context.stopService(new Intent(context, GuardService.class));
    }
    @Override public void onCreate() { super.onCreate(); store = ProbeStore.get(this); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stopByUser(this, "用户从通知停止守护；开机自动守护已关闭");
            if (!stopping) shutdown("用户从通知停止守护");
            return START_NOT_STICKY;
        }
        long request = intent == null ? 0 : intent.getLongExtra("request", 0);
        boolean boot = intent != null && BOOT_START.equals(intent.getAction());
        if (intent == null || (!START.equals(intent.getAction()) && !boot)
                || !starts.consume(request, boot ? StartAuthorization.Origin.BOOT : StartAuthorization.Origin.MANUAL)) {
            // A revoked BOOT intent can arrive before a newer registered manual start. Stop only
            // this delivery: unqualified stopSelf would destroy the newer start and its capability.
            if (!policy.enabled() && !bootWait.waiting()) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (policy.enabled() || bootWait.waiting()) return START_NOT_STICKY;
        if (stopping) {
            clearQueuedBoot(store);
            if (boot) reportBootFailure(this, "服务正在停止；本次开机请求已撤销", "boot_start_cancelled");
            else store.report("服务正在停止；本次手动启动请求已撤销，请待停止后重新启用", "guard_start_cancelled");
            stopSelf(startId); return START_NOT_STICKY;
        }
        if (!supported() || !notificationsVisible(this) || store.policy.phase() != OneShotPolicy.Phase.IDLE
                || store.attempts.current() != null || (boot && !BootPreferences.enabled(this))) {
            store.report("守护未启用：需本机已验证的华为API31、通知可见且无验证占用", "guard_start_refused");
            clearQueuedBoot(store);
            if (boot) reportBootFailure(this, "开机请求处理时条件已变化；未启动守护", "boot_start_refused");
            stopSelf(startId); return START_NOT_STICKY;
        }
        pollMillis = GuardPolicy.pollMillis(boot ? BootPreferences.pollSeconds(this) : intent.getIntExtra("pollSeconds", 60));
        NotificationManager manager = getSystemService(NotificationManager.class);
        try {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "USB共享守护", NotificationManager.IMPORTANCE_LOW));
            if (Build.VERSION.SDK_INT != 31) { stopSelf(startId); return START_NOT_STICKY; }
            promoteForegroundApi31();
            foreground = true;
        } catch (RuntimeException denied) {
            store.report("系统拒绝前台通知或服务；守护未启用", "guard_start_failed");
            clearQueuedBoot(store);
            if (boot) reportBootFailure(this, "系统拒绝开机前台通知或服务；未启动守护", "boot_start_failed");
            stopSelf(startId); return START_NOT_STICKY;
        }
        store.guard = this;
        if (boot) {
            bootWait.begin(SystemClock.elapsedRealtime());
            store.guardEnabled = true; store.guardState = "WAIT_A11Y";
            store.guardStatus = "开机守护等待无障碍连接（最多120秒）；可随时停止";
            store.guardSessionId = 0; store.guardAttemptId = 0; store.guardAttempts = 0;
            store.guardPauseKind = "NONE"; store.guardPausedAt = 0; store.guardConsumedCycle = 0;
            store.guardPollSeconds = (int) (pollMillis / 1000);
            try { updateNotificationApi31(store.guardStatus); }
            catch (RuntimeException denied) {
                shutdown("系统拒绝更新开机等待通知；守护未启动"); return START_NOT_STICKY;
            }
            BootPreferences.record(this, store.guardStatus, "boot_wait_a11y");
            checkBootWait();
        } else beginGuard(false);
        return START_NOT_STICKY;
    }
    private void beginGuard(boolean boot) {
        clearBootFailure(this);
        // Monotonic process-independent identity also rejects a delayed Activity from an old service instance.
        policy.start(SystemClock.elapsedRealtimeNanos(), boot);
        store.guard = this;
        store.listeners.add(changed);
        store.wake.clear();
        detail = "守护已启动，等待状态检查；尚未进行本次验证";
        publish("guard_started");
        requestEvaluation();
        handler.postDelayed(periodic, pollMillis);
        if (boot) BootPreferences.record(this, "开机等待完成，已进入守护；仍按原安全条件检查", "boot_guard_started");
    }
    /** The a11y callback only wakes an already authorized wait; it cannot create a request. */
    boolean waitingForBootAccessibility() { return bootWait.waiting() && !stopping; }
    void accessibilityConnected() { if (bootWait.waiting()) checkBootWait(); }
    private void checkBootWait() {
        if (!bootWait.waiting() || stopping) return;
        handler.removeCallbacks(bootCheck);
        BootWaitPolicy.Result result = bootWait.evaluate(SystemClock.elapsedRealtime(), BootPreferences.enabled(this),
                notificationsVisible(this), store.policy.phase() != OneShotPolicy.Phase.IDLE || store.attempts.current() != null,
                store.service != null);
        if (result == BootWaitPolicy.Result.WAITING) {
            handler.postDelayed(bootCheck, Math.min(1_000, Math.max(1, bootWait.deadline() - SystemClock.elapsedRealtime())));
        } else if (result == BootWaitPolicy.Result.READY) {
            beginGuard(true);
        } else if (result != BootWaitPolicy.Result.INACTIVE) {
            shutdown(result == BootWaitPolicy.Result.TIMED_OUT ? "开机等待无障碍连接已满120秒；守护未启动，需手动启用"
                    : result == BootWaitPolicy.Result.NOTIFICATION_OFF ? "开机等待时通知已关闭；守护未启动"
                    : result == BootWaitPolicy.Result.CONFLICT ? "开机等待时已有验证占用；守护未启动"
                    : "开机自动守护已关闭；等待已撤销");
        }
    }
    // This exact-device guard rejects every OS except API31 before calling this helper.
    // Lint31.13.2 checks targetSdk/manifest only here and does not inspect runtime SDK branches.
    @SuppressLint("ForegroundServiceType")
    private void promoteForegroundApi31() {
        if (Build.VERSION.SDK_INT == 31) startForeground(NOTIFICATION_ID, notification("正在启动；可随时停止"));
    }
    /** Main-thread gate: stopping revokes the token/slot before returning to a pending click. */
    boolean requireVisibleNotification(long session, long attempt) {
        if (!policy.enabled() || stopping || policy.sessionId() != session || policy.attemptId() != attempt) return false;
        if (notificationsVisible(this)) return true;
        shutdown("通知已关闭，守护已停止；请手动开启通知后重新启用");
        return false;
    }
    private void requestEvaluation() {
        if (!policy.enabled() || stopping) return;
        if (!evaluationPosted) {
            evaluationPosted = true; handler.postDelayed(evaluateTask, 150);
        }
    }
    private void evaluate() {
        if (!policy.enabled() || stopping) return;
        evaluating = true;
        try {
            if (!requireVisibleNotification(policy.sessionId(), policy.attemptId())) return;
            // Sticky reads are a batch and are never refreshed during an in-flight verification.
            if (policy.attemptId() == 0) store.refresh();
            long now = SystemClock.elapsedRealtime();
            boolean usbFresh = GuardPolicy.fresh(store.usbAt, now);
            boolean tetherFresh = GuardPolicy.fresh(store.tetherAt, now);
            boolean active = tetherFresh && InterfaceState.hasUsb(store.tethered);
            boolean positiveFlag = usbFresh && (store.rndis == ProbeStore.Truth.YES || store.ncm == ProbeStore.Truth.YES);
            boolean absent = (tetherFresh && store.tethered != null && !active)
                    || (usbFresh && store.rndis == ProbeStore.Truth.NO && store.ncm != ProbeStore.Truth.YES);
            long oldAttempt = policy.attemptId();
            GuardPolicy.State oldState = policy.state();
            boolean wasWaking = policy.state() == GuardPolicy.State.WAKING;
            boolean wakeDeadlineReached = wasWaking && now >= policy.wakeUntil();
            boolean connected = policy.attemptId() != 0 ? store.reliableConnected()
                    : usbFresh && store.connected == ProbeStore.Truth.YES;
            GuardPolicy.Action action = policy.evaluate(now, new GuardPolicy.Input(store.service != null,
                    connected, ProbeAccessibilityService.unlocked(this),
                    active, active || positiveFlag, absent, ProbeAccessibilityService.nonSecureWakeEligible(this),
                    store.usbCycles.generation(), store.usbCycles.cycleStartedAt(), store.usbCycles.cycleCompletedAt()));
            // Remove only our old Activity's screen-on request before opening settings or publishing
            // a cancelled wake. Its finish() must never target the subsequently opened Settings UI.
            closeInvalidWakeWindow();
            if (policy.state() == GuardPolicy.State.PAUSED && oldState != GuardPolicy.State.PAUSED) {
                detail = policy.pauseKind() == GuardPolicy.PauseKind.HARD
                        ? "无障碍权限或服务不可用；恢复后需停止并重新启用守护" : roundPauseText();
            }
            if (action == GuardPolicy.Action.CANCEL_WAIT_USB || action == GuardPolicy.Action.CANCEL_WAIT_UNLOCK
                    || action == GuardPolicy.Action.CANCEL_PAUSE) {
                VerificationAttempt.Outcome outcome = action == GuardPolicy.Action.CANCEL_WAIT_USB ? VerificationAttempt.Outcome.WAIT_USB
                        : action == GuardPolicy.Action.CANCEL_WAIT_UNLOCK ? VerificationAttempt.Outcome.WAIT_UNLOCK
                        : policy.pauseKind() == GuardPolicy.PauseKind.HARD ? VerificationAttempt.Outcome.SERVICE_UNAVAILABLE
                        : VerificationAttempt.Outcome.UNKNOWN;
                cancelOwned(oldAttempt, outcome, "守护等待安全条件或已暂停；当前验证已撤销");
                if (wasWaking && action == GuardPolicy.Action.CANCEL_PAUSE && wakeDeadlineReached && store.service != null) {
                    ProbeStore.PowerSnapshot power = store.powerSnapshot();
                    detail = "本次亮屏失败：10秒内未进入验证；"
                            + store.wake.timedOut(power.interactive, power.deviceLocked, power.keyguardLocked).message()
                            + "；" + roundPauseText();
                    publish("guard_wake_timeout");
                }
            }
            if (action == GuardPolicy.Action.START_WAKE || action == GuardPolicy.Action.START_ATTEMPT) {
                if (!requireVisibleNotification(policy.sessionId(), policy.attemptId())) return;
            }
            if (action == GuardPolicy.Action.START_WAKE) {
                store.wake.begin(policy.sessionId(), policy.attemptId());
                detail = "无安全锁，尝试显示亮屏提示；不会操作锁屏，最多等待10秒";
                publish("guard_wake_started");
                handler.removeCallbacks(retry);
                // Keep the original deadline even when the stricter window gate rejects launch.
                handler.postDelayed(retry, Math.max(1, policy.wakeUntil() - SystemClock.elapsedRealtime()));
                if (!mayUseWakeWindow(policy.sessionId(), policy.attemptId())) { requestEvaluation(); return; }
                ProbeAccessibilityService.WakeLaunch launched = store.service.openWakeActivity(policy.sessionId(), policy.attemptId());
                if (launched == ProbeAccessibilityService.WakeLaunch.SECURITY_DENIED) {
                    hardPause("系统拒绝亮屏页面访问；权限恢复后需停止并重新启用守护");
                } else if (launched != ProbeAccessibilityService.WakeLaunch.OPENED) {
                    wakeRefused(policy.sessionId(), policy.attemptId());
                }
            } else if (action == GuardPolicy.Action.START_ATTEMPT) {
                if (wasWaking) store.wake.observe(policy.sessionId(), policy.attemptId(), "VERIFICATION_STARTED");
                handler.removeCallbacks(retry);
                detail = "检测到可能未共享，打开设置确认；本次最多点击一次";
                publish("guard_attempt_started");
                VerificationAttempt attempt = new VerificationAttempt(VerificationAttempt.Owner.GUARD,
                        policy.sessionId(), policy.attemptId(), this::finished);
                store.service.startVerification(attempt);
            } else {
                if (policy.state() == GuardPolicy.State.PAUSED) { /* Keep the pause reason visible. */ }
                else if (policy.state() == GuardPolicy.State.COOLDOWN) detail = pageCooldownDetail != null ? pageCooldownDetail
                        : "保留失败后至少10秒冷却；截止后自动检查，本次尚未确认恢复";
                else if (policy.state() == GuardPolicy.State.WAIT_USB) detail = "USB未连接或连接状态未知；接回后重新检查，本次尚未确认恢复";
                else if (policy.state() == GuardPolicy.State.WAIT_UNLOCK) detail = "当前屏幕或锁定条件不满足；等待安全条件恢复，本次尚未确认恢复";
                else if (active) detail = "手机USB共享接口已观察到；Windows联网未验证";
                else if (positiveFlag) detail = "有共享标志，保持观察；不重复开启，Windows联网未验证";
                else if (policy.state() == GuardPolicy.State.WATCHING) detail = "等待可靠共享状态；Windows联网未验证";
                publish(null);
                if (policy.state() == GuardPolicy.State.COOLDOWN) scheduleRetry();
            }
        } catch (SecurityException denied) {
            hardPause("系统拒绝状态或页面访问；权限恢复后需停止并重新启用守护");
        } finally { evaluating = false; }
    }
    private void finished(VerificationAttempt attempt, VerificationAttempt.Outcome outcome) {
        long now = SystemClock.elapsedRealtime();
        VerificationAttempt.Outcome effective = outcome;
        // ALREADY_ON includes a real fresh node read; interface-only observations never use this outcome.
        if (outcome == VerificationAttempt.Outcome.ALREADY_ON && GuardPolicy.fresh(store.tetherAt, now)
                && InterfaceState.hasUsb(store.tethered)) effective = VerificationAttempt.Outcome.OBSERVED_ON;
        if (attempt.owner != VerificationAttempt.Owner.GUARD
                || !policy.finished(attempt.sessionId, attempt.attemptId, effective, now)) return;
        closeInvalidWakeWindow();
        switch (effective) {
            case OBSERVED_ON: detail = "已观察到设置开关开启和USB共享接口；无需点击，Windows联网未验证"; break;
            case CONFIRMED_ON: detail = "手机开关和USB接口已确认开启；Windows联网未验证"; break;
            case OFF: detail = policy.state() == GuardPolicy.State.PAUSED ? "已达本轮3次上限；" + roundPauseText()
                    : "可靠回读仍关闭；完成后至少10秒再尝试"; break;
            case PAGE_NOT_READY:
                detail = !policy.pageReadyRetryAllowed() ? "状态或页面无法确认；" + roundPauseText()
                        : policy.state() == GuardPolicy.State.PAUSED ? "共享设置页未就绪，已耗尽本轮3次总预算；" + roundPauseText()
                        : "共享设置页未就绪且未尝试点击；至少10秒后进行下一次有限页面检查（共享本轮3次总预算）";
                break;
            case WAIT_USB: detail = "等待USB重新连接，自动重新检查；保留本次故障尝试次数"; break;
            case WAIT_UNLOCK: detail = "当前验证已撤销；安全锁等待解锁，无安全锁时可重新检查"; break;
            case ALREADY_ON:
            case SWITCH_ON: detail = "设置开关已开启，本次最终闭环未确认；" + roundPauseText(); break;
            case SECURITY_DENIED:
            case SERVICE_UNAVAILABLE: detail = "权限或服务中断；恢复后需停止并重新启用守护"; break;
            default: detail = "状态或页面无法确认；" + roundPauseText(); break;
        }
        pageCooldownDetail = effective == VerificationAttempt.Outcome.PAGE_NOT_READY
                && policy.state() == GuardPolicy.State.COOLDOWN ? detail : null;
        publish("guard_attempt_finished");
        handler.removeCallbacks(retry);
        if (policy.state() == GuardPolicy.State.COOLDOWN) {
            scheduleRetry();
        }
        requestEvaluation();
    }
    boolean ownsWake(long session, long attempt) {
        return !stopping && policy.ownsWake(session, attempt, SystemClock.elapsedRealtime());
    }
    /** Live Activity-window gate, independent of onResume and of delayed policy evaluation. */
    boolean mayUseWakeWindow(long session, long attempt) {
        if (!ownsWake(session, attempt) || store.guard != this || store.service == null || !store.reliableConnected()) return false;
        if (!requireVisibleNotification(session, attempt)) return false;
        try {
            KeyguardManager keyguard = getSystemService(KeyguardManager.class);
            return keyguard != null && !keyguard.isDeviceSecure() && !keyguard.isDeviceLocked()
                    && !keyguard.isKeyguardLocked();
        } catch (SecurityException denied) {
            hardPause("系统拒绝锁定状态访问；权限恢复后需停止并重新启用守护"); return false;
        }
    }
    long wakeDeadline(long session, long attempt) {
        return ownsWake(session, attempt) ? policy.wakeUntil() : 0;
    }
    boolean attachWakeWindow(WakeActivity activity, long session, long attempt) {
        if (!mayUseWakeWindow(session, attempt)) return false;
        WakeActivity old = wakeWindow.replace(activity, session, attempt);
        if (old != null && old != activity) old.revokeWakeWindow(this);
        return wakeWindow.matches(activity, session, attempt) && mayUseWakeWindow(session, attempt);
    }
    void detachWakeWindow(WakeActivity activity, long session, long attempt) {
        wakeWindow.release(activity, session, attempt);
    }
    void recordInternalWakeFinish(WakeActivity activity, long session, long attempt) {
        // Slot/policy may already have moved to verification. The actual attached Activity is
        // the proof of this internal finish; Intent extras alone cannot create a credential.
        if (stopping || !activity.wasAttachedTo(this, session, attempt) || activity.isFinishing()
                || !activity.isTaskRoot() || activity.getIntent() == null
                || !new ComponentName(this, WakeActivity.class).equals(activity.getIntent().getComponent())) return;
        InternalTaskRemoval.Recorded result = internalTaskRemoval.record(session, attempt);
        if (result == InternalTaskRemoval.Recorded.DROPPED_OLDEST) store.event("guard_internal_task_credential_evicted");
        if (result == InternalTaskRemoval.Recorded.ADDED || result == InternalTaskRemoval.Recorded.DROPPED_OLDEST) {
            store.event("guard_internal_wake_finish_recorded");
        }
    }
    private void closeInvalidWakeWindow() {
        if (wakeWindow.current() != null && !mayUseWakeWindow(wakeWindow.sessionId(), wakeWindow.attemptId())) closeWakeWindow();
    }
    private void closeWakeWindow() {
        WakeActivity activity = wakeWindow.clear(); // Detach first: onDestroy may unregister reentrantly.
        if (activity != null) activity.revokeWakeWindow(this);
    }
    boolean wakeResumed(long session, long attempt) {
        if (!ownsWake(session, attempt)) return false;
        requestEvaluation(); return true;
    }
    void wakeRefused(long session, long attempt) {
        if (!ownsWake(session, attempt)) return;
        policy.pauseRound(SystemClock.elapsedRealtime()); handler.removeCallbacks(retry);
        closeWakeWindow();
        store.wake.observe(session, attempt, "REFUSED");
        detail = "本次亮屏失败：页面启动未继续或屏幕、锁定条件不满足；" + roundPauseText();
        publish("guard_wake_refused");
    }
    private static String roundPauseText() {
        return "本轮暂停，USB断开至少3秒接回后再检查";
    }
    private void scheduleRetry() {
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, Math.max(1, policy.retryAt() - SystemClock.elapsedRealtime()));
    }
    void hardPause(String reason) {
        if (bootWait.waiting()) { shutdown(reason + "；开机等待已撤销"); return; }
        if (!policy.enabled() || stopping) return;
        long oldAttempt = policy.attemptId();
        policy.pauseHard(SystemClock.elapsedRealtime()); handler.removeCallbacks(retry);
        closeWakeWindow();
        cancelOwned(oldAttempt, VerificationAttempt.Outcome.SERVICE_UNAVAILABLE, reason);
        detail = reason; publish("guard_hard_paused");
    }
    private void cancelOwned(long attemptId, VerificationAttempt.Outcome outcome, String reason) {
        VerificationAttempt current = store.attempts.current();
        if (current != null && current.owner == VerificationAttempt.Owner.GUARD
                && current.sessionId == policy.sessionId() && current.attemptId == attemptId) store.cancel(outcome, reason);
    }
    private String stateText() {
        switch (policy.state()) {
            case WAIT_USB: return "等待USB连接（接回后自动检查）";
            case WAIT_UNLOCK: return "息屏/锁屏等待，不会自动开启；请自行亮屏解锁";
            case WAKING: return "正在显示无安全锁亮屏提示（最多10秒）";
            case ATTEMPTING: return "正在确认并开启";
            case COOLDOWN: return "等待至少10秒后重试";
            case PAUSED: return policy.pauseKind() == GuardPolicy.PauseKind.HARD
                    ? "权限/服务中断暂停，需重启守护" : "本轮暂停，等待新的USB连接周期";
            case WATCHING: return "守护观察中";
            default: return "守护已停止";
        }
    }
    private void publish(String event) {
        String text = stateText() + "；" + detail;
        // These states are current outcomes, not the previous verification's historical success.
        boolean currentResult = "guard_started".equals(event)
                || ("ROUND".equals(store.guardPauseKind) && policy.pauseKind() == GuardPolicy.PauseKind.NONE)
                || policy.state() == GuardPolicy.State.COOLDOWN || policy.state() == GuardPolicy.State.STOPPED
                || policy.state() == GuardPolicy.State.PAUSED || policy.state() == GuardPolicy.State.WAKING
                || policy.state() == GuardPolicy.State.ATTEMPTING || policy.state() == GuardPolicy.State.WAIT_USB
                || policy.state() == GuardPolicy.State.WAIT_UNLOCK;
        boolean changedValue = store.guardEnabled != policy.enabled() || !store.guardState.equals(policy.state().name())
                || !store.guardStatus.equals(text) || store.guardAttemptId != policy.attemptId()
                || store.guardAttempts != policy.attempts() || (currentResult && !store.result.equals(text));
        store.guardEnabled = policy.enabled(); store.guardState = policy.state().name(); store.guardStatus = text;
        store.guardSource = !policy.enabled() ? "OFF" : policy.pageReadyRetryAllowed() ? "BOOT" : "MANUAL";
        store.guardSessionId = policy.sessionId(); store.guardAttemptId = policy.attemptId();
        store.guardPauseKind = policy.pauseKind().name(); store.guardPausedAt = policy.pausedAt();
        store.guardConsumedCycle = policy.consumedCycle();
        store.guardAttempts = policy.attempts(); store.guardPollSeconds = (int) (pollMillis / 1000);
        if (currentResult) store.result = text;
        if (Build.VERSION.SDK_INT == 31 && foreground && (changedValue || event != null)) {
            updateNotificationApi31(text);
        }
        if (event != null) store.event(event);
        if (changedValue || event != null) store.changed();
    }
    // POST_NOTIFICATIONS exists from API33; this guard never runs on that OS.
    // Keep the exception local rather than requesting an unused permission or lowering targetSdk.
    @SuppressLint("NotificationPermission")
    private void updateNotificationApi31(String text) {
        if (Build.VERSION.SDK_INT == 31) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
        }
    }
    private Notification notification(String text) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, GuardService.class).setAction(STOP), flags);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("USB辅助守护（安全锁时等待）").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "停止守护", stop).build()).build();
    }
    @SuppressLint("NotificationPermission")
    static void reportBootFailure(Context context, String text, String event) {
        BootPreferences.record(context, text, event);
        if (Build.VERSION.SDK_INT != 31) return;
        try {
            if (!notificationsVisible(context)) return;
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "USB共享守护", NotificationManager.IMPORTANCE_LOW));
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent open = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class), flags);
            PendingIntent stop = PendingIntent.getService(context, 1,
                    new Intent(context, GuardService.class).setAction(STOP), flags);
            Notification failure = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_sync_noanim).setContentTitle("开机守护未启动")
                    .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open).setAutoCancel(true).setOngoing(false)
                    .addAction(new Notification.Action.Builder(null, "停止并关闭开机守护", stop).build()).build();
            manager.notify(BOOT_FAILURE_ID, failure);
        } catch (RuntimeException unavailable) { ProbeStore.get(context).event("boot_failure_notification_unavailable"); }
    }
    private static void clearBootFailure(Context context) {
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(BOOT_FAILURE_ID);
        } catch (RuntimeException unavailable) { ProbeStore.get(context).event("boot_failure_notification_unavailable"); }
    }
    private void shutdown(String reason) {
        if (stopping) return;
        stopping = true;
        closeWakeWindow();
        internalTaskRemoval.clear();
        boolean wasBootWaiting = bootWait.waiting() || "WAIT_A11Y".equals(store.guardState);
        starts.revoke(); bootWait.cancel();
        long oldAttempt = policy.attemptId(), oldSession = policy.sessionId();
        policy.stop(); handler.removeCallbacksAndMessages(null); store.listeners.remove(changed);
        if (store.guard == this) store.guard = null;
        VerificationAttempt current = store.attempts.current();
        if (current != null && current.owner == VerificationAttempt.Owner.GUARD
                && current.sessionId == oldSession && current.attemptId == oldAttempt) {
            store.cancel(VerificationAttempt.Outcome.STOPPED, reason);
        }
        foreground = false; detail = reason; publish("guard_stopped");
        if (wasBootWaiting) BootPreferences.record(this, reason, "boot_wait_stopped");
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        if (wasBootWaiting && BootPreferences.enabled(this)) {
            reportBootFailure(this, reason, "boot_wait_failed");
        }
    }
    // Task removal stops this process session, retaining only next-boot opt-in (no process restart).
    @Override public void onTaskRemoved(Intent rootIntent) {
        boolean internal = false;
        String rejection = "guard_task_removed_unknown";
        try {
            if (stopping) rejection = "guard_task_removed_stopping";
            else if (rootIntent == null) rejection = "guard_task_removed_missing_intent";
            else if (new ComponentName(this, MainActivity.class).equals(rootIntent.getComponent())) {
                rejection = "guard_task_removed_main";
            } else if (!new ComponentName(this, WakeActivity.class).equals(rootIntent.getComponent())) {
                rejection = "guard_task_removed_wrong_component";
            } else if (!rootIntent.hasExtra("session") || !rootIntent.hasExtra("attempt")) {
                rejection = "guard_task_removed_missing_ids";
            } else {
                long session = rootIntent.getLongExtra("session", 0), attempt = rootIntent.getLongExtra("attempt", 0);
                if (session <= 0 || attempt <= 0) rejection = "guard_task_removed_invalid_ids";
                else {
                    internal = internalTaskRemoval.consume(true, session, attempt);
                    rejection = "guard_task_removed_unmatched";
                }
            }
        } catch (RuntimeException malformed) { rejection = "guard_task_removed_malformed"; }
        if (internal) { store.event("guard_internal_wake_task_removed"); return; }
        store.event(rejection); // Fixed categories only; never serialize an arbitrary task Intent.
        shutdown("应用任务已移除，守护已停止；本次不自动恢复");
    }
    @Override public void onDestroy() {
        shutdown("前台服务已停止；守护默认关闭，需手动重新启用");
        internalTaskRemoval.clear(); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
