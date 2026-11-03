package com.autousb.helper;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public final class ProbeAccessibilityService extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ProbeStore store;
    private long clickStartedAt;
    private long manualAttemptId;
    private Runnable tick;
    private boolean pageWaitReported, readbackWaitReported;
    private final VerificationWindow window = new VerificationWindow();
    private final NavigationRetryBudget navigation = new NavigationRetryBudget();
    private final ReadbackEvidence readback = new ReadbackEvidence();
    private final UsbModeDialogProbe usbModeProbe = new UsbModeDialogProbe();
    private final UsbModeDialogCancelPolicy usbModeCancel = new UsbModeDialogCancelPolicy();
    static boolean unlocked(android.content.Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return power != null && power.isInteractive() && keyguard != null
                && !keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked();
    }
    static boolean nonSecureWakeEligible(android.content.Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return power != null && keyguard != null
                && GuardPolicy.mayWake(power.isInteractive(), keyguard.isDeviceSecure(), keyguard.isDeviceLocked());
    }
    enum WakeLaunch { OPENED, REFUSED, SECURITY_DENIED }
    WakeLaunch openWakeActivity(long session, long attempt) {
        try { return launchWakeActivity(session, attempt); }
        catch (SecurityException denied) {
            wakeLaunchEvent("wake_launch_exception", "LAUNCH_EXCEPTION", session, attempt, "SECURITY");
            return WakeLaunch.SECURITY_DENIED;
        }
    }
    private WakeLaunch launchWakeActivity(long session, long attempt) {
        ProbeStore current = ProbeStore.get(this);
        GuardService requestedGuard = current.guard;
        if (requestedGuard == null || current.service != this || !nonSecureWakeEligible(this)
                || !requestedGuard.mayUseWakeWindow(session, attempt)) {
            wakeLaunchEvent("wake_launch_ineligible", "LAUNCH_INELIGIBLE", session, attempt, null);
            return WakeLaunch.REFUSED;
        }
        wakeLaunchEvent("wake_launch_requested", "LAUNCH_REQUESTED", session, attempt, null);
        try {
            android.content.Intent intent = new android.content.Intent(this, WakeActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_NO_HISTORY
                            | android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    .putExtra("session", session).putExtra("attempt", attempt);
            // Recheck the captured Service instance and live gates immediately before the
            // framework can process manifest screen-on, rather than relying on an earlier caller.
            if (current.guard != requestedGuard || current.service != this || !nonSecureWakeEligible(this)
                    || !requestedGuard.mayUseWakeWindow(session, attempt)) {
                wakeLaunchEvent("wake_launch_refused", "LAUNCH_REFUSED", session, attempt, null);
                return WakeLaunch.REFUSED;
            }
            startActivity(intent);
            wakeLaunchEvent("wake_launch_returned", "LAUNCH_RETURNED", session, attempt, null);
            return WakeLaunch.OPENED;
        } catch (SecurityException denied) { throw denied; }
        catch (RuntimeException blocked) {
            String type = blocked instanceof SecurityException ? "SECURITY"
                    : blocked instanceof android.content.ActivityNotFoundException ? "ACTIVITY_NOT_FOUND" : "OTHER_RUNTIME";
            wakeLaunchEvent("wake_launch_exception", "LAUNCH_EXCEPTION", session, attempt, type);
            return WakeLaunch.REFUSED;
        }
    }
    private void wakeLaunchEvent(String event, String stage, long session, long attempt, String exceptionType) {
        ProbeStore current = ProbeStore.get(this);
        current.wakeEvent(event, stage, session, attempt,
                current.guard != null && current.guard.ownsWake(session, attempt), exceptionType);
    }
    @Override protected void onServiceConnected() {
        store = ProbeStore.get(this); store.service = this;
        if (store.guard != null && !store.guard.waitingForBootAccessibility()) {
            store.guard.hardPause("无障碍服务重新连接；需停止并重新启用守护");
        }
        store.cancel(VerificationAttempt.Outcome.SERVICE_UNAVAILABLE, "无障碍重新连接，旧验证已撤销");
        store.report("无障碍已连接；默认仅观察，守护需手动启用", "service_connected");
        if (store.guard != null) store.guard.accessibilityConnected();
    }
    boolean startVerification() {
        return startVerification(new VerificationAttempt(VerificationAttempt.Owner.MANUAL, 0, ++manualAttemptId, null));
    }
    boolean startVerification(VerificationAttempt attempt) {
        try { return beginVerification(attempt); }
        catch (SecurityException denied) { securityDenied(); return false; }
    }
    private boolean beginVerification(VerificationAttempt attempt) {
        if (store == null) return refuse(attempt, "拒绝验证：无障碍服务尚未连接");
        if (attempt.owner == VerificationAttempt.Owner.MANUAL && GuardService.hasPendingStart()) {
            return refuse(attempt, "拒绝验证：守护启动请求待处理；请先停止守护");
        }
        if (store.policy.phase() != OneShotPolicy.Phase.IDLE || !store.attempts.claim(attempt, store.guardEnabled)) {
            return refuse(attempt, "拒绝验证：已有验证或守护占用；请先停止守护");
        }
        if (!store.policy.arm(SystemClock.elapsedRealtime(), store.service == this, store.reliableConnected(), unlocked(this))) {
            store.complete(VerificationAttempt.Outcome.REFUSED,
                    "拒绝验证：需要服务已连接、USB可靠连接、亮屏且未锁定", "arm_refused");
            return false;
        }
        readback.reset(); usbModeProbe.reset(); usbModeCancel.begin(attempt);
        clickStartedAt = 0;
        pageWaitReported = false; readbackWaitReported = false;
        long beganAt = SystemClock.elapsedRealtime();
        window.begin(attempt, beganAt);
        store.pageReady.begin(attempt, beganAt);
        store.report((attempt.owner == VerificationAttempt.Owner.GUARD
                ? "守护验证已就绪；从首次打开设置起最多等待10秒，页面不确定时只读等待"
                : "一次验证已就绪（30秒）；等待已确认的共享设置页")
                + (exactHuaweiDevice() ? "；" + SwitchStatePolicy.ADAPTER_NOTE : ""), "verification_armed");
        if (!currentAttempt(attempt)) return false;
        SettingsNavigator.Result opened = requestSettings(attempt, false);
        if (opened == null) return false; // Ownership/safety changed while the request was being prepared.
        if (opened == SettingsNavigator.Result.SECURITY_DENIED) { securityDenied(); return false; }
        if (opened != SettingsNavigator.Result.OPENED) {
            store.cancel("无法打开共享设置。请手动进入：设置 → 移动网络 → 个人热点/网络共享");
            return false;
        }
        if (!currentAttempt(attempt)) return false;
        tick = new Runnable() {
            @Override public void run() {
                if (!currentAttempt(attempt)) return;
                inspect(attempt, store.policy.phase() == OneShotPolicy.Phase.ARMED);
                if (currentAttempt(attempt)) handler.postDelayed(this, 300);
            }
        };
        handler.postDelayed(tick, 250);
        return true;
    }
    private boolean refuse(VerificationAttempt attempt, String message) {
        if (store != null) store.report(message, "arm_refused");
        if (attempt.listener != null) attempt.listener.finished(attempt, VerificationAttempt.Outcome.REFUSED);
        return false;
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (store == null || event.getPackageName() == null || !"com.android.settings".contentEquals(event.getPackageName())) return;
        inspect(store.attempts.current(), store.policy.phase() == OneShotPolicy.Phase.ARMED);
    }
    private void securityDenied() {
        if (store == null) return;
        store.securityDenied();
        store.cancel(VerificationAttempt.Outcome.SECURITY_DENIED, "系统拒绝访问；需重新启用守护");
    }
    private void inspect(VerificationAttempt attempt, boolean mayClick) {
        try { inspectNodes(attempt, mayClick); }
        catch (SecurityException denied) { securityDenied(); }
        catch (RuntimeException staleNode) {
            try { unavailablePage(attempt, "节点暂时失效"); }
            catch (SecurityException denied) { securityDenied(); }
        }
    }
    private void inspectNodes(VerificationAttempt attempt, boolean mayClick) {
        if (store == null) return;
        if (attempt != null && !currentAttempt(attempt)) return;
        if (store.policy.phase() != OneShotPolicy.Phase.IDLE && (!store.reliableConnected() || !unlocked(this))) {
            invalidateCurrentReadback();
            cancelUnsafe(); return;
        }
        // Even passive observation never traverses a locked screen.
        if (!unlocked(this)) { invalidateCurrentReadback(); return; }
        if (attempt != null) {
            if (store.service != this) {
                store.cancel(VerificationAttempt.Outcome.SERVICE_UNAVAILABLE, "无障碍服务已变化，验证已撤销"); return;
            }
            if (!guardNotificationAllowsClick(attempt) || deadlineReached(attempt)) return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) { missingPage(attempt, NavigationRetryBudget.Page.NO_ROOT, "活动页面不可读"); return; }
        AccessibilityNodeInfo target = null;
        try {
            NavigationRetryBudget.Page page = pageCategory(root);
            logPageCategory(attempt, page);
            if (attempt != null && !currentAttempt(attempt)) return;
            if (page != NavigationRetryBudget.Page.CONFIRMED_TETHER) {
                if (handleUsbModeDialog(attempt, page, root)) return;
                missingPage(attempt, page, "未处于已确认的共享设置页"); return;
            }
            TargetDetector.Target observation = TargetDetector.findUsbTarget(root);
            target = observation == null ? null : observation.node;
            if (target == null) {
                unavailablePage(attempt, "共享页USB行/可用开关无法唯一确认", mayClick);
                return;
            }
            SwitchStatePolicy.State state = observe(observation);
            if (attempt != null && (!currentAttempt(attempt) || deadlineReached(attempt))) return;
            if (store.policy.phase() == OneShotPolicy.Phase.VERIFYING) {
                boolean enabled = target.isEnabled(), clickable = target.isClickable();
                long now = SystemClock.elapsedRealtime();
                if (!window.mayReadback(attempt, store.attempts.current(), now)) { finishReadback(attempt, false); return; }
                if (!window.recordReadback(attempt, store.attempts.current(), readback, state,
                        enabled, clickable, now)) {
                    unavailablePage(attempt, "回读开关暂不可用或状态未知", false); return;
                }
                if (readback.confirmedOn(now, currentUsbInterfaceEvidence(now))) {
                    finishReadback(attempt, true);
                }
                return;
            }
            if (!mayClick || store.policy.phase() != OneShotPolicy.Phase.ARMED) return;
            if (attempt.owner == VerificationAttempt.Owner.GUARD && (!target.isEnabled() || !target.isClickable())) {
                unavailablePage(attempt, "共享开关暂不可用"); return;
            }
            if (state == SwitchStatePolicy.State.ON) {
                store.complete(VerificationAttempt.Outcome.ALREADY_ON,
                        "已观察到设置开关开启；无需点击。" + store.usbInterfaces() + "；Windows联网未验证", "already_enabled");
                return;
            }
            if (!SwitchStatePolicy.mayClickOff(state, target.isChecked(), target.isEnabled(), target.isClickable(), store.sharingConflict())) {
                unavailablePage(attempt, "开关状态未知、rawchecked已开、不可点击或共享证据冲突"); return;
            }
        } catch (SecurityException denied) { throw denied; }
        catch (RuntimeException staleNode) {
            unavailablePage(attempt, "节点暂时失效");
            return;
        } finally {
            if (target != null) target.recycle(); root.recycle();
        }
        // Reacquire a fresh root and fresh target immediately before consuming the token.
        root = getRootInActiveWindow(); target = null;
        try {
            if (root == null) {
                if (attempt.owner == VerificationAttempt.Owner.GUARD) {
                    missingPage(attempt, NavigationRetryBudget.Page.NO_ROOT, "点击前页面已变化");
                } else { unavailablePage(attempt, "点击前页面已变化"); }
                return;
            }
            NavigationRetryBudget.Page page = pageCategory(root);
            logPageCategory(attempt, page);
            if (!currentAttempt(attempt)) return;
            if (page != NavigationRetryBudget.Page.CONFIRMED_TETHER) {
                if (attempt.owner == VerificationAttempt.Owner.GUARD) {
                    missingPage(attempt, page, "点击前页面已变化");
                } else { unavailablePage(attempt, "点击前目标已变化或无法确认"); }
                return;
            }
            TargetDetector.Target observation = TargetDetector.findUsbTarget(root);
            target = observation == null ? null : observation.node;
            if (target == null) {
                unavailablePage(attempt, "点击前目标已变化或无法确认"); return;
            }
            SwitchStatePolicy.State state = observe(observation);
            if (!currentAttempt(attempt) || deadlineReached(attempt)) return;
            if (!SwitchStatePolicy.mayClickOff(state, target.isChecked(), target.isEnabled(), target.isClickable(), store.sharingConflict())) {
                unavailablePage(attempt, "点击前状态未知、已开或共享证据冲突"); return;
            }
            if (!guardNotificationAllowsClick(attempt)) return;
            boolean connected = store.reliableConnected(), screenUnlocked = unlocked(this);
            boolean rawChecked = target.isChecked();
            long consumeAt = SystemClock.elapsedRealtime();
            if (!window.mayConsume(attempt, store.attempts.current(), store.policy.phase(), consumeAt)) {
                deadlineReached(attempt); return;
            }
            if (!store.policy.consume(consumeAt, connected, screenUnlocked, true, rawChecked)) {
                store.cancel("点击前安全条件不满足，验证取消"); return;
            }
            store.pageReady.possibleClick(attempt, store.attempts.current());
            // The only USB switch ACTION_CLICK. Its token is independent of dialog cancellation.
            clickStartedAt = SystemClock.elapsedRealtime();
            window.clicked(attempt, store.attempts.current(), clickStartedAt);
            if (!guardNotificationAllowsClick(attempt)) return;
            if (!store.reliableConnected() || !unlocked(this)) { invalidateCurrentReadback(); cancelUnsafe(); return; }
            boolean accepted = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            store.report(accepted ? "点击请求已提交；正在只读回查（不代表成功）" : "系统未接受点击；正在只读回查，不重试", "click_submitted");
        } catch (SecurityException denied) { throw denied; }
        catch (RuntimeException changedOrDenied) {
            unavailablePage(attempt, "系统操作或节点暂不可读；不会重复点击");
        } finally { if (target != null) target.recycle(); if (root != null) root.recycle(); }
    }
    private boolean guardNotificationAllowsClick(VerificationAttempt attempt) {
        if (!currentAttempt(attempt)) return false;
        if (attempt.owner == VerificationAttempt.Owner.MANUAL) return true;
        boolean allowed = store.guard != null
                && store.guard.requireVisibleNotification(attempt.sessionId, attempt.attemptId);
        if (!allowed && store.attempts.current() == attempt) {
            store.cancel(VerificationAttempt.Outcome.STOPPED, "守护授权已失效，验证已撤销；未点击");
        }
        return allowed && currentAttempt(attempt);
    }
    private boolean handleUsbModeDialog(VerificationAttempt attempt, NavigationRetryBudget.Page page, AccessibilityNodeInfo root) {
        if (page != NavigationRetryBudget.Page.SETTINGS_UNCONFIRMED || !usbModeProbeAllowed(attempt)) return false;
        boolean prepare = exactHuaweiDevice() && usbModeCancel.available(attempt, store.attempts.current());
        if (usbModeProbe.exhausted() && !prepare) return false;
        UsbModeDialogProbe.Result first = UsbModeDialogProbe.scan(root);
        if (prepare && first.status == UsbModeDialogProbe.Status.SECURITY_DENIED) throw new SecurityException();
        if (usbModeProbeAllowed(attempt) && usbModeProbe.claim(first)) store.usbModeDialogEvent(attempt, first);
        GuardService expectedGuard = store.guard;
        if (!prepare || !usbModeCancel.consume(attempt, store.attempts.current(), store.policy.phase(),
                store.pageReady.facts(), SystemClock.elapsedRealtime(), cancelLiveSafety(attempt, expectedGuard), first)) return false;
        // The independent preparation token is spent even if fresh acquisition or any later gate fails.
        return cancelKnownUsbModeDialog(attempt, expectedGuard, first.rootWindowId);
    }
    private boolean cancelLiveSafety(VerificationAttempt attempt, GuardService expectedGuard) {
        return expectedGuard != null && store.guard == expectedGuard && exactHuaweiDevice()
                && "NONE".equals(store.guardPauseKind) && usbModeProbeAllowed(attempt);
    }
    private boolean cancelFinalGate(VerificationAttempt attempt, GuardService expectedGuard) {
        return UsbModeDialogCancelPolicy.gates(attempt, store.attempts.current(), store.policy.phase(),
                store.pageReady.facts(), SystemClock.elapsedRealtime(), cancelLiveSafety(attempt, expectedGuard));
    }
    private boolean cancelKnownUsbModeDialog(VerificationAttempt attempt, GuardService expectedGuard, int firstWindowId) {
        AccessibilityNodeInfo freshRoot = null;
        java.util.List<AccessibilityNodeInfo> matches = null;
        boolean actionStarted = false;
        try {
            store.usbModeCancelEvent(attempt, UsbModeDialogCancelPolicy.Stage.PREPARED, UsbModeDialogCancelPolicy.Reason.NONE, null);
            if (!cancelFinalGate(attempt, expectedGuard)) return cancelRefused(attempt, UsbModeDialogCancelPolicy.Reason.SAFETY_GATE);
            freshRoot = getRootInActiveWindow();
            if (freshRoot == null || !settingsNode(freshRoot) || freshRoot.getWindowId() != firstWindowId) {
                return cancelRefused(attempt, UsbModeDialogCancelPolicy.Reason.FRESH_ROOT);
            }
            UsbModeDialogProbe.Result fresh = UsbModeDialogProbe.scan(freshRoot);
            if (fresh.status == UsbModeDialogProbe.Status.SECURITY_DENIED) throw new SecurityException();
            if (!UsbModeDialogCancelPolicy.signature(fresh) || fresh.rootWindowId != firstWindowId) {
                return cancelRefused(attempt, UsbModeDialogCancelPolicy.Reason.FRESH_SIGNATURE);
            }
            matches = freshRoot.findAccessibilityNodeInfosByViewId("android:id/button2");
            if (matches == null || matches.size() != 1 || !exactCancelNode(matches.get(0), fresh.rootWindowId)) {
                return cancelRefused(attempt, UsbModeDialogCancelPolicy.Reason.CANCEL_NODE);
            }
            AccessibilityNodeInfo cancel = matches.get(0);
            // No logging, listener callback, wait, or navigation between the final live gate and the action.
            if (!cancelFinalGate(attempt, expectedGuard)) return cancelRefused(attempt, UsbModeDialogCancelPolicy.Reason.SAFETY_GATE);
            actionStarted = true;
            boolean accepted = cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            store.usbModeCancelEvent(attempt, UsbModeDialogCancelPolicy.Stage.RETURNED, UsbModeDialogCancelPolicy.Reason.NONE, accepted);
        } catch (SecurityException denied) {
            store.usbModeCancelEvent(attempt, actionStarted ? UsbModeDialogCancelPolicy.Stage.THREW : UsbModeDialogCancelPolicy.Stage.REFUSED,
                    UsbModeDialogCancelPolicy.Reason.SECURITY, null);
            throw denied; // Preserve the existing hard-pause/cancellation path.
        } catch (RuntimeException stale) {
            store.usbModeCancelEvent(attempt, actionStarted ? UsbModeDialogCancelPolicy.Stage.THREW : UsbModeDialogCancelPolicy.Stage.REFUSED,
                    UsbModeDialogCancelPolicy.Reason.RUNTIME, null);
        } finally {
            if (matches != null) for (AccessibilityNodeInfo match : matches) {
                if (match != null && match != freshRoot) try { match.recycle(); } catch (RuntimeException ignored) { }
            }
            if (freshRoot != null) try { freshRoot.recycle(); } catch (RuntimeException ignored) { }
        }
        return true; // Never inspect/click the USB switch in the cancellation sample, even after a false return.
    }
    private boolean cancelRefused(VerificationAttempt attempt, UsbModeDialogCancelPolicy.Reason reason) {
        store.usbModeCancelEvent(attempt, UsbModeDialogCancelPolicy.Stage.REFUSED, reason, null); return true;
    }
    private static boolean settingsNode(AccessibilityNodeInfo node) {
        CharSequence name = node.getPackageName(); return name != null && "com.android.settings".contentEquals(name);
    }
    private static boolean exactCancelNode(AccessibilityNodeInfo node, int windowId) {
        // Package and window are checked before reading any content from a lookup result.
        return node != null && settingsNode(node) && node.getWindowId() == windowId
                && "android:id/button2".equals(node.getViewIdResourceName())
                && "android.widget.Button".contentEquals(node.getClassName() == null ? "" : node.getClassName())
                && "取消".contentEquals(node.getText() == null ? "" : node.getText())
                && node.isVisibleToUser() && node.isEnabled() && node.isClickable()
                && (node.getActions() & AccessibilityNodeInfo.ACTION_CLICK) != 0;
    }
    private boolean usbModeProbeAllowed(VerificationAttempt attempt) {
        return currentAttempt(attempt) && attempt.owner == VerificationAttempt.Owner.GUARD
                && store.policy.phase() == OneShotPolicy.Phase.ARMED && store.service == this
                && store.guard != null && store.guardEnabled && "ATTEMPTING".equals(store.guardState)
                && store.guardSessionId == attempt.sessionId && store.guardAttemptId == attempt.attemptId
                && !"HARD".equals(store.guardPauseKind) && store.reliableConnected() && unlocked(this)
                && GuardService.notificationsVisible(this)
                && window.mayConsume(attempt, store.attempts.current(), store.policy.phase(), SystemClock.elapsedRealtime());
    }
    private boolean exactHuaweiDevice() {
        return SwitchStatePolicy.supportedHuawei(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT, Build.DISPLAY);
    }
    private SwitchStatePolicy.State observe(TargetDetector.Target observation) {
        AccessibilityNodeInfo node = observation.node;
        SwitchStatePolicy.State state = SwitchStatePolicy.resolve(node.isCheckable(), node.isChecked(), node.isEnabled(),
                node.isClickable(), exactHuaweiDevice(), observation.exactHuaweiPageAndRow,
                node.getViewIdResourceName(), String.valueOf(node.getClassName()), observation.summary);
        store.observeSwitch(node.isCheckable(), node.isChecked(), node.isEnabled(), String.valueOf(node.getClassName()),
                node.getViewIdResourceName(), observation.summary, state,
                !node.isCheckable() && exactHuaweiDevice() && observation.exactHuaweiPageAndRow);
        return state;
    }
    private void invalidateCurrentReadback() {
        store.invalidateSwitch(); readback.invalidate();
    }
    private void cancelUnsafe() {
        store.cancel(!store.reliableConnected() ? VerificationAttempt.Outcome.WAIT_USB : VerificationAttempt.Outcome.WAIT_UNLOCK,
                "USB状态失效、息屏或锁定，验证已取消；守护等待安全条件恢复");
    }
    private boolean currentAttempt(VerificationAttempt attempt) {
        return store != null && store.policy.phase() != OneShotPolicy.Phase.IDLE
                && window.owns(attempt, store.attempts.current());
    }
    private NavigationRetryBudget.Page pageCategory(AccessibilityNodeInfo root) {
        if (root == null) return NavigationRetryBudget.Page.NO_ROOT;
        // Never traverse another application's nodes or persist its package name/content.
        if (!"com.android.settings".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
            return NavigationRetryBudget.Page.OTHER_WINDOW;
        }
        return TargetDetector.confirmedPage(root) ? NavigationRetryBudget.Page.CONFIRMED_TETHER
                : NavigationRetryBudget.Page.SETTINGS_UNCONFIRMED;
    }
    private void logPageCategory(VerificationAttempt attempt, NavigationRetryBudget.Page page) {
        // Latch every sample before log deduplication, node lookup or subsequent framework calls.
        store.pageReady.sample(attempt, store.attempts.current(), page, SystemClock.elapsedRealtime());
        if (currentAttempt(attempt) && navigation.shouldLogPage(attempt, store.attempts.current(), page)) {
            // Enum-only event names: at most five distinct page categories per attempt.
            store.event("settings_page_" + page.name());
        }
    }
    private boolean guardNavigationAllowed(VerificationAttempt attempt) {
        if (!currentAttempt(attempt) || attempt.owner != VerificationAttempt.Owner.GUARD) return false;
        if (!store.guardEnabled || store.guardSessionId != attempt.sessionId || store.guardAttemptId != attempt.attemptId
                || "HARD".equals(store.guardPauseKind)) {
            store.cancel(VerificationAttempt.Outcome.STOPPED, "守护导航授权已失效；本次请求已撤销"); return false;
        }
        if (store.service != this) {
            store.cancel(VerificationAttempt.Outcome.SERVICE_UNAVAILABLE, "无障碍服务已变化，设置请求已撤销"); return false;
        }
        if (!guardNotificationAllowsClick(attempt)) return false;
        if (!store.reliableConnected() || !unlocked(this)) { invalidateCurrentReadback(); cancelUnsafe(); return false; }
        return currentAttempt(attempt) && !deadlineReached(attempt);
    }
    private void missingPage(VerificationAttempt attempt, NavigationRetryBudget.Page page, String reason) {
        logPageCategory(attempt, page);
        unavailablePage(attempt, reason, false);
        if (!guardNavigationAllowed(attempt)) return;
        if (!navigation.claim(attempt, store.attempts.current(), store.policy.phase(), window,
                page, SystemClock.elapsedRealtime())) return;
        SettingsNavigator.Result retried = requestSettings(attempt, true);
        if (retried == SettingsNavigator.Result.SECURITY_DENIED && currentAttempt(attempt)) securityDenied();
        // OPENED means the request returned, not that the page was verified. UNAVAILABLE spends the budget too.
    }
    private SettingsNavigator.Result requestSettings(VerificationAttempt attempt, boolean retry) {
        if (attempt.owner == VerificationAttempt.Owner.MANUAL) return SettingsNavigator.openTetherResult(this);
        if (!guardNavigationAllowed(attempt)) return null;
        String prefix = retry ? "settings_retry_" : "settings_initial_";
        store.report(retry ? "页面仍不可确认；准备本次唯一的额外设置请求，不延长原截止时间"
                : "准备首次请求共享设置；请求返回不代表页面已确认", prefix + "REQUESTED");
        if (!guardNavigationAllowed(attempt)) return null;
        if (retry) {
            AccessibilityNodeInfo freshRoot = null;
            NavigationRetryBudget.Page freshPage;
            try {
                freshRoot = getRootInActiveWindow();
                freshPage = pageCategory(freshRoot);
                logPageCategory(attempt, freshPage);
            } catch (SecurityException denied) { throw denied; }
            catch (RuntimeException unreadable) {
                freshPage = NavigationRetryBudget.Page.UNREADABLE_ROOT;
                logPageCategory(attempt, freshPage);
            }
            finally { if (freshRoot != null) freshRoot.recycle(); }
            if (!freshPage.retryable()) {
                if (currentAttempt(attempt)) store.report("额外设置请求已跳过：页面已确认或根窗口不可可靠分类；预算已使用",
                        prefix + "SKIPPED");
                return null;
            }
            if (!guardNavigationAllowed(attempt)) return null;
        } else {
            navigation.begin(attempt, SystemClock.elapsedRealtime());
        }
        SettingsNavigator.Result result = SettingsNavigator.openTetherResult(this);
        if (!currentAttempt(attempt)) return null;
        if (result == SettingsNavigator.Result.SECURITY_DENIED) {
            store.report("系统拒绝共享设置请求；验证将撤销", prefix + "SECURITY");
            return currentAttempt(attempt) ? result : null;
        }
        if (!guardNavigationAllowed(attempt)) return null;
        store.report(result == SettingsNavigator.Result.OPENED
                ? "共享设置请求已返回；页面尚未确认，保留原截止时间"
                : "共享设置请求不可用；请求返回不代表页面已确认", prefix
                + (result == SettingsNavigator.Result.OPENED ? "RETURNED" : "UNAVAILABLE"));
        return guardNavigationAllowed(attempt) ? result : null;
    }
    private boolean deadlineReached(VerificationAttempt attempt) {
        if (!currentAttempt(attempt)) return true;
        long now = SystemClock.elapsedRealtime();
        if (store.policy.phase() == OneShotPolicy.Phase.ARMED
                && window.pageExpired(attempt, store.attempts.current(), now)) {
            // Expiry must not recategorize a concurrent safety loss as a page-readiness retry.
            if (store.service != this) {
                store.cancel(VerificationAttempt.Outcome.SERVICE_UNAVAILABLE, "无障碍服务已变化，验证已撤销"); return true;
            }
            if (!guardNotificationAllowsClick(attempt)) return true;
            if (!store.reliableConnected() || !unlocked(this)) {
                invalidateCurrentReadback(); cancelUnsafe(); return true;
            }
            VerificationAttempt.Outcome outcome = store.pageReady.pageTimeout(attempt, store.attempts.current(),
                    store.policy.phase(), now);
            invalidateCurrentReadback();
            if (currentAttempt(attempt)) store.cancel(outcome, outcome == VerificationAttempt.Outcome.PAGE_NOT_READY
                    ? "共享设置页始终未确认，固定10秒已到且未尝试点击；等待守护判定剩余检查预算"
                    : "共享设置页等待已达固定10秒；本轮暂停，USB断开至少3秒接回后再检查");
            return true;
        }
        if (store.policy.phase() == OneShotPolicy.Phase.VERIFYING
                && window.readbackExpired(attempt, store.attempts.current(), now)) {
            finishReadback(attempt, false); return true;
        }
        if (store.policy.expired(now)) {
            invalidateCurrentReadback();
            if (currentAttempt(attempt)) store.cancel(VerificationAttempt.Outcome.UNKNOWN, "一次验证令牌超时；未自动重试");
            return true;
        }
        return false;
    }
    private void unavailablePage(VerificationAttempt attempt, String reason) {
        unavailablePage(attempt, reason, true);
    }
    private void unavailablePage(VerificationAttempt attempt, String reason, boolean cancelManual) {
        if (attempt != null && !currentAttempt(attempt)) return;
        invalidateCurrentReadback();
        if (attempt == null || !currentAttempt(attempt)) return;
        if (!store.reliableConnected() || !unlocked(this)) { cancelUnsafe(); return; }
        if (store.service != this) {
            store.cancel(VerificationAttempt.Outcome.SERVICE_UNAVAILABLE, "无障碍服务已变化，验证已撤销"); return;
        }
        if (attempt.owner == VerificationAttempt.Owner.GUARD) {
            if (!guardNotificationAllowsClick(attempt) || deadlineReached(attempt)) return;
            boolean verifying = store.policy.phase() == OneShotPolicy.Phase.VERIFYING;
            if (verifying ? !readbackWaitReported : !pageWaitReported) {
                if (verifying) readbackWaitReported = true; else pageWaitReported = true;
                store.report(reason + (verifying
                        ? "；只读等待，仍以点击后固定10秒为截止；不会再次点击"
                        : "；只读等待，仍以首次打开设置后固定10秒为截止；未点击"),
                        verifying ? "readback_page_wait" : "settings_page_wait");
            }
        } else if (cancelManual) {
            // Manual verification keeps its 30 s token and existing fail-closed target behavior.
            store.cancel("拒绝操作：" + reason);
        }
    }
    void attemptEnded() {
        if (tick != null) handler.removeCallbacks(tick);
        tick = null; window.end(); navigation.end(); clickStartedAt = 0; readback.invalidate();
        pageWaitReported = false; readbackWaitReported = false; usbModeProbe.reset(); usbModeCancel.end();
        if (store != null) store.pageReady.end(); // Retain terminal facts until the next attempt begins.
    }
    private boolean currentUsbInterfaceEvidence(long now) {
        return ReadbackEvidence.freshPostClickUsbInterface(clickStartedAt, store.tetherAt, now, store.tethered);
    }
    private void finishReadback(VerificationAttempt attempt, boolean confirmed) {
        if (!currentAttempt(attempt)) return;
        long now = SystemClock.elapsedRealtime();
        // Recheck both evidence sources at completion; historical ON never decides the result.
        if (confirmed && (!window.mayReadback(attempt, store.attempts.current(), now)
                || !readback.confirmedOn(now, currentUsbInterfaceEvidence(now)))) return;
        SwitchStatePolicy.State last = readback.finalState(now, 1_000);
        String current = last == SwitchStatePolicy.State.ON ? "最终新鲜回读：设置开关开启"
                : last == SwitchStatePolicy.State.OFF ? "最终新鲜回读：设置开关关闭；未确认开启" : "最终回读未知；未确认开启";
        String interfaces = currentUsbInterfaceEvidence(now) ? store.usbInterfaces() : "本次点击后的新鲜USB共享接口未确认";
        VerificationAttempt.Outcome outcome = confirmed ? VerificationAttempt.Outcome.CONFIRMED_ON
                : last == SwitchStatePolicy.State.ON ? VerificationAttempt.Outcome.SWITCH_ON
                : last == SwitchStatePolicy.State.OFF ? VerificationAttempt.Outcome.OFF : VerificationAttempt.Outcome.UNKNOWN;
        store.complete(outcome, current + (confirmed ? "；连续回读已稳定至少1秒" : "")
                + "；" + interfaces + "；Windows联网未验证", confirmed ? "readback_confirmed" : "readback_finished");
    }
    @Override public void onInterrupt() { stop("无障碍服务中断，验证已取消"); }
    @Override public boolean onUnbind(android.content.Intent intent) { stop("无障碍服务已断开，验证已取消"); return super.onUnbind(intent); }
    @Override public void onDestroy() { stop("无障碍服务已销毁，验证已取消"); super.onDestroy(); }
    private void stop(String reason) {
        if (tick != null) handler.removeCallbacks(tick);
        if (store != null) {
            invalidateCurrentReadback();
            if (store.guard != null) store.guard.hardPause("无障碍服务中断；恢复权限后需停止并重新启用守护");
            store.cancel(VerificationAttempt.Outcome.SERVICE_UNAVAILABLE, reason);
            if (store.service == this) store.service = null;
            store.report(reason, "service_disconnected");
        }
    }
}
