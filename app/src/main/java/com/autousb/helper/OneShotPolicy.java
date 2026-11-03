package com.autousb.helper;

/** Main-thread owner. A consumed token can only be read back, never reused. */
public final class OneShotPolicy {
    public enum Phase { IDLE, ARMED, VERIFYING }
    private Phase phase = Phase.IDLE;
    private long expiresAt;
    public Phase phase() { return phase; }
    public boolean arm(long now, boolean service, boolean connected, boolean unlocked) {
        if (phase != Phase.IDLE) return false;
        if (!service || !connected || !unlocked) return false;
        expiresAt = now + 30_000;
        phase = Phase.ARMED;
        return true;
    }
    public boolean consume(long now, boolean connected, boolean unlocked,
                           boolean uniqueTarget, boolean alreadyChecked) {
        if (!connected || !unlocked || now >= expiresAt) { cancel(); return false; }
        if (phase != Phase.ARMED || !uniqueTarget || alreadyChecked) return false;
        phase = Phase.VERIFYING;
        return true;
    }
    public boolean expired(long now) { return phase != Phase.IDLE && now >= expiresAt; }
    public void cancel() { phase = Phase.IDLE; expiresAt = 0; }
}
