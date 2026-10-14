package com.zhenghuaizhi.flashskip.platform.accessibility

import android.app.Application
import android.view.accessibility.AccessibilityEvent
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowRecognitionOutcome
import com.zhenghuaizhi.flashskip.testing.TARGET
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [FaultableAccessibilityNodeShadow::class, CopyingAccessibilityServiceShadow::class])
class CaptureWindowResolutionLifecycleTest {
    @Test fun retriesUseCumulativeDelaysAndFreshRequestsWithoutRetainingEvent() = runTest {
        val f = ResolutionFixture(this)
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED)
        try {
            f.session.resolve(f.request(), event) { error("无根时不得回调") }
            runCurrent()
            advanceTimeBy(180)
            runCurrent()
            assertEquals(listOf(0L, 40L, 100L, 180L), f.attempts.map { it.at })
            assertTrue(f.attempts.first().hasEvent)
            assertTrue(f.attempts.drop(1).none { it.hasEvent })
            assertNull(f.attempts.first().deadline)
            assertEquals(listOf(250L, 250L, 250L), f.attempts.drop(1).map { it.deadline })
            assertEquals(4, f.records.count { it.outcome == TargetWindowRecognitionOutcome.CHILD_WINDOW_PENDING })
            assertEquals(TargetWindowRecognitionOutcome.RETRY_EXHAUSTED, f.records.last().outcome)
            assertTrue(f.resolved.isEmpty())
        } finally { event.recycle() }
    }

    @Test fun exhaustedKeyDoesNotRestartButDifferentWindowGetsIndependentBudget() = runTest {
        val f = ResolutionFixture(this)
        f.session.resolve(f.request())
        runCurrent()
        advanceTimeBy(180)
        runCurrent()
        f.session.resolve(f.request())
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        assertEquals(5, f.attempts.size)
        assertEquals(TargetWindowRecognitionOutcome.RETRY_EXHAUSTED, f.records.last().outcome)

        f.session.resolve(f.request(windowId = 9))
        runCurrent()
        advanceTimeBy(40)
        runCurrent()
        assertEquals(listOf(480L, 520L), f.attempts.takeLast(2).map { it.at })
    }

    @Test fun successfulWindowCancelsOnlyItsOwnRetryKey() = runTest {
        val f = ResolutionFixture(this)
        f.result = { request, block ->
            if (request.eventWindowId == 8 && f.attempts.count { it.windowId == 8 } > 1) f.success(request, block)
            else AccessibilityCaptureWindowResolver.Result.Retryable()
        }
        f.session.resolve(f.request(windowId = 8))
        f.session.resolve(f.request(windowId = 9))
        runCurrent()
        advanceTimeBy(180)
        runCurrent()
        assertEquals(2, f.attempts.count { it.windowId == 8 })
        assertEquals(4, f.attempts.count { it.windowId == 9 })
        assertEquals(listOf(8), f.resolved)
        assertTrue(f.records.any { it.request.eventWindowId == 9 && it.outcome == TargetWindowRecognitionOutcome.RETRY_EXHAUSTED })
    }

    @Test fun cancellationBeforeNextRetryPreventsReadsAndExhaustionRecord() = runTest {
        val f = ResolutionFixture(this)
        f.session.resolve(f.request())
        runCurrent()
        f.session.cancel()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, f.attempts.size)
        assertEquals(1, f.records.size)
        assertTrue(f.resolved.isEmpty())
    }

    @Test fun staleSessionProviderStopsOldRetryBeforeResolverOrCallback() = runTest {
        val f = ResolutionFixture(this)
        f.session.resolve(f.request())
        runCurrent()
        f.currentId = 2
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, f.attempts.size)
        assertEquals(1, f.records.size)
        assertTrue(f.resolved.isEmpty())
    }

    @Test fun slowAttemptPastDeadlineStopsFurtherReadsAndRecordsObservationLimit() = runTest {
        val f = ResolutionFixture(this)
        f.result = { _, _ ->
            if (f.attempts.size == 2) f.elapsedOffset = 220
            AccessibilityCaptureWindowResolver.Result.Retryable()
        }
        f.session.resolve(f.request())
        runCurrent()
        advanceTimeBy(40)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, f.attempts.size)
        assertEquals(TargetWindowRecognitionOutcome.RETRY_EXHAUSTED, f.records.last().outcome)
        assertTrue(f.resolved.isEmpty())
    }

    @Test fun rejectedBoundaryStopsRetryWithoutPretendingRootWasResolved() = runTest {
        val f = ResolutionFixture(this)
        f.result = { _, _ -> AccessibilityCaptureWindowResolver.Result.Rejected(CaptureWindowBoundaryPolicy.RejectReason.ACTIVE_ROOT_MISMATCH) }
        f.session.resolve(f.request())
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, f.attempts.size)
        assertEquals(TargetWindowRecognitionOutcome.ACTIVE_ROOT_MISMATCH, f.records.single().outcome)
        assertTrue(f.resolved.isEmpty())
    }

    @Test fun synchronousResolverCancellationPropagatesAndDoesNotCreateRetry() = runTest {
        val f = ResolutionFixture(this)
        f.result = { _, _ -> throw CancellationException("窗口解析取消") }
        try {
            f.session.resolve(f.request())
            fail("取消必须传播")
        } catch (_: CancellationException) { }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, f.attempts.size)
        assertTrue(f.records.isEmpty())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class ResolutionFixture(private val scope: TestScope) {
    private val nodes = AccessibilityFixture()
    var currentId = 1L
    var elapsedOffset = 0L
    val attempts = mutableListOf<Attempt>()
    val records = mutableListOf<CaptureWindowResolutionRecord>()
    val resolved = mutableListOf<Int>()
    var result: (AccessibilityCaptureWindowResolver.Request, (AccessibilityCaptureWindowResolver.ResolvedRoot) -> Unit) -> AccessibilityCaptureWindowResolver.Result<Unit> =
        { _, _ -> AccessibilityCaptureWindowResolver.Result.Retryable() }
    val session = CaptureWindowResolutionSession(
        scope = scope.backgroundScope,
        elapsedRealtimeMs = { scope.testScheduler.currentTime + elapsedOffset },
        attemptResolver = CaptureWindowAttemptResolver { request, event, deadline, block ->
            attempts += Attempt(scope.testScheduler.currentTime, request.eventWindowId, event != null, deadline)
            result(request, block)
        },
        retryRequestProvider = { key, type ->
            request(key.windowId, type).takeIf { key.sessionId == currentId }
        },
        onRetryResolved = { request, _ -> resolved += request.eventWindowId },
        recordResolution = { records += it },
    )

    fun request(windowId: Int = 8, eventType: Int = AccessibilityEvent.TYPE_VIEW_CLICKED) =
        AccessibilityCaptureWindowResolver.Request(
            sessionId = currentId,
            eventPackageName = TARGET.packageName,
            eventWindowId = windowId,
            host = VerifiedCaptureHost(currentId, TARGET.packageName, nodes.identity.activityName!!, 7),
            eventType = eventType,
        )

    fun success(request: AccessibilityCaptureWindowResolver.Request, block: (AccessibilityCaptureWindowResolver.ResolvedRoot) -> Unit): AccessibilityCaptureWindowResolver.Result<Unit> {
        val source = CaptureWindowResolutionSource.WINDOW_LIST
        val details = AccessibilityCaptureWindowResolver.ResolutionDetails()
        block(AccessibilityCaptureWindowResolver.ResolvedRoot(nodes.root, source, request.eventWindowId != 7))
        return AccessibilityCaptureWindowResolver.Result.Success(Unit, source, request.eventWindowId != 7, details)
    }

    data class Attempt(val at: Long, val windowId: Int, val hasEvent: Boolean, val deadline: Long?)
}

private fun CaptureWindowResolutionSession.resolve(request: AccessibilityCaptureWindowResolver.Request) = resolve(request, null) { error("无根时不得回调") }
