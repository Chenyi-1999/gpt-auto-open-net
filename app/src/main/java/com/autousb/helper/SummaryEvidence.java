package com.autousb.helper;

/** A hidden or duplicate summary invalidates the whole row's summary evidence. */
final class SummaryEvidence {
    private int count;
    private boolean valid = true;
    private String summary = "";
    void observe(String id, boolean visible, String text) {
        if (!"android:id/summary".equals(id)) return;
        count++;
        boolean known = SwitchStatePolicy.OFF_SUMMARY.equals(text)
                || SwitchStatePolicy.ON_SUMMARY.equals(text)
                || "USB已连接，选中以共享网络".equals(text)
                || "USB 未连接".equals(text) || "USB未连接".equals(text);
        if (!visible || !known) valid = false;
        else summary = text;
    }
    String value() { return count == 1 && valid ? summary : ""; }
}
