package com.autousb.helper;

import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Conservative structural match; an unknown Huawei layout must fail closed. */
final class TargetDetector {
    static final class Target {
        final AccessibilityNodeInfo node;
        final String summary;
        final boolean exactHuaweiPageAndRow;
        Target(AccessibilityNodeInfo node, String summary, boolean exactHuaweiPageAndRow) {
            this.node = node; this.summary = summary; this.exactHuaweiPageAndRow = exactHuaweiPageAndRow;
        }
    }
    private static final Set<String> USB_LABELS = new HashSet<>(Arrays.asList(
            "USB共享网络", "USB 网络共享", "USB网络共享", "USB 共享网络"));
    private static final Set<String> PAGE_TITLES = new HashSet<>(Arrays.asList(
            "网络共享", "网络共享与便携式热点", "移动网络共享", "个人热点", "更多共享设置"));
    private static String text(AccessibilityNodeInfo node) {
        CharSequence value = node.getText(); return value == null ? "" : value.toString().trim();
    }
    private static boolean settings(AccessibilityNodeInfo root) {
        return root != null && "com.android.settings".contentEquals(root.getPackageName() == null ? "" : root.getPackageName());
    }
    static boolean confirmedPage(AccessibilityNodeInfo root) {
        return confirmedTitle(root, false);
    }
    private static boolean confirmedTitle(AccessibilityNodeInfo root, boolean exactHuawei) {
        if (!settings(root)) return false;
        ArrayList<AccessibilityNodeInfo> nodes = descendants(root, 500);
        if (nodes == null) return false;
        try {
            for (AccessibilityNodeInfo node : nodes) {
                String id = node.getViewIdResourceName();
                if (exactHuawei && node.isVisibleToUser() && "更多共享设置".equals(text(node))
                        && "android:id/action_bar_title".equals(id)) return true;
                if (exactHuawei) continue;
                if (node.isVisibleToUser() && PAGE_TITLES.contains(text(node)) && id != null
                        && (id.endsWith(":id/action_bar_title") || id.endsWith(":id/toolbar_title"))) return true;
            }
            return false;
        } finally { recycle(nodes); }
    }
    static Target findUsbTarget(AccessibilityNodeInfo root) {
        if (!confirmedPage(root)) return null;
        ArrayList<AccessibilityNodeInfo> nodes = descendants(root, 500);
        if (nodes == null) return null;
        AccessibilityNodeInfo label = null;
        int labels = 0;
        for (AccessibilityNodeInfo node : nodes) {
            if (node.isVisibleToUser() && USB_LABELS.contains(text(node))) { labels++; label = node; }
        }
        if (labels != 1) { recycle(nodes); return null; }
        boolean exactHuaweiPageAndRow = confirmedTitle(root, true) && "USB 共享网络".equals(text(label))
                && "android:id/title".equals(label.getViewIdResourceName());
        AccessibilityNodeInfo ancestor = label.getParent();
        Target selected = null;
        // Only a compact nearby row, never a full preference list or arbitrary Switch.
        for (int level = 0; ancestor != null && level < 3; level++) {
            ArrayList<AccessibilityNodeInfo> row = descendants(ancestor, 24);
            if (row == null) { ancestor.recycle(); ancestor = null; break; }
            int usbLabels = 0, checks = 0;
            boolean unrelatedRowText = false;
            SummaryEvidence summaryEvidence = new SummaryEvidence();
            AccessibilityNodeInfo candidate = null;
            for (AccessibilityNodeInfo node : row) {
                if (node.isVisibleToUser() && USB_LABELS.contains(text(node))) usbLabels++;
                CharSequence nodeClass = node.getClassName();
                if (node.isVisibleToUser() && (node.isCheckable() || "android.widget.Switch".contentEquals(nodeClass == null ? "" : nodeClass))) {
                    checks++; candidate = node;
                }
                String nodeText = text(node);
                String nodeId = node.getViewIdResourceName();
                // A compact list can still contain another preference: never bind its switch.
                if (node.isVisibleToUser() && !nodeText.isEmpty() && !USB_LABELS.contains(nodeText)
                        && (nodeId == null || !nodeId.endsWith(":id/summary"))) unrelatedRowText = true;
                summaryEvidence.observe(nodeId, node.isVisibleToUser(), nodeText);
            }
            if (!unrelatedRowText && usbLabels == 1 && checks == 1
                    && candidate.getViewIdResourceName() != null && candidate.getViewIdResourceName().endsWith(":id/switch_widget")
                    && ("android.widget.Switch".contentEquals(candidate.getClassName() == null ? "" : candidate.getClassName())
                    || "android.widget.CompoundButton".contentEquals(candidate.getClassName() == null ? "" : candidate.getClassName()))) {
                selected = new Target(AccessibilityNodeInfo.obtain(candidate), summaryEvidence.value(), exactHuaweiPageAndRow);
                recycle(row); ancestor.recycle(); ancestor = null; break;
            }
            // Any checkable node in the small row that is ambiguous/disabled is a refusal.
            boolean refuse = checks > 0 || unrelatedRowText;
            recycle(row);
            AccessibilityNodeInfo next = refuse ? null : ancestor.getParent();
            ancestor.recycle(); ancestor = next;
        }
        if (ancestor != null) ancestor.recycle();
        recycle(nodes);
        return selected;
    }
    private static ArrayList<AccessibilityNodeInfo> descendants(AccessibilityNodeInfo root, int limit) {
        ArrayList<AccessibilityNodeInfo> out = new ArrayList<>();
        out.add(AccessibilityNodeInfo.obtain(root));
        for (int i = 0; i < out.size(); i++) {
            AccessibilityNodeInfo node = out.get(i);
            for (int j = 0; j < node.getChildCount(); j++) {
                AccessibilityNodeInfo child = node.getChild(j);
                if (child != null) out.add(child);
                if (out.size() > limit) { recycle(out); return null; }
            }
        }
        return out;
    }
    private static void recycle(ArrayList<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo node : nodes) node.recycle();
    }
}
