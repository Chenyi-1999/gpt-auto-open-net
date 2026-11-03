package com.autousb.helper;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.autousb.helper.WakeDiagnostics.TimeoutFact.*;

public class WakeDiagnosticsTest {
    private static WakeDiagnostics started() {
        WakeDiagnostics diagnostics = new WakeDiagnostics(); diagnostics.begin(10, 1); return diagnostics;
    }
    @Test public void timeoutReportsObservedLifecycleWithoutClaimingLaunchWasRejected() {
        WakeDiagnostics diagnostics = started();
        assertEquals(CREATED_NOT_OBSERVED, diagnostics.timedOut(false, false, false));
        diagnostics = started(); diagnostics.observe(10, 1, "CREATED");
        assertEquals(RESUMED_NOT_OBSERVED, diagnostics.timedOut(false, false, false));
        diagnostics.observe(10, 1, "RESUMED"); diagnostics.observe(10, 1, "PAUSED");
        assertEquals(RESUMED_GATES_NOT_MET, diagnostics.timedOut(false, false, false));
        assertEquals("TIMED_OUT", diagnostics.stage);
        assertEquals("RESUMED_GATES_NOT_MET", diagnostics.timeoutFact);
    }
    @Test public void anyUnreadableUnlockGateIsUnknownRatherThanImplicitlyFalse() {
        for (Boolean[] gates : new Boolean[][] {
                {null, false, false}, {true, null, false}, {true, false, null}}) {
            WakeDiagnostics diagnostics = started();
            diagnostics.observe(10, 1, "CREATED"); diagnostics.observe(10, 1, "RESUMED");
            assertEquals(RESUMED_GATES_UNKNOWN, diagnostics.timedOut(gates[0], gates[1], gates[2]));
        }
    }
    @Test public void resumedWithEachFailedGateAndAllSatisfiedHaveDistinctFacts() {
        WakeDiagnostics diagnostics = started();
        diagnostics.observe(10, 1, "CREATED"); diagnostics.observe(10, 1, "RESUMED");
        assertEquals(RESUMED_GATES_NOT_MET, diagnostics.timedOut(false, false, false));
        assertEquals(RESUMED_GATES_NOT_MET, diagnostics.timedOut(true, true, false));
        assertEquals(RESUMED_GATES_NOT_MET, diagnostics.timedOut(true, false, true));
        assertEquals(RESUMED_GATES_MET, diagnostics.timedOut(true, false, false));
    }
    @Test public void staleSessionOrAttemptCannotChangeCurrentLifecycleFacts() {
        WakeDiagnostics diagnostics = started();
        assertFalse(diagnostics.observe(9, 1, "CREATED"));
        assertFalse(diagnostics.observe(10, 2, "RESUMED"));
        assertFalse(diagnostics.observe(10, 0, "CREATED"));
        assertEquals("STARTED", diagnostics.stage);
        assertFalse(diagnostics.created); assertFalse(diagnostics.resumed);
        diagnostics.observe(10, 1, "CREATED"); diagnostics.observe(10, 1, "RESUMED");
        diagnostics.begin(11, 1);
        assertFalse(diagnostics.observe(10, 1, "DESTROYED"));
        assertFalse(diagnostics.created); assertFalse(diagnostics.resumed);
        assertEquals(CREATED_NOT_OBSERVED, diagnostics.timedOut(true, false, false));
        diagnostics.clear();
        assertFalse(diagnostics.observe(11, 1, "RESUMED"));
        assertEquals(0, diagnostics.sessionId); assertEquals(0, diagnostics.attemptId);
        assertEquals("NONE", diagnostics.stage); assertEquals("", diagnostics.timeoutFact);
    }
    @Test public void repeatedFocusAndLifecycleCallbacksHaveAFixedPerActivityBudget() {
        WakeDiagnostics.Lifecycle lifecycle = new WakeDiagnostics.Lifecycle();
        int rows = 0;
        for (int repeat = 0; repeat < 1000; repeat++) {
            for (WakeDiagnostics.Boundary boundary : WakeDiagnostics.Boundary.values()) {
                if (lifecycle.first(boundary)) rows++;
            }
        }
        assertEquals(6, rows);
        assertTrue(new WakeDiagnostics.Lifecycle().first(WakeDiagnostics.Boundary.CREATED));
    }
}
