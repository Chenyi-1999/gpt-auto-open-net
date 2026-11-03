package com.autousb.helper;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.autousb.helper.GuardPolicy.Action.*;
import static com.autousb.helper.GuardPolicy.State.*;
import static com.autousb.helper.VerificationAttempt.Outcome.CONFIRMED_ON;
import static com.autousb.helper.VerificationAttempt.Outcome.UNKNOWN;
import static com.autousb.helper.VerificationAttempt.Outcome.REFUSED;
import static com.autousb.helper.VerificationAttempt.Outcome.SWITCH_ON;
import static com.autousb.helper.VerificationAttempt.Outcome.ALREADY_ON;
import static com.autousb.helper.VerificationAttempt.Outcome.OFF;
import static com.autousb.helper.VerificationAttempt.Outcome.SERVICE_UNAVAILABLE;
import static com.autousb.helper.VerificationAttempt.Outcome.SECURITY_DENIED;
import static com.autousb.helper.UsbReconnectEvidence.Connection.NO;
import static com.autousb.helper.UsbReconnectEvidence.Connection.YES;

public class GuardReconnectTest {
    private final UsbReconnectEvidence cycles = new UsbReconnectEvidence();
    private GuardPolicy.Input input(boolean service, boolean connected, boolean active, boolean awake) {
        return new GuardPolicy.Input(service, connected, awake, active, active, !active, !awake,
                cycles.generation(), cycles.cycleStartedAt(), cycles.cycleCompletedAt());
    }
    private GuardPolicy.Input absent() { return input(true, true, false, true); }
    private GuardPolicy running() { GuardPolicy p = new GuardPolicy(); p.start(); return p; }
    private void cycle(long disconnectedAt) {
        cycles.observe(NO, disconnectedAt, true);
        cycles.observe(YES, disconnectedAt + 3_000, true);
    }
    private void finish(GuardPolicy p, VerificationAttempt.Outcome outcome, long now) {
        assertTrue(p.finished(p.sessionId(), p.attemptId(), outcome, now));
    }
    @Test public void fiveSuccessiveCyclesEachRecoverAndAlreadyActiveNeverStartsAttempt() {
        GuardPolicy p = running();
        for (int round = 0; round < 5; round++) {
            long now = 1_000 + round * 20_000;
            assertEquals(NONE, p.evaluate(now, input(true, false, false, true)));
            cycle(now);
            assertEquals(START_ATTEMPT, p.evaluate(now + 3_000, absent()));
            assertEquals(1, p.attempts());
            finish(p, CONFIRMED_ON, now + 4_000);
            assertEquals(NONE, p.evaluate(now + 4_001, input(true, true, true, false)));
            assertEquals(0, p.attempts());
            assertEquals(WATCHING, p.state());
        }
        assertEquals(5, cycles.generation());
    }
    @Test public void unknownAndUnreadableRoundRequireNewCycleAndKeepTenSecondCooldown() {
        for (VerificationAttempt.Outcome outcome : new VerificationAttempt.Outcome[] { UNKNOWN, REFUSED, SWITCH_ON, ALREADY_ON }) {
            GuardPolicy p = running();
            assertEquals(START_ATTEMPT, p.evaluate(100, absent()));
            long old = p.attemptId();
            finish(p, outcome, 200);
            assertEquals(GuardPolicy.PauseKind.ROUND, p.pauseKind());
            assertEquals(NONE, p.evaluate(300, absent()));
            cycle(400);
            assertEquals(NONE, p.evaluate(3_400, absent()));
            assertEquals(COOLDOWN, p.state());
            assertEquals(0, p.attempts());
            assertFalse(p.finished(p.sessionId(), old, CONFIRMED_ON, 4_000));
            assertEquals(NONE, p.evaluate(10_199, absent()));
            assertEquals(START_ATTEMPT, p.evaluate(10_200, absent()));
            assertNotEquals(old, p.attemptId());
        }
    }
    @Test public void wakeTimeoutNeedsNewCycleAndRejectsOldWakeCallbacks() {
        GuardPolicy p = running();
        assertEquals(START_WAKE, p.evaluate(100, input(true, true, false, false)));
        long old = p.attemptId(), session = p.sessionId();
        assertEquals(CANCEL_PAUSE, p.evaluate(10_100, input(true, true, false, false)));
        cycle(10_101);
        assertEquals(NONE, p.evaluate(13_101, input(true, true, false, false)));
        assertFalse(p.ownsWake(session, old, 13_101));
        assertFalse(p.finished(session, old, CONFIRMED_ON, 14_000));
        assertEquals(START_WAKE, p.evaluate(20_100, input(true, true, false, false)));
        assertFalse(p.ownsWake(session, old, 20_100));
        assertEquals(1, p.attempts());
    }
    @Test public void thirdOffRequiresCycleThenRestartsBudgetAfterCooldown() {
        GuardPolicy p = running();
        for (int attempt = 0; attempt < 3; attempt++) {
            long now = 100 + attempt * 20_000;
            assertEquals(START_ATTEMPT, p.evaluate(now, absent()));
            finish(p, OFF, now + 100);
        }
        assertEquals(PAUSED, p.state());
        assertEquals(NONE, p.evaluate(80_000, absent()));
        cycle(80_001);
        assertEquals(START_ATTEMPT, p.evaluate(83_001, absent()));
        assertEquals(1, p.attempts());
    }
    @Test public void disconnectBeginningBeforePauseCannotGrantAnotherRound() {
        GuardPolicy p = running(); p.evaluate(100, absent());
        cycles.observe(NO, 150, true);
        finish(p, UNKNOWN, 200);
        cycles.observe(YES, 3_150, true);
        assertEquals(NONE, p.evaluate(30_000, absent()));
        assertEquals(PAUSED, p.state());
        cycle(30_001);
        assertEquals(START_ATTEMPT, p.evaluate(33_001, absent()));
    }
    @Test public void shortUnknownAndReadOnlySequencesCannotResetPausedBudget() {
        GuardPolicy p = running(); p.evaluate(100, absent()); finish(p, UNKNOWN, 200);
        cycles.observe(NO, 1_000, true); cycles.observe(YES, 3_999, true);
        cycles.observe(NO, 5_000, true); cycles.observe(UsbReconnectEvidence.Connection.UNKNOWN, 6_000, true);
        cycles.observe(YES, 10_000, true);
        cycles.observe(NO, 20_000, false); cycles.observe(YES, 30_000, false);
        cycles.observe(YES, 40_000, true);
        assertEquals(NONE, p.evaluate(50_000, absent()));
        assertEquals(PAUSED, p.state()); assertEquals(1, p.attempts());
    }
    @Test public void shortDisconnectThenRefreshYesCannotResetRoundBudgetViaConfiguredBroadcast() {
        GuardPolicy p = running(); p.evaluate(10, absent()); finish(p, UNKNOWN, 50);
        cycles.observe(NO, 100, true);
        cycles.observe(YES, 250, false);
        cycles.observe(YES, 4_000, true);
        assertEquals(NONE, p.evaluate(20_000, absent()));
        assertEquals(PAUSED, p.state()); assertEquals(1, p.attempts());
        assertEquals(0, p.consumedCycle());
        cycle(20_001);
        assertEquals(START_ATTEMPT, p.evaluate(23_001, absent()));
        assertEquals(1, p.attempts());
    }
    @Test public void refreshUnknownAlsoPreventsOldDisconnectResettingRoundBudget() {
        GuardPolicy p = running(); p.evaluate(10, absent()); finish(p, UNKNOWN, 50);
        cycles.observe(NO, 100, true);
        cycles.observe(UsbReconnectEvidence.Connection.UNKNOWN, 250, false);
        cycles.observe(YES, 4_000, true);
        assertEquals(NONE, p.evaluate(20_000, absent()));
        assertEquals(PAUSED, p.state()); assertEquals(1, p.attempts());
        assertEquals(0, p.consumedCycle());
    }
    @Test public void freshInterfaceResumesObservationWithoutErasingFailureCooldown() {
        GuardPolicy p = running(); p.evaluate(100, absent()); finish(p, UNKNOWN, 200);
        assertEquals(NONE, p.evaluate(300, input(true, true, true, true)));
        assertEquals(WATCHING, p.state()); assertEquals(0, p.attempts());
        assertEquals(NONE, p.evaluate(10_199, absent()));
        assertEquals(START_ATTEMPT, p.evaluate(10_200, absent()));
    }
    @Test public void newCycleDuringCooldownOrUsbWaitGetsNewBudgetButNotEarlierRetry() {
        GuardPolicy p = running(); p.evaluate(100, absent()); finish(p, OFF, 200);
        assertEquals(NONE, p.evaluate(300, input(true, false, false, true)));
        cycle(400);
        assertEquals(NONE, p.evaluate(3_400, absent()));
        assertEquals(0, p.attempts()); assertEquals(COOLDOWN, p.state());
        assertEquals(START_ATTEMPT, p.evaluate(10_200, absent()));
        assertEquals(1, p.attempts());
        finish(p, OFF, 10_300);
        assertEquals(START_ATTEMPT, p.evaluate(20_300, absent()));
        assertEquals(2, p.attempts()); // Duplicate generation cannot reset a second time.
    }
    @Test public void hardPauseAndStopCannotReviveFromCycleOrActiveInterface() {
        for (VerificationAttempt.Outcome outcome : new VerificationAttempt.Outcome[] { SERVICE_UNAVAILABLE, SECURITY_DENIED }) {
            GuardPolicy p = running(); p.evaluate(100, absent()); finish(p, outcome, 200);
            cycle(300);
            assertEquals(NONE, p.evaluate(30_000, absent()));
            assertEquals(NONE, p.evaluate(30_001, input(true, true, true, true)));
            assertEquals(GuardPolicy.PauseKind.HARD, p.pauseKind());
            p.stop();
            cycle(40_000);
            assertEquals(NONE, p.evaluate(50_000, input(true, true, true, true)));
            assertEquals(STOPPED, p.state());
            p.start(); assertEquals(START_ATTEMPT, p.evaluate(50_001, absent()));
        }
    }
    @Test public void observedOnAndInterfaceReturnToWatchingWithoutClaimingClickedConfirmation() {
        GuardPolicy p = running(); assertEquals(START_ATTEMPT, p.evaluate(100, absent()));
        finish(p, VerificationAttempt.Outcome.OBSERVED_ON, 200);
        assertEquals(WATCHING, p.state()); assertEquals(0, p.attempts());
        for (long now = 201; now < 1_000; now += 100) {
            assertEquals(NONE, p.evaluate(now, input(true, true, true, true)));
        }
        assertEquals(0, p.attemptId());
    }
    @Test public void serviceLossWhileRoundPausedLatchesHardPauseBeforeInterfaceRecovery() {
        GuardPolicy p = running(); p.evaluate(100, absent()); finish(p, UNKNOWN, 200);
        assertEquals(NONE, p.evaluate(300, input(false, true, true, true)));
        cycle(400);
        assertEquals(NONE, p.evaluate(30_000, input(true, true, true, true)));
        assertEquals(GuardPolicy.PauseKind.HARD, p.pauseKind());
    }
}
