package com.zhenghuaizhi.flashskip.platform.accessibility

import android.app.Application
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.zhenghuaizhi.flashskip.testing.TARGET
import java.util.IdentityHashMap
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.Resetter
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.shadows.ShadowAccessibilityWindowInfo

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [P3RecyclingNodeShadow::class, P3RecyclingWindowShadow::class, P3ResolverServiceShadow::class])
class AccessibilityCaptureWindowResolverLifecycleTest {
    @Before fun resetRecyclingCounters() {
        P3RecyclingNodeShadow.reset()
        P3RecyclingWindowShadow.reset()
        P3ResolverServiceShadow.reset()
    }

    @Test fun resolvesMatchingApplicationRootAndReleasesBorrowedObjects() {
        val f = AccessibilityFixture()
        var resolvedNode: AccessibilityNodeInfo? = null
        val result = resolver(f).withResolvedRoot(request(f), null) {
            resolvedNode = it.node
            assertEquals(TARGET.packageName, it.node.packageName.toString())
            assertEquals(7, it.node.windowId)
            assertFalse(it.ownedChild)
            "读取完成"
        }
        assertTrue(result is AccessibilityCaptureWindowResolver.Result.Success)
        assertEquals(CaptureWindowResolutionSource.WINDOW_LIST, (result as AccessibilityCaptureWindowResolver.Result.Success).source)
        assertEquals("读取完成", result.value)
        assertEquals(1, P3RecyclingNodeShadow.recycled[resolvedNode])
        assertEquals(1, P3RecyclingWindowShadow.recycled.size)
        assertFalse(P3RecyclingWindowShadow.recycled.containsKey(f.window))
        assertTrue(resolver(f).withResolvedRoot(request(f), null) { Unit } is AccessibilityCaptureWindowResolver.Result.Success)
    }

    @Test fun wrongPackageInMatchingRootIsRejectedWithoutInvokingConsumer() {
        val f = AccessibilityFixture()
        f.root.packageName = "com.example.other"
        val result = resolver(f).withResolvedRoot(request(f), null) { error("错误包不得进入消费方") }
        assertEquals(CaptureWindowBoundaryPolicy.RejectReason.WINDOW_ROOT_MISMATCH,
            (result as AccessibilityCaptureWindowResolver.Result.Rejected).reason)
        assertEquals(1, P3RecyclingNodeShadow.recycled.size)
        assertEquals(1, P3RecyclingWindowShadow.recycled.size)
    }

    @Test fun duplicateWindowIdsAreRejectedEvenWhenActiveRootMatchesHost() {
        val f = AccessibilityFixture()
        f.serviceShadow.setWindows(listOf(f.window, f.window))
        val result = resolver(f).withResolvedRoot(request(f), null) { error("歧义窗口不得读取") }
        assertEquals(CaptureWindowBoundaryPolicy.RejectReason.EVENT_WINDOW_AMBIGUOUS,
            (result as AccessibilityCaptureWindowResolver.Result.Rejected).reason)
        assertEquals(2, result.details.matchingWindowCount)
        assertEquals(2, P3RecyclingWindowShadow.recycled.size)
        assertEquals(1, P3RecyclingNodeShadow.recycled.size)
    }

    @Test fun missingWindowListCanResolveActiveRootUsingItsWindowMetadata() {
        val f = AccessibilityFixture()
        f.serviceShadow.setWindows(emptyList())
        val result = resolver(f).withResolvedRoot(request(f), null) {
            assertEquals(7, it.node.windowId)
            assertFalse(it.ownedChild)
            Unit
        }
        assertEquals(CaptureWindowResolutionSource.ACTIVE_ROOT,
            (result as AccessibilityCaptureWindowResolver.Result.Success).source)
        assertEquals(1, P3RecyclingNodeShadow.recycled.size)
        assertEquals(1, P3RecyclingWindowShadow.recycled.size)
    }

    @Test fun absentActiveRootIsRetryableAndDoesNotProveAnAbsentCandidate() {
        val f = AccessibilityFixture()
        f.serviceShadow.setWindows(emptyList())
        f.serviceShadow.setRootInActiveWindow(null)
        val result = resolver(f).withResolvedRoot(request(f), null) { error("无根不得扫描") }
        assertTrue(result is AccessibilityCaptureWindowResolver.Result.Retryable)
        assertTrue(P3RecyclingNodeShadow.recycled.isEmpty())
    }

    @Test fun inactiveMatchingApplicationWindowCannotResolveClickRoot() {
        val f = AccessibilityFixture()
        shadowOf(f.window).setActive(false)
        val result = resolver(f).withResolvedRoot(request(f), null) { error("非活动窗口不得读取") }
        assertEquals(CaptureWindowBoundaryPolicy.RejectReason.WINDOW_NOT_ACTIVE,
            (result as AccessibilityCaptureWindowResolver.Result.Rejected).reason)
        assertEquals(1, P3RecyclingWindowShadow.recycled.size)
    }

    @Test fun samePackageEventSourceResolvesOwnedChildThroughVerifiedHostForeground() {
        val f = AccessibilityFixture()
        val childWindow = childWindow()
        val childRoot = f.node("child_root")
        val childButton = f.node("child_skip", "跳过", true)
        shadowOf(childRoot).setAccessibilityWindowInfo(childWindow)
        shadowOf(childButton).setAccessibilityWindowInfo(childWindow)
        f.add(childRoot, childButton)
        shadowOf(childWindow).setRoot(childRoot)
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED)
        shadowOf(event).setSourceNode(childButton)
        try {
            val result = resolver(f).withResolvedRoot(request(f, windowId = 8), event) {
                assertEquals(8, it.node.windowId)
                assertEquals(childRoot.viewIdResourceName, it.node.viewIdResourceName)
                assertTrue(it.ownedChild)
                Unit
            }
            assertEquals(CaptureWindowResolutionSource.EVENT_SOURCE,
                (result as AccessibilityCaptureWindowResolver.Result.Success).source)
            assertTrue(result.ownedChild)
            assertEquals(2, P3RecyclingWindowShadow.recycled.size)
            assertTrue(P3RecyclingNodeShadow.recycled.size >= 3)
        } finally { event.recycle() }
    }

    @Test fun eventSourceFromAnotherPackageIsRejectedDespiteVerifiedActiveHost() {
        val f = AccessibilityFixture()
        val child = f.node("foreign_source")
        shadowOf(child).setAccessibilityWindowInfo(childWindow())
        child.packageName = "com.example.other"
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED)
        shadowOf(event).setSourceNode(child)
        try {
            val result = resolver(f).withResolvedRoot(request(f, windowId = 8), event) { error("错误源不得读取") }
            assertEquals(CaptureWindowBoundaryPolicy.RejectReason.SOURCE_MISMATCH,
                (result as AccessibilityCaptureWindowResolver.Result.Rejected).reason)
            assertTrue(P3RecyclingNodeShadow.recycled.size >= 2)
        } finally { event.recycle() }
    }

    @Test fun missingChildSourceAllowsExplicitHostFallbackButClickRequestDoesNot() {
        val f = AccessibilityFixture()
        val result = resolver(f).withResolvedRoot(request(f, windowId = 8), null) { error("点击缺源不得换窗口") }
        assertTrue(result is AccessibilityCaptureWindowResolver.Result.Retryable)
        val fallback = resolver(f).withResolvedRoot(request(f, windowId = 8).copy(allowActiveHostRootFallback = true), null) {
            assertEquals(7, it.node.windowId)
            assertFalse(it.ownedChild)
            Unit
        }
        assertEquals(CaptureWindowResolutionSource.ACTIVE_ROOT,
            (fallback as AccessibilityCaptureWindowResolver.Result.Success).source)
    }

    @Test fun consumerExceptionStillReleasesRootAndWindowCopies() {
        val f = AccessibilityFixture()
        var resolvedNode: AccessibilityNodeInfo? = null
        try {
            resolver(f).withResolvedRoot(request(f), null) {
                resolvedNode = it.node
                throw IllegalStateException("消费方失败")
            }
            fail("消费方异常必须传播")
        } catch (failure: IllegalStateException) {
            assertEquals("消费方失败", failure.message)
        }
        assertEquals(1, P3RecyclingNodeShadow.recycled[resolvedNode])
        assertEquals(1, P3RecyclingWindowShadow.recycled.size)
    }

    @Test fun reachedRetryDeadlineDoesNotReadAnyWindowOrInvokeConsumer() {
        val f = AccessibilityFixture()
        val result = resolver(f).withResolvedRoot(request(f), null, deadlineAtElapsedMs = 250, elapsedRealtimeMs = { 250 }) { error("到期不得读取") }
        assertTrue(result is AccessibilityCaptureWindowResolver.Result.Retryable)
        assertTrue(P3RecyclingNodeShadow.recycled.isEmpty())
        assertTrue(P3RecyclingWindowShadow.recycled.isEmpty())
        assertEquals(0, P3ResolverServiceShadow.windowReads)
        assertEquals(0, P3ResolverServiceShadow.activeRootReads)
    }

    private fun resolver(f: AccessibilityFixture) = AccessibilityCaptureWindowResolver(f.service)
    private fun request(f: AccessibilityFixture, windowId: Int = 7) = AccessibilityCaptureWindowResolver.Request(
        1, TARGET.packageName, windowId, VerifiedCaptureHost(1, TARGET.packageName, f.identity.activityName!!, 7),
    )
    private fun childWindow() = AccessibilityWindowInfo.obtain().also {
        shadowOf(it).setId(8)
        shadowOf(it).setActive(true)
        shadowOf(it).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
    }
}

@Implements(android.accessibilityservice.AccessibilityService::class)
class P3ResolverServiceShadow : ShadowAccessibilityService() {
    @Implementation override fun getWindows(): List<AccessibilityWindowInfo> {
        windowReads++
        return super.getWindows().map { AccessibilityWindowInfo.obtain(it) }
    }
    @Implementation override fun getRootInActiveWindow(): AccessibilityNodeInfo? {
        activeRootReads++
        return super.getRootInActiveWindow()
    }
    companion object {
        var windowReads = 0
        var activeRootReads = 0
        @JvmStatic @Resetter fun reset() { windowReads = 0; activeRootReads = 0 }
    }
}

/** 只记录框架资源释放调用，仍由原 shadow 执行释放；不实现窗口边界政策。 */
@Suppress("DEPRECATION")
@Implements(AccessibilityNodeInfo::class)
class P3RecyclingNodeShadow : ShadowAccessibilityNodeInfo() {
    @RealObject private lateinit var node: AccessibilityNodeInfo
    @Implementation override fun recycle() {
        recycled[node] = (recycled[node] ?: 0) + 1
        super.recycle()
    }
    companion object {
        val recycled = IdentityHashMap<AccessibilityNodeInfo, Int>()
        @JvmStatic @Resetter fun reset() { recycled.clear() }
    }
}

@Suppress("DEPRECATION")
@Implements(AccessibilityWindowInfo::class)
class P3RecyclingWindowShadow : ShadowAccessibilityWindowInfo() {
    @RealObject private lateinit var window: AccessibilityWindowInfo
    @Implementation override fun recycle() {
        recycled[window] = (recycled[window] ?: 0) + 1
        super.recycle()
    }
    companion object {
        val recycled = IdentityHashMap<AccessibilityWindowInfo, Int>()
        @JvmStatic @Resetter fun reset() { recycled.clear() }
    }
}
