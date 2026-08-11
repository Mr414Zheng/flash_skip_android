package com.zhenghuaizhi.flashskip.domain.automation

import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.AdRule
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.model.TextCondition
import com.zhenghuaizhi.flashskip.domain.ports.ClickResult
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipPreferences
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipStore
import com.zhenghuaizhi.flashskip.domain.ports.GestureResult
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.PageGateway
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.ports.QueryResult
import com.zhenghuaizhi.flashskip.domain.ports.WallClock
import com.zhenghuaizhi.flashskip.domain.rules.BuiltinRuleCatalog
import java.util.ArrayDeque
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationEngineForegroundTest {
    @Test
    fun outsideThenSameTargetCreatesExactlyOneNewSession() = runTest {
        val fixture = Fixture(this)
        fixture.target(QQ_IDENTITY, 0)
        fixture.advance(this, 120)
        assertEquals(1, fixture.gateway.queryCalls)

        fixture.outside(200)
        fixture.target(QQ_IDENTITY, 300)
        fixture.gateway.queryResults.add(QueryResult.Unique)
        fixture.gateway.clickResults.add(ClickResult.Clicked)
        fixture.advance(this, 120, clockNow = 420)

        assertEquals(2, fixture.gateway.queryCalls)
        assertEquals(1, fixture.gateway.clickCalls)
        assertEquals(1, fixture.store.current.records.size)
        fixture.engine.close()
    }

    @Test
    fun targetSwitchInvalidatesFirstTargetAndStartsSecondTarget() = runTest {
        val fixture = Fixture(this, rules = listOf(QQ_RULE, TAOBAO_RULE))
        fixture.target(QQ_IDENTITY, 0)
        fixture.advance(this, 50)
        fixture.target(TAOBAO_IDENTITY, 50)
        fixture.gateway.queryResults.add(QueryResult.Unique)
        fixture.gateway.clickResults.add(ClickResult.Clicked)
        fixture.advance(this, 120, clockNow = 170)

        assertEquals(listOf(TAOBAO_IDENTITY), fixture.gateway.queriedIdentities)
        assertEquals(TargetAppId.TAOBAO, fixture.store.current.records.single().targetAppId)
        fixture.engine.close()
    }

    @Test
    fun outsideCancelsPendingQueryWithoutRecord() = runTest {
        val fixture = Fixture(this)
        fixture.target(QQ_IDENTITY, 0)
        fixture.advance(this, 50)
        fixture.outside(50)
        fixture.advance(this, 200, clockNow = 250)

        assertEquals(0, fixture.gateway.queryCalls)
        assertTrue(fixture.store.current.records.isEmpty())
        fixture.engine.close()
    }

    @Test
    fun disabledOrUnavailableRuleDoesNotArmWithinSameForeground() = runTest {
        val disabled = Fixture(this, enabledTargets = emptySet())
        disabled.target(QQ_IDENTITY, 0)
        disabled.store.setAppEnabled(TargetAppId.QQ_MUSIC, true)
        disabled.target(QQ_IDENTITY.copy(windowId = 8), 1)
        disabled.advance(this, 200, clockNow = 201)
        assertEquals(0, disabled.gateway.queryCalls)
        disabled.engine.close()

        val unavailable = Fixture(this, rules = emptyList())
        unavailable.target(QQ_IDENTITY, 300)
        unavailable.target(QQ_IDENTITY.copy(activityName = "com.example.OtherActivity"), 301)
        unavailable.advance(this, 200, clockNow = 501)
        assertEquals(0, unavailable.gateway.queryCalls)
        unavailable.engine.close()
    }

    @Test
    fun newEngineLifecycleStartsFromUnknown() = runTest {
        val firstLifecycle = Fixture(this)
        firstLifecycle.target(QQ_IDENTITY, 0)
        firstLifecycle.engine.close()

        val secondLifecycle = Fixture(this)
        secondLifecycle.gateway.queryResults.add(QueryResult.Unique)
        secondLifecycle.gateway.clickResults.add(ClickResult.Clicked)
        secondLifecycle.target(QQ_IDENTITY, 1)
        secondLifecycle.advance(this, 120, clockNow = 121)

        assertEquals(1, secondLifecycle.gateway.queryCalls)
        assertEquals(1, secondLifecycle.store.current.records.size)
        secondLifecycle.engine.close()
    }

    private class Fixture(
        scope: TestScope,
        rules: List<AdRule> = listOf(QQ_RULE),
        enabledTargets: Set<TargetAppId> = setOf(TargetAppId.QQ_MUSIC, TargetAppId.TAOBAO),
    ) {
        val clock = FakeClock()
        val store = FakeStore(enabledTargets)
        val gateway = FakeGateway()
        val engine = AutomationEngine(
            catalog = BuiltinRuleCatalog(rules),
            store = store,
            pageGateway = gateway,
            monotonicClock = clock,
            wallClock = clock,
            scope = scope.backgroundScope,
        )

        suspend fun target(identity: PageIdentity, occurredAt: Long) {
            clock.now = occurredAt
            engine.onForegroundSignal(ForegroundSignal.TargetActive(identity, occurredAt))
            engine.awaitIdle()
        }

        suspend fun outside(occurredAt: Long) {
            clock.now = occurredAt
            engine.onForegroundSignal(ForegroundSignal.OutsideTargets(occurredAt))
            engine.awaitIdle()
        }

        suspend fun advance(scope: TestScope, by: Long, clockNow: Long = clock.now + by) {
            clock.now = clockNow
            scope.advanceTimeBy(by)
            scope.runCurrent()
            engine.awaitIdle()
        }
    }

    private class FakeClock : MonotonicClock, WallClock {
        var now = 0L
        override fun elapsedRealtimeMs(): Long = now
        override fun currentTimeMillis(): Long = 1_000_000L + now
    }

    private class FakeStore(enabledTargets: Set<TargetAppId>) : FlashSkipStore {
        private val state = MutableStateFlow(
            FlashSkipPreferences(
                enabledApps = TargetAppId.entries.associateWith(enabledTargets::contains),
            ),
        )
        val current: FlashSkipPreferences get() = state.value
        override val preferences = state

        override suspend fun setAppEnabled(targetAppId: TargetAppId, enabled: Boolean) {
            state.value = state.value.copy(
                enabledApps = state.value.enabledApps + (targetAppId to enabled),
            )
        }

        override suspend fun isAppEnabled(targetAppId: TargetAppId): Boolean =
            state.value.enabledApps[targetAppId] == true

        override suspend fun appendRecord(record: ActionRecord) {
            state.value = state.value.copy(records = listOf(record) + state.value.records)
        }
    }

    private class FakeGateway : PageGateway {
        val queryResults = ArrayDeque<QueryResult>()
        val clickResults = ArrayDeque<ClickResult>()
        var queryCalls = 0
        var clickCalls = 0
        val queriedIdentities = mutableListOf<PageIdentity>()

        override suspend fun query(identity: PageIdentity, selector: NodeSelector): QueryResult {
            queryCalls++
            queriedIdentities += identity
            return queryResults.pollFirst() ?: QueryResult.None
        }

        override suspend fun click(identity: PageIdentity, selector: NodeSelector): ClickResult {
            clickCalls++
            return clickResults.pollFirst() ?: ClickResult.NodeDisappeared
        }

        override suspend fun gestureAtCenter(
            identity: PageIdentity,
            selector: NodeSelector,
        ): GestureResult = GestureResult.Rejected
    }

    private companion object {
        val QQ_IDENTITY = PageIdentity(
            packageName = "com.tencent.qqmusic",
            activityName = "com.example.QqSplashActivity",
            windowId = 7,
        )
        val TAOBAO_IDENTITY = PageIdentity(
            packageName = "com.taobao.taobao",
            activityName = "com.example.TaobaoSplashActivity",
            windowId = 9,
        )
        val QQ_RULE = testRule(
            id = "qq-test",
            targetAppId = TargetAppId.QQ_MUSIC,
            identity = QQ_IDENTITY,
        )
        val TAOBAO_RULE = testRule(
            id = "taobao-test",
            targetAppId = TargetAppId.TAOBAO,
            identity = TAOBAO_IDENTITY,
        )

        fun testRule(id: String, targetAppId: TargetAppId, identity: PageIdentity) = AdRule(
            id = id,
            targetAppId = targetAppId,
            packageName = identity.packageName,
            allowedActivities = setOf(requireNotNull(identity.activityName)),
            selectors = listOf(NodeSelector(text = TextCondition.Exact("关闭"))),
            startupDelayMs = 0,
            cooldownMs = 500,
        )
    }
}
