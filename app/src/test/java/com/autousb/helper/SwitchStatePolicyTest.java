package com.autousb.helper;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.autousb.helper.SwitchStatePolicy.State.*;

public class SwitchStatePolicyTest {
    private SwitchStatePolicy.State huawei(boolean raw, String summary, boolean device, boolean page) {
        return SwitchStatePolicy.resolve(false, raw, true, true, device, page,
                "android:id/switch_widget", "android.widget.Switch", summary);
    }
    @Test public void standardCheckedStateNeverClicks() {
        SwitchStatePolicy.State state = SwitchStatePolicy.resolve(true, true, true, true, false, false, "", "", "");
        assertEquals(ON, state);
        assertFalse(SwitchStatePolicy.mayClickOff(state, true, true, true, false));
    }
    @Test public void standardOffCanClickOnlyWithoutConflict() {
        SwitchStatePolicy.State state = SwitchStatePolicy.resolve(true, false, true, true, false, false, "", "", "");
        assertEquals(OFF, state);
        assertTrue(SwitchStatePolicy.mayClickOff(state, false, true, true, false));
        assertFalse(SwitchStatePolicy.mayClickOff(state, false, true, true, true));
        assertFalse(SwitchStatePolicy.mayClickOff(state, true, true, true, false));
    }
    @Test public void huaweiComparedOnNeverClicksAndOffRequiresExactSummary() {
        assertEquals(ON, huawei(true, SwitchStatePolicy.ON_SUMMARY, true, true));
        assertFalse(SwitchStatePolicy.mayClickOff(huawei(true, SwitchStatePolicy.ON_SUMMARY, true, true), true, true, true, false));
        assertEquals(OFF, huawei(false, SwitchStatePolicy.OFF_SUMMARY, true, true));
        assertTrue(SwitchStatePolicy.mayClickOff(huawei(false, SwitchStatePolicy.OFF_SUMMARY, true, true), false, true, true, false));
    }
    @Test public void contradictoryOrUnknownSummaryIsUnknown() {
        assertEquals(UNKNOWN, huawei(true, SwitchStatePolicy.OFF_SUMMARY, true, true));
        assertEquals(UNKNOWN, huawei(false, SwitchStatePolicy.ON_SUMMARY, true, true));
        assertEquals(UNKNOWN, huawei(false, "", true, true));
        assertEquals(UNKNOWN, SwitchStatePolicy.resolve(true, false, true, true, false, false, "", "", SwitchStatePolicy.ON_SUMMARY));
    }
    @Test public void wrongDeviceBuildPageOrNodeCannotUseFallback() {
        assertTrue(SwitchStatePolicy.supportedHuawei("HUAWEI", "WLZ-AN00", 31, "WLZ-AN00 4.2.0.121(C00E121R3P3)"));
        assertFalse(SwitchStatePolicy.supportedHuawei("OTHER", "WLZ-AN00", 31, "WLZ-AN00 4.2.0.121(C00E121R3P3)"));
        assertFalse(SwitchStatePolicy.supportedHuawei("HUAWEI", "OTHER", 31, "WLZ-AN00 4.2.0.121(C00E121R3P3)"));
        assertFalse(SwitchStatePolicy.supportedHuawei("HUAWEI", "WLZ-AN00", 32, "WLZ-AN00 4.2.0.121(C00E121R3P3)"));
        assertFalse(SwitchStatePolicy.supportedHuawei("HUAWEI", "WLZ-AN00", 31, "different build"));
        assertEquals(UNKNOWN, huawei(false, SwitchStatePolicy.OFF_SUMMARY, false, true));
        assertEquals(UNKNOWN, huawei(false, SwitchStatePolicy.OFF_SUMMARY, true, false));
        assertEquals(UNKNOWN, SwitchStatePolicy.resolve(false, false, true, true, true, true,
                "other:id/switch_widget", "android.widget.Switch", SwitchStatePolicy.OFF_SUMMARY));
    }
    @Test public void unsupportedOrDisabledSwitchCannotClick() {
        assertEquals(UNKNOWN, SwitchStatePolicy.resolve(false, false, false, true, true, true,
                "android:id/switch_widget", "android.widget.Switch", SwitchStatePolicy.OFF_SUMMARY));
        assertFalse(SwitchStatePolicy.mayClickOff(UNKNOWN, false, true, true, false));
        assertFalse(SwitchStatePolicy.mayClickOff(OFF, false, false, true, false));
    }
    @Test public void staleOrInvalidCurrentValueIsUnknownAndHistoryIsUnchanged() {
        Boolean historical = true;
        assertEquals(Boolean.TRUE, SwitchStatePolicy.currentValue(historical, true, 100, 120_100, 120_000));
        assertNull(SwitchStatePolicy.currentValue(historical, true, 100, 120_101, 120_000));
        assertNull(SwitchStatePolicy.currentValue(historical, false, 100, 101, 120_000));
        assertNull(SwitchStatePolicy.currentValue(false, true, 100, 120_101, 120_000));
        assertTrue(historical);
    }
}
