package com.autousb.helper;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.autousb.helper.SwitchStatePolicy.State.*;

public class ReadbackEvidenceTest {
    @Test public void laterOffDoesNotReportSuccessFromHistoricalOn() {
        ReadbackEvidence evidence = new ReadbackEvidence();
        evidence.observe(ON, 100); evidence.observe(OFF, 200);
        assertTrue(evidence.everOn());
        assertEquals(OFF, evidence.finalState(201, 1000));
    }
    @Test public void leavingPageOrUnreadableTargetClearsCurrentEvidence() {
        ReadbackEvidence evidence = new ReadbackEvidence();
        evidence.observe(ON, 100); evidence.invalidate();
        assertTrue(evidence.everOn());
        assertEquals(UNKNOWN, evidence.finalState(101, 1000));
        evidence.observe(ON, 200); evidence.observe(UNKNOWN, 201);
        assertEquals(UNKNOWN, evidence.finalState(202, 1000));
    }
    @Test public void staleReadbackAndNewAttemptCannotReuseOn() {
        ReadbackEvidence evidence = new ReadbackEvidence();
        evidence.observe(ON, 100);
        assertEquals(ON, evidence.finalState(1100, 1000));
        assertEquals(UNKNOWN, evidence.finalState(1101, 1000));
        evidence.reset();
        assertFalse(evidence.everOn());
        assertEquals(UNKNOWN, evidence.finalState(1102, 1000));
    }
    @Test public void onMustSpanOneSecondEvenWithRepeatedSamples() {
        ReadbackEvidence evidence = new ReadbackEvidence();
        evidence.observe(ON, 100);
        evidence.observe(ON, 600);
        evidence.observe(ON, 1099);
        assertFalse(evidence.confirmedOn(1099, true));
        evidence.observe(ON, 1100);
        assertTrue(evidence.confirmedOn(1100, true));
    }
    @Test public void duplicateCallbacksCannotCreateIndependentObservations() {
        ReadbackEvidence evidence = new ReadbackEvidence();
        evidence.observe(ON, 100);
        evidence.observe(ON, 100);
        assertFalse(evidence.confirmedOn(1100, true));
        evidence.observe(ON, 1100); // A one-second gap starts a new chain.
        assertFalse(evidence.confirmedOn(1100, true));
    }
    @Test public void gapBoundaryIsInclusiveAndLongerGapRestartsStreak() {
        ReadbackEvidence evidence = new ReadbackEvidence();
        evidence.observe(ON, 100);
        evidence.observe(ON, 700);
        evidence.observe(ON, 1100);
        assertTrue(evidence.confirmedOn(1100, true));
        evidence.observe(ON, 1701);
        assertFalse(evidence.confirmedOn(1701, true));
        evidence.observe(ON, 2201);
        evidence.observe(ON, 2701);
        assertTrue(evidence.confirmedOn(2701, true));
    }
    @Test public void stableOnStillNeedsCurrentInterfaceAndFreshLastSample() {
        ReadbackEvidence evidence = stableOn();
        assertFalse(evidence.confirmedOn(1100, false));
        assertTrue(evidence.confirmedOn(1700, true));
        assertFalse(evidence.confirmedOn(1701, true));
        assertFalse(evidence.confirmedOn(1099, true));
    }
    @Test public void unknownOffAndUnreadableTargetEachBreakStability() {
        for (SwitchStatePolicy.State interruption : new SwitchStatePolicy.State[] {UNKNOWN, OFF}) {
            ReadbackEvidence evidence = stableOn();
            evidence.observe(interruption, 1200);
            assertFalse(evidence.confirmedOn(1200, true));
            evidence.observe(ON, 1300);
            assertFalse(evidence.confirmedOn(1300, true));
        }
        ReadbackEvidence evidence = stableOn();
        evidence.invalidate();
        evidence.observe(ON, 1300);
        assertFalse(evidence.confirmedOn(1300, true));
    }
    @Test public void priorAttemptAndBackwardsTimestampCannotSupplyStability() {
        ReadbackEvidence evidence = stableOn();
        evidence.reset();
        evidence.observe(ON, 1200);
        assertFalse(evidence.confirmedOn(1200, true));
        evidence = stableOn();
        evidence.observe(ON, 900);
        assertFalse(evidence.confirmedOn(1100, true));
    }
    @Test public void interfaceMustBePostClickFreshAndAnActiveUsbInterface() {
        assertTrue(ReadbackEvidence.freshPostClickUsbInterface(100, 100, 2100, new String[] {"rndis0"}));
        assertTrue(ReadbackEvidence.freshPostClickUsbInterface(100, 200, 201, new String[] {"usb1"}));
        assertFalse(ReadbackEvidence.freshPostClickUsbInterface(100, 99, 101, new String[] {"rndis0"}));
        assertFalse(ReadbackEvidence.freshPostClickUsbInterface(100, 100, 2101, new String[] {"rndis0"}));
        assertFalse(ReadbackEvidence.freshPostClickUsbInterface(100, 200, 199, new String[] {"rndis0"}));
        assertFalse(ReadbackEvidence.freshPostClickUsbInterface(0, 100, 101, new String[] {"rndis0"}));
        assertFalse(ReadbackEvidence.freshPostClickUsbInterface(100, 100, 101, null));
        assertFalse(ReadbackEvidence.freshPostClickUsbInterface(100, 100, 101, new String[0]));
        assertFalse(ReadbackEvidence.freshPostClickUsbInterface(100, 100, 101, new String[] {"wlan0", "rndis", "ncm0"}));
    }
    @Test public void historicalInterfaceOrOnCannotCompleteANewAttempt() {
        ReadbackEvidence evidence = stableOn();
        boolean oldInterface = ReadbackEvidence.freshPostClickUsbInterface(100, 99, 1100, new String[] {"rndis0"});
        assertFalse(evidence.confirmedOn(1100, oldInterface));
        evidence.invalidate();
        boolean freshInterface = ReadbackEvidence.freshPostClickUsbInterface(100, 1100, 1100, new String[] {"rndis0"});
        assertTrue(evidence.everOn());
        assertFalse(evidence.confirmedOn(1100, freshInterface));
        assertEquals(UNKNOWN, evidence.finalState(1100, 1000));
    }
    private static ReadbackEvidence stableOn() {
        ReadbackEvidence evidence = new ReadbackEvidence();
        evidence.observe(ON, 100);
        evidence.observe(ON, 600);
        evidence.observe(ON, 1100);
        return evidence;
    }
}
