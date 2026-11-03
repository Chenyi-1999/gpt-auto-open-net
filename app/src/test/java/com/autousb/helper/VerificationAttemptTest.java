package com.autousb.helper;

import org.junit.Test;
import static org.junit.Assert.*;

public class VerificationAttemptTest {
    private static VerificationAttempt attempt(VerificationAttempt.Owner owner, long session, long id) {
        return new VerificationAttempt(owner, session, id, null);
    }
    @Test public void manualAndGuardCannotReplaceEachOtherWhileBusy() {
        VerificationAttempt.Slot slot = new VerificationAttempt.Slot();
        VerificationAttempt manual = attempt(VerificationAttempt.Owner.MANUAL, 0, 1);
        VerificationAttempt guard = attempt(VerificationAttempt.Owner.GUARD, 1, 1);
        assertTrue(slot.claim(manual, false));
        assertFalse(slot.claim(guard, true));
        assertSame(manual, slot.current());
        assertTrue(slot.release(manual));
        assertTrue(slot.claim(guard, true));
        assertFalse(slot.claim(manual, false));
        assertSame(guard, slot.current());
    }
    @Test public void guardIncludingPausedOwnsManualEntryUntilStopped() {
        VerificationAttempt.Slot slot = new VerificationAttempt.Slot();
        VerificationAttempt manual = attempt(VerificationAttempt.Owner.MANUAL, 0, 1);
        assertFalse(slot.claim(manual, true));
        assertNull(slot.current());
        assertTrue(slot.claim(manual, false));
    }
    @Test public void staleOrDuplicateReleaseCannotDetachNewAttempt() {
        VerificationAttempt.Slot slot = new VerificationAttempt.Slot();
        VerificationAttempt old = attempt(VerificationAttempt.Owner.GUARD, 1, 1);
        VerificationAttempt current = attempt(VerificationAttempt.Owner.GUARD, 2, 1);
        assertTrue(slot.claim(old, true));
        assertTrue(slot.release(old));
        assertFalse(slot.release(old));
        assertTrue(slot.claim(current, true));
        assertFalse(slot.release(old));
        assertSame(current, slot.current());
        assertFalse(slot.release(null));
    }
}
