package com.autousb.helper;

/** Process-only, consume-once capability. Intent extras carry an id, never authority. */
final class StartAuthorization {
    enum Origin { MANUAL, BOOT }
    private long sequence, pending;
    private Origin origin;
    StartAuthorization(long processSeed) { sequence = processSeed; }
    long issue(Origin requested) {
        if (pending != 0) return 0;
        pending = ++sequence; origin = requested; return pending;
    }
    boolean consume(long id, Origin requested) {
        if (id == 0 || id != pending || requested != origin) return false;
        revoke(); return true;
    }
    boolean pending() { return pending != 0; }
    boolean pendingBoot() { return pending != 0 && origin == Origin.BOOT; }
    void revokeBoot() { if (pendingBoot()) revoke(); }
    void revoke() { pending = 0; origin = null; }
}
