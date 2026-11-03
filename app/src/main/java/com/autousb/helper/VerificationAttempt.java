package com.autousb.helper;

/** Main-thread ownership: detach before delivering exactly one terminal callback. */
final class VerificationAttempt {
    enum Owner { MANUAL, GUARD }
    enum Outcome { CONFIRMED_ON, OBSERVED_ON, SWITCH_ON, OFF, ALREADY_ON, UNKNOWN, PAGE_NOT_READY, REFUSED,
        WAIT_USB, WAIT_UNLOCK, SERVICE_UNAVAILABLE, SECURITY_DENIED, STOPPED }
    interface Listener { void finished(VerificationAttempt attempt, Outcome outcome); }
    final Owner owner;
    final long sessionId, attemptId;
    final Listener listener;
    VerificationAttempt(Owner owner, long sessionId, long attemptId, Listener listener) {
        this.owner = owner; this.sessionId = sessionId; this.attemptId = attemptId; this.listener = listener;
    }
    static final class Slot {
        private VerificationAttempt current;
        VerificationAttempt current() { return current; }
        boolean claim(VerificationAttempt attempt, boolean guardEnabled) {
            if (current != null || (guardEnabled && attempt.owner == Owner.MANUAL)) return false;
            current = attempt; return true;
        }
        boolean release(VerificationAttempt attempt) {
            if (attempt == null || current != attempt) return false;
            current = null; return true;
        }
    }
}
