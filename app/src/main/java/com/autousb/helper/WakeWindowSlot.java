package com.autousb.helper;

import java.lang.ref.WeakReference;

/** One Activity instance owns a wake window. Detach before framework/lifecycle callbacks. */
final class WakeWindowSlot<T> {
    private WeakReference<T> reference = new WeakReference<>(null);
    private long session, attempt;
    T current() { return reference.get(); }
    long sessionId() { return session; }
    long attemptId() { return attempt; }
    boolean matches(T instance, long session, long attempt) {
        return instance != null && current() == instance && this.session == session && this.attempt == attempt;
    }
    T replace(T instance, long session, long attempt) {
        T old = current();
        reference = new WeakReference<>(instance); this.session = session; this.attempt = attempt;
        return old;
    }
    boolean release(T instance, long session, long attempt) {
        if (!matches(instance, session, attempt)) return false;
        clear(); return true;
    }
    T clear() {
        T old = current(); reference.clear(); session = 0; attempt = 0; return old;
    }
}
