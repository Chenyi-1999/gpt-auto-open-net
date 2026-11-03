package com.autousb.helper;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import static org.junit.Assert.*;

public class InterfaceStateTest {
    @Test public void acceptsOnlySpecifiedContainersAndSafeNames() {
        assertArrayEquals(new String[] {"rndis0"}, InterfaceState.parse(new String[] {"rndis0"}));
        assertArrayEquals(new String[] {"usb0"}, InterfaceState.parse(new ArrayList<>(Arrays.asList("usb0"))));
        assertNull(InterfaceState.parse(Arrays.asList("rndis0")));
        assertNull(InterfaceState.parse(new Object[] {"rndis0"}));
        assertNull(InterfaceState.parse(new ArrayList<>(Arrays.asList("rndis0", 1))));
    }
    @Test public void illegalNameInvalidatesWholeListInsteadOfBecomingEmpty() {
        assertNull(InterfaceState.parse(new String[] {"192.168.1.1"}));
        assertNull(InterfaceState.parse(new String[] {"rndis0", "invalid name"}));
        assertNull(InterfaceState.parse(new String[] {null}));
        assertNull(InterfaceState.parse(new String[65]));
    }
    @Test public void overlapsOrMissingListsInvalidateAllThree() {
        assertUnknown(InterfaceState.normalize(new String[] {"rndis0"}, new String[0], new String[] {"rndis0"}));
        assertUnknown(InterfaceState.normalize(new String[] {"usb0"}, new String[] {"usb0"}, new String[0]));
        assertUnknown(InterfaceState.normalize(new String[0], new String[] {"usb0"}, new String[] {"usb0"}));
        assertUnknown(InterfaceState.normalize(new String[] {"rndis0"}, null, new String[0]));
    }
    @Test public void validEmptyIsDifferentFromUnknownAndUsbEvidenceIsNarrow() {
        String[][] valid = InterfaceState.normalize(new String[0], new String[] {"wlan0"}, new String[0]);
        assertNotNull(valid[0]); assertEquals(0, valid[0].length);
        assertTrue(InterfaceState.hasUsb(new String[] {"rndis0"}));
        assertTrue(InterfaceState.hasUsb(new String[] {"usb12"}));
        assertFalse(InterfaceState.hasUsb(new String[] {"wlan0", "ncm0"}));
    }
    @Test public void onlyFreshSharingEvidenceVetoesOffSummary() {
        assertTrue(InterfaceState.sharingConflict(true, true, false, false, null));
        assertTrue(InterfaceState.sharingConflict(true, false, true, false, null));
        assertTrue(InterfaceState.sharingConflict(false, false, false, true, new String[] {"rndis0"}));
        assertFalse(InterfaceState.sharingConflict(false, true, true, false, new String[] {"rndis0"}));
        assertFalse(InterfaceState.sharingConflict(true, false, false, true, new String[] {"wlan0"}));
    }
    private void assertUnknown(String[][] groups) { for (String[] group : groups) assertNull(group); }
}
