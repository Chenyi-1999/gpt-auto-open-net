package com.autousb.helper;

/** Pure rules. Huawei fallback is pinned to the one manually compared device/build. */
final class SwitchStatePolicy {
    enum State { ON, OFF, UNKNOWN }
    static final String ADAPTER_NOTE = "华为本机已对照适配，待自动开启验证";
    static final String OFF_SUMMARY = "USB 已连接，选中以共享网络";
    static final String ON_SUMMARY = "已共享网络";
    static boolean supportedHuawei(String maker, String model, int sdk, String display) {
        return "HUAWEI".equals(maker) && "WLZ-AN00".equals(model) && sdk == 31
                && "WLZ-AN00 4.2.0.121(C00E121R3P3)".equals(display);
    }
    static State resolve(boolean checkable, boolean rawChecked, boolean enabled, boolean clickable,
                         boolean exactHuaweiDevice, boolean exactHuaweiPageAndRow,
                         String nodeId, String nodeClass, String summary) {
        if (checkable) {
            if (!rawChecked && ON_SUMMARY.equals(summary)) return State.UNKNOWN;
            return rawChecked ? State.ON : State.OFF;
        }
        if (!exactHuaweiDevice || !exactHuaweiPageAndRow || !enabled || !clickable
                || !"android:id/switch_widget".equals(nodeId) || !"android.widget.Switch".equals(nodeClass)) return State.UNKNOWN;
        if (rawChecked && ON_SUMMARY.equals(summary)) return State.ON;
        if (!rawChecked && OFF_SUMMARY.equals(summary)) return State.OFF;
        return State.UNKNOWN;
    }
    static boolean mayClickOff(State state, boolean rawChecked, boolean enabled, boolean clickable, boolean sharingConflict) {
        return state == State.OFF && !rawChecked && enabled && clickable && !sharingConflict;
    }
    static Boolean currentValue(Boolean historical, boolean readable, long observedAt, long now, long maxAge) {
        return readable && observedAt > 0 && now >= observedAt && now - observedAt <= maxAge ? historical : null;
    }
}
