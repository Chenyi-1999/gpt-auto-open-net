package com.autousb.helper;

/** Pure policy. The Android adapter supplies current gates; this class never clicks or opens UI. */
final class GuardPolicy {
    static final int MAX_ATTEMPTS = 3;
    static final long RETRY_DELAY_MS = 10_000;
    static final long INPUT_MAX_AGE_MS = 2_000;
    static final long WAKE_TIMEOUT_MS = 10_000;
    enum State { STOPPED, WATCHING, WAIT_USB, WAIT_UNLOCK, WAKING, ATTEMPTING, COOLDOWN, PAUSED }
    enum PauseKind { NONE, ROUND, HARD }
    enum Action { NONE, START_WAKE, START_ATTEMPT, CANCEL_WAIT_USB, CANCEL_WAIT_UNLOCK, CANCEL_PAUSE }
    static final class Input {
        final boolean service, connected, unlocked, activeInterface, sharingPresent, absencePlausible;
        final boolean wakeEligible;
        final long cycle, cycleStartedAt, cycleCompletedAt;
        Input(boolean service, boolean connected, boolean unlocked, boolean activeInterface,
              boolean sharingPresent, boolean absencePlausible) {
            this(service, connected, unlocked, activeInterface, sharingPresent, absencePlausible, false);
        }
        Input(boolean service, boolean connected, boolean unlocked, boolean activeInterface,
              boolean sharingPresent, boolean absencePlausible, boolean wakeEligible) {
            this(service, connected, unlocked, activeInterface, sharingPresent, absencePlausible,
                    wakeEligible, 0, 0, 0);
        }
        Input(boolean service, boolean connected, boolean unlocked, boolean activeInterface,
              boolean sharingPresent, boolean absencePlausible, boolean wakeEligible,
              long cycle, long cycleStartedAt, long cycleCompletedAt) {
            this.cycle = cycle; this.cycleStartedAt = cycleStartedAt; this.cycleCompletedAt = cycleCompletedAt;
            this.service = service; this.connected = connected; this.unlocked = unlocked;
            this.activeInterface = activeInterface; this.sharingPresent = sharingPresent;
            this.absencePlausible = absencePlausible;
            this.wakeEligible = wakeEligible;
        }
    }
    private State state = State.STOPPED;
    private long sessionId, nextId, attemptId, retryAt, wakeUntil;
    private int attempts;
    private boolean allowPageReadyRetry;
    private PauseKind pauseKind = PauseKind.NONE;
    private long pausedAt, consumedCycle;
    PauseKind pauseKind() { return pauseKind; }
    long pausedAt() { return pausedAt; }
    long consumedCycle() { return consumedCycle; }
    State state() { return state; }
    boolean enabled() { return state != State.STOPPED; }
    long sessionId() { return sessionId; }
    long attemptId() { return attemptId; }
    int attempts() { return attempts; }
    long retryAt() { return retryAt; }
    long wakeUntil() { return wakeUntil; }
    boolean pageReadyRetryAllowed() { return allowPageReadyRetry; }
    boolean ownsWake(long session, long attempt, long now) {
        return state == State.WAKING && attempt != 0 && sessionId == session && attemptId == attempt
                && now >= wakeUntil - WAKE_TIMEOUT_MS && now < wakeUntil;
    }
    void start() { start(sessionId + 1); }
    void start(long session) { start(session, false); }
    void start(long session, boolean allowPageReadyRetry) {
        this.allowPageReadyRetry = allowPageReadyRetry;
        sessionId = session; attemptId = 0; attempts = 0; retryAt = 0; wakeUntil = 0;
        pauseKind = PauseKind.NONE; pausedAt = 0; consumedCycle = 0; state = State.WATCHING;
    }
    void stop() { sessionId++; attemptId = 0; allowPageReadyRetry = false; pauseKind = PauseKind.NONE; state = State.STOPPED; }
    void pauseRound(long now) {
        if (!enabled() || pauseKind == PauseKind.HARD) return;
        attemptId = 0; state = State.PAUSED; pauseKind = PauseKind.ROUND; pausedAt = now;
        retryAt = Math.max(retryAt, now + RETRY_DELAY_MS);
    }
    void pauseHard(long now) {
        if (!enabled()) return;
        attemptId = 0; state = State.PAUSED; pauseKind = PauseKind.HARD; pausedAt = now;
    }
    Action evaluate(long now, Input input) {
        if (state == State.STOPPED) return Action.NONE;
        if (!input.service) {
            boolean running = attemptId != 0;
            pauseHard(now); return running ? Action.CANCEL_PAUSE : Action.NONE;
        }
        if (state == State.PAUSED) {
            if (pauseKind == PauseKind.HARD) return Action.NONE;
            if (input.activeInterface && input.connected) {
                attempts = 0; pauseKind = PauseKind.NONE; state = State.WATCHING;
                return Action.NONE;
            }
            // Only a complete new cycle that STARTED after this pause can grant a new budget.
            if (!input.connected || input.cycle <= consumedCycle || input.cycleStartedAt <= pausedAt
                    || input.cycleCompletedAt < input.cycleStartedAt || input.cycleCompletedAt > now) return Action.NONE;
            consumedCycle = input.cycle; attemptId = 0; attempts = 0;
            pauseKind = PauseKind.NONE; state = State.WATCHING;
            // retryAt deliberately survives recovery: a new cable cycle cannot bypass cooldown.
        }
        if (attemptId == 0 && input.connected && input.cycle > consumedCycle
                && input.cycleCompletedAt <= now && input.cycleCompletedAt > input.cycleStartedAt) {
            consumedCycle = input.cycle; attempts = 0;
        }
        if (!input.connected) return waitOrPause(State.WAIT_USB, Action.CANCEL_WAIT_USB);
        if (attemptId != 0) {
            if (state == State.WAKING) {
                if (now >= wakeUntil) { pauseRound(now); return Action.CANCEL_PAUSE; }
                if (input.activeInterface || input.sharingPresent) {
                    attemptId = 0; state = State.WATCHING;
                    if (input.activeInterface) attempts = 0;
                    return Action.NONE;
                }
                if (input.unlocked) { state = State.ATTEMPTING; return Action.START_ATTEMPT; }
                return Action.NONE;
            }
            if (!input.unlocked) return waitOrPause(State.WAIT_UNLOCK, Action.CANCEL_WAIT_UNLOCK);
            state = State.ATTEMPTING; return Action.NONE;
        }
        if (input.activeInterface) {
            attempts = 0; state = State.WATCHING; return Action.NONE;
        }
        // A positive RNDIS/NCM flag prevents action, but does not reset the incident budget.
        if (input.sharingPresent) { state = State.WATCHING; return Action.NONE; }
        if (now < retryAt) { state = State.COOLDOWN; return Action.NONE; }
        if (!input.absencePlausible) { state = input.unlocked ? State.WATCHING : State.WAIT_UNLOCK; return Action.NONE; }
        if (attempts >= MAX_ATTEMPTS) { pauseRound(now); return Action.NONE; }
        if (!input.unlocked && !input.wakeEligible) { state = State.WAIT_UNLOCK; return Action.NONE; }
        attempts++; attemptId = ++nextId;
        if (!input.unlocked) {
            wakeUntil = now + WAKE_TIMEOUT_MS; state = State.WAKING; return Action.START_WAKE;
        }
        state = State.ATTEMPTING;
        return Action.START_ATTEMPT;
    }
    private Action waitOrPause(State next, Action cancel) {
        boolean running = attemptId != 0;
        attemptId = 0; state = next;
        return running ? cancel : Action.NONE;
    }
    boolean finished(long session, long attempt, VerificationAttempt.Outcome outcome, long now) {
        if (!enabled() || state == State.PAUSED || session != sessionId || attempt == 0 || attempt != attemptId) return false;
        attemptId = 0;
        switch (outcome) {
            case OBSERVED_ON:
            case CONFIRMED_ON: attempts = 0; retryAt = 0; state = State.WATCHING; break;
            case OFF:
                retryAt = now + RETRY_DELAY_MS;
                if (attempts >= MAX_ATTEMPTS) pauseRound(now); else state = State.COOLDOWN; break;
            case PAGE_NOT_READY:
                if (!allowPageReadyRetry) { pauseRound(now); break; }
                retryAt = now + RETRY_DELAY_MS;
                if (attempts >= MAX_ATTEMPTS) pauseRound(now); else state = State.COOLDOWN; break;
            case WAIT_USB: state = State.WAIT_USB; break;
            case WAIT_UNLOCK: state = State.WAIT_UNLOCK; break;
            case SERVICE_UNAVAILABLE:
            case SECURITY_DENIED: pauseHard(now); break;
            case STOPPED: stop(); break;
            default: pauseRound(now); break;
        }
        return true;
    }
    static long pollMillis(int seconds) { return seconds == 30 || seconds == 120 ? seconds * 1_000L : 60_000; }
    static boolean fresh(long at, long now) { return at > 0 && now >= at && now - at <= INPUT_MAX_AGE_MS; }
    static boolean mayWake(boolean interactive, boolean secure, boolean deviceLocked) {
        return !interactive && !secure && !deviceLocked;
    }
}
