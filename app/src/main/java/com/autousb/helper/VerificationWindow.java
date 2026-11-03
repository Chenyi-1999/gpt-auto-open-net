package com.autousb.helper;

/** Main-thread deadlines: only the claimed attempt may act; gaps never renew either window. */
final class VerificationWindow {
    static final long PAGE_WAIT_MS = 10_000;
    static final long READBACK_MS = 10_000;
    private VerificationAttempt owner;
    private long pageUntil, readUntil;

    void begin(VerificationAttempt attempt, long navigationAt) {
        owner = attempt;
        pageUntil = navigationAt + PAGE_WAIT_MS;
        readUntil = 0;
    }
    boolean owns(VerificationAttempt expected, VerificationAttempt current) {
        return expected != null && expected == owner && expected == current;
    }
    boolean pageExpired(VerificationAttempt expected, VerificationAttempt current, long now) {
        return owns(expected, current) && expected.owner == VerificationAttempt.Owner.GUARD && now >= pageUntil;
    }
    boolean mayConsume(VerificationAttempt expected, VerificationAttempt current, OneShotPolicy.Phase phase, long now) {
        return owns(expected, current) && phase == OneShotPolicy.Phase.ARMED
                && !pageExpired(expected, current, now);
    }
    void clicked(VerificationAttempt expected, VerificationAttempt current, long now) {
        if (owns(expected, current) && readUntil == 0) readUntil = now + READBACK_MS;
    }
    boolean readbackExpired(VerificationAttempt expected, VerificationAttempt current, long now) {
        return owns(expected, current) && readUntil > 0
                && (expected.owner == VerificationAttempt.Owner.GUARD ? now >= readUntil : now > readUntil);
    }
    boolean mayReadback(VerificationAttempt expected, VerificationAttempt current, long now) {
        return owns(expected, current) && readUntil > 0
                && (expected.owner == VerificationAttempt.Owner.GUARD ? now < readUntil : now <= readUntil);
    }
    boolean recordReadback(VerificationAttempt expected, VerificationAttempt current, ReadbackEvidence evidence,
                           SwitchStatePolicy.State state, boolean enabled, boolean clickable, long now) {
        if (!mayReadback(expected, current, now)) return false;
        if (expected.owner == VerificationAttempt.Owner.GUARD
                && (!enabled || !clickable || state == SwitchStatePolicy.State.UNKNOWN)) {
            evidence.invalidate(); return false;
        }
        evidence.observe(state, now); return true;
    }
    void end() { owner = null; pageUntil = 0; readUntil = 0; }
}
