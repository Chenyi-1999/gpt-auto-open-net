package com.autousb.helper;

import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.Arrays;

/** Read-only, fixed-vocabulary diagnostics. A result never authorizes an action. */
final class UsbModeDialogProbe {
    static final int NODE_LIMIT = 128, CANDIDATE_LIMIT = 2;
    private static final String[] LABELS = { "USB连接方式", "传输照片", "传输文件", "仅充电", "取消" };
    enum Status { COMPLETE, NODE_LIMIT, UNREADABLE, SECURITY_DENIED, FOREIGN_NODE }

    /** Borrowed root; every child acquired by the scanner is released, including on failure. */
    interface Node {
        boolean settings();
        CharSequence text(); CharSequence description(); CharSequence nodeClass(); String resourceId();
        int windowId(); int childCount(); Node child(int index);
        boolean visible(); boolean enabled(); boolean clickable(); boolean supportsClick();
        void release();
    }
    static final class Candidate {
        final String resourceId, nodeClass;
        final int windowId;
        final boolean visible, enabled, clickable, actionClick;
        private Candidate(Node node) {
            resourceId = safeId(node.resourceId()); nodeClass = safeClass(node.nodeClass());
            windowId = node.windowId();
            visible = node.visible(); enabled = node.enabled(); clickable = node.clickable();
            actionClick = node.supportsClick();
        }
    }
    static final class Result {
        Status status = Status.COMPLETE;
        int rootWindowId = -1;
        String rootClass = "UNAVAILABLE";
        final int[] counts = new int[LABELS.length];
        final int[] visibleTextCounts = new int[LABELS.length];
        final ArrayList<Candidate> titles = new ArrayList<>(), cancels = new ArrayList<>();
        private void omit(Status reason) {
            status = reason; Arrays.fill(counts, 0); Arrays.fill(visibleTextCounts, 0); titles.clear(); cancels.clear();
        }
        boolean complete() { return status == Status.COMPLETE; }
    }
    private int rows;
    private int[] firstCounts, firstVisibleTextCounts;
    void reset() { rows = 0; firstCounts = null; firstVisibleTextCounts = null; }
    boolean exhausted() { return rows >= 2; }
    /** First sample, then at most one changed known-label signature; metadata changes do not spend rows. */
    boolean claim(Result result) {
        if (exhausted() || (rows > 0 && (!result.complete() || (Arrays.equals(firstCounts, result.counts)
                && Arrays.equals(firstVisibleTextCounts, result.visibleTextCounts))))) return false;
        if (rows == 0 && result.complete()) {
            firstCounts = result.counts.clone(); firstVisibleTextCounts = result.visibleTextCounts.clone();
        }
        rows++; return true;
    }
    static Result scan(AccessibilityNodeInfo root) { return scan(new AndroidNode(root)); }
    static Result scan(Node root) {
        Result result = new Result();
        if (root == null) { result.omit(Status.UNREADABLE); return result; }
        ArrayList<Node> nodes = new ArrayList<>(); nodes.add(root);
        try {
            if (!root.settings()) { result.omit(Status.FOREIGN_NODE); return result; }
            result.rootWindowId = root.windowId(); result.rootClass = rootCategory(root.nodeClass());
            int slots = 1;
            for (int at = 0; at < nodes.size(); at++) {
                Node node = nodes.get(at);
                // Embedded or stale foreign/unknown-package nodes are never read, even under a Settings root.
                if (!node.settings()) { result.omit(Status.FOREIGN_NODE); return result; }
                CharSequence text = node.text(), description = node.description();
                for (int label = 0; label < LABELS.length; label++) {
                    // A node contributes once per label even if text and contentDescription agree.
                    if (!matchesLabel(label, text) && !matchesLabel(label, description)) continue;
                    result.counts[label]++;
                    if (matchesLabel(label, text) && node.visible()) result.visibleTextCounts[label]++;
                    ArrayList<Candidate> candidates = label == 0 ? result.titles : label == 4 ? result.cancels : null;
                    if (candidates != null && candidates.size() < CANDIDATE_LIMIT) candidates.add(new Candidate(node));
                }
                int children = node.childCount();
                // Count child slots too: a stale tree with huge counts/null children cannot evade the bound.
                if (children < 0) { result.omit(Status.UNREADABLE); return result; }
                if (children > NODE_LIMIT - slots) { result.omit(Status.NODE_LIMIT); return result; }
                for (int child = 0; child < children; child++) {
                    slots++;
                    Node acquired = node.child(child);
                    if (acquired != null) nodes.add(acquired);
                }
            }
        } catch (SecurityException denied) { result.omit(Status.SECURITY_DENIED); }
        catch (RuntimeException stale) { result.omit(Status.UNREADABLE); }
        finally {
            // The caller owns index zero and continues its normal inspection with that same root.
            for (int at = 1; at < nodes.size(); at++) {
                try { nodes.get(at).release(); } catch (RuntimeException ignored) { }
            }
        }
        return result;
    }
    private static boolean matchesLabel(int label, CharSequence value) {
        return matches(LABELS[label], value) || (label == 0 && matches("USB 连接方式", value));
    }
    private static boolean matches(String known, CharSequence value) {
        if (value == null || value.length() != known.length()) return false;
        for (int at = 0; at < known.length(); at++) if (known.charAt(at) != value.charAt(at)) return false;
        return true;
    }
    static String safeId(String value) {
        return value != null && value.length() <= 160
                && value.matches("[A-Za-z0-9_.]+:id/[A-Za-z0-9_]{1,100}") ? value : "UNAVAILABLE";
    }
    static String safeClass(CharSequence value) {
        if (value == null || value.length() == 0 || value.length() > 160) return "UNAVAILABLE";
        StringBuilder safe = new StringBuilder(value.length());
        for (int at = 0; at < value.length(); at++) {
            char c = value.charAt(at);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '$')) return "UNAVAILABLE";
            safe.append(c);
        }
        return safe.toString();
    }
    private static String rootCategory(CharSequence value) {
        if (value == null) return "UNAVAILABLE";
        if (matches("android.widget.FrameLayout", value)) return "FRAME_LAYOUT";
        if (matches("android.widget.LinearLayout", value)) return "LINEAR_LAYOUT";
        if (matches("android.widget.RelativeLayout", value)) return "RELATIVE_LAYOUT";
        if (matches("android.widget.ScrollView", value)) return "SCROLL_VIEW";
        return "OTHER";
    }
    private static final class AndroidNode implements Node {
        private final AccessibilityNodeInfo node;
        AndroidNode(AccessibilityNodeInfo node) { this.node = node; }
        public boolean settings() {
            CharSequence name = node.getPackageName(); return matches("com.android.settings", name);
        }
        public CharSequence text() { return node.getText(); }
        public CharSequence description() { return node.getContentDescription(); }
        public CharSequence nodeClass() { return node.getClassName(); }
        public String resourceId() { return node.getViewIdResourceName(); }
        public int windowId() { return node.getWindowId(); }
        public int childCount() { return node.getChildCount(); }
        public Node child(int index) {
            AccessibilityNodeInfo child = node.getChild(index); return child == null ? null : new AndroidNode(child);
        }
        public boolean visible() { return node.isVisibleToUser(); }
        public boolean enabled() { return node.isEnabled(); }
        public boolean clickable() { return node.isClickable(); }
        public boolean supportsClick() { return (node.getActions() & AccessibilityNodeInfo.ACTION_CLICK) != 0; }
        public void release() { node.recycle(); }
    }
}
