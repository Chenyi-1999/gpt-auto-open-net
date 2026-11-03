package com.autousb.helper;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.autousb.helper.SwitchStatePolicy.State.*;

/** Replays use the production token, attempt slot, deadline and readback policies together. */
public class VerificationWindowTest {
    @Test public void pageMayBecomeReadyAfterTheFormerThreeSecondCutoff() {
        Replay replay = new Replay();
        for (long missingAt : new long[] {350, 1000, 3108, 3138, 8000}) {
            replay.gap();
            assertFalse(replay.window.pageExpired(replay.attempt, replay.slot.current(), missingAt));
            assertEquals(OneShotPolicy.Phase.ARMED, replay.token.phase());
        }
        assertTrue(replay.click(replay.attempt, 8100, OFF, false, true, true, false));
        assertFalse(replay.click(replay.attempt, 8200, OFF, false, true, true, false));
    }

    @Test public void lateValidOffEventCannotUseTheRemainingThirtySecondToken() {
        for (long readyAt : new long[] {10_100, 10_101, 29_000}) {
            Replay replay = new Replay();
            assertFalse(replay.token.expired(readyAt));
            assertFalse(replay.click(replay.attempt, readyAt, OFF, false, true, true, false));
            assertEquals(OneShotPolicy.Phase.ARMED, replay.token.phase());
            assertTrue(replay.window.pageExpired(replay.attempt, replay.slot.current(), readyAt));
        }
    }

    @Test public void readinessImmediatelyBeforeDeadlineIsAllowedButDoesNotRenewPageWindow() {
        Replay replay = new Replay();
        replay.gap(); replay.gap();
        assertTrue(replay.window.mayConsume(replay.attempt, replay.slot.current(), replay.token.phase(), 10_099));
        assertFalse(replay.window.mayConsume(replay.attempt, replay.slot.current(), replay.token.phase(), 10_100));
    }

    @Test public void unknownDisabledRawCheckedAndInterfaceConflictNeverAuthorizeOffClick() {
        Replay replay = new Replay();
        assertFalse(replay.click(replay.attempt, 3200, UNKNOWN, false, true, true, false));
        assertFalse(replay.click(replay.attempt, 3500, OFF, false, false, true, false));
        assertFalse(replay.click(replay.attempt, 3800, OFF, false, true, false, false));
        assertFalse(replay.click(replay.attempt, 4100, OFF, true, true, true, false));
        assertFalse(replay.click(replay.attempt, 4400, OFF, false, true, true, true));
        assertEquals(OneShotPolicy.Phase.ARMED, replay.token.phase());
        assertTrue(replay.click(replay.attempt, 4700, OFF, false, true, true, false));
    }

    @Test public void exportedEarlyPageGapsDoNotFinishReadbackBeforeFreshUsbArrives() {
        long[][] cases = {{672, 171}, {1076, 66}, {690, 153}};
        for (long[] sample : cases) {
            Replay replay = new Replay();
            assertTrue(replay.click(replay.attempt, 1000, OFF, false, true, true, false));
            replay.on(1100); replay.gap();
            long interfaceAt = 1000 + sample[0] + sample[1];
            assertFalse(replay.window.readbackExpired(replay.attempt, replay.slot.current(), interfaceAt));
            assertFalse(replay.readback.confirmedOn(interfaceAt, true));
            replay.on(interfaceAt); replay.on(interfaceAt + 500); replay.on(interfaceAt + 1000);
            assertTrue(replay.confirmed(interfaceAt + 1000, interfaceAt));
            assertFalse(replay.click(replay.attempt, interfaceAt + 1100, OFF, false, true, true, false));
        }
    }

    @Test public void resumedPageMustBuildANewStableOnChain() {
        Replay replay = clicked();
        replay.on(1100); replay.on(1600); replay.on(2100);
        assertTrue(replay.confirmed(2100, 2050));
        replay.gap();
        replay.on(2300); replay.on(2800);
        assertFalse(replay.confirmed(2800, 2750));
        replay.on(3300);
        assertTrue(replay.confirmed(3300, 3250));
    }

    @Test public void disabledOrNonclickableCheckableOnBreaksTheReadbackChain() {
        for (boolean enabled : new boolean[] {false, true}) {
            Replay replay = clicked();
            replay.on(1100); replay.on(1600); replay.on(2100);
            assertEquals(ON, SwitchStatePolicy.resolve(true, true, enabled, !enabled,
                    false, false, "id", "class", ""));
            assertFalse(replay.window.recordReadback(replay.attempt, replay.slot.current(), replay.readback,
                    ON, enabled, !enabled, 2200));
            assertEquals(UNKNOWN, replay.readback.finalState(2200, 1000));
            replay.on(2300); replay.on(2800);
            assertFalse(replay.confirmed(2800, 2750));
            replay.on(3300);
            assertTrue(replay.confirmed(3300, 3250));
        }
    }

    @Test public void persistentPageGapFinishesUnknownAtOriginalPostClickDeadline() {
        Replay replay = clicked();
        replay.on(1100); replay.gap();
        replay.window.clicked(replay.attempt, replay.slot.current(), 2000); // Cannot renew a consumed window.
        for (long missingAt : new long[] {2000, 6000, 10_999}) {
            replay.gap();
            assertFalse(replay.window.readbackExpired(replay.attempt, replay.slot.current(), missingAt));
        }
        assertTrue(replay.window.readbackExpired(replay.attempt, replay.slot.current(), 11_000));
        assertEquals(UNKNOWN, replay.readback.finalState(11_000, 1000));
        assertFalse(replay.window.recordReadback(replay.attempt, replay.slot.current(), replay.readback,
                ON, true, true, 11_000));
        assertEquals(UNKNOWN, replay.readback.finalState(11_000, 1000));
    }

    @Test public void freshSwitchOnStillNeedsFreshPostClickUsbEvidence() {
        Replay replay = clicked();
        replay.on(1100); replay.on(1600); replay.on(2100);
        assertFalse(replay.confirmed(2100, 999));
        assertTrue(replay.confirmed(2100, 1000));
        assertFalse(replay.confirmed(3101, 1000));
    }

    @Test public void stopDisconnectLockAndRevocationCancelDuringEitherWaitingPhase() {
        for (VerificationAttempt.Outcome reason : new VerificationAttempt.Outcome[] {
                VerificationAttempt.Outcome.STOPPED, VerificationAttempt.Outcome.WAIT_USB,
                VerificationAttempt.Outcome.WAIT_UNLOCK, VerificationAttempt.Outcome.SERVICE_UNAVAILABLE,
                VerificationAttempt.Outcome.SECURITY_DENIED}) {
            for (boolean afterClick : new boolean[] {false, true}) {
                Replay replay = afterClick ? clicked() : new Replay();
                VerificationAttempt old = replay.attempt;
                GuardPolicy guard = new GuardPolicy(); guard.start(1);
                assertEquals(GuardPolicy.Action.START_ATTEMPT, guard.evaluate(100,
                        new GuardPolicy.Input(true, true, true, false, false, true)));
                switch (reason) {
                    case STOPPED: guard.stop(); assertFalse(guard.enabled()); break;
                    case WAIT_USB:
                        assertEquals(GuardPolicy.Action.CANCEL_WAIT_USB, guard.evaluate(2000,
                                new GuardPolicy.Input(true, false, true, false, false, true))); break;
                    case WAIT_UNLOCK:
                        assertEquals(GuardPolicy.Action.CANCEL_WAIT_UNLOCK, guard.evaluate(2000,
                                new GuardPolicy.Input(true, true, false, false, false, true))); break;
                    case SERVICE_UNAVAILABLE:
                        assertEquals(GuardPolicy.Action.CANCEL_PAUSE, guard.evaluate(2000,
                                new GuardPolicy.Input(false, true, true, false, false, true))); break;
                    case SECURITY_DENIED:
                        assertTrue(guard.finished(1, 1, reason, 2000));
                        assertEquals(GuardPolicy.PauseKind.HARD, guard.pauseKind()); break;
                    default: fail("Unexpected safety case");
                }
                replay.gap(); replay.cancel();
                assertFalse(reason.name(), replay.window.mayConsume(old, replay.slot.current(), replay.token.phase(), 3000));
                assertFalse(reason.name(), replay.window.mayReadback(old, replay.slot.current(), 3000));
                assertFalse(reason.name(), replay.token.consume(3000, true, true, true, false));
            }
        }
    }

    @Test public void oldCallbackCannotConsumeOrUpdateEvidenceForANewGeneration() {
        Replay replay = clicked();
        VerificationAttempt old = replay.attempt;
        replay.cancel(); replay.begin(5000, 2, 1);
        assertFalse(replay.click(old, 5100, OFF, false, true, true, false));
        replay.window.clicked(old, replay.slot.current(), 5100);
        assertFalse(replay.window.mayReadback(replay.attempt, replay.slot.current(), 5101));
        assertTrue(replay.click(replay.attempt, 5200, OFF, false, true, true, false));
        assertFalse(replay.window.recordReadback(old, replay.slot.current(), replay.readback, ON, true, true, 5300));
        assertEquals(UNKNOWN, replay.readback.finalState(5300, 1000));
        assertTrue(replay.window.mayReadback(replay.attempt, replay.slot.current(), 15_199));
        assertFalse(replay.window.mayReadback(replay.attempt, replay.slot.current(), 15_200));
    }

    @Test public void manualTokenRetainsItsThirtySecondArmedWindow() {
        Replay replay = new Replay(); replay.cancel();
        replay.attempt = new VerificationAttempt(VerificationAttempt.Owner.MANUAL, 0, 1, null);
        assertTrue(replay.slot.claim(replay.attempt, false));
        assertTrue(replay.token.arm(100, true, true, true));
        replay.window.begin(replay.attempt, 100);
        assertTrue(replay.click(replay.attempt, 20_000, OFF, false, true, true, false));
        assertTrue(replay.window.recordReadback(replay.attempt, replay.slot.current(), replay.readback,
                ON, false, false, 30_000));
        assertEquals(ON, replay.readback.finalState(30_000, 1000));
        assertFalse(replay.window.mayReadback(replay.attempt, replay.slot.current(), 30_001));
    }

    private static Replay clicked() {
        Replay replay = new Replay();
        assertTrue(replay.click(replay.attempt, 1000, OFF, false, true, true, false));
        return replay;
    }
    private static final class Replay {
        final OneShotPolicy token = new OneShotPolicy();
        final VerificationAttempt.Slot slot = new VerificationAttempt.Slot();
        final VerificationWindow window = new VerificationWindow();
        final ReadbackEvidence readback = new ReadbackEvidence();
        VerificationAttempt attempt;
        long clickAt;
        Replay() { begin(100, 1, 1); }
        void begin(long now, long session, long id) {
            attempt = new VerificationAttempt(VerificationAttempt.Owner.GUARD, session, id, null);
            assertTrue(slot.claim(attempt, true));
            assertTrue(token.arm(now, true, true, true));
            window.begin(attempt, now); readback.reset();
        }
        boolean click(VerificationAttempt callback, long now, SwitchStatePolicy.State state,
                      boolean checked, boolean enabled, boolean clickable, boolean conflict) {
            if (!SwitchStatePolicy.mayClickOff(state, checked, enabled, clickable, conflict)
                    || !window.mayConsume(callback, slot.current(), token.phase(), now)
                    || !token.consume(now, true, true, true, checked)) return false;
            clickAt = now; window.clicked(callback, slot.current(), now); return true;
        }
        void on(long now) {
            assertTrue(window.recordReadback(attempt, slot.current(), readback, ON, true, true, now));
        }
        void gap() { readback.invalidate(); }
        boolean confirmed(long now, long interfaceAt) {
            return window.mayReadback(attempt, slot.current(), now) && readback.confirmedOn(now,
                    ReadbackEvidence.freshPostClickUsbInterface(clickAt, interfaceAt, now, new String[] {"rndis0"}));
        }
        void cancel() { slot.release(attempt); token.cancel(); window.end(); readback.invalidate(); }
    }
}
