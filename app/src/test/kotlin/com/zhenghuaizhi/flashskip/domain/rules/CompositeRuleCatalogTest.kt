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
    fun readyLocalRulePublishesFirstAndCandidateWithdrawalLeavesBuiltin() = runTest {
        val store = FakeCaptureStore(
            RuleCaptureState(samples = (1L..5L).map(::sample)),
        )
        val builtin = builtinRule()
        val catalog = CompositeRuleCatalog(
            BuiltinRuleCatalog(listOf(builtin)),
            store,
            backgroundScope,
        )
        runCurrent()

        val readyRules = catalog.snapshot.value.rulesForTarget(TargetAppId.QQ_MUSIC, VERSION)
        assertEquals(2, readyRules.size)
        assertTrue(readyRules.first().id.startsWith("local-"))
        assertEquals(builtin.id, readyRules.last().id)

        store.update { state ->
            state.copy(samples = state.samples + divergentSample(6))
        }
        runCurrent()
        assertEquals(
            listOf(builtin.id),
            catalog.snapshot.value.rulesForTarget(TargetAppId.QQ_MUSIC, VERSION).map { it.id },
        )
    }

    @Test
    fun versionBoundRuleNeverAppearsForAnotherVersion() = runTest {
        val store = FakeCaptureStore(
            RuleCaptureState(samples = (1L..5L).map(::sample)),
        )
        val catalog = CompositeRuleCatalog(BuiltinRuleCatalog(), store, backgroundScope)
        runCurrent()

        assertEquals(
            1,
            catalog.snapshot.value.rulesForTarget(TargetAppId.QQ_MUSIC, VERSION).size,
        )
        assertTrue(
            catalog.snapshot.value.rulesForTarget(
                TargetAppId.QQ_MUSIC,
                VERSION.copy(versionCode = 99),
            ).isEmpty(),
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
        val SELECTOR = CapturedSelector(resourceId = "com.tencent.qqmusic:id/close")

        fun sample(id: Long) = captureSample(id, SELECTOR)

        fun divergentSample(id: Long) = captureSample(
            id,
            CapturedSelector(resourceId = "com.tencent.qqmusic:id/dismiss"),
        )

        fun captureSample(id: Long, selector: CapturedSelector) = CaptureSample(
            id = id,
            targetAppId = TargetAppId.QQ_MUSIC,
            appVersion = VERSION,
            activityName = "com.tencent.qqmusic.activity.AppStarterActivity",
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
            preClickUniqueSelectors = if (id <= 2) listOf(selector) else emptyList(),
        )

        fun builtinRule() = AdRule(
            id = "builtin-qq",
            targetAppId = TargetAppId.QQ_MUSIC,
            packageName = "com.tencent.qqmusic",
            allowedActivities = setOf("com.tencent.qqmusic.activity.AppStarterActivity"),
            selectors = listOf(NodeSelector(resourceId = "builtin-close")),
            startupDelayMs = 0,
            cooldownMs = 10_000,
        )
    }
}
