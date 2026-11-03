package com.autousb.helper;

/** One extra navigation per GUARD attempt, shared by both phases; claiming never renews a deadline. */
final class NavigationRetryBudget {
    static final long MIN_RETRY_DELAY_MS = 2_000;
    enum Page {
        NO_ROOT, OTHER_WINDOW, SETTINGS_UNCONFIRMED, CONFIRMED_TETHER, UNREADABLE_ROOT;
        boolean retryable() {
            return this == NO_ROOT || this == OTHER_WINDOW || this == SETTINGS_UNCONFIRMED;
        }
    }
    private VerificationAttempt owner;
    private long initialRequestedAt;
    private boolean spent;
    private int reportedPages;

    void begin(VerificationAttempt attempt, long requestedAt) {
        owner = attempt; initialRequestedAt = requestedAt; spent = false; reportedPages = 0;
    }
    private boolean owns(VerificationAttempt expected, VerificationAttempt current) {
        return expected != null && expected == owner && expected == current
                && expected.owner == VerificationAttempt.Owner.GUARD;
    }
    boolean claim(VerificationAttempt expected, VerificationAttempt current, OneShotPolicy.Phase phase,
                  VerificationWindow window, Page page, long now) {
        if (!owns(expected, current) || spent || page == null || !page.retryable()
                || now < initialRequestedAt || now - initialRequestedAt < MIN_RETRY_DELAY_MS) return false;
        boolean withinDeadline = phase == OneShotPolicy.Phase.ARMED
                ? window.mayConsume(expected, current, phase, now)
                : phase == OneShotPolicy.Phase.VERIFYING && window.mayReadback(expected, current, now);
        if (!withinDeadline) return false;
        // Spend before any report, framework call, exception, or reentrant callback.
        spent = true; return true;
    }
    boolean shouldLogPage(VerificationAttempt expected, VerificationAttempt current, Page page) {
        if (!owns(expected, current) || page == null) return false;
        int bit = 1 << page.ordinal();
        if ((reportedPages & bit) != 0) return false;
        reportedPages |= bit; return true;
    }
    void end() { owner = null; initialRequestedAt = 0; spent = false; reportedPages = 0; }
}
