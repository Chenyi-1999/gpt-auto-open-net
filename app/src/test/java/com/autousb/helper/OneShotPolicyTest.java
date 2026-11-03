package com.autousb.helper;

import org.junit.Test;
import static org.junit.Assert.*;

public class OneShotPolicyTest {
    @Test public void busyArmDoesNotReplaceOrCancelConsumedToken() {
        OneShotPolicy policy = new OneShotPolicy();
        assertTrue(policy.arm(100, true, true, true));
        assertTrue(policy.consume(101, true, true, true, false));
        assertFalse(policy.arm(102, true, true, true));
        assertEquals(OneShotPolicy.Phase.VERIFYING, policy.phase());
        assertFalse(policy.consume(103, true, true, true, false));
    }
    @Test public void unknownOrDisconnectedCannotArm() {
        OneShotPolicy policy = new OneShotPolicy();
        assertFalse(policy.arm(100, true, false, true));
        assertEquals(OneShotPolicy.Phase.IDLE, policy.phase());
    }
    @Test public void lockedOrServiceMissingCannotArm() {
        OneShotPolicy policy = new OneShotPolicy();
        assertFalse(policy.arm(100, true, true, false));
        assertFalse(policy.arm(100, false, true, true));
    }
    @Test public void checkedOrAmbiguousTargetNeverConsumes() {
        OneShotPolicy policy = new OneShotPolicy();
        assertTrue(policy.arm(100, true, true, true));
        assertFalse(policy.consume(101, true, true, true, true));
        assertFalse(policy.consume(102, true, true, false, false));
        assertEquals(OneShotPolicy.Phase.ARMED, policy.phase());
    }
    @Test public void tokenCanOnlyBeConsumedOnce() {
        OneShotPolicy policy = new OneShotPolicy();
        assertTrue(policy.arm(100, true, true, true));
        assertTrue(policy.consume(101, true, true, true, false));
        assertEquals(OneShotPolicy.Phase.VERIFYING, policy.phase());
        assertFalse(policy.consume(102, true, true, true, false));
        policy.cancel();
        assertFalse(policy.consume(103, true, true, true, false));
    }
    @Test public void expiryBoundaryAndRevocationPreventClick() {
        OneShotPolicy policy = new OneShotPolicy();
        policy.arm(100, true, true, true);
        assertFalse(policy.consume(30_100, true, true, true, false));
        policy.arm(100, true, true, true);
        assertFalse(policy.consume(101, false, true, true, false));
        policy.arm(100, true, true, true);
        assertFalse(policy.consume(101, true, false, true, false));
        assertEquals(OneShotPolicy.Phase.IDLE, policy.phase());
    }
}
