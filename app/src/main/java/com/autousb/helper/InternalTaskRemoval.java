package com.autousb.helper;

import java.util.ArrayDeque;
import java.util.Iterator;

/** Service-instance bookkeeping for internal finishes, never authority to wake, navigate or click. */
final class InternalTaskRemoval {
    static final int CAPACITY = 8;
    enum Recorded { ADDED, ALREADY_PENDING, DROPPED_OLDEST, REJECTED }
    private static final class Credential {
        final long session, attempt;
        Credential(long session, long attempt) { this.session = session; this.attempt = attempt; }
        boolean matches(long session, long attempt) { return this.session == session && this.attempt == attempt; }
    }
    private final ArrayDeque<Credential> pending = new ArrayDeque<>();
    Recorded record(long session, long attempt) {
        if (session <= 0 || attempt <= 0) return Recorded.REJECTED;
        for (Credential value : pending) if (value.matches(session, attempt)) return Recorded.ALREADY_PENDING;
        boolean full = pending.size() == CAPACITY;
        if (full) pending.removeFirst();
        pending.addLast(new Credential(session, attempt));
        return full ? Recorded.DROPPED_OLDEST : Recorded.ADDED;
    }
    boolean consume(boolean exactWakeComponent, long session, long attempt) {
        if (!exactWakeComponent || session <= 0 || attempt <= 0) return false;
        Iterator<Credential> entries = pending.iterator();
        while (entries.hasNext()) {
            if (entries.next().matches(session, attempt)) { entries.remove(); return true; }
        }
        return false;
    }
    int size() { return pending.size(); }
    void clear() { pending.clear(); }
}
