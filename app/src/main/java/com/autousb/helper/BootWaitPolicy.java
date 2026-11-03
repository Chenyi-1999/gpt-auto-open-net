package com.autousb.helper;

/** Isolated from GuardPolicy: no attempts, wake requests, USB evidence or reconnect recovery. */
final class BootWaitPolicy {
    static final long TIMEOUT_MS = 120_000;
    enum Result { INACTIVE, WAITING, READY, TIMED_OUT, REVOKED, NOTIFICATION_OFF, CONFLICT }
    private boolean waiting;
    private long deadline;
    void begin(long now) {
        if (waiting) return; // Duplicate delivery cannot extend the original deadline.
        waiting = true; deadline = now + TIMEOUT_MS;
    }
    boolean waiting() { return waiting; }
    long deadline() { return deadline; }
    void cancel() { waiting = false; }
    Result evaluate(long now, boolean optedIn, boolean notification, boolean conflict, boolean connected) {
        if (!waiting) return Result.INACTIVE;
        Result result = !optedIn ? Result.REVOKED : !notification ? Result.NOTIFICATION_OFF
                : conflict ? Result.CONFLICT : now >= deadline ? Result.TIMED_OUT
                : connected ? Result.READY : Result.WAITING;
        if (result != Result.WAITING) waiting = false;
        return result;
    }
    static int normalizePoll(int seconds) { return seconds == 30 || seconds == 120 ? seconds : 60; }
    static boolean mayClaimBoot(long current, long claimed) {
        return current >= 0 && claimed >= -1 && current > claimed;
    }
}
