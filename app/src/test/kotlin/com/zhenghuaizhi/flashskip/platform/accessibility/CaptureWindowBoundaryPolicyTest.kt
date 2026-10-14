package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.platform.accessibility.CaptureWindowBoundaryPolicy.Decision
import com.zhenghuaizhi.flashskip.platform.accessibility.CaptureWindowBoundaryPolicy.RejectReason
import org.junit.Assert.*
import org.junit.Test

class CaptureWindowBoundaryPolicyTest {
    private val host = VerifiedCaptureHost(100, "app", "LaunchActivity", 7)
    private val primary = CaptureWindowBoundaryEvidence(100, "app", 7, matchingWindowCount = 1,
        matchingWindowType = 1, matchingWindowActive = true, matchingRootAvailable = true,
        matchingRootPackageName = "app", matchingRootWindowId = 7)

    @Test fun ac01_activeApplicationWindowWithMatchingRootIsAccepted() {
        assertEquals(Decision.AcceptPrimary(CaptureWindowResolutionSource.WINDOW_LIST), CaptureWindowBoundaryPolicy.decide(host, primary))
        assertEquals(Decision.AcceptOwnedChild(CaptureWindowResolutionSource.WINDOW_LIST), CaptureWindowBoundaryPolicy.decide(host, primary.copy(eventWindowId = 8, matchingRootWindowId = 8)))
    }

    @Test fun ac01_unverifiedAndWrongSessionOrPackageAreRejected() {
        assertTrue(CaptureWindowBoundaryPolicy.decide(null, primary) is Decision.Reject)
        assertTrue(CaptureWindowBoundaryPolicy.decide(host.copy(activityName = ""), primary) is Decision.Reject)
        assertEquals(Decision.Reject(RejectReason.SESSION_MISMATCH), CaptureWindowBoundaryPolicy.decide(host, primary.copy(sessionId = 101)))
        assertEquals(Decision.Reject(RejectReason.PACKAGE_MISMATCH), CaptureWindowBoundaryPolicy.decide(host, primary.copy(eventPackageName = "other")))
    }

    @Test fun ac01_duplicateWrongRootAndInvalidApplicationWindowsAreRejected() {
        assertEquals(Decision.Reject(RejectReason.EVENT_WINDOW_AMBIGUOUS), CaptureWindowBoundaryPolicy.decide(host, primary.copy(matchingWindowCount = 2)))
        assertEquals(Decision.Reject(RejectReason.WINDOW_ROOT_MISMATCH), CaptureWindowBoundaryPolicy.decide(host, primary.copy(matchingRootWindowId = 8)))
        assertEquals(Decision.Reject(RejectReason.WINDOW_ROOT_MISMATCH), CaptureWindowBoundaryPolicy.decide(host, primary.copy(matchingRootPackageName = "other")))
        assertEquals(Decision.Reject(RejectReason.WINDOW_NOT_ACTIVE), CaptureWindowBoundaryPolicy.decide(host, foreground().copy(matchingWindowCount = 1, matchingWindowActive = false)))
        assertEquals(Decision.Reject(RejectReason.NON_APPLICATION_WINDOW), CaptureWindowBoundaryPolicy.decide(host, foreground().copy(matchingWindowCount = 1, matchingWindowActive = true, matchingWindowType = 2)))
    }

    @Test fun ac01_destroyedChildCanUseVerifiedHostOnlyWithExplicitNonClickFallback() {
        val evidence = foreground()
        assertTrue(CaptureWindowBoundaryPolicy.decide(host, evidence) is Decision.Retryable)
        assertEquals(Decision.AcceptPrimary(CaptureWindowResolutionSource.ACTIVE_ROOT), CaptureWindowBoundaryPolicy.decide(host, evidence, true))
        assertTrue(CaptureWindowBoundaryPolicy.decide(host, evidence.copy(activeRootWindowActive = false), true) is Decision.Reject)
    }

    @Test fun ac01_sourceWindowMustHaveCompleteMatchingMetadata() {
        val source = foreground().copy(sourcePackageName = "app", sourceWindowId = 8,
            sourceWindowAvailable = true, sourceWindowObjectId = 8, sourceWindowType = 1, sourceWindowActive = true)
        assertEquals(Decision.AcceptOwnedChild(CaptureWindowResolutionSource.EVENT_SOURCE), CaptureWindowBoundaryPolicy.decide(host, source))
        listOf(source.copy(sourceWindowType = 2), source.copy(sourceWindowActive = false),
            source.copy(sourceWindowObjectId = 9), source.copy(sourcePackageName = "other")).forEach {
            assertTrue(CaptureWindowBoundaryPolicy.decide(host, it) is Decision.Reject)
        }
    }

    @Test fun policy05_missingSourceWindowMetadataCannotBeAccepted() {
        val decision = CaptureWindowBoundaryPolicy.decide(host, foreground().copy(sourcePackageName = "app", sourceWindowId = 8))
        assertTrue("缺失 source.window 元数据只能拒绝或重试: $decision", decision is Decision.Reject || decision is Decision.Retryable)
    }

    private fun foreground() = CaptureWindowBoundaryEvidence(100, "app", 8,
        activeRootPackageName = "app", activeRootWindowId = 7, activeRootWindowAvailable = true,
        activeRootWindowObjectId = 7, activeRootWindowType = 1, activeRootWindowActive = true)
}
