package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.TargetWindowRecognitionOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetWindowDiagnosticsTrackerTest {
    @Test
    fun snapshotDistinguishesObservedEventsFromWindowVerificationFailure() {
        val tracker = TargetWindowDiagnosticsTracker()
        tracker.onSessionChanged(7)
        tracker.recordIgnoredPackage()
        tracker.recordContentChanged("com.example.maps", 100)
        tracker.recordWindowState(
            packageName = "com.example.maps",
            className = "android.view.View\u0000",
            windowId = 12,
            windowCount = 3,
            matchingWindowCount = 1,
            matchingWindowType = 1,
            matchingWindowActive = true,
            outcome = TargetWindowRecognitionOutcome.ACTIVITY_UNVERIFIED,
            occurredAtElapsedMs = 150,
        )

        val diagnostics = requireNotNull(tracker.snapshot(7, 250))

        assertEquals(1, diagnostics.windowStateEventCount)
        assertEquals(1, diagnostics.contentChangedEventCount)
        assertEquals(1, diagnostics.ignoredPackageEventCount)
        assertEquals("com.example.maps", diagnostics.lastPackageName)
        assertEquals("android.view.View", diagnostics.lastClassName)
        assertEquals(TargetWindowRecognitionOutcome.ACTIVITY_UNVERIFIED, diagnostics.lastOutcome)
        assertEquals(100L, diagnostics.lastEventAgeMs)
        assertNull(tracker.snapshot(8, 250))
    }

    @Test
    fun newSessionClearsPreviousEvidence() {
        val tracker = TargetWindowDiagnosticsTracker()
        tracker.onSessionChanged(7)
        tracker.recordViewClicked("com.example.maps", 100)
        tracker.onSessionChanged(8)

        val diagnostics = requireNotNull(tracker.snapshot(8, 150))

        assertEquals(0, diagnostics.viewClickedEventCount)
        assertEquals(TargetWindowRecognitionOutcome.NO_WINDOW_STATE_EVENT, diagnostics.lastOutcome)
        assertNull(diagnostics.lastPackageName)
        assertNull(diagnostics.lastEventAgeMs)
    }
}
