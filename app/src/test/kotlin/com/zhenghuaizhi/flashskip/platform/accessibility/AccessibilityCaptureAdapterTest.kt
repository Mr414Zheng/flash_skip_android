package com.zhenghuaizhi.flashskip.platform.accessibility

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.zhenghuaizhi.flashskip.domain.model.*
import com.zhenghuaizhi.flashskip.testing.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [FaultableAccessibilityNodeShadow::class, CopyingAccessibilityServiceShadow::class])
class AccessibilityCaptureAdapterTest {
    @Test fun ac08_observedCandidateIsPendingAndNeverSavesOrChangesTargetSwitch() = runTest {
        val nodes = AccessibilityFixture()
        val capture = CaptureSaveFixture(backgroundScope, testScheduler, CapturePhase.OBSERVING_CLICK)
        val adapter = adapter(nodes, capture)
        adapter.onStateChanged(capture.captureStore.state.value)
        adapter.onVerifiedTargetWindow(null, TARGET.packageName, "Target", nodes.identity.activityName!!, 7)
        runCurrent()
        val state = capture.captureStore.state.value
        assertEquals(CapturePhase.AWAITING_CONFIRMATION, state.activeSession!!.phase)
        assertNotNull(state.activeSession!!.pendingObservedCandidate)
        assertNull(state.activeSession!!.pendingSample)
        assertEquals(capture.initialCapture.samples, state.samples)
        assertTrue(capture.flashStore.attempts.isEmpty())
    }

    @Test fun ac07_matchingSourceUpgradesCandidateThroughRealCoordinatorWithoutSaving() = runTest {
        val nodes = AccessibilityFixture()
        val capture = CaptureSaveFixture(backgroundScope, testScheduler, CapturePhase.OBSERVING_CLICK)
        val adapter = adapter(nodes, capture)
        adapter.onStateChanged(capture.captureStore.state.value)
        adapter.onVerifiedTargetWindow(null, TARGET.packageName, "Target", nodes.identity.activityName!!, 7)
        runCurrent()
        adapter.onStateChanged(capture.captureStore.state.value)
        val event = click(nodes.target)
        try { adapter.onClicked(event, TARGET.packageName) } finally { event.recycle() }
        runCurrent()
        val state = capture.captureStore.state.value
        assertEquals(CapturePhase.CAPTURED, state.activeSession!!.phase)
        assertEquals(CaptureEvidence.ACCESSIBILITY_CLICK, state.activeSession!!.pendingSample!!.evidence)
        assertNull(state.activeSession!!.pendingObservedCandidate)
        assertFalse(state.activeSession!!.candidateClickListeningEnabled)
        assertEquals(capture.initialCapture.samples, state.samples)
        assertTrue(capture.flashStore.attempts.isEmpty())
    }

    @Test fun ac07_missingOrWrongStableSourceCannotUpgradeFrozenCandidate() = runTest {
        val nodes = AccessibilityFixture()
        val capture = CaptureSaveFixture(backgroundScope, testScheduler, CapturePhase.OBSERVING_CLICK)
        val adapter = adapter(nodes, capture)
        adapter.onStateChanged(capture.captureStore.state.value)
        adapter.onVerifiedTargetWindow(null, TARGET.packageName, "Target", nodes.identity.activityName!!, 7)
        runCurrent()
        val original = capture.captureStore.state.value.activeSession!!.pendingObservedCandidate
        listOf(null, nodes.node("different", "跳过", true)).forEach { source ->
            val event = click(source)
            try { adapter.onClicked(event, TARGET.packageName) } finally { event.recycle() }
            runCurrent()
            assertEquals(original, capture.captureStore.state.value.activeSession!!.pendingObservedCandidate)
            assertNull(capture.captureStore.state.value.activeSession!!.pendingSample)
        }
    }

    @Test fun ac07_clickBeforeCandidateCommitWaitsThenUpgradesSameFrozenEvidence() = runTest {
        val nodes = AccessibilityFixture()
        val capture = CaptureSaveFixture(backgroundScope, testScheduler, CapturePhase.OBSERVING_CLICK)
        val gate = CommitGate()
        capture.captureStore.beforeCommit = { gate.pause() }
        val adapter = adapter(nodes, capture)
        adapter.onStateChanged(capture.captureStore.state.value)
        adapter.onVerifiedTargetWindow(null, TARGET.packageName, "Target", nodes.identity.activityName!!, 7)
        runCurrent()
        assertTrue(gate.entered.isCompleted)
        val event = click(nodes.target)
        try { adapter.onClicked(event, TARGET.packageName) } finally { event.recycle() }
        assertEquals(CapturePhase.OBSERVING_CLICK, capture.captureStore.state.value.activeSession!!.phase)
        capture.captureStore.beforeCommit = {}
        gate.release.complete(Unit)
        runCurrent()
        assertEquals(CapturePhase.CAPTURED, capture.captureStore.state.value.activeSession!!.phase)
        assertEquals(CaptureEvidence.ACCESSIBILITY_CLICK, capture.captureStore.state.value.activeSession!!.pendingSample!!.evidence)
        assertEquals(capture.initialCapture.samples, capture.captureStore.state.value.samples)
    }

    @Test fun ac07_failedCandidateWriteCanStillSubmitVerifiedQueuedClick() = runTest {
        val nodes = AccessibilityFixture()
        val capture = CaptureSaveFixture(backgroundScope, testScheduler, CapturePhase.OBSERVING_CLICK)
        val gate = CommitGate()
        capture.captureStore.beforeCommit = { gate.pause(); throw IOException("候选未提交") }
        val adapter = adapter(nodes, capture)
        adapter.onStateChanged(capture.captureStore.state.value)
        adapter.onVerifiedTargetWindow(null, TARGET.packageName, "Target", nodes.identity.activityName!!, 7)
        runCurrent()
        val event = click(nodes.target)
        try { adapter.onClicked(event, TARGET.packageName) } finally { event.recycle() }
        capture.captureStore.beforeCommit = {}
        gate.release.complete(Unit)
        runCurrent()
        assertEquals(CapturePhase.CAPTURED, capture.captureStore.state.value.activeSession!!.phase)
        assertEquals(CaptureEvidence.ACCESSIBILITY_CLICK, capture.captureStore.state.value.activeSession!!.pendingSample!!.evidence)
        assertEquals(capture.initialCapture.samples, capture.captureStore.state.value.samples)
        assertTrue(capture.flashStore.attempts.isEmpty())
    }

    @Test fun ac07_cancelledCandidateCommitCannotUpgradeQueuedClick() = runTest {
        val nodes = AccessibilityFixture()
        val capture = CaptureSaveFixture(backgroundScope, testScheduler, CapturePhase.OBSERVING_CLICK)
        val gate = CommitGate()
        capture.captureStore.beforeCommit = { gate.pause(); throw CancellationException("候选提交取消") }
        val adapter = adapter(nodes, capture)
        adapter.onStateChanged(capture.captureStore.state.value)
        adapter.onVerifiedTargetWindow(null, TARGET.packageName, "Target", nodes.identity.activityName!!, 7)
        runCurrent()
        val event = click(nodes.target)
        try { adapter.onClicked(event, TARGET.packageName) } finally { event.recycle() }
        capture.captureStore.beforeCommit = {}
        gate.release.complete(Unit)
        runCurrent()
        assertEquals(CapturePhase.OBSERVING_CLICK, capture.captureStore.state.value.activeSession!!.phase)
        assertNull(capture.captureStore.state.value.activeSession!!.pendingSample)
        assertNull(capture.captureStore.state.value.activeSession!!.pendingObservedCandidate)
        assertEquals(capture.initialCapture.samples, capture.captureStore.state.value.samples)
        assertTrue(capture.flashStore.attempts.isEmpty())
    }

    @Test fun ac07_duplicateClicksAfterUpgradeDoNotRepeatWritesOrSave() = runTest {
        val nodes = AccessibilityFixture()
        val capture = CaptureSaveFixture(backgroundScope, testScheduler, CapturePhase.OBSERVING_CLICK)
        val adapter = adapter(nodes, capture)
        adapter.onStateChanged(capture.captureStore.state.value)
        adapter.onVerifiedTargetWindow(null, TARGET.packageName, "Target", nodes.identity.activityName!!, 7)
        runCurrent()
        val event = click(nodes.target)
        try { adapter.onClicked(event, TARGET.packageName); runCurrent() } finally { event.recycle() }
        val after = capture.captureStore.state.value
        val writes = capture.captureStore.writes.size
        repeat(2) {
            val duplicate = click(nodes.target)
            try { adapter.onClicked(duplicate, TARGET.packageName) } finally { duplicate.recycle() }
        }
        runCurrent()
        assertEquals(after, capture.captureStore.state.value)
        assertEquals(writes, capture.captureStore.writes.size)
        assertEquals(capture.initialCapture.samples, after.samples)
        assertTrue(capture.flashStore.attempts.isEmpty())
    }

    @Test fun ac02_directSourceFastClickBeforeTargetStateCommitIsCapturedWithoutCandidate() = runTest {
        val nodes = AccessibilityFixture()
        nodes.target.text = "Continue"
        nodes.target.viewIdResourceName = "${TARGET.packageName}:id/continue"
        val capture = CaptureSaveFixture(backgroundScope, testScheduler, CapturePhase.OBSERVING_CLICK)
        val adapter = adapter(nodes, capture)
        adapter.onStateChanged(capture.captureStore.state.value)
        adapter.onVerifiedTargetWindow(null, TARGET.packageName, "Target", nodes.identity.activityName!!, 7)
        assertNull(capture.captureStore.state.value.activeSession!!.pendingObservedCandidate)
        val event = click(nodes.target)
        try { adapter.onClicked(event, TARGET.packageName) } finally { event.recycle() }
        runCurrent()
        val state = capture.captureStore.state.value
        assertEquals(CapturePhase.CAPTURED, state.activeSession!!.phase)
        assertEquals("Continue", state.activeSession!!.pendingSample!!.nodes.first().text)
        assertEquals(CaptureEvidence.ACCESSIBILITY_CLICK, state.activeSession!!.pendingSample!!.evidence)
        assertEquals(capture.initialCapture.samples, state.samples)
        assertTrue(capture.flashStore.attempts.isEmpty())
    }

    @Test fun ac03_primaryCandidateWindowExpiresButVerifiedOtherApplicationWindowIsAllowed() = runTest {
        val nodes = AccessibilityFixture()
        val capture = CaptureSaveFixture(backgroundScope, testScheduler, CapturePhase.OBSERVING_CLICK)
        capture.captureStore.state.value = capture.captureStore.state.value.copy(activeSession = capture.captureStore.state.value.activeSession!!.copy(targetEnteredAtElapsedMs = 0))
        advanceTimeBy(8_001)
        val adapter = adapter(nodes, capture)
        adapter.onStateChanged(capture.captureStore.state.value)
        adapter.onVerifiedTargetWindow(null, TARGET.packageName, "Target", nodes.identity.activityName!!, 7)
        runCurrent()
        assertNull(capture.captureStore.state.value.activeSession!!.pendingObservedCandidate)
        val childWindow = AccessibilityWindowInfo.obtain().also {
            shadowOf(it).setId(8); shadowOf(it).setType(AccessibilityWindowInfo.TYPE_APPLICATION); shadowOf(it).setActive(true)
        }
        shadowOf(nodes.target).setAccessibilityWindowInfo(childWindow)
        nodes.install(nodes.root, childWindow)
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).also {
            it.packageName = TARGET.packageName
            shadowOf(it).setWindowId(8)
        }
        try { adapter.onContentChanged(event, TARGET.packageName) } finally { event.recycle() }
        runCurrent()
        assertEquals(CapturePhase.AWAITING_CONFIRMATION, capture.captureStore.state.value.activeSession!!.phase)
        assertNotNull(capture.captureStore.state.value.activeSession!!.pendingObservedCandidate)
        assertEquals(capture.initialCapture.samples, capture.captureStore.state.value.samples)
    }

    @Test fun policy01_missingSourceAndEventSemanticsCannotAttributeImmediateRecoveryAsTrueClick() = runTest {
        val nodes = AccessibilityFixture()
        val capture = CaptureSaveFixture(backgroundScope, testScheduler, CapturePhase.OBSERVING_CLICK)
        capture.captureStore.state.value = capture.captureStore.state.value.copy(activeSession =
            capture.captureStore.state.value.activeSession!!.copy(targetEnteredAtElapsedMs = 0))
        advanceTimeBy(8_001)
        val adapter = adapter(nodes, capture)
        adapter.onStateChanged(capture.captureStore.state.value)
        adapter.onVerifiedTargetWindow(null, TARGET.packageName, "Target", nodes.identity.activityName!!, 7)
        runCurrent()
        assertNull("已超过主窗候选期限，必须实际进入缺源点击恢复分支", capture.captureStore.state.value.activeSession!!.pendingObservedCandidate)
        val event = click(null)
        assertTrue(event.text.isEmpty())
        assertNull(event.contentDescription)
        assertNull(event.source)
        try { adapter.onClicked(event, TARGET.packageName) } finally { event.recycle() }
        runCurrent()
        val state = capture.captureStore.state.value
        assertNotEquals("唯一关闭节点不能替代明确事件语义来归因真实点击",
            CaptureEvidence.ACCESSIBILITY_CLICK, state.activeSession!!.pendingSample?.evidence)
        assertEquals(capture.initialCapture.samples, state.samples)
        assertTrue(capture.flashStore.attempts.isEmpty())
    }

    @Test fun ac02_missingSourceWithExplicitMatchingSemanticsAndCompleteUniqueScanIsCaptured() = runTest {
        val nodes = AccessibilityFixture()
        val capture = CaptureSaveFixture(backgroundScope, testScheduler, CapturePhase.OBSERVING_CLICK)
        capture.captureStore.state.value = capture.captureStore.state.value.copy(activeSession =
            capture.captureStore.state.value.activeSession!!.copy(targetEnteredAtElapsedMs = 0))
        advanceTimeBy(8_001)
        val adapter = adapter(nodes, capture)
        adapter.onStateChanged(capture.captureStore.state.value)
        adapter.onVerifiedTargetWindow(null, TARGET.packageName, "Target", nodes.identity.activityName!!, 7)
        runCurrent()
        assertNull(capture.captureStore.state.value.activeSession!!.pendingObservedCandidate)
        val event = click(null).also { it.text.add("跳过") }
        try { adapter.onClicked(event, TARGET.packageName) } finally { event.recycle() }
        runCurrent()
        val state = capture.captureStore.state.value
        assertEquals(CapturePhase.CAPTURED, state.activeSession!!.phase)
        assertEquals(CaptureEvidence.ACCESSIBILITY_CLICK, state.activeSession!!.pendingSample!!.evidence)
        assertEquals(nodes.target.viewIdResourceName, state.activeSession!!.pendingSample!!.nodes.first().resourceId)
        assertEquals(capture.initialCapture.samples, state.samples)
        assertTrue(capture.flashStore.attempts.isEmpty())
    }

    private fun kotlinx.coroutines.test.TestScope.adapter(nodes: AccessibilityFixture, capture: CaptureSaveFixture) = AccessibilityCaptureAdapter(
        service = nodes.service,
        coordinator = capture.coordinator,
        scope = backgroundScope,
        stateProvider = { capture.captureStore.state.value },
        targetWindowDiagnosticsProvider = { null },
        targetWindowResolutionRecorder = {},
    )

    private fun click(source: AccessibilityNodeInfo?) = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED).also {
        it.packageName = TARGET.packageName
        shadowOf(it).setWindowId(7)
        source?.let(shadowOf(it)::setSourceNode)
    }
}
