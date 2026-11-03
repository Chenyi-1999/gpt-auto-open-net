package com.autousb.helper;

/** Independent, attempt-owned preparation token. Never consumes or renews the USB token/window. */
final class UsbModeDialogCancelPolicy {
    enum Stage { PREPARED, RETURNED, THREW, REFUSED }
    enum Reason { NONE, FRESH_ROOT, FRESH_SIGNATURE, CANCEL_NODE, SAFETY_GATE, SECURITY, RUNTIME }
    private VerificationAttempt owner;
    private boolean consumed;
    void begin(VerificationAttempt attempt) { owner = attempt; consumed = false; }
    void end() { owner = null; consumed = false; }
    boolean available(VerificationAttempt expected, VerificationAttempt current) {
        return expected != null && expected == owner && expected == current && !consumed;
    }
    static boolean gates(VerificationAttempt expected, VerificationAttempt current, OneShotPolicy.Phase phase,
                         PageReadyEvidence.Facts page, long now, boolean exactDeviceAndLiveSafety) {
        return exactDeviceAndLiveSafety && expected != null && expected == current
                && expected.owner == VerificationAttempt.Owner.GUARD && phase == OneShotPolicy.Phase.ARMED
                && expected.sessionId > 0 && expected.attemptId > 0
                && page != null && page.active && page.sessionId == expected.sessionId && page.attemptId == expected.attemptId
                && page.lastPage == NavigationRetryBudget.Page.SETTINGS_UNCONFIRMED
                && !page.everConfirmed && !page.possibleClick && page.pageDeadline > 0 && now >= 0 && now < page.pageDeadline;
    }
    /** Consume before fresh acquisition too: a refused/stale/throwing preparation never retries this attempt. */
    boolean consume(VerificationAttempt expected, VerificationAttempt current, OneShotPolicy.Phase phase,
                    PageReadyEvidence.Facts page, long now, boolean exactDeviceAndLiveSafety, UsbModeDialogProbe.Result first) {
        if (!available(expected, current) || !gates(expected, current, phase, page, now, exactDeviceAndLiveSafety)
                || !signature(first)) return false;
        consumed = true; return true;
    }
    static boolean signature(UsbModeDialogProbe.Result result) {
        if (result == null || !result.complete() || result.rootWindowId < 0 || !"FRAME_LAYOUT".equals(result.rootClass)
                || result.titles.size() != 1 || result.cancels.size() != 1) return false;
        for (int i = 0; i < 5; i++) if (result.counts[i] != 1 || result.visibleTextCounts[i] != 1) return false;
        UsbModeDialogProbe.Candidate title = result.titles.get(0), cancel = result.cancels.get(0);
        return title.windowId == result.rootWindowId && "android:id/alertTitle".equals(title.resourceId)
                && "android.widget.TextView".equals(title.nodeClass) && title.visible && title.enabled
                && !title.clickable && !title.actionClick && cancel.windowId == result.rootWindowId
                && "android:id/button2".equals(cancel.resourceId) && "android.widget.Button".equals(cancel.nodeClass)
                && cancel.visible && cancel.enabled && cancel.clickable && cancel.actionClick;
    }
}
