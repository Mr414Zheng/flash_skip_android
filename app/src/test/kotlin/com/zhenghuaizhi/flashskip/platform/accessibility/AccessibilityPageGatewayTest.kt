package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.ports.GestureResult
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.shadows.ShadowAccessibilityWindowInfo

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [AccessibilityPageGatewayTest.CountingWindowShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
class AccessibilityPageGatewayTest {
    private lateinit var controller: ServiceController<TestAccessibilityService>
    private lateinit var shadowService: ShadowAccessibilityService
    private lateinit var gateway: AccessibilityPageGateway
    private lateinit var clock: FakeMonotonicClock

    @Before
    @Suppress("DEPRECATION")
    fun setUp() {
        CountingWindowShadow.rootReads = 0
        CountingWindowShadow.onRootRead = null
        clock = FakeMonotonicClock()
        controller = Robolectric.buildService(TestAccessibilityService::class.java).create()
        val service = controller.get()
        shadowService = shadowOf(service)
        val window = AccessibilityWindowInfo.obtain()
        shadowOf(window).apply {
            setId(IDENTITY.windowId)
            setType(AccessibilityWindowInfo.TYPE_APPLICATION)
            setActive(true)
        }
        val root = AccessibilityNodeInfo.obtain().apply {
            packageName = IDENTITY.packageName
            contentDescription = "skip"
            isVisibleToUser = true
            setBoundsInScreen(Rect(10, 10, 60, 60))
        }
        shadowOf(root).setAccessibilityWindowInfo(window)
        shadowOf(window).setRoot(root)
        shadowService.setWindows(listOf(window))
        shadowService.setCanDispatchGestures(true)
        gateway = AccessibilityPageGateway(
            service = service,
            targetPackages = { setOf(IDENTITY.packageName) },
            monotonicClock = clock,
            currentIdentity = { IDENTITY },
        )
    }

    @After
    fun tearDown() {
        CountingWindowShadow.onRootRead = null
        controller.destroy()
    }

    @Test
    fun completionCallbackEndsGestureWait() = runTest {
        val result = async { gateway.gestureAtCenter(IDENTITY, SELECTOR, 1_000) }
        runCurrent()
        assertFalse(result.isCompleted)
        val dispatch = shadowService.gesturesDispatched.single()
        dispatch.callback().onCompleted(dispatch.description())
        runCurrent()

        assertEquals(GestureResult.Completed, result.await())
        dispatch.callback().onCancelled(dispatch.description())
        assertEquals(GestureResult.Completed, result.await())
    }

    @Test
    fun platformCancellationHasDistinctResult() = runTest {
        val result = async { gateway.gestureAtCenter(IDENTITY, SELECTOR, 1_000) }
        runCurrent()
        val dispatch = shadowService.gesturesDispatched.single()
        dispatch.callback().onCancelled(dispatch.description())
        runCurrent()

        assertEquals(GestureResult.Cancelled, result.await())
    }

    @Test
    fun rejectedGestureDoesNotWaitForCallback() = runTest {
        shadowService.setCanDispatchGestures(false)
        val result = gateway.gestureAtCenter(IDENTITY, SELECTOR, 1_000)

        assertEquals(GestureResult.Rejected, result)
    }

    @Test
    fun callerCancellationIgnoresLatePlatformCallback() = runTest {
        val result = async { gateway.gestureAtCenter(IDENTITY, SELECTOR, 1_000) }
        runCurrent()
        val dispatch = shadowService.gesturesDispatched.single()
        result.cancel()
        runCurrent()
        dispatch.callback().onCompleted(dispatch.description())
        runCurrent()

        assertTrue(result.isCancelled)
    }

    @Test
    fun missingCallbackCanBeTimedOutWithoutLateResumption() = runTest {
        val result = async {
            withTimeoutOrNull(1_000) { gateway.gestureAtCenter(IDENTITY, SELECTOR, 1_000) }
        }
        runCurrent()
        val dispatch = shadowService.gesturesDispatched.single()
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(result.await())

        dispatch.callback().onCompleted(dispatch.description())
        runCurrent()
        assertNull(result.await())
    }

    @Test
    fun synchronousScanCrossingDeadlineDoesNotDispatchGesture() = runTest {
        clock.now = 9_970
        // 根节点读取模拟主线程同步耗时；不推进协程调度器，确保超时任务尚未得到执行机会。
        CountingWindowShadow.onRootRead = { clock.now += 60 }

        val result = withTimeoutOrNull(30) {
            gateway.gestureAtCenter(IDENTITY, SELECTOR, deadlineElapsedMs = 10_000)
        }

        assertEquals(1, CountingWindowShadow.rootReads)
        assertEquals(10_030L, clock.now)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(GestureResult.TimedOut, result)
        assertTrue(shadowService.gesturesDispatched.isEmpty())
    }

    @Test
    fun expiredDeadlineDoesNotEvenReadWindowRoot() = runTest {
        clock.now = 1_000

        val result = gateway.gestureAtCenter(IDENTITY, SELECTOR, deadlineElapsedMs = 1_000)

        assertEquals(GestureResult.TimedOut, result)
        assertEquals(0, CountingWindowShadow.rootReads)
        assertTrue(shadowService.gesturesDispatched.isEmpty())
    }

    @Test
    fun cancellationDuringSynchronousScanDoesNotDispatchGesture() = runTest {
        val requestJob = Job()
        CountingWindowShadow.onRootRead = { requestJob.cancel() }
        val result = async(requestJob) {
            gateway.gestureAtCenter(IDENTITY, SELECTOR, deadlineElapsedMs = 1_000)
        }
        runCurrent()

        assertEquals(1, CountingWindowShadow.rootReads)
        assertTrue(result.isCancelled)
        assertTrue(shadowService.gesturesDispatched.isEmpty())
    }

    @Implements(AccessibilityWindowInfo::class)
    class CountingWindowShadow : ShadowAccessibilityWindowInfo() {
        @Implementation
        public override fun getRoot(): AccessibilityNodeInfo? {
            rootReads++
            onRootRead?.invoke()
            return super.getRoot()
        }

        companion object {
            var rootReads = 0
            var onRootRead: (() -> Unit)? = null
        }
    }

    private class FakeMonotonicClock : MonotonicClock {
        var now = 0L

        override fun elapsedRealtimeMs() = now
    }

    class TestAccessibilityService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

        override fun onInterrupt() = Unit
    }

    private companion object {
        val IDENTITY = PageIdentity("test.app", "test.app.MainActivity", 1)
        val SELECTOR = NodeSelector(contentDescription = "skip")
    }
}
