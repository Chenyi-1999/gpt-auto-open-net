package com.autousb.helper;

/** Observation only: none of these facts authorizes a wake, verification, or click. */
final class WakeDiagnostics {
    enum Boundary {
        CREATED, RESUMED, PAUSED, DESTROYED, FOCUSED, UNFOCUSED;
        String event() { return "wake_activity_" + name().toLowerCase(java.util.Locale.ROOT); }
    }
    /** At most six lifecycle rows per Activity instance, never a row per polling callback. */
    static final class Lifecycle {
        private int seen;
        boolean first(Boundary boundary) {
            int bit = 1 << boundary.ordinal();
            if ((seen & bit) != 0) return false;
            seen |= bit;
            return true;
        }
    }
    enum TimeoutFact {
        CREATED_NOT_OBSERVED, RESUMED_NOT_OBSERVED, RESUMED_GATES_UNKNOWN,
        RESUMED_GATES_NOT_MET, RESUMED_GATES_MET;
        String message() {
            switch (this) {
                case CREATED_NOT_OBSERVED: return "未观察到亮屏页面启动";
                case RESUMED_NOT_OBSERVED: return "页面已启动，未观察到进入前台";
                case RESUMED_GATES_UNKNOWN: return "页面已进入前台，当前屏幕或锁定状态未知";
                case RESUMED_GATES_NOT_MET: return "页面已进入前台，当前屏幕或锁定条件不满足";
                default: return "页面已进入前台，当前屏幕和锁定条件满足，但尚未进入验证";
            }
        }
    }
    long sessionId, attemptId;
    String stage = "NONE", timeoutFact = "";
    boolean created, resumed;
    void clear() { begin(0, 0); stage = "NONE"; }
    void begin(long session, long attempt) {
        sessionId = session; attemptId = attempt; stage = "STARTED";
        timeoutFact = ""; created = false; resumed = false;
    }
    boolean observe(long session, long attempt, String boundary) {
        if (attempt == 0 || session != sessionId || attempt != attemptId) return false;
        stage = boundary;
        if (Boundary.CREATED.name().equals(boundary)) created = true;
        if (Boundary.RESUMED.name().equals(boundary)) resumed = true;
        return true;
    }
    TimeoutFact timedOut(Boolean interactive, Boolean deviceLocked, Boolean keyguardLocked) {
        TimeoutFact fact;
        if (!created) fact = TimeoutFact.CREATED_NOT_OBSERVED;
        else if (!resumed) fact = TimeoutFact.RESUMED_NOT_OBSERVED;
        else if (interactive == null || deviceLocked == null || keyguardLocked == null) fact = TimeoutFact.RESUMED_GATES_UNKNOWN;
        else if (!interactive || deviceLocked || keyguardLocked) fact = TimeoutFact.RESUMED_GATES_NOT_MET;
        else fact = TimeoutFact.RESUMED_GATES_MET;
        timeoutFact = fact.name(); stage = "TIMED_OUT";
        return fact;
    }
}
