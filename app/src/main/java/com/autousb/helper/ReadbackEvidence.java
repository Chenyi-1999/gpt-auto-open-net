package com.autousb.helper;

/** Historical ON is diagnostic only; the final conclusion uses the last fresh observation. */
final class ReadbackEvidence {
    static final long MIN_ON_SPAN_MS = 1_000;
    static final long MAX_ON_GAP_MS = 600;
    static final long MAX_INTERFACE_AGE_MS = 2_000;
    private SwitchStatePolicy.State latest = SwitchStatePolicy.State.UNKNOWN;
    private long observedAt;
    private boolean everOn;
    private long onSince;
    private int onSamples;
    void reset() { invalidate(); everOn = false; }
    void observe(SwitchStatePolicy.State value, long now) {
        if (now <= 0) { invalidate(); return; }
        if (value == SwitchStatePolicy.State.ON) {
            // Only a continuous chain of distinct, fresh observations can confirm ON.
            if (latest != SwitchStatePolicy.State.ON || onSince == 0 || now < observedAt
                    || now - observedAt > MAX_ON_GAP_MS) {
                onSince = now; onSamples = 1;
            } else if (now > observedAt) {
                onSamples = Math.min(2, onSamples + 1);
            }
            everOn = true;
        } else {
            onSince = 0; onSamples = 0;
        }
        latest = value == null ? SwitchStatePolicy.State.UNKNOWN : value;
        observedAt = now;
    }
    void invalidate() {
        latest = SwitchStatePolicy.State.UNKNOWN; observedAt = 0; onSince = 0; onSamples = 0;
    }
    boolean everOn() { return everOn; }
    boolean confirmedOn(long now, boolean interfaceEvidenceValid) {
        return interfaceEvidenceValid && finalState(now, MAX_ON_GAP_MS) == SwitchStatePolicy.State.ON
                && onSince > 0 && onSamples >= 2 && observedAt - onSince >= MIN_ON_SPAN_MS;
    }
    static boolean freshPostClickUsbInterface(long clickStartedAt, long tetherAt, long now, String[] tethered) {
        return clickStartedAt > 0 && tetherAt >= clickStartedAt && now >= tetherAt
                && now - tetherAt <= MAX_INTERFACE_AGE_MS && InterfaceState.hasUsb(tethered);
    }
    SwitchStatePolicy.State finalState(long now, long maxAge) {
        return observedAt > 0 && now >= observedAt && now - observedAt <= maxAge ? latest : SwitchStatePolicy.State.UNKNOWN;
    }
}
