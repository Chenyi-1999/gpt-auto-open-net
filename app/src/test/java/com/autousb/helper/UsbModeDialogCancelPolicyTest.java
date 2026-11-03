package com.autousb.helper;

import org.junit.Test;
import static org.junit.Assert.*;
import com.autousb.helper.UsbModeDialogProbeTest.FakeNode;

/** Exact observed signature plus independent ownership/token/deadline boundaries. */
public class UsbModeDialogCancelPolicyTest {
    private FakeNode dialog() {
        FakeNode root = new FakeNode(null); root.nodeClass = "android.widget.FrameLayout";
        FakeNode title = new FakeNode("USB 连接方式"); title.resourceId = "android:id/alertTitle";
        FakeNode cancel = new FakeNode("取消"); cancel.resourceId = "android:id/button2";
        cancel.nodeClass = "android.widget.Button"; cancel.clickable = true; cancel.actionClick = true;
        return root.add(title).add(new FakeNode("传输照片")).add(new FakeNode("传输文件")).add(new FakeNode("仅充电")).add(cancel);
    }
    private boolean signature(FakeNode root) { return UsbModeDialogCancelPolicy.signature(UsbModeDialogProbe.scan(root)); }
    private VerificationAttempt attempt(long id) { return new VerificationAttempt(VerificationAttempt.Owner.GUARD, 31, id, null); }
    private PageReadyEvidence evidence(VerificationAttempt attempt) {
        PageReadyEvidence page = new PageReadyEvidence(); page.begin(attempt, 100);
        page.sample(attempt, attempt, NavigationRetryBudget.Page.SETTINGS_UNCONFIRMED, 110); return page;
    }
    private boolean consume(UsbModeDialogCancelPolicy token, VerificationAttempt attempt, PageReadyEvidence page, long now, boolean live) {
        return token.consume(attempt, attempt, OneShotPolicy.Phase.ARMED, page.facts(), now, live, UsbModeDialogProbe.scan(dialog()));
    }
    @Test public void acceptsBothExactTitleVariantsAndWindowZero() {
        assertTrue(signature(dialog())); FakeNode root = dialog(); root.children.get(0).text = "USB连接方式"; assertTrue(signature(root));
        root = dialog(); root.windowId = 0; for (FakeNode child : root.children) child.windowId = 0; assertTrue(signature(root));
    }
    @Test public void rejectsForeignRootOrChildWithoutReadingItsContent() {
        FakeNode root = dialog(); root.settings = false; root.failText = true; assertFalse(signature(root));
        root = dialog(); root.children.get(1).settings = false; root.children.get(1).failText = true; assertFalse(signature(root));
    }
    @Test public void rejectsMissingDuplicateAndAmbiguousLabels() {
        for (int at = 0; at < 5; at++) {
            FakeNode root = dialog(); root.children.remove(at); assertFalse(signature(root));
            root = dialog(); root.add(new FakeNode(root.children.get(at).text.toString())); assertFalse(signature(root));
        }
    }
    @Test public void everyLabelMustBeActualTextAndVisible() {
        for (int at = 0; at < 5; at++) {
            FakeNode root = dialog(); root.children.get(at).visible = false; assertFalse(signature(root));
            root = dialog(); FakeNode node = root.children.get(at); node.description = node.text; node.text = null;
            UsbModeDialogProbe.Result result = UsbModeDialogProbe.scan(root);
            assertEquals(1, result.counts[at]); assertEquals(0, result.visibleTextCounts[at]);
            assertFalse(UsbModeDialogCancelPolicy.signature(result));
        }
    }
    @Test public void rejectsWrongTitleIdClassEnabledOrActions() {
        FakeNode root = dialog(); root.children.get(0).resourceId = "android:id/title"; assertFalse(signature(root));
        root = dialog(); root.children.get(0).nodeClass = "android.widget.Button"; assertFalse(signature(root));
        root = dialog(); root.children.get(0).enabled = false; assertFalse(signature(root));
        root = dialog(); root.children.get(0).clickable = true; assertFalse(signature(root));
        root = dialog(); root.children.get(0).actionClick = true; assertFalse(signature(root));
        root = dialog(); root.children.get(0).text = "USB  连接方式"; assertFalse(signature(root));
    }
    @Test public void rejectsWrongCancelIdClassStateActionOrText() {
        FakeNode root = dialog(); root.children.get(4).resourceId = "android:id/button1"; assertFalse(signature(root));
        root = dialog(); root.children.get(4).nodeClass = "android.widget.TextView"; assertFalse(signature(root));
        root = dialog(); root.children.get(4).enabled = false; assertFalse(signature(root));
        root = dialog(); root.children.get(4).clickable = false; assertFalse(signature(root));
        root = dialog(); root.children.get(4).actionClick = false; assertFalse(signature(root));
        root = dialog(); root.children.get(4).text = "允许"; assertFalse(signature(root));
    }
    @Test public void rejectsUnknownRootClassNegativeWindowAndCandidateWindowChanges() {
        FakeNode root = dialog(); root.nodeClass = "android.widget.LinearLayout"; assertFalse(signature(root));
        root = dialog(); root.windowId = -1; assertFalse(signature(root));
        root = dialog(); root.children.get(0).windowId++; assertFalse(signature(root));
        root = dialog(); root.children.get(4).windowId++; assertFalse(signature(root));
    }
    @Test public void requiresLiveSafetyActivePageAndOriginalDeadline() {
        VerificationAttempt a = attempt(1); PageReadyEvidence page = evidence(a);
        UsbModeDialogCancelPolicy token = new UsbModeDialogCancelPolicy(); token.begin(a);
        assertFalse(consume(token, a, page, 110, false)); assertTrue(token.available(a, a));
        assertFalse(consume(token, a, page, 10_100, true)); assertTrue(token.available(a, a));
        page.end(); assertFalse(consume(token, a, page, 110, true));
        page = evidence(a); assertTrue(consume(token, a, page, 10_099, true));
    }
    @Test public void refusesManualVerifyingPreviouslyConfirmedAndPossibleUsbClick() {
        VerificationAttempt a = attempt(1); UsbModeDialogCancelPolicy token = new UsbModeDialogCancelPolicy(); token.begin(a);
        PageReadyEvidence page = evidence(a);
        assertFalse(token.consume(a, a, OneShotPolicy.Phase.VERIFYING, page.facts(), 120, true, UsbModeDialogProbe.scan(dialog())));
        page.sample(a, a, NavigationRetryBudget.Page.CONFIRMED_TETHER, 115);
        page.sample(a, a, NavigationRetryBudget.Page.SETTINGS_UNCONFIRMED, 120); assertFalse(consume(token, a, page, 120, true));
        page = evidence(a); page.possibleClick(a, a); assertFalse(consume(token, a, page, 120, true));
        VerificationAttempt manual = new VerificationAttempt(VerificationAttempt.Owner.MANUAL, 31, 1, null);
        token.begin(manual); assertFalse(consume(token, manual, evidence(manual), 120, true));
    }
    @Test public void falseReturnThrowOrFreshRefusalCannotReturnThePreparationToken() {
        for (int outcome = 0; outcome < 3; outcome++) {
            VerificationAttempt a = attempt(1); PageReadyEvidence page = evidence(a);
            UsbModeDialogCancelPolicy token = new UsbModeDialogCancelPolicy(); token.begin(a);
            assertTrue(consume(token, a, page, 120, true));
            int[] calls = {0}; final int simulatedOutcome = outcome;
            java.util.function.BooleanSupplier action = () -> {
                calls[0]++; if (simulatedOutcome == 1) throw new IllegalStateException(); return false;
            };
            if (outcome == 2) {
                // A changed fresh signature refuses before any action, while retaining the spent preparation.
                FakeNode changed = dialog(); changed.children.get(4).resourceId = "android:id/button1";
                assertFalse(signature(changed)); assertEquals(0, calls[0]);
            } else {
                try { assertFalse(action.getAsBoolean()); assertEquals(0, outcome); }
                catch (IllegalStateException expected) { assertEquals(1, outcome); }
                assertEquals(1, calls[0]);
            }
            // The outcome has no reset API: a false/throw/fresh refusal leaves the same consumed token.
            assertFalse(consume(token, a, page, 121, true)); assertFalse(token.available(a, a));
        }
    }
    @Test public void stoppedAndOldAttemptsCannotBorrowNewAttemptToken() {
        VerificationAttempt old = attempt(1), next = attempt(2), impostor = attempt(2);
        UsbModeDialogCancelPolicy token = new UsbModeDialogCancelPolicy(); token.begin(old); token.end();
        assertFalse(consume(token, old, evidence(old), 120, true)); token.begin(next);
        assertFalse(token.available(old, next)); assertFalse(consume(token, old, evidence(old), 120, true));
        assertFalse(consume(token, impostor, evidence(impostor), 120, true)); assertTrue(consume(token, next, evidence(next), 120, true));
    }
    @Test public void independentPreparationDoesNotConsumeUsbTokenOrRenewPageWindow() {
        VerificationAttempt a = attempt(1); OneShotPolicy usb = new OneShotPolicy(); assertTrue(usb.arm(100, true, true, true));
        VerificationWindow window = new VerificationWindow(); window.begin(a, 100); PageReadyEvidence page = evidence(a);
        UsbModeDialogCancelPolicy token = new UsbModeDialogCancelPolicy(); token.begin(a);
        assertTrue(token.consume(a, a, usb.phase(), page.facts(), 120, true, UsbModeDialogProbe.scan(dialog())));
        assertEquals(OneShotPolicy.Phase.ARMED, usb.phase()); assertFalse(window.pageExpired(a, a, 10_099));
        assertTrue(window.pageExpired(a, a, 10_100)); assertTrue(usb.consume(121, true, true, true, false));
        assertEquals(OneShotPolicy.Phase.VERIFYING, usb.phase());
    }
}
