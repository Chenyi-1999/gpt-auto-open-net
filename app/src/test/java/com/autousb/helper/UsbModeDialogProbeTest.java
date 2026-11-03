package com.autousb.helper;

import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

public class UsbModeDialogProbeTest {
    static final class FakeNode implements UsbModeDialogProbe.Node {
        CharSequence text, description, nodeClass = "android.widget.TextView";
        String resourceId = "android:id/title";
        boolean visible = true, enabled = true, settings = true, clickable, actionClick, failText, denyText, failMetadata;
        int declaredChildren = -1, releases, reads, windowId = 19;
        final ArrayList<FakeNode> children = new ArrayList<>();
        FakeNode(String text) { this.text = text; }
        FakeNode add(FakeNode child) { children.add(child); return this; }
        public boolean settings() { return settings; }
        public CharSequence text() {
            if (denyText) throw new SecurityException("must never be logged");
            if (failText) throw new IllegalStateException("must never be logged");
            return text;
        }
        public CharSequence description() { return description; }
        public CharSequence nodeClass() { if (failMetadata) throw new IllegalStateException(); return nodeClass; }
        public String resourceId() { if (failMetadata) throw new IllegalStateException(); return resourceId; }
        public int windowId() { return windowId; }
        public int childCount() { return declaredChildren >= 0 ? declaredChildren : children.size(); }
        public UsbModeDialogProbe.Node child(int index) { reads++; return children.get(index); }
        public boolean visible() { return visible; }
        public boolean enabled() { return enabled; }
        public boolean clickable() { return clickable; }
        public boolean supportsClick() { return actionClick; }
        public void release() { releases++; }
    }
    @Test public void recordsOnlyKnownExactLabelsAndDeduplicatesDescriptionOnSameNode() {
        FakeNode title = new FakeNode("USB 连接方式"); title.description = "USB连接方式";
        FakeNode root = new FakeNode("private unrelated text"); root.nodeClass = "android.widget.FrameLayout";
        root.add(title).add(new FakeNode("传输照片")).add(new FakeNode("传输文件"))
                .add(new FakeNode("仅充电")).add(new FakeNode("取消")).add(new FakeNode(" 取消"))
                .add(new FakeNode("USB  连接方式")).add(new FakeNode(" USB连接方式"));
        UsbModeDialogProbe.Result result = UsbModeDialogProbe.scan(root);
        assertTrue(result.complete()); assertArrayEquals(new int[] {1, 1, 1, 1, 1}, result.counts);
        assertArrayEquals(new int[] {1, 1, 1, 1, 1}, result.visibleTextCounts);
        assertEquals(19, result.rootWindowId); assertEquals("FRAME_LAYOUT", result.rootClass);
        assertEquals(1, result.titles.size()); assertEquals(1, result.cancels.size());
        assertEquals(0, root.releases); for (FakeNode child : root.children) assertEquals(1, child.releases);
    }
    @Test public void metadataIsReadOnlyForTitleAndCancelAndPreservesClickFlags() {
        FakeNode root = new FakeNode(null), unrelated = new FakeNode("传输文件"), cancel = new FakeNode("取消");
        unrelated.failMetadata = true; cancel.clickable = true; cancel.actionClick = false; cancel.enabled = false;
        cancel.visible = false; cancel.resourceId = "android:id/button2"; root.add(unrelated).add(cancel);
        UsbModeDialogProbe.Result result = UsbModeDialogProbe.scan(root);
        assertTrue(result.complete()); UsbModeDialogProbe.Candidate item = result.cancels.get(0);
        assertEquals("android:id/button2", item.resourceId); assertTrue(item.clickable);
        assertFalse(item.actionClick); assertFalse(item.enabled); assertFalse(item.visible);
        assertEquals(0, result.visibleTextCounts[4]);
    }
    @Test public void duplicateCandidatesAreCountedButMetadataHasTwoItemLimit() {
        FakeNode root = new FakeNode(null);
        for (int i = 0; i < 4; i++) root.add(new FakeNode("USB连接方式")).add(new FakeNode("取消"));
        UsbModeDialogProbe.Result result = UsbModeDialogProbe.scan(root);
        assertEquals(4, result.counts[0]); assertEquals(4, result.counts[4]);
        assertEquals(2, result.titles.size()); assertEquals(2, result.cancels.size());
    }
    @Test public void invalidAndOverlongMetadataIsOmitted() {
        FakeNode title = new FakeNode("USB连接方式"); title.resourceId = "android:id/title\nsecret";
        title.nodeClass = "android.widget.TextView\nsecret";
        UsbModeDialogProbe.Result result = UsbModeDialogProbe.scan(title);
        assertEquals("UNAVAILABLE", result.titles.get(0).resourceId);
        assertEquals("UNAVAILABLE", result.titles.get(0).nodeClass);
        assertEquals("OTHER", result.rootClass);
        assertEquals("UNAVAILABLE", UsbModeDialogProbe.safeClass("x".repeat(161)));
        assertEquals("UNAVAILABLE", UsbModeDialogProbe.safeId("x".repeat(161)));
        assertEquals("UNAVAILABLE", UsbModeDialogProbe.safeId(null));
        assertEquals("UNAVAILABLE", UsbModeDialogProbe.safeClass(null));
    }
    @Test public void exactly128NodesAreAllowedAndAllChildrenAreReleased() {
        FakeNode root = new FakeNode(null);
        for (int i = 1; i < 128; i++) root.add(new FakeNode("取消"));
        UsbModeDialogProbe.Result result = UsbModeDialogProbe.scan(root);
        assertTrue(result.complete()); assertEquals(127, result.counts[4]); assertEquals(127, root.reads);
        for (FakeNode child : root.children) assertEquals(1, child.releases);
    }
    @Test public void overflowOmitsPartialCountsAndReleasesAlreadyAcquiredChildren() {
        FakeNode child = new FakeNode("取消"); child.declaredChildren = Integer.MAX_VALUE;
        FakeNode root = new FakeNode("USB连接方式").add(child);
        UsbModeDialogProbe.Result result = UsbModeDialogProbe.scan(root);
        assertEquals(UsbModeDialogProbe.Status.NODE_LIMIT, result.status);
        assertArrayEquals(new int[5], result.counts); assertTrue(result.titles.isEmpty()); assertTrue(result.cancels.isEmpty());
        assertEquals(1, child.releases); assertEquals(0, child.reads); assertEquals(0, root.releases);
    }
    @Test public void hugeNullChildCountIsBoundedWithoutAttemptingReads() {
        FakeNode root = new FakeNode(null); root.declaredChildren = Integer.MAX_VALUE;
        assertEquals(UsbModeDialogProbe.Status.NODE_LIMIT, UsbModeDialogProbe.scan(root).status);
        assertEquals(0, root.reads);
    }
    @Test public void staleOrDeniedReadOmitsPartialPayloadAndReleasesSiblings() {
        for (boolean deny : new boolean[] {false, true}) {
            FakeNode bad = new FakeNode(null), sibling = new FakeNode("取消");
            bad.failText = !deny; bad.denyText = deny;
            FakeNode root = new FakeNode("USB连接方式").add(bad).add(sibling);
            UsbModeDialogProbe.Result result = UsbModeDialogProbe.scan(root);
            assertEquals(deny ? UsbModeDialogProbe.Status.SECURITY_DENIED : UsbModeDialogProbe.Status.UNREADABLE, result.status);
            assertArrayEquals(new int[5], result.counts); assertTrue(result.titles.isEmpty());
            assertEquals(1, bad.releases); assertEquals(1, sibling.releases);
        }
    }
    @Test public void nullRootIsUnavailable() {
        assertEquals(UsbModeDialogProbe.Status.UNREADABLE, UsbModeDialogProbe.scan((UsbModeDialogProbe.Node) null).status);
    }
    @Test public void foreignChildTextAndSubtreeAreNeverReadAndPartialPayloadIsOmitted() {
        FakeNode foreign = new FakeNode("private"); foreign.settings = false; foreign.failText = true;
        foreign.declaredChildren = Integer.MAX_VALUE;
        UsbModeDialogProbe.Result result = UsbModeDialogProbe.scan(new FakeNode("USB连接方式").add(foreign));
        assertEquals(UsbModeDialogProbe.Status.FOREIGN_NODE, result.status);
        assertArrayEquals(new int[5], result.counts); assertTrue(result.titles.isEmpty());
        assertArrayEquals(new int[5], result.visibleTextCounts);
        assertEquals(0, foreign.reads); assertEquals(1, foreign.releases);
    }
    @Test public void rowsRequireKnownSignatureChangeStopAtTwoAndResetPerAttempt() {
        UsbModeDialogProbe probe = new UsbModeDialogProbe();
        UsbModeDialogProbe.Result empty = UsbModeDialogProbe.scan(new FakeNode(null));
        assertTrue(probe.claim(empty)); assertFalse(probe.claim(empty));
        FakeNode title = new FakeNode("USB连接方式");
        assertTrue(probe.claim(UsbModeDialogProbe.scan(title))); assertTrue(probe.exhausted());
        assertFalse(probe.claim(empty)); probe.reset(); assertFalse(probe.exhausted()); assertTrue(probe.claim(empty));
    }
    @Test public void incompleteFirstSampleMayBeFollowedByCompleteSignatureButCannotChurnErrors() {
        UsbModeDialogProbe probe = new UsbModeDialogProbe();
        UsbModeDialogProbe.Result failed = UsbModeDialogProbe.scan((UsbModeDialogProbe.Node) null);
        assertTrue(probe.claim(failed)); assertFalse(probe.claim(failed));
        assertTrue(probe.claim(UsbModeDialogProbe.scan(new FakeNode("取消")))); assertTrue(probe.exhausted());
    }
    @Test public void descriptionOnlyLabelIsDiagnosticButNeverVisibleTextEvidence() {
        FakeNode node = new FakeNode(null); node.description = "取消";
        UsbModeDialogProbe.Result result = UsbModeDialogProbe.scan(node);
        assertEquals(1, result.counts[4]); assertEquals(0, result.visibleTextCounts[4]);
    }
    @Test public void visibleTextCountChangeMayUseSecondDiagnosticRow() {
        FakeNode node = new FakeNode("取消"); node.visible = false;
        UsbModeDialogProbe probe = new UsbModeDialogProbe(); assertTrue(probe.claim(UsbModeDialogProbe.scan(node)));
        node.visible = true; assertTrue(probe.claim(UsbModeDialogProbe.scan(node))); assertTrue(probe.exhausted());
    }
}
