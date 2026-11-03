package com.autousb.helper;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.autousb.helper.NavigationRetryBudget.Page.*;
import static com.autousb.helper.SwitchStatePolicy.State.*;

/** Policy composition only: framework navigation and Android permission callbacks require device validation. */
public class NavigationRetryBudgetTest {
    @Test public void waitsAtLeastTwoSecondsFromTheInitialRequest() {
        Replay replay = new Replay();
        assertFalse(replay.retry(NO_ROOT, 99));
        assertFalse(replay.retry(NO_ROOT, 2099));
        assertTrue(replay.retry(NO_ROOT, 2100));
        assertFalse(replay.retry(OTHER_WINDOW, 2400));
    }

    @Test public void oneBudgetIsSharedAcrossArmedAndVerifying() {
        Replay replay = new Replay();
        assertTrue(replay.retry(SETTINGS_UNCONFIRMED, 2100));
        replay.click(2200);
        assertFalse(replay.retry(NO_ROOT, 4300));
        assertFalse(replay.retry(OTHER_WINDOW, 4500));
        assertFalse(replay.token.consume(4600, true, true, true, false));
        assertEquals(OneShotPolicy.Phase.VERIFYING, replay.token.phase());
    }

    @Test public void firstRetryMayOccurDuringReadbackWithoutGrantingAnotherClick() {
        Replay replay = new Replay(); replay.click(500);
        assertFalse(replay.retry(NO_ROOT, 2099));
        assertTrue(replay.retry(NO_ROOT, 2100));
        assertFalse(replay.token.consume(2101, true, true, true, false));
        assertFalse(replay.retry(SETTINGS_UNCONFIRMED, 2400));
        assertTrue(replay.window.mayReadback(replay.attempt, replay.slot.current(), 10_499));
        assertFalse(replay.window.mayReadback(replay.attempt, replay.slot.current(), 10_500));
    }

    @Test public void armedAndReadbackDeadlinesApplyBeforeClaimingEvenWithUnexpiredToken() {
        Replay armed = new Replay();
        assertFalse(armed.token.expired(10_100));
        assertFalse(armed.retry(NO_ROOT, 10_100));
        Replay verifying = new Replay(); verifying.click(500);
        assertFalse(verifying.token.expired(10_500));
        assertFalse(verifying.retry(OTHER_WINDOW, 10_500));
        assertFalse(verifying.retry(SETTINGS_UNCONFIRMED, 10_501));
    }

    @Test public void retryDoesNotRenewOriginalPageOrReadbackDeadline() {
        Replay armed = new Replay();
        assertTrue(armed.retry(NO_ROOT, 10_099));
        assertFalse(armed.window.mayConsume(armed.attempt, armed.slot.current(), armed.token.phase(), 10_100));
        Replay verifying = new Replay(); verifying.click(500);
        assertTrue(verifying.retry(NO_ROOT, 10_499));
        assertTrue(verifying.window.readbackExpired(verifying.attempt, verifying.slot.current(), 10_500));
    }

    @Test public void onlyThreeUnavailablePageCategoriesCanSpendTheBudget() {
        for (NavigationRetryBudget.Page page : NavigationRetryBudget.Page.values()) {
            Replay replay = new Replay();
            boolean expected = page == NO_ROOT || page == OTHER_WINDOW || page == SETTINGS_UNCONFIRMED;
            assertEquals(page.name(), expected, replay.retry(page, 2100));
        }
        Replay replay = new Replay();
        assertFalse(replay.retry(null, 2100));
    }

    @Test public void confirmedPageOffDisabledUnknownAndConflictDoNotCauseNavigation() {
        Replay replay = new Replay();
        for (SwitchStatePolicy.State state : SwitchStatePolicy.State.values()) {
            // Node state is irrelevant to the retry budget once the page itself is confirmed.
            assertFalse(state.name(), replay.retry(CONFIRMED_TETHER, 2100));
        }
        assertFalse(SwitchStatePolicy.mayClickOff(OFF, false, false, true, false));
        assertFalse(SwitchStatePolicy.mayClickOff(OFF, false, true, true, true));
        assertFalse(replay.retry(CONFIRMED_TETHER, 2400));
        assertTrue(replay.retry(OTHER_WINDOW, 2700)); // A genuine later page loss remains eligible.
    }

    @Test public void requestExceptionOrFreshConfirmedPageSkipStillSpendsTheClaim() {
        for (boolean requestThrows : new boolean[] {false, true}) {
            Replay replay = new Replay();
            assertTrue(replay.retry(NO_ROOT, 2100));
            try {
                if (requestThrows) throw new IllegalStateException("simulated framework failure");
                assertFalse(CONFIRMED_TETHER.retryable()); // Adapter skips before requesting.
            } catch (IllegalStateException expected) { /* Claim happened before the request. */ }
            assertFalse(replay.retry(SETTINGS_UNCONFIRMED, 2400));
            assertFalse(replay.retry(NO_ROOT, 3000));
        }
    }

    @Test public void stopAndHardPauseInvalidateSlotWindowAndBudgetBeforeLaterCallbacks() {
        for (boolean hardPause : new boolean[] {false, true}) {
            Replay replay = new Replay();
            GuardPolicy guard = new GuardPolicy(); guard.start(1);
            assertEquals(GuardPolicy.Action.START_ATTEMPT, guard.evaluate(100,
                    new GuardPolicy.Input(true, true, true, false, false, true)));
            if (hardPause) guard.pauseHard(2000); else guard.stop();
            replay.cancel();
            assertFalse(replay.retry(NO_ROOT, 2100));
            assertFalse(replay.budget.shouldLogPage(replay.attempt, replay.slot.current(), NO_ROOT));
            assertEquals(GuardPolicy.Action.NONE, guard.evaluate(3000,
                    new GuardPolicy.Input(true, true, true, false, false, true)));
        }
    }

    @Test public void oldGenerationCannotSpendOrLogAgainstReplacementAttempt() {
        Replay replay = new Replay();
        VerificationAttempt old = replay.attempt;
        replay.cancel(); replay.begin(5000, 2);
        assertFalse(replay.budget.claim(old, replay.slot.current(), replay.token.phase(), replay.window, NO_ROOT, 7100));
        assertFalse(replay.budget.shouldLogPage(old, replay.slot.current(), OTHER_WINDOW));
        assertTrue(replay.retry(NO_ROOT, 7100));
        assertFalse(replay.retry(NO_ROOT, 7400));
    }

    @Test public void usbLockAndServiceCancellationRevokeRetryDuringEitherPhase() {
        GuardPolicy.Input[] unsafe = {
                new GuardPolicy.Input(true, false, true, false, false, true),
                new GuardPolicy.Input(true, true, false, false, false, true),
                new GuardPolicy.Input(false, true, true, false, false, true)
        };
        GuardPolicy.Action[] cancellation = {GuardPolicy.Action.CANCEL_WAIT_USB,
                GuardPolicy.Action.CANCEL_WAIT_UNLOCK, GuardPolicy.Action.CANCEL_PAUSE};
        for (int condition = 0; condition < unsafe.length; condition++) {
            for (boolean verifying : new boolean[] {false, true}) {
                Replay replay = new Replay();
                GuardPolicy guard = new GuardPolicy(); guard.start(1);
                assertEquals(GuardPolicy.Action.START_ATTEMPT, guard.evaluate(100,
                        new GuardPolicy.Input(true, true, true, false, false, true)));
                if (verifying) replay.click(500);
                assertEquals(cancellation[condition], guard.evaluate(2000, unsafe[condition]));
                replay.cancel(); // Adapter applies policy cancellation before any later page callback.
                assertFalse(replay.retry(NO_ROOT, 2100));
                assertFalse(replay.token.consume(2101, true, true, true, false));
            }
        }
    }

    @Test public void manualAndIdleAttemptsNeverReceiveAnAutomaticRetry() {
        Replay replay = new Replay();
        replay.token.cancel();
        assertFalse(replay.retry(NO_ROOT, 2100));
        replay.cancel();
        VerificationAttempt manual = new VerificationAttempt(VerificationAttempt.Owner.MANUAL, 0, 1, null);
        assertTrue(replay.slot.claim(manual, false));
        assertTrue(replay.token.arm(100, true, true, true));
        replay.window.begin(manual, 100); replay.budget.begin(manual, 100);
        assertFalse(replay.budget.claim(manual, manual, replay.token.phase(), replay.window, NO_ROOT, 2100));
        assertFalse(replay.budget.shouldLogPage(manual, manual, NO_ROOT));
    }

    @Test public void pageDiagnosticsAreBoundedToOneEventPerCategoryPerAttempt() {
        Replay replay = new Replay();
        int logged = 0;
        for (int scan = 0; scan < 100; scan++) {
            for (NavigationRetryBudget.Page page : NavigationRetryBudget.Page.values()) {
                if (replay.budget.shouldLogPage(replay.attempt, replay.slot.current(), page)) logged++;
            }
        }
        assertEquals(5, logged);
        replay.cancel(); replay.begin(5000, 2);
        assertTrue(replay.budget.shouldLogPage(replay.attempt, replay.slot.current(), NO_ROOT));
    }

    @Test public void retryAfterPageGapRequiresNewOnChainAndDoesNotRefreshUsbEvidence() {
        Replay replay = new Replay(); replay.click(500);
        ReadbackEvidence evidence = new ReadbackEvidence();
        replay.on(evidence, 600); replay.on(evidence, 1100); replay.on(evidence, 1600);
        assertTrue(evidence.confirmedOn(1600, true));
        evidence.invalidate(); // The service invalidates current switch/readback before requesting.
        assertTrue(replay.retry(SETTINGS_UNCONFIRMED, 2100));
        replay.on(evidence, 2200); replay.on(evidence, 2700);
        assertFalse(evidence.confirmedOn(2700, true));
        replay.on(evidence, 3200);
        assertFalse(evidence.confirmedOn(3200,
                ReadbackEvidence.freshPostClickUsbInterface(500, 499, 3200, new String[] {"rndis0"})));
        assertFalse(evidence.confirmedOn(3200,
                ReadbackEvidence.freshPostClickUsbInterface(500, 500, 3200, new String[] {"rndis0"})));
        assertTrue(evidence.confirmedOn(3200,
                ReadbackEvidence.freshPostClickUsbInterface(500, 3000, 3200, new String[] {"rndis0"})));
        assertFalse(replay.token.consume(3201, true, true, true, false));
    }

    private static final class Replay {
        final OneShotPolicy token = new OneShotPolicy();
        final VerificationAttempt.Slot slot = new VerificationAttempt.Slot();
        final VerificationWindow window = new VerificationWindow();
        final NavigationRetryBudget budget = new NavigationRetryBudget();
        VerificationAttempt attempt;
        Replay() { begin(100, 1); }
        void begin(long now, long session) {
            attempt = new VerificationAttempt(VerificationAttempt.Owner.GUARD, session, 1, null);
            assertTrue(slot.claim(attempt, true)); assertTrue(token.arm(now, true, true, true));
            window.begin(attempt, now); budget.begin(attempt, now);
        }
        boolean retry(NavigationRetryBudget.Page page, long now) {
            return budget.claim(attempt, slot.current(), token.phase(), window, page, now);
        }
        void click(long now) {
            assertTrue(window.mayConsume(attempt, slot.current(), token.phase(), now));
            assertTrue(token.consume(now, true, true, true, false));
            window.clicked(attempt, slot.current(), now);
        }
        void on(ReadbackEvidence evidence, long now) {
            assertTrue(window.recordReadback(attempt, slot.current(), evidence, ON, true, true, now));
        }
        void cancel() { slot.release(attempt); token.cancel(); window.end(); budget.end(); }
    }
}
