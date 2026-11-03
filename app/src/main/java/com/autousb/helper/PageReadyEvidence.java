package com.autousb.helper;

/** Attempt-scoped monotonic facts. Cleanup revokes writes/qualification but retains terminal diagnosis. */
final class PageReadyEvidence {
    static final class Facts {
        final long sessionId, attemptId, pageDeadline, sampleAt;
        final NavigationRetryBudget.Page lastPage;
        final boolean everConfirmed, possibleClick, active;
        Facts(long sessionId, long attemptId, long deadline, NavigationRetryBudget.Page page, long sampleAt,
              boolean confirmed, boolean clicked, boolean active) {
            this.sessionId = sessionId; this.attemptId = attemptId;
            pageDeadline = deadline; lastPage = page; this.sampleAt = sampleAt;
            everConfirmed = confirmed; possibleClick = clicked; this.active = active;
        }
    }
    private VerificationAttempt owner;
    private long sessionId, attemptId, deadline, sampleAt;
    private NavigationRetryBudget.Page lastPage;
    private boolean everConfirmed, possibleClick, active;
    void begin(VerificationAttempt attempt, long now) {
        owner = attempt; deadline = now + VerificationWindow.PAGE_WAIT_MS;
        sessionId = attempt.sessionId; attemptId = attempt.attemptId;
        lastPage = null; sampleAt = 0; everConfirmed = false; possibleClick = false; active = true;
    }
    private boolean owns(VerificationAttempt expected, VerificationAttempt current) {
        return active && expected != null && expected == owner && expected == current;
    }
    void sample(VerificationAttempt expected, VerificationAttempt current, NavigationRetryBudget.Page page, long now) {
        if (!owns(expected, current) || page == null) return;
        lastPage = page; sampleAt = now;
        if (page == NavigationRetryBudget.Page.CONFIRMED_TETHER) everConfirmed = true;
    }
    /** Call immediately after token consumption, before framework action or any further callback. */
    void possibleClick(VerificationAttempt expected, VerificationAttempt current) {
        if (owns(expected, current)) possibleClick = true;
    }
    VerificationAttempt.Outcome pageTimeout(VerificationAttempt expected, VerificationAttempt current,
                                            OneShotPolicy.Phase phase, long now) {
        return owns(expected, current) && expected.owner == VerificationAttempt.Owner.GUARD
                && phase == OneShotPolicy.Phase.ARMED && now >= deadline && !everConfirmed && !possibleClick
                ? VerificationAttempt.Outcome.PAGE_NOT_READY : VerificationAttempt.Outcome.UNKNOWN;
    }
    Facts facts() { return new Facts(sessionId, attemptId, deadline, lastPage, sampleAt, everConfirmed, possibleClick, active); }
    void end() { active = false; owner = null; } // Do not retain an attempt listener or stopped Service.
}
