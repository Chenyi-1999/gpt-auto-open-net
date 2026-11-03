package com.autousb.helper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** A malformed or contradictory broadcast has no usable interface lists. */
final class InterfaceState {
    static String[] parse(Object value) {
        Object[] items;
        if (value instanceof String[]) items = (String[]) value;
        else if (value instanceof ArrayList<?>) items = ((ArrayList<?>) value).toArray();
        else return null;
        if (items.length > 64) return null;
        Set<String> safe = new LinkedHashSet<>();
        for (Object item : items) {
            if (!(item instanceof String) || !((String) item).matches("[A-Za-z][A-Za-z0-9_.-]{0,31}")) return null;
            safe.add((String) item);
        }
        return safe.toArray(new String[0]);
    }
    static String[][] normalize(String[] tethered, String[] available, String[] errored) {
        if (tethered == null || available == null || errored == null) return unknown();
        Set<String> seen = new LinkedHashSet<>();
        for (String[] group : new String[][] {tethered, available, errored}) {
            for (String name : group) if (!seen.add(name)) return unknown();
        }
        return new String[][] {tethered, available, errored};
    }
    private static String[][] unknown() { return new String[][] {null, null, null}; }
    static boolean hasUsb(String[] interfaces) {
        return interfaces != null && Arrays.stream(interfaces).anyMatch(name -> name.matches("(?:usb|rndis)[0-9]+"));
    }
    static boolean sharingConflict(boolean usbFresh, boolean rndis, boolean ncm, boolean tetherFresh, String[] tethered) {
        return (usbFresh && (rndis || ncm)) || (tetherFresh && hasUsb(tethered));
    }
}
