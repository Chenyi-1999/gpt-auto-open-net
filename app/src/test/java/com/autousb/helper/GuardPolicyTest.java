package com.autousb.helper;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.autousb.helper.GuardPolicy.Action.*;
import static com.autousb.helper.GuardPolicy.State.*;
import static com.autousb.helper.VerificationAttempt.Outcome.OFF;
import static com.autousb.helper.VerificationAttempt.Outcome.CONFIRMED_ON;
import static com.autousb.helper.VerificationAttempt.Outcome.UNKNOWN;
import static com.autousb.helper.VerificationAttempt.Outcome.REFUSED;
import static com.autousb.helper.VerificationAttempt.Outcome.ALREADY_ON;
import static com.autousb.helper.VerificationAttempt.Outcome.SWITCH_ON;
import static com.autousb.helper.VerificationAttempt.Outcome.SERVICE_UNAVAILABLE;

public class GuardPolicyTest {
    private static GuardPolicy.Input absent() { return input(true, true, true, false, false, true); }
    private static GuardPolicy.Input input(boolean service, boolean usb, boolean unlocked,
                                           boolean active, boolean positive, boolean absent) {
        return new GuardPolicy.Input(service, usb, unlocked, active, positive, absent);
    }
    private static GuardPolicy running() { GuardPolicy policy = new GuardPolicy(); policy.start(); return policy; }
    private static void finish(GuardPolicy policy, VerificationAttempt.Outcome outcome, long now) {
        assertTrue(policy.finished(policy.sessionId(), policy.attemptId(), outcome, now));
    }
    @Test public void newProcessIsOffAndHasNoAutomaticStart() {
        GuardPolicy policy = new GuardPolicy();
        assertFalse(policy.enabled());
        assertEquals(NONE, policy.evaluate(100, absent()));
        assertEquals(0, policy.attempts());
    }
    @Test public void duplicateEventsCannotStartASecondAttempt() {
        GuardPolicy policy = running();
        assertEquals(START_ATTEMPT, policy.evaluate(100, absent()));
        long id = policy.attemptId();
        assertEquals(NONE, policy.evaluate(101, absent()));
        assertEquals(id, policy.attemptId());
        assertEquals(1, policy.attempts());
    }
    @Test public void positiveInterfaceOrFlagAlwaysPreventsOpeningSettings() {
        GuardPolicy policy = running();
        assertEquals(NONE, policy.evaluate(100, input(true, true, true, true, true, true)));
        assertEquals(NONE, policy.evaluate(101, input(true, true, true, false, true, true)));
        assertEquals(0, policy.attempts());
    }
    @Test public void unknownAbsenceDoesNotAuthorizeAnAttempt() {
        GuardPolicy policy = running();
        assertEquals(NONE, policy.evaluate(100, input(true, true, true, false, false, false)));
        assertEquals(0, policy.attempts());
    }
    @Test public void unplugAndReplugResumeWithoutHumanRestartButKeepBudget() {
        GuardPolicy policy = running();
        policy.evaluate(100, absent());
        long oldAttempt = policy.attemptId(), session = policy.sessionId();
        assertEquals(CANCEL_WAIT_USB, policy.evaluate(101, input(true, false, true, false, false, true)));
        assertEquals(WAIT_USB, policy.state());
        assertTrue(policy.enabled());
        assertFalse(policy.finished(session, oldAttempt, OFF, 102));
        assertEquals(START_ATTEMPT, policy.evaluate(103, absent()));
        assertEquals(2, policy.attempts());
    }
    @Test public void screenOffWaitsAndUnlockResumesWithoutClearingBudget() {
        GuardPolicy policy = running(); policy.evaluate(100, absent());
        assertEquals(CANCEL_WAIT_UNLOCK, policy.evaluate(101, input(true, true, false, false, false, true)));
        assertEquals(WAIT_UNLOCK, policy.state());
        assertEquals(NONE, policy.evaluate(102, input(true, true, false, false, false, true)));
        assertEquals(START_ATTEMPT, policy.evaluate(103, absent()));
        assertEquals(2, policy.attempts());
    }
    @Test public void engineWaitCancellationIsNotAnUnknownFailureOrRetryLoop() {
        GuardPolicy policy = running(); policy.evaluate(100, absent());
        finish(policy, VerificationAttempt.Outcome.WAIT_USB, 101);
        assertEquals(WAIT_USB, policy.state());
        assertEquals(NONE, policy.evaluate(102, input(true, false, true, false, false, true)));
        assertEquals(START_ATTEMPT, policy.evaluate(103, absent()));
    }
    @Test public void onlyReliableOffGetsRetryAfterTenSecondsFromFinish() {
        GuardPolicy policy = running(); policy.evaluate(100, absent());
        finish(policy, OFF, 9_000);
        assertEquals(NONE, policy.evaluate(18_999, absent()));
        assertEquals(START_ATTEMPT, policy.evaluate(19_000, absent()));
        assertEquals(2, policy.attempts());
    }
    @Test public void thirdOffPausesButFreshInterfaceCanResumeObservation() {
        GuardPolicy policy = running();
        for (int attempt = 0; attempt < 3; attempt++) {
            long now = 100 + attempt * 20_000L;
            assertEquals(START_ATTEMPT, policy.evaluate(now, absent()));
            finish(policy, OFF, now + 100);
        }
        assertEquals(PAUSED, policy.state());
        assertEquals(NONE, policy.evaluate(100_000, absent()));
        assertEquals(NONE, policy.evaluate(100_001, input(true, true, true, true, true, false)));
        assertEquals(0, policy.attempts());
        assertEquals(WATCHING, policy.state());
    }
    @Test public void uncertainOutcomesDoNotRetryOnSameConnection() {
        for (VerificationAttempt.Outcome outcome : new VerificationAttempt.Outcome[] {
                UNKNOWN, REFUSED, ALREADY_ON, SWITCH_ON, SERVICE_UNAVAILABLE}) {
            GuardPolicy policy = running(); policy.evaluate(100, absent()); finish(policy, outcome, 101);
            assertEquals(PAUSED, policy.state());
            assertEquals(NONE, policy.evaluate(30_000, absent()));
            policy.stop(); policy.start();
            assertEquals(START_ATTEMPT, policy.evaluate(30_001, absent()));
        }
    }
    @Test public void serviceRevocationCancelsAndPausesEvenDuringUsbWait() {
        GuardPolicy policy = running(); policy.evaluate(100, absent());
        assertEquals(CANCEL_PAUSE, policy.evaluate(101, input(false, false, false, false, false, true)));
        assertEquals(PAUSED, policy.state());
        assertEquals(NONE, policy.evaluate(102, absent()));
    }
    @Test public void confirmedRecoveryResetsBudgetButPositiveRndisFlagDoesNot() {
        GuardPolicy policy = running(); policy.evaluate(100, absent()); finish(policy, OFF, 101);
        policy.evaluate(102, input(true, true, true, false, true, false));
        assertEquals(1, policy.attempts());
        policy.evaluate(103, input(true, true, true, true, true, false));
        assertEquals(0, policy.attempts());
        assertEquals(NONE, policy.evaluate(104, absent()));
        assertEquals(START_ATTEMPT, policy.evaluate(10_101, absent()));
        finish(policy, CONFIRMED_ON, 10_102);
        assertEquals(0, policy.attempts());
    }
    @Test public void stoppedOldSessionAndDuplicateResultCannotChangeNewAttempt() {
        GuardPolicy policy = running(); policy.evaluate(100, absent());
        long session = policy.sessionId(), attempt = policy.attemptId();
        policy.stop();
        assertFalse(policy.finished(session, attempt, CONFIRMED_ON, 101));
        policy.start(); policy.evaluate(102, absent());
        assertFalse(policy.finished(session, attempt, OFF, 103));
        long currentAttempt = policy.attemptId();
        finish(policy, OFF, 104);
        assertFalse(policy.finished(policy.sessionId(), currentAttempt, CONFIRMED_ON, 105));
        assertEquals(COOLDOWN, policy.state());
    }
    @Test public void cooldownSurvivesDisconnectAndUnlockWait() {
        GuardPolicy policy = running(); policy.evaluate(100, absent()); finish(policy, OFF, 200);
        policy.evaluate(300, input(true, false, true, false, false, true));
        policy.evaluate(400, input(true, true, false, false, false, true));
        assertEquals(NONE, policy.evaluate(10_199, absent()));
        assertEquals(START_ATTEMPT, policy.evaluate(10_200, absent()));
    }
    @Test public void freshnessAndPollIntervalsHaveExplicitBounds() {
        assertTrue(GuardPolicy.fresh(100, 2100));
        assertFalse(GuardPolicy.fresh(100, 2101));
        assertFalse(GuardPolicy.fresh(0, 100));
        assertFalse(GuardPolicy.fresh(100, 99));
        assertEquals(30_000, GuardPolicy.pollMillis(30));
        assertEquals(60_000, GuardPolicy.pollMillis(60));
        assertEquals(120_000, GuardPolicy.pollMillis(120));
        assertEquals(60_000, GuardPolicy.pollMillis(1));
        assertEquals(60_000, GuardPolicy.pollMillis(Integer.MAX_VALUE));
    }
    private static GuardPolicy.Input nonSecureScreenOff() {
        return new GuardPolicy.Input(true, true, false, false, false, true, true);
    }
    @Test public void wakeEligibilityNeverIncludesSecureOrDeviceLockedScreen() {
        assertTrue(GuardPolicy.mayWake(false, false, false));
        assertFalse(GuardPolicy.mayWake(true, false, false));
        assertFalse(GuardPolicy.mayWake(false, true, false));
        assertFalse(GuardPolicy.mayWake(false, false, true));
        assertFalse(GuardPolicy.mayWake(false, true, true));
    }
    @Test public void wakeAndVerificationShareOneAttemptBudgetAndIdentity() {
        GuardPolicy policy = running();
        assertEquals(START_WAKE, policy.evaluate(100, nonSecureScreenOff()));
        assertEquals(WAKING, policy.state());
        long attempt = policy.attemptId(), session = policy.sessionId();
        assertTrue(policy.ownsWake(session, attempt, 100));
        assertEquals(NONE, policy.evaluate(101, nonSecureScreenOff()));
        assertEquals(START_ATTEMPT, policy.evaluate(102, absent()));
        assertEquals(attempt, policy.attemptId());
        assertEquals(1, policy.attempts());
        assertFalse(policy.ownsWake(session, attempt, 102));
    }
    @Test public void blockedWakeTimesOutOnceAndCannotWakeAgainFromPolls() {
        GuardPolicy policy = running(); policy.evaluate(100, nonSecureScreenOff());
        assertEquals(NONE, policy.evaluate(10_099, nonSecureScreenOff()));
        assertEquals(CANCEL_PAUSE, policy.evaluate(10_100, nonSecureScreenOff()));
        assertEquals(PAUSED, policy.state());
        assertEquals(NONE, policy.evaluate(50_000, nonSecureScreenOff()));
        assertEquals(1, policy.attempts());
    }
    @Test public void stopDisconnectAndRevokeInvalidateWakeCallbacks() {
        GuardPolicy policy = running(); policy.evaluate(100, nonSecureScreenOff());
        long session = policy.sessionId(), attempt = policy.attemptId();
        policy.stop();
        assertFalse(policy.ownsWake(session, attempt, 101));
        policy.start(); policy.evaluate(101, nonSecureScreenOff());
        assertFalse(policy.ownsWake(session, attempt, 101));
        session = policy.sessionId(); attempt = policy.attemptId();
        assertEquals(CANCEL_WAIT_USB, policy.evaluate(102, input(true, false, false, false, false, true)));
        assertFalse(policy.ownsWake(session, attempt, 102));
        assertEquals(START_WAKE, policy.evaluate(103, nonSecureScreenOff()));
        assertEquals(2, policy.attempts());
        session = policy.sessionId(); attempt = policy.attemptId();
        assertEquals(CANCEL_PAUSE, policy.evaluate(104, input(false, true, false, false, false, true)));
        assertFalse(policy.ownsWake(session, attempt, 104));
    }
    @Test public void positiveInterfaceOrFlagAvoidsWakeEvenWhenScreenIsOff() {
        GuardPolicy policy = running();
        assertEquals(NONE, policy.evaluate(100, new GuardPolicy.Input(true, true, false, true, true, true, true)));
        assertEquals(NONE, policy.evaluate(101, new GuardPolicy.Input(true, true, false, false, true, true, true)));
        assertEquals(0, policy.attempts());
        policy.evaluate(102, nonSecureScreenOff());
        assertEquals(NONE, policy.evaluate(103, new GuardPolicy.Input(true, true, true, true, true, false, false)));
        assertEquals(WATCHING, policy.state());
        assertEquals(0, policy.attemptId());
        assertEquals(0, policy.attempts());
    }
    @Test public void explicitWakeRefusalPausesAndRejectsLateResume() {
        GuardPolicy policy = running(); policy.evaluate(100, nonSecureScreenOff());
        long session = policy.sessionId(), attempt = policy.attemptId();
        policy.pauseRound(150);
        assertFalse(policy.ownsWake(session, attempt, 150));
        assertEquals(NONE, policy.evaluate(200, absent()));
        assertEquals(1, policy.attempts());
    }
    @Test public void adapterSessionIdentityRejectsDelayedWakeFromPreviousServiceInstance() {
        GuardPolicy previous = new GuardPolicy(); previous.start(100);
        previous.evaluate(100, nonSecureScreenOff());
        GuardPolicy current = new GuardPolicy(); current.start(200);
        current.evaluate(200, nonSecureScreenOff());
        assertEquals(previous.attemptId(), current.attemptId());
        assertFalse(current.ownsWake(previous.sessionId(), previous.attemptId(), 201));
        assertFalse(current.finished(previous.sessionId(), previous.attemptId(), CONFIRMED_ON, 201));
        assertTrue(current.ownsWake(200, current.attemptId(), 201));
    }
}
