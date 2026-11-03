package com.autousb.helper;

import org.junit.Test;
import static org.junit.Assert.*;

/** Deadline/ownership boundaries only: this does not emulate Android relayout or WMS wake-up. */
public class WakeWindowTest {
    private GuardPolicy.Input asleep() { return new GuardPolicy.Input(true, true, false, false, false, true, true); }
    private GuardPolicy waking(long now) {
        GuardPolicy p = new GuardPolicy(); p.start(50);
        assertEquals(GuardPolicy.Action.START_WAKE, p.evaluate(now, asleep()));
        return p;
    }
    @Test public void exactDeadlineRevokesOwnershipWithoutWaitingForPolicyTimer() {
        GuardPolicy p = waking(1_000);
        long session = p.sessionId(), attempt = p.attemptId();
        assertTrue(p.ownsWake(session, attempt, 1_000));
        assertTrue(p.ownsWake(session, attempt, 10_999));
        assertFalse(p.ownsWake(session, attempt, 11_000));
        assertFalse(p.ownsWake(session, attempt, 99_000));
        assertEquals(GuardPolicy.State.WAKING, p.state()); // Deliberately no evaluate callback yet.
        assertEquals(1, p.attempts());
        assertEquals(GuardPolicy.Action.CANCEL_PAUSE, p.evaluate(99_000, asleep()));
        assertEquals(1, p.attempts());
    }
    @Test public void resumeChecksCannotExtendDeadlineOrAuthorizeWrongIdentity() {
        GuardPolicy p = waking(1_000);
        long session = p.sessionId(), attempt = p.attemptId();
        for (long now = 1_000; now < 11_000; now += 250) {
            assertTrue(p.ownsWake(session, attempt, now));
            assertEquals(GuardPolicy.Action.NONE, p.evaluate(now, asleep()));
        }
        assertEquals(11_000, p.wakeUntil());
        assertFalse(p.ownsWake(session + 1, attempt, 2_000));
        assertFalse(p.ownsWake(session, attempt + 1, 2_000));
        assertFalse(p.ownsWake(session, 0, 2_000));
        assertFalse(p.ownsWake(session, attempt, 999));
        assertFalse(p.ownsWake(session, attempt, 11_000));
    }
    @Test public void transitionIntoVerificationInvalidatesWindowButPreservesAttemptBudget() {
        GuardPolicy p = waking(1_000);
        long session = p.sessionId(), attempt = p.attemptId();
        assertEquals(GuardPolicy.Action.START_ATTEMPT,
                p.evaluate(1_001, new GuardPolicy.Input(true, true, true, false, false, true)));
        assertFalse(p.ownsWake(session, attempt, 1_001));
        assertEquals(attempt, p.attemptId()); assertEquals(1, p.attempts());
        assertTrue(p.finished(session, attempt, VerificationAttempt.Outcome.CONFIRMED_ON, 1_002));
    }
    @Test public void stopHardPauseUsbLossAndSharingPresenceRevokeWakeWindowIdentity() {
        for (int ending = 0; ending < 4; ending++) {
            GuardPolicy p = waking(1_000);
            long session = p.sessionId(), attempt = p.attemptId();
            if (ending == 0) p.stop();
            else if (ending == 1) p.pauseHard(1_001);
            else if (ending == 2) p.evaluate(1_001, new GuardPolicy.Input(true, false, false, false, false, true, true));
            else p.evaluate(1_001, new GuardPolicy.Input(true, true, false, true, true, false, true));
            assertFalse(p.ownsWake(session, attempt, 1_001));
        }
    }
    @Test public void oldInstanceCleanupCannotDetachReplacementEvenWithSameAttemptIds() {
        WakeWindowSlot<Object> slot = new WakeWindowSlot<>();
        Object old = new Object(), replacement = new Object();
        assertNull(slot.replace(old, 50, 1));
        assertSame(old, slot.replace(replacement, 50, 1));
        assertFalse(slot.release(old, 50, 1));
        assertSame(replacement, slot.current());
        assertTrue(slot.matches(replacement, 50, 1));
        assertTrue(slot.release(replacement, 50, 1));
        assertNull(slot.current());
    }
    @Test public void detachedWindowCallbackCannotClearNewSessionRegisteredDuringCleanup() {
        WakeWindowSlot<Object> slot = new WakeWindowSlot<>();
        Object old = new Object(), nextSession = new Object();
        slot.replace(old, 50, 1);
        Object detached = slot.clear(); // Service detaches BEFORE calling old Activity's cleanup.
        assertSame(old, detached); assertNull(slot.current());
        slot.replace(nextSession, 60, 1);
        assertFalse(slot.release(detached, 50, 1));
        assertSame(nextSession, slot.current());
        assertFalse(slot.release(nextSession, 50, 1));
        assertSame(nextSession, slot.current());
        assertTrue(slot.release(nextSession, 60, 1));
    }
    @Test public void expiryAndCleanupCannotGrantAWindowForTheNextGuard() {
        GuardPolicy p = waking(1_000); WakeWindowSlot<Object> slot = new WakeWindowSlot<>();
        Object activity = new Object(); long session = p.sessionId(), attempt = p.attemptId();
        slot.replace(activity, session, attempt);
        assertFalse(p.ownsWake(session, attempt, 11_000));
        assertSame(activity, slot.clear());
        p.stop(); p.start(60); p.evaluate(12_000, asleep());
        assertFalse(p.ownsWake(session, attempt, 12_000));
        assertFalse(slot.matches(activity, p.sessionId(), p.attemptId()));
        assertNull(slot.current());
    }
}
