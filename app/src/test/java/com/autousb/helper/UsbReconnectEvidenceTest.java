package com.autousb.helper;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.autousb.helper.UsbReconnectEvidence.Connection.*;

public class UsbReconnectEvidenceTest {
    @Test public void repeatedNoPreservesStartAndRepeatedYesCannotCreateAnotherCycle() {
        UsbReconnectEvidence evidence = new UsbReconnectEvidence();
        evidence.observe(NO, 100, true);
        evidence.observe(NO, 2_000, true);
        evidence.observe(YES, 3_100, true);
        evidence.observe(YES, 9_000, true);
        assertEquals(1, evidence.generation());
        assertEquals(100, evidence.cycleStartedAt());
        assertEquals(3_100, evidence.cycleCompletedAt());
    }
    @Test public void shortReconfigurationAndUnknownBreakCannotQualify() {
        UsbReconnectEvidence evidence = new UsbReconnectEvidence();
        evidence.observe(NO, 100, true);
        evidence.observe(YES, 3_099, true);
        evidence.observe(NO, 4_000, true);
        evidence.observe(UNKNOWN, 7_001, true);
        evidence.observe(YES, 8_000, true);
        assertEquals(0, evidence.generation());
        evidence.observe(NO, 9_000, true);
        evidence.observe(YES, 12_000, true);
        assertEquals(1, evidence.generation());
        assertEquals(9_000, evidence.cycleStartedAt());
    }
    @Test public void stickyRefreshAndConfiguredOnlyYesCannotInventDisconnect() {
        UsbReconnectEvidence evidence = new UsbReconnectEvidence();
        evidence.observe(NO, 100, false); // Initial sticky / explicit refresh.
        evidence.observe(YES, 4_000, false);
        evidence.observe(YES, 5_000, true); // Configured changes while already connected.
        evidence.observe(YES, 9_000, true);
        assertEquals(0, evidence.generation());
        evidence.observe(NO, 10_000, true);
        evidence.observe(YES, 13_000, false); // A read cannot complete the cycle.
        assertEquals(0, evidence.generation());
    }
    @Test public void refreshOrInitialStickyYesAndUnknownInvalidatePendingDisconnect() {
        for (UsbReconnectEvidence.Connection read : new UsbReconnectEvidence.Connection[] { YES, UNKNOWN }) {
            UsbReconnectEvidence evidence = new UsbReconnectEvidence();
            evidence.observe(NO, 100, true);
            evidence.observe(read, 250, false);
            evidence.observe(YES, 4_000, true); // A later configured-only broadcast cannot complete old NO.
            assertEquals(0, evidence.generation());
            evidence.observe(NO, 5_000, true);
            evidence.observe(YES, 8_000, true);
            assertEquals(1, evidence.generation());
            assertEquals(5_000, evidence.cycleStartedAt());
        }
    }
    @Test public void nonDynamicNoNeitherEstablishesNorMovesDisconnectStart() {
        UsbReconnectEvidence evidence = new UsbReconnectEvidence();
        evidence.observe(NO, 100, false);
        evidence.observe(YES, 4_000, true);
        assertEquals(0, evidence.generation());
        evidence.observe(NO, 5_000, true);
        evidence.observe(NO, 7_000, false);
        evidence.observe(YES, 8_000, true);
        assertEquals(1, evidence.generation());
        assertEquals(5_000, evidence.cycleStartedAt());
    }
}
