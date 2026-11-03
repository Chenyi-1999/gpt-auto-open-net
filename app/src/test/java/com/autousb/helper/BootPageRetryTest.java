package com.autousb.helper;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.autousb.helper.VerificationAttempt.Outcome.*;
import static com.autousb.helper.NavigationRetryBudget.Page.*;

/** Compose page evidence with the unchanged incident budget and safety cancellation behavior. */
public class BootPageRetryTest {
    private GuardPolicy boot() { GuardPolicy p = new GuardPolicy(); p.start(50, true); return p; }
    private GuardPolicy.Input absent() { return new GuardPolicy.Input(true, true, true, false, false, true); }
    private VerificationAttempt begin(GuardPolicy p, PageReadyEvidence evidence, long now) {
        assertEquals(GuardPolicy.Action.START_ATTEMPT, p.evaluate(now, absent()));
        VerificationAttempt attempt = new VerificationAttempt(VerificationAttempt.Owner.GUARD,
                p.sessionId(), p.attemptId(), null);
        evidence.begin(attempt, now);
        evidence.sample(attempt, attempt, SETTINGS_UNCONFIRMED, now + 100);
        return attempt;
    }
    private void noPage(GuardPolicy p, PageReadyEvidence evidence, VerificationAttempt attempt, long now) {
        VerificationAttempt.Outcome outcome = evidence.pageTimeout(attempt, attempt, OneShotPolicy.Phase.ARMED, now);
        assertEquals(PAGE_NOT_READY, outcome);
        evidence.end();
        assertTrue(p.finished(attempt.sessionId, attempt.attemptId, outcome, now));
    }
    @Test public void firstTwoNoPageAttemptsCoolDownAndThirdCanCompleteNormally() {
        GuardPolicy p = boot(); PageReadyEvidence e = new PageReadyEvidence();
        for (int i = 0; i < 2; i++) {
            long start = 1_000 + i * 20_000;
            VerificationAttempt a = begin(p, e, start);
            noPage(p, e, a, start + 10_000);
            assertEquals(GuardPolicy.State.COOLDOWN, p.state());
            assertEquals(i + 1, p.attempts());
            assertEquals(GuardPolicy.Action.NONE, p.evaluate(start + 19_999, absent()));
        }
        VerificationAttempt third = begin(p, e, 41_000);
        e.sample(third, third, CONFIRMED_TETHER, 41_100);
        assertTrue(p.finished(third.sessionId, third.attemptId, CONFIRMED_ON, 42_000));
        assertEquals(GuardPolicy.State.WATCHING, p.state());
        assertEquals(0, p.attempts());
    }
    @Test public void thirdNoPageFailureExhaustsOriginalTotalBudget() {
        GuardPolicy p = boot(); PageReadyEvidence e = new PageReadyEvidence();
        for (int i = 0; i < 3; i++) {
            long start = 1_000 + i * 20_000;
            noPage(p, e, begin(p, e, start), start + 10_000);
        }
        assertEquals(3, p.attempts());
        assertEquals(GuardPolicy.PauseKind.ROUND, p.pauseKind());
        assertEquals(GuardPolicy.Action.NONE, p.evaluate(100_000, absent()));
    }
    @Test public void offAndPageFailuresShareOneBudgetRatherThanSeparateCounters() {
        GuardPolicy p = boot(); PageReadyEvidence e = new PageReadyEvidence();
        VerificationAttempt first = begin(p, e, 1_000);
        assertTrue(p.finished(first.sessionId, first.attemptId, OFF, 2_000));
        VerificationAttempt second = begin(p, e, 12_000);
        noPage(p, e, second, 22_000);
        VerificationAttempt third = begin(p, e, 32_000);
        assertTrue(p.finished(third.sessionId, third.attemptId, OFF, 33_000));
        assertEquals(3, p.attempts());
        assertEquals(GuardPolicy.PauseKind.ROUND, p.pauseKind());
    }
    @Test public void allDefaultStartsKeepManualGuardPageFailurePausedAndClearBootMode() {
        GuardPolicy p = boot();
        for (int mode = 0; mode < 2; mode++) {
            if (mode == 0) p.start(60); else p.start();
            assertFalse(p.pageReadyRetryAllowed());
            PageReadyEvidence e = new PageReadyEvidence();
            VerificationAttempt a = begin(p, e, 1_000);
            noPage(p, e, a, 11_000);
            assertEquals(GuardPolicy.PauseKind.ROUND, p.pauseKind());
            assertEquals(GuardPolicy.Action.NONE, p.evaluate(30_000, absent()));
        }
    }
    @Test public void confirmedThenMissingPageIsUnknownAndCannotTakeBootRetry() {
        GuardPolicy p = boot(); PageReadyEvidence e = new PageReadyEvidence();
        VerificationAttempt a = begin(p, e, 1_000);
        e.sample(a, a, CONFIRMED_TETHER, 1_100);
        e.sample(a, a, OTHER_WINDOW, 5_000);
        assertEquals(UNKNOWN, e.pageTimeout(a, a, OneShotPolicy.Phase.ARMED, 11_000));
        assertTrue(p.finished(a.sessionId, a.attemptId, UNKNOWN, 11_000));
        assertEquals(GuardPolicy.PauseKind.ROUND, p.pauseKind());
        assertTrue(e.facts().everConfirmed);
        assertEquals(OTHER_WINDOW, e.facts().lastPage);
    }
    @Test public void unknownAndReadbackFailuresRetainOriginalPauseBehaviorForBoot() {
        for (VerificationAttempt.Outcome outcome : new VerificationAttempt.Outcome[] {UNKNOWN, REFUSED, SWITCH_ON, ALREADY_ON}) {
            GuardPolicy p = boot(); PageReadyEvidence e = new PageReadyEvidence();
            VerificationAttempt a = begin(p, e, 1_000);
            assertTrue(p.finished(a.sessionId, a.attemptId, outcome, 2_000));
            assertEquals(GuardPolicy.PauseKind.ROUND, p.pauseKind());
        }
    }
    @Test public void possibleClickCannotQualifyInAnyPhaseAndPageGapsCannotClearIt() {
        PageReadyEvidence e = new PageReadyEvidence();
        VerificationAttempt a = new VerificationAttempt(VerificationAttempt.Owner.GUARD, 1, 2, null);
        e.begin(a, 1_000); e.possibleClick(a, a);
        // There is deliberately no rollback API for a false/throwing framework action.
        // Its placement before performAction is checked in the Android adapter source review.
        e.sample(a, a, UNREADABLE_ROOT, 5_000);
        assertTrue(e.facts().possibleClick);
        assertEquals(UNKNOWN, e.pageTimeout(a, a, OneShotPolicy.Phase.ARMED, 11_000));
        assertEquals(UNKNOWN, e.pageTimeout(a, a, OneShotPolicy.Phase.VERIFYING, 11_000));
    }
    @Test public void eligibilityRequiresExactGuardIdentityArmedPhaseAndFixedDeadline() {
        PageReadyEvidence e = new PageReadyEvidence();
        VerificationAttempt a = new VerificationAttempt(VerificationAttempt.Owner.GUARD, 1, 2, null);
        VerificationAttempt sameIds = new VerificationAttempt(VerificationAttempt.Owner.GUARD, 1, 2, null);
        e.begin(a, 1_000);
        assertEquals(UNKNOWN, e.pageTimeout(a, a, OneShotPolicy.Phase.ARMED, 10_999));
        assertEquals(UNKNOWN, e.pageTimeout(a, sameIds, OneShotPolicy.Phase.ARMED, 11_000));
        assertEquals(UNKNOWN, e.pageTimeout(sameIds, sameIds, OneShotPolicy.Phase.ARMED, 11_000));
        assertEquals(UNKNOWN, e.pageTimeout(a, a, OneShotPolicy.Phase.VERIFYING, 11_000));
        assertEquals(UNKNOWN, e.pageTimeout(a, a, OneShotPolicy.Phase.IDLE, 11_000));
        assertEquals(PAGE_NOT_READY, e.pageTimeout(a, a, OneShotPolicy.Phase.ARMED, 11_000));
        VerificationAttempt manual = new VerificationAttempt(VerificationAttempt.Owner.MANUAL, 0, 1, null);
        e.begin(manual, 1_000);
        assertEquals(UNKNOWN, e.pageTimeout(manual, manual, OneShotPolicy.Phase.ARMED, 11_000));
    }
    @Test public void terminalFactsSurviveCleanupAndOldCallbacksCannotContaminateNextAttempt() {
        PageReadyEvidence e = new PageReadyEvidence();
        VerificationAttempt old = new VerificationAttempt(VerificationAttempt.Owner.GUARD, 1, 2, null);
        e.begin(old, 1_000); e.sample(old, old, CONFIRMED_TETHER, 1_100); e.possibleClick(old, old);
        PageReadyEvidence.Facts frozen = e.facts();
        e.end(); e.sample(old, old, OTHER_WINDOW, 11_100);
        assertFalse(e.facts().active); assertTrue(e.facts().everConfirmed); assertTrue(e.facts().possibleClick);
        assertEquals(1, e.facts().sessionId); assertEquals(2, e.facts().attemptId);
        assertEquals(1_100, e.facts().sampleAt); assertEquals(11_000, e.facts().pageDeadline);
        assertEquals(UNKNOWN, e.pageTimeout(old, old, OneShotPolicy.Phase.ARMED, 11_000));
        VerificationAttempt next = new VerificationAttempt(VerificationAttempt.Owner.GUARD, 1, 3, null);
        e.begin(next, 20_000); e.sample(old, next, CONFIRMED_TETHER, 20_100); e.possibleClick(old, next);
        assertFalse(e.facts().everConfirmed); assertFalse(e.facts().possibleClick);
        assertNull(e.facts().lastPage); assertEquals(3, e.facts().attemptId);
        assertEquals(30_000, e.facts().pageDeadline);
        assertTrue(frozen.everConfirmed); assertTrue(frozen.possibleClick); assertEquals(2, frozen.attemptId);
    }
    @Test public void pageGapsAndRetrySamplesCannotClearConfirmationOrExtendDeadline() {
        PageReadyEvidence e = new PageReadyEvidence();
        VerificationAttempt a = new VerificationAttempt(VerificationAttempt.Owner.GUARD, 1, 2, null);
        e.begin(a, 1_000);
        for (NavigationRetryBudget.Page page : NavigationRetryBudget.Page.values()) e.sample(a, a, page, 3_000);
        e.sample(a, a, NO_ROOT, 10_999);
        assertTrue(e.facts().everConfirmed);
        assertEquals(11_000, e.facts().pageDeadline);
        assertEquals(UNKNOWN, e.pageTimeout(a, a, OneShotPolicy.Phase.ARMED, 11_000));
    }
    @Test public void stopHardPauseAndNotificationRevocationRejectOldPageCallbacks() {
        for (int cancellation = 0; cancellation < 3; cancellation++) {
            GuardPolicy p = boot(); PageReadyEvidence e = new PageReadyEvidence();
            VerificationAttempt a = begin(p, e, 1_000);
            if (cancellation == 1) p.pauseHard(2_000); else p.stop(); // Notification-off uses shutdown/stop.
            e.end();
            assertFalse(p.finished(a.sessionId, a.attemptId, PAGE_NOT_READY, 11_000));
            assertEquals(UNKNOWN, e.pageTimeout(a, a, OneShotPolicy.Phase.ARMED, 11_000));
            assertEquals(GuardPolicy.Action.NONE, p.evaluate(30_000, absent()));
        }
    }
    @Test public void usbLockAndPermissionLossKeepTheirExistingSafetyOutcomes() {
        for (VerificationAttempt.Outcome safety : new VerificationAttempt.Outcome[] {WAIT_USB, WAIT_UNLOCK, SERVICE_UNAVAILABLE, SECURITY_DENIED}) {
            GuardPolicy p = boot(); PageReadyEvidence e = new PageReadyEvidence();
            VerificationAttempt a = begin(p, e, 1_000);
            assertTrue(p.finished(a.sessionId, a.attemptId, safety, 2_000)); e.end();
            assertEquals(1, p.attempts());
            assertFalse(p.finished(a.sessionId, a.attemptId, PAGE_NOT_READY, 11_000));
            if (safety == WAIT_USB) assertEquals(GuardPolicy.State.WAIT_USB, p.state());
            else if (safety == WAIT_UNLOCK) assertEquals(GuardPolicy.State.WAIT_UNLOCK, p.state());
            else assertEquals(GuardPolicy.PauseKind.HARD, p.pauseKind());
        }
    }
}
