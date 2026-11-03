package com.autousb.helper;

import org.junit.Test;
import static org.junit.Assert.*;

public class SummaryEvidenceTest {
    @Test public void visibleUniqueOffIsUsable() {
        SummaryEvidence evidence = new SummaryEvidence();
        evidence.observe("android:id/title", true, "USB 共享网络");
        evidence.observe("android:id/summary", true, SwitchStatePolicy.OFF_SUMMARY);
        assertEquals(SwitchStatePolicy.OFF_SUMMARY, evidence.value());
    }
    @Test public void hiddenOldOffCannotOverrideVisibleOn() {
        SummaryEvidence evidence = new SummaryEvidence();
        evidence.observe("android:id/summary", true, SwitchStatePolicy.ON_SUMMARY);
        evidence.observe("android:id/summary", false, SwitchStatePolicy.OFF_SUMMARY);
        assertEquals("", evidence.value());
    }
    @Test public void hiddenOffAloneIsUnknown() {
        SummaryEvidence evidence = new SummaryEvidence();
        evidence.observe("android:id/summary", false, SwitchStatePolicy.OFF_SUMMARY);
        assertEquals("", evidence.value());
    }
    @Test public void duplicateVisibleSummaryIsAmbiguous() {
        SummaryEvidence evidence = new SummaryEvidence();
        evidence.observe("android:id/summary", true, SwitchStatePolicy.OFF_SUMMARY);
        evidence.observe("android:id/summary", true, SwitchStatePolicy.OFF_SUMMARY);
        assertEquals("", evidence.value());
    }
    @Test public void foreignIdOrUnknownTextIsNotEvidence() {
        SummaryEvidence foreign = new SummaryEvidence();
        foreign.observe("other:id/summary", true, SwitchStatePolicy.OFF_SUMMARY);
        assertEquals("", foreign.value());
        SummaryEvidence unknown = new SummaryEvidence();
        unknown.observe("android:id/summary", true, "尚未识别的说明");
        assertEquals("", unknown.value());
    }
}
