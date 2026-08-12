package com.zhenghuaizhi.flashskip.domain.rules

import com.zhenghuaizhi.flashskip.domain.model.AdRule
import com.zhenghuaizhi.flashskip.domain.model.AppVersion
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.RuleCaptureStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompositeRuleCatalogTest {
    @Test
    fun successfulLocalRulesAccumulateBeforeBuiltinRules() = runTest {
        val store = FakeCaptureStore(
            RuleCaptureState(samples = (1L..2L).map(::sample)),
        )
        val builtin = builtinRule()
        val catalog = CompositeRuleCatalog(
            BuiltinRuleCatalog(listOf(builtin)),
            store,
            backgroundScope,
        )
        runCurrent()

        val readyRules = catalog.snapshot.value.rulesForTarget(TargetAppId("com.example.music"), VERSION)
        assertEquals(2, readyRules.size)
        assertTrue(readyRules.first().id.startsWith("local-"))
        assertEquals(builtin.id, readyRules.last().id)

        store.update { state ->
            state.copy(samples = state.samples + divergentSample(3))
        }
        runCurrent()
        val accumulated = catalog.snapshot.value.rulesForTarget(TargetAppId("com.example.music"), VERSION)
        assertEquals(3, accumulated.size)
        assertTrue(accumulated.take(2).all { it.id.startsWith("local-") })
        assertEquals(builtin.id, accumulated.last().id)
    }

    @Test
    fun capturedRuleRemainsAvailableForAnotherInstalledVersion() = runTest {
        val store = FakeCaptureStore(
            RuleCaptureState(samples = (1L..2L).map(::sample)),
        )
        val catalog = CompositeRuleCatalog(BuiltinRuleCatalog(), store, backgroundScope)
        runCurrent()

        assertEquals(
            1,
            catalog.snapshot.value.rulesForTarget(TargetAppId("com.example.music"), VERSION).size,
        )
        assertEquals(
            1,
            catalog.snapshot.value.rulesForTarget(
                TargetAppId("com.example.music"),
                VERSION.copy(versionCode = 99),
            ).size,
        )
    }

    private class FakeCaptureStore(initial: RuleCaptureState) : RuleCaptureStore {
        override val state = MutableStateFlow(initial)
        override suspend fun update(transform: (RuleCaptureState) -> RuleCaptureState) {
            state.value = transform(state.value)
        }
    }

    private companion object {
        val VERSION = AppVersion("13.2.0", 42)
        val SELECTOR = CapturedSelector(resourceId = "com.example.music:id/close")

        fun sample(id: Long) = captureSample(id, SELECTOR)

        fun divergentSample(id: Long) = captureSample(
            id,
            CapturedSelector(resourceId = "com.example.music:id/dismiss"),
        )

        fun captureSample(id: Long, selector: CapturedSelector) = CaptureSample(
            id = id,
            targetAppId = TargetAppId("com.example.music"),
            activityName = "com.example.music.activity.AppStarterActivity",
            clickedAfterTargetMs = 1_000 + id,
            capturedAtEpochMs = 2_000 + id,
            nodes = listOf(
                CapturedNode(
                    resourceId = selector.resourceId,
                    clickable = true,
                    supportsActionClick = true,
                    visibleToUser = true,
                    hasUsableBounds = true,
                ),
            ),
            preClickUniqueSelectors = if (id == 2L) listOf(selector) else emptyList(),
        )

        fun builtinRule() = AdRule(
            id = "builtin-example",
            targetAppId = TargetAppId("com.example.music"),
            packageName = "com.example.music",
            allowedActivities = setOf("com.example.music.activity.AppStarterActivity"),
            selectors = listOf(NodeSelector(resourceId = "builtin-close")),
            startupDelayMs = 0,
            cooldownMs = 10_000,
        )
    }
}
