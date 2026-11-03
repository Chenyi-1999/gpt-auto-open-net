package com.autousb.helper;

/** Process-only evidence from real USB broadcasts, never from sticky reads or refreshes. */
final class UsbReconnectEvidence {
    // Filters brief USB reconfiguration; this duration is not proof of a physical unplug.
    static final long MIN_DISCONNECT_MS = 3_000;
    enum Connection { YES, NO, UNKNOWN }
    private long disconnectedAt = -1;
    private long generation, cycleStartedAt, cycleCompletedAt;

    void observe(Connection connected, long now, boolean realDynamicBroadcast) {
        if (!realDynamicBroadcast) {
            // Readbacks cannot prove a cycle, but YES/UNKNOWN disprove continuous observed NO.
            if (connected != Connection.NO) disconnectedAt = -1;
            return;
        }
        if (connected == Connection.UNKNOWN) { disconnectedAt = -1; return; }
        if (connected == Connection.NO) {
            if (disconnectedAt < 0) disconnectedAt = now;
            return;
        }
        if (disconnectedAt >= 0 && now >= disconnectedAt
                && now - disconnectedAt >= MIN_DISCONNECT_MS) {
            generation++; cycleStartedAt = disconnectedAt; cycleCompletedAt = now;
        }
        disconnectedAt = -1;
    }
    long generation() { return generation; }
    long cycleStartedAt() { return cycleStartedAt; }
    long cycleCompletedAt() { return cycleCompletedAt; }
}
