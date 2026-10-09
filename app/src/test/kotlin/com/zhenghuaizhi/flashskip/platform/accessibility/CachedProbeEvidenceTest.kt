package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Suppress("DEPRECATION")
class CachedProbeEvidenceTest {
    private lateinit var controller: ServiceController<TestAccessibilityService>
    private lateinit var probe: AccessibilityCaptureProbe
    private val nodes = mutableListOf<AccessibilityNodeInfo>()
    private val windows = mutableListOf<AccessibilityWindowInfo>()
    private var currentIdentity: PageIdentity? = IDENTITY

    @Before
    fun setUp() {
        controller = Robolectric.buildService(TestAccessibilityService::class.java).create()
        val service = controller.get()
        probe = AccessibilityCaptureProbe(
            service,
            targetPackages = { setOf(TARGET.packageName) },
            currentIdentity = { currentIdentity },
            semanticSnapshotter = AccessibilitySemanticSnapshotter(service),
        )
    }

    @After
    fun tearDown() {
        nodes.forEach(AccessibilityNodeInfo::recycle)
        nodes.clear()
        windows.forEach(AccessibilityWindowInfo::recycle)
        windows.clear()
        controller.destroy()
    }

    @Test
    fun cachedSelectorsStillRejectSecondRootWithMultipleMatches() {
        val cache = CaptureProbeSelectorCache()
        val samples = samples()
        val selectors = cache.selectorsFor(samples, TARGET)
        assertTrue(selectors.isNotEmpty())
        val unique = probe.uniqueSelectorsOnVerifiedRoot(IDENTITY, root(matches = 1), selectors)
        assertEquals(AccessibilityCaptureProbe.ProbeResult.Success(selectors.toSet()), unique)

        val cached = cache.selectorsFor(samples, TARGET)
        assertSame(selectors, cached)
        val ambiguous = probe.uniqueSelectorsOnVerifiedRoot(IDENTITY, root(matches = 2), cached)
        assertEquals(AccessibilityCaptureProbe.ProbeResult.Success(emptySet()), ambiguous)
    }

    @Test
    fun cachedSelectorsStillRejectChangedWindowIdentity() {
        val cache = CaptureProbeSelectorCache()
        val samples = samples()
        val selectors = cache.selectorsFor(samples, TARGET)
        assertEquals(
            AccessibilityCaptureProbe.ProbeResult.Success(selectors.toSet()),
            probe.uniqueSelectorsOnVerifiedRoot(IDENTITY, root(matches = 1), selectors),
        )
        currentIdentity = IDENTITY.copy(windowId = IDENTITY.windowId + 1)
        assertEquals(
            AccessibilityCaptureProbe.ProbeResult.PageChanged,
            probe.uniqueSelectorsOnVerifiedRoot(IDENTITY, root(matches = 1), cache.selectorsFor(samples, TARGET)),
        )
    }

    private fun root(matches: Int): AccessibilityNodeInfo {
        val window = AccessibilityWindowInfo.obtain().also { windows += it }
        shadowOf(window).setId(IDENTITY.windowId)
        fun node() = AccessibilityNodeInfo.obtain().apply {
            packageName = TARGET.packageName
            viewIdResourceName = "${TARGET.packageName}:id/skip"
            text = "跳过"
            isVisibleToUser = true
            setBoundsInScreen(Rect(10, 10, 60, 60))
            shadowOf(this).setAccessibilityWindowInfo(window)
        }.also { nodes += it }
        return node().also { root ->
            repeat(matches - 1) { shadowOf(root).addChild(node()) }
        }
    }

    class TestAccessibilityService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }

    private companion object {
        val TARGET = TargetAppId("com.example.target")
        val IDENTITY = PageIdentity(TARGET.packageName, "com.example.target.MainActivity", 7)

        fun samples() = listOf(
            CaptureSample(
                id = 1L,
                targetAppId = TARGET,
                activityName = requireNotNull(IDENTITY.activityName),
                clickedAfterTargetMs = 100L,
                capturedAtEpochMs = 1_000L,
                nodes = listOf(
                    CapturedNode(
                        resourceId = "${TARGET.packageName}:id/skip",
                        text = "跳过",
                        clickable = true,
                        visibleToUser = true,
                        hasUsableBounds = true,
                    ),
                ),
                preClickUniqueSelectors = emptyList(),
            ),
        )
    }
}
