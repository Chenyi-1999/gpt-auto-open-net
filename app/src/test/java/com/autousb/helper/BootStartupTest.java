package com.autousb.helper;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.autousb.helper.StartAuthorization.Origin.*;
import static com.autousb.helper.BootWaitPolicy.Result.*;

/** Event orders at the authorization/wait/running boundary, using no saved USB observations. */
public class BootStartupTest {
    private final StartAuthorization starts = new StartAuthorization(100);
    private final BootWaitPolicy wait = new BootWaitPolicy();
    private BootWaitPolicy.Result check(long now, boolean ready) {
        return wait.evaluate(now, true, true, false, ready);
    }
    private void deliverBoot(long now) {
        long token = starts.issue(BOOT);
        assertTrue(starts.consume(token, BOOT));
        wait.begin(now);
    }
    @Test public void freshProcessAndAccessibilityAloneNeverAuthorizeAnything() {
        assertFalse(starts.pending());
        assertFalse(starts.consume(101, BOOT));
        assertEquals(INACTIVE, check(1, true));
        assertFalse(wait.waiting());
    }
    @Test public void intentCannotChangeGrantedOriginAndGrantIsConsumedOnce() {
        long token = starts.issue(BOOT);
        assertFalse(starts.consume(token, MANUAL));
        assertFalse(starts.consume(token + 1, BOOT));
        assertTrue(starts.pendingBoot());
        assertTrue(starts.consume(token, BOOT));
        assertFalse(starts.consume(token, BOOT));
    }
    @Test public void eitherPendingOriginRefusesOverlappingStart() {
        for (StartAuthorization.Origin origin : StartAuthorization.Origin.values()) {
            long token = starts.issue(origin);
            assertEquals(0, starts.issue(MANUAL));
            assertEquals(0, starts.issue(BOOT));
            assertTrue(starts.consume(token, origin));
        }
    }
    @Test public void stopBeforeDeliveryRevokesBothOriginsAndOldTokenCannotMatchRetry() {
        for (StartAuthorization.Origin origin : StartAuthorization.Origin.values()) {
            long old = starts.issue(origin); starts.revoke();
            long replacement = starts.issue(origin);
            assertFalse(starts.consume(old, origin));
            assertTrue(starts.consume(replacement, origin));
        }
    }
    @Test public void optOutCancelsQueuedBootButKeepsManualAuthorization() {
        long boot = starts.issue(BOOT); starts.revokeBoot();
        assertFalse(starts.consume(boot, BOOT));
        long manual = starts.issue(MANUAL); starts.revokeBoot();
        assertTrue(starts.consume(manual, MANUAL));
    }
    @Test public void revokedBootDeliveryBeforeReplacementManualPreservesNewCapability() {
        long oldBoot = starts.issue(BOOT);
        starts.revokeBoot(); // opt-out while the old framework delivery remains queued
        long manual = starts.issue(MANUAL);
        // Dispatch old BOOT first: rejection must neither consume nor revoke the newer grant.
        assertFalse(starts.consume(oldBoot, BOOT));
        assertTrue(starts.pending());
        assertFalse(starts.pendingBoot());
        assertEquals(INACTIVE, check(1_000, true));
        assertTrue(starts.consume(manual, MANUAL));
        assertFalse(starts.pending());
        assertFalse(starts.consume(oldBoot, BOOT));
        // Android stopSelf(startId) wiring is source-reviewed; this pure test does not emulate AMS.
    }
    @Test public void accessibilityAlreadyConnectedTransfersExactlyOnce() {
        deliverBoot(1_000);
        assertEquals(READY, check(1_000, true));
        assertEquals(INACTIVE, check(1_001, true));
        assertEquals(INACTIVE, check(2_000, false));
    }
    @Test public void delayedConnectionTransfersBeforeFixedDeadlineOnly() {
        deliverBoot(1_000);
        assertEquals(WAITING, check(1_000, false));
        wait.begin(90_000); // An accidental duplicate cannot buy another two minutes.
        assertEquals(WAITING, check(120_998, false));
        assertEquals(121_000, wait.deadline());
        assertEquals(READY, check(120_999, true));
    }
    @Test public void exactDeadlineWinsOverSimultaneousConnectionAndLateBindNeverRearms() {
        deliverBoot(1_000);
        assertEquals(TIMED_OUT, check(121_000, true));
        assertEquals(INACTIVE, check(121_001, true));
        assertEquals(INACTIVE, check(999_999, true));
        assertFalse(starts.pending());
    }
    @Test public void absentServiceTimesOutWithoutAnyClickOrWakePolicy() {
        deliverBoot(0);
        for (long now = 0; now < 120_000; now += 1_000) assertEquals(WAITING, check(now, false));
        assertEquals(TIMED_OUT, check(120_000, false));
    }
    @Test public void cancellationDuringWaitRejectsAllLateCallbacks() {
        deliverBoot(1_000); starts.revoke(); wait.cancel();
        assertEquals(INACTIVE, check(1_001, true));
        assertEquals(INACTIVE, check(121_000, true));
    }
    @Test public void optOutDuringWaitCannotBeRearmedByOptInOrBindingAgain() {
        deliverBoot(1_000);
        assertEquals(REVOKED, wait.evaluate(1_001, false, true, false, true));
        assertEquals(INACTIVE, check(1_002, true));
    }
    @Test public void notificationLossDuringWaitIsTerminalEvenIfRestored() {
        deliverBoot(1_000);
        assertEquals(NOTIFICATION_OFF, wait.evaluate(1_001, true, false, false, true));
        assertEquals(INACTIVE, check(1_002, true));
    }
    @Test public void manualVerificationConflictCannotTransferIntoGuard() {
        deliverBoot(1_000);
        assertEquals(CONFLICT, wait.evaluate(1_001, true, true, true, true));
        assertEquals(INACTIVE, check(1_002, true));
    }
    @Test public void serviceDisconnectRevokesWaitButRunningGuardStillHardPauses() {
        deliverBoot(1_000); wait.cancel();
        assertEquals(INACTIVE, check(1_001, true));
        deliverBoot(2_000); assertEquals(READY, check(2_001, true));
        GuardPolicy guard = new GuardPolicy(); guard.start(5);
        guard.evaluate(2_002, new GuardPolicy.Input(false, true, true, false, false, true));
        assertEquals(GuardPolicy.PauseKind.HARD, guard.pauseKind());
        assertEquals(GuardPolicy.Action.NONE, guard.evaluate(2_003,
                new GuardPolicy.Input(true, true, true, false, false, true)));
        assertEquals(GuardPolicy.State.PAUSED, guard.state());
        assertEquals(INACTIVE, check(2_004, true));
    }
    @Test public void bootClaimSurvivesProcessLossAndRejectsResetInvalidOrDuplicateCounter() {
        long persistedClaim = -1;
        assertTrue(BootWaitPolicy.mayClaimBoot(20, persistedClaim));
        persistedClaim = 20; // durable write must complete before service launch
        StartAuthorization nextProcess = new StartAuthorization(10_000);
        assertFalse(nextProcess.consume(101, BOOT));
        assertFalse(BootWaitPolicy.mayClaimBoot(20, persistedClaim));
        assertFalse(BootWaitPolicy.mayClaimBoot(19, persistedClaim));
        assertFalse(BootWaitPolicy.mayClaimBoot(-1, persistedClaim));
        assertFalse(BootWaitPolicy.mayClaimBoot(21, -2));
        assertTrue(BootWaitPolicy.mayClaimBoot(21, persistedClaim));
        assertTrue(BootWaitPolicy.mayClaimBoot(0, -1));
    }
    @Test public void processSeedsPreventPriorProcessTokenMatchingNewRequest() {
        long old = starts.issue(MANUAL);
        StartAuthorization next = new StartAuthorization(10_000);
        long fresh = next.issue(MANUAL);
        assertFalse(next.consume(old, MANUAL)); assertTrue(next.consume(fresh, MANUAL));
    }
    @Test public void savedPollOnlyAcceptsSupportedIntervals() {
        assertEquals(30, BootWaitPolicy.normalizePoll(30));
        assertEquals(60, BootWaitPolicy.normalizePoll(60));
        assertEquals(120, BootWaitPolicy.normalizePoll(120));
        for (int invalid : new int[] {-1, 0, 29, 31, 90, Integer.MAX_VALUE}) {
            assertEquals(60, BootWaitPolicy.normalizePoll(invalid));
        }
    }
}
