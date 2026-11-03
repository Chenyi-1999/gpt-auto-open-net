package com.autousb.helper;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.autousb.helper.InternalTaskRemoval.Recorded.*;

/** Bookkeeping only: Android root Intent delivery and OEM task-removal reasons are not emulated. */
public class InternalTaskRemovalTest {
    @Test public void exactRecordedWakeFinishIsConsumedOnceAndReplayFails() {
        InternalTaskRemoval tasks = new InternalTaskRemoval();
        assertEquals(ADDED, tasks.record(100, 1));
        assertTrue(tasks.consume(true, 100, 1));
        assertFalse(tasks.consume(true, 100, 1));
        assertEquals(0, tasks.size());
    }
    @Test public void otherTaskCannotConsumeWakeCredentialEvenWithMatchingIds() {
        InternalTaskRemoval tasks = new InternalTaskRemoval(); tasks.record(100, 1);
        assertFalse(tasks.consume(false, 100, 1)); // Main/null/wrong component all default to stop.
        assertEquals(1, tasks.size());
        assertTrue(tasks.consume(true, 100, 1));
    }
    @Test public void missingInvalidAndUnknownIdsNeverMatch() {
        InternalTaskRemoval tasks = new InternalTaskRemoval(); tasks.record(100, 1);
        for (long[] ids : new long[][] {{0, 1}, {100, 0}, {-1, 1}, {100, -1}, {101, 1}, {100, 2}}) {
            assertFalse(tasks.consume(true, ids[0], ids[1]));
        }
        assertEquals(REJECTED, tasks.record(0, 1));
        assertEquals(REJECTED, tasks.record(100, 0));
        assertEquals(REJECTED, tasks.record(-1, -1));
        assertEquals(1, tasks.size());
    }
    @Test public void delayedOldAttemptConsumesOnlyItsOwnCredentialWithoutCurrentPolicyState() {
        InternalTaskRemoval tasks = new InternalTaskRemoval();
        tasks.record(100, 1); tasks.record(100, 2); tasks.record(200, 1);
        assertTrue(tasks.consume(true, 100, 1));
        assertFalse(tasks.consume(true, 100, 1));
        assertEquals(2, tasks.size());
        assertTrue(tasks.consume(true, 200, 1));
        assertTrue(tasks.consume(true, 100, 2));
    }
    @Test public void repeatedPendingRegistrationDoesNotCreateExtraUsesOrRefreshEvictionOrder() {
        InternalTaskRemoval tasks = new InternalTaskRemoval();
        for (int i = 1; i <= InternalTaskRemoval.CAPACITY; i++) assertEquals(ADDED, tasks.record(100, i));
        assertEquals(ALREADY_PENDING, tasks.record(100, 1));
        assertEquals(InternalTaskRemoval.CAPACITY, tasks.size());
        assertEquals(DROPPED_OLDEST, tasks.record(100, 9));
        assertFalse(tasks.consume(true, 100, 1));
        assertTrue(tasks.consume(true, 100, 2));
        assertFalse(tasks.consume(true, 100, 2));
    }
    @Test public void capacityAlwaysKeepsOnlyEightNewestPendingFinishes() {
        InternalTaskRemoval tasks = new InternalTaskRemoval();
        for (int i = 1; i <= 100; i++) {
            assertEquals(i <= 8 ? ADDED : DROPPED_OLDEST, tasks.record(100, i));
            assertTrue(tasks.size() <= 8);
        }
        for (int i = 1; i <= 92; i++) assertFalse(tasks.consume(true, 100, i));
        for (int i = 93; i <= 100; i++) assertTrue(tasks.consume(true, 100, i));
        assertEquals(0, tasks.size());
    }
    @Test public void serviceShutdownClearRevokesAllPendingInternalFinishes() {
        InternalTaskRemoval tasks = new InternalTaskRemoval(); tasks.record(100, 1); tasks.record(100, 2);
        tasks.clear();
        assertEquals(0, tasks.size());
        assertFalse(tasks.consume(true, 100, 1));
        assertFalse(tasks.consume(true, 100, 2));
    }
    @Test public void anotherServiceInstanceCannotInheritUnconsumedCredential() {
        InternalTaskRemoval oldService = new InternalTaskRemoval(); oldService.record(100, 1);
        InternalTaskRemoval newService = new InternalTaskRemoval();
        assertFalse(newService.consume(true, 100, 1));
        assertTrue(oldService.consume(true, 100, 1));
    }
}
