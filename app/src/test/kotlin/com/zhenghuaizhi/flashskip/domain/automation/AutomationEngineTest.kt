package com.zhenghuaizhi.flashskip.domain.automation

import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.AdRule
import com.zhenghuaizhi.flashskip.domain.model.AppVersion
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
class AutomationEngineTest {
    @Test
    fun clickIsConfirmedAfterSelectorDisappearsAndNeverFallsBackToGesture() = runTest {
        val fixture = fixture()
        fixture.gateway.queryResults.add(QueryResult.Unique)
        fixture.gateway.clickResults.add(ClickResult.Clicked)

        fixture.startAndAdvance(this, 120)

        assertEquals(1, fixture.gateway.queryCalls)
        assertEquals(1, fixture.gateway.clickCalls)
        assertEquals(0, fixture.gateway.gestureCalls)
        assertTrue(fixture.store.current.records.isEmpty())

        fixture.advance(this, 150)

        assertEquals(2, fixture.gateway.queryCalls)
        val record = fixture.store.current.records.single()
        assertEquals(ActionOutcome.CLOSED, record.outcome)
        assertEquals(120L, record.timing?.targetToFirstQueryMs)
        assertEquals(120L, record.timing?.targetToFirstMatchMs)
        assertEquals(120L, record.timing?.targetToActionMs)
        assertEquals(270L, record.timing?.targetToCompletedMs)
        assertEquals(2, record.timing?.selectorQueryCount)

        fixture.clock.now = 500
        fixture.engine.onForegroundSignal(ForegroundSignal.TargetActive(IDENTITY, 500))
        fixture.engine.awaitIdle()
        advanceTimeBy(120)
        runCurrent()
        fixture.engine.awaitIdle()
        assertEquals(1, fixture.gateway.clickCalls)
        fixture.engine.close()
    }

    @Test
    fun visibleSelectorIsRetriedTwiceBeforeCloseIsConfirmed() = runTest {
        val fixture = fixture()
        repeat(3) { fixture.gateway.queryResults.add(QueryResult.Unique) }
        repeat(3) { fixture.gateway.clickResults.add(ClickResult.Clicked) }

        fixture.startAndAdvance(this, 120)
        fixture.advance(this, 150)
        fixture.advance(this, 150)
        fixture.advance(this, 150)

        assertEquals(4, fixture.gateway.queryCalls)
        assertEquals(3, fixture.gateway.clickCalls)
        val record = fixture.store.current.records.single()
        assertEquals(ActionOutcome.CLOSED, record.outcome)
        assertEquals(
            com.zhenghuaizhi.flashskip.domain.model.ActionMethod.NODE_CLICK,
            record.method,
        )
        fixture.engine.close()
    }

    @Test
    fun persistentSelectorFailsAfterThreeBoundedNodeClicks() = runTest {
        val fixture = fixture()
        repeat(4) { fixture.gateway.queryResults.add(QueryResult.Unique) }
        repeat(3) { fixture.gateway.clickResults.add(ClickResult.Clicked) }

        fixture.startAndAdvance(this, 120)
        repeat(3) { fixture.advance(this, 150) }

        assertEquals(4, fixture.gateway.queryCalls)
        assertEquals(3, fixture.gateway.clickCalls)
        val record = fixture.store.current.records.single()
        assertEquals(ActionOutcome.FAILED, record.outcome)
        assertEquals(
            com.zhenghuaizhi.flashskip.domain.model.ActionReason.CLICK_UNCONFIRMED,
            record.reason,
        )
        fixture.engine.close()
    }

    @Test
    fun pageChangeAfterNodeClickConfirmsCloseWithoutRetry() = runTest {
        val fixture = fixture()
        fixture.gateway.queryResults.add(QueryResult.Unique)
        fixture.gateway.queryResults.add(QueryResult.PageChanged)
        fixture.gateway.clickResults.add(ClickResult.Clicked)

        fixture.startAndAdvance(this, 120)
        fixture.advance(this, 150)

        assertEquals(1, fixture.gateway.clickCalls)
        assertEquals(ActionOutcome.CLOSED, fixture.store.current.records.single().outcome)
        fixture.engine.close()
    }

    @Test
    fun traversalLimitAfterNodeClickConfirmsCloseWithoutRetry() = runTest {
        val fixture = fixture()
        fixture.gateway.queryResults.add(QueryResult.Unique)
        fixture.gateway.queryResults.add(QueryResult.TraversalLimit)
        fixture.gateway.clickResults.add(ClickResult.Clicked)

        fixture.startAndAdvance(this, 120)
        fixture.advance(this, 150)

        assertEquals(2, fixture.gateway.queryCalls)
        assertEquals(1, fixture.gateway.clickCalls)
        val record = fixture.store.current.records.single()
        assertEquals(ActionOutcome.CLOSED, record.outcome)
        assertEquals(
            com.zhenghuaizhi.flashskip.domain.model.ActionMethod.NODE_CLICK,
            record.method,
        )
        fixture.engine.close()
    }

    @Test
    fun clickFalseTriggersFreshGesturePath() = runTest {
        val fixture = fixture()
        fixture.gateway.queryResults.add(QueryResult.Unique)
        fixture.gateway.clickResults.add(ClickResult.NotSupported)
        fixture.gateway.gestureResults.add(GestureResult.Completed)

        fixture.startAndAdvance(this, 120)

        assertEquals(1, fixture.gateway.clickCalls)
        assertEquals(1, fixture.gateway.gestureCalls)
        assertEquals(
            com.zhenghuaizhi.flashskip.domain.model.ActionMethod.CENTER_GESTURE,
            fixture.store.current.records.single().method,
        )
        fixture.engine.close()
    }

    @Test
    fun visibleButNotActionableCountdownWaitsForNextContentEventWithoutGesture() = runTest {
        val fixture = fixture()
        fixture.gateway.queryResults.add(QueryResult.Unique)
        fixture.gateway.clickResults.add(ClickResult.NotActionable)

        fixture.startAndAdvance(this, 120)

        assertEquals(1, fixture.gateway.clickCalls)
        assertEquals(0, fixture.gateway.gestureCalls)
        assertTrue(fixture.store.current.records.isEmpty())

        fixture.gateway.queryResults.add(QueryResult.Unique)
        fixture.gateway.clickResults.add(ClickResult.Clicked)
        fixture.clock.now = 130
        advanceTimeBy(10)
        runCurrent()
        fixture.engine.onContentChanged(IDENTITY, 130)
        fixture.engine.awaitIdle()
        fixture.advance(this, 120)

        assertEquals(2, fixture.gateway.clickCalls)
        assertEquals(0, fixture.gateway.gestureCalls)
        fixture.engine.close()
    }

    @Test
    fun ambiguousMatchAbandonsWithoutAnyAction() = runTest {
        val fixture = fixture()
        fixture.gateway.queryResults.add(QueryResult.Ambiguous)

        fixture.startAndAdvance(this, 120)

        assertEquals(0, fixture.gateway.clickCalls)
        assertEquals(0, fixture.gateway.gestureCalls)
        val record = fixture.store.current.records.single()
        assertEquals(ActionOutcome.ABANDONED, record.outcome)
        assertEquals(
            com.zhenghuaizhi.flashskip.domain.model.ActionReason.AMBIGUOUS_MATCH,
            record.reason,
        )
        fixture.engine.close()
    }

    @Test
    fun candidateSelectorsAreEvaluatedInDeclaredOrder() = runTest {
        val selectors = listOf(
            NodeSelector(text = TextCondition.Exact("first")),
            NodeSelector(text = TextCondition.Exact("second")),
        )
        val fixture = fixture(selectors = selectors)
        fixture.gateway.queryResults.add(QueryResult.None)
        fixture.gateway.queryResults.add(QueryResult.Unique)
        fixture.gateway.clickResults.add(ClickResult.Clicked)

        fixture.startAndAdvance(this, 120)

        assertEquals(selectors, fixture.gateway.queriedSelectors)
        assertEquals(1, fixture.gateway.clickCalls)
        fixture.engine.close()
    }

    @Test
    fun traversalLimitAbandonsWithoutAction() = runTest {
        val fixture = fixture()
        fixture.gateway.queryResults.add(QueryResult.TraversalLimit)

        fixture.startAndAdvance(this, 120)

        assertEquals(ActionOutcome.ABANDONED, fixture.store.current.records.single().outcome)
        assertEquals(0, fixture.gateway.clickCalls)
        fixture.engine.close()
    }

    @Test
    fun actionAllowedAt9999ButRejectedAt10000() = runTest {
        val beforeBoundary = fixture(startupDelayMs = 9_999)
        beforeBoundary.gateway.queryResults.add(QueryResult.Unique)
        beforeBoundary.gateway.clickResults.add(ClickResult.Clicked)
        beforeBoundary.startAndAdvance(this, 9_999)
        assertEquals(1, beforeBoundary.gateway.clickCalls)
        beforeBoundary.engine.close()

        val atBoundary = fixture(startupDelayMs = 10_000)
        atBoundary.gateway.queryResults.add(QueryResult.Unique)
        atBoundary.gateway.clickResults.add(ClickResult.Clicked)
        atBoundary.startAndAdvance(this, 10_000)
        assertEquals(0, atBoundary.gateway.clickCalls)
        assertTrue(atBoundary.store.current.records.isEmpty())
        atBoundary.engine.close()
    }

    @Test
    fun samePackageContentDoesNotExtendDeadline() = runTest {
        val fixture = fixture(startupDelayMs = 10_050)
        fixture.createEngine(this)
        fixture.engine.onForegroundSignal(ForegroundSignal.TargetActive(IDENTITY, 0))
        fixture.engine.awaitIdle()

        fixture.clock.now = 9_000
        advanceTimeBy(9_000)
        runCurrent()
        fixture.engine.onContentChanged(IDENTITY, 9_000)
        fixture.engine.awaitIdle()

        fixture.clock.now = 10_050
        advanceTimeBy(1_050)
        runCurrent()
        fixture.engine.awaitIdle()

        assertEquals(0, fixture.gateway.queryCalls)
        assertEquals(0, fixture.gateway.clickCalls)
        fixture.engine.close()
    }

    @Test
    fun samePackageWindowAfterDeadlineDoesNotRearmSession() = runTest {
        val fixture = fixture()
        fixture.createEngine(this)
        fixture.engine.onForegroundSignal(ForegroundSignal.TargetActive(IDENTITY, 0))
        fixture.engine.awaitIdle()

        fixture.clock.now = 120
        advanceTimeBy(120)
        runCurrent()
        fixture.engine.awaitIdle()
        assertEquals(1, fixture.gateway.queryCalls)

        fixture.gateway.queryResults.add(QueryResult.Unique)
        fixture.gateway.clickResults.add(ClickResult.Clicked)
        fixture.clock.now = 10_000
        advanceTimeBy(9_880)
        runCurrent()
        fixture.engine.onForegroundSignal(ForegroundSignal.TargetActive(IDENTITY, 10_000))
        fixture.engine.awaitIdle()
        fixture.clock.now = 10_120
        advanceTimeBy(120)
        runCurrent()
        fixture.engine.awaitIdle()

        assertEquals(1, fixture.gateway.queryCalls)
        assertEquals(0, fixture.gateway.clickCalls)
        assertTrue(fixture.store.current.records.isEmpty())
        fixture.engine.close()
    }

    @Test
    fun laterStartupRuleIsQueriedWithoutAnotherAccessibilityEvent() = runTest {
        val firstSelector = NodeSelector(text = TextCondition.Exact("first"))
        val secondSelector = NodeSelector(text = TextCondition.Exact("second"))
        val firstRule = testRule(
            id = "first-rule",
            startupDelayMs = 0,
            selectors = listOf(firstSelector),
        )
        val secondRule = testRule(
            id = "second-rule",
            startupDelayMs = 500,
            selectors = listOf(secondSelector),
        )
        val fixture = fixture(rules = listOf(firstRule, secondRule))
        fixture.createEngine(this)
        fixture.gateway.queryResults.add(QueryResult.None)
        fixture.gateway.queryResults.add(QueryResult.None)
        fixture.gateway.queryResults.add(QueryResult.Unique)
        fixture.gateway.clickResults.add(ClickResult.Clicked)

        fixture.engine.onForegroundSignal(ForegroundSignal.TargetActive(IDENTITY, 0))
        fixture.engine.awaitIdle()
        fixture.clock.now = 120
        advanceTimeBy(120)
        runCurrent()
        fixture.engine.awaitIdle()
        assertEquals(listOf(firstSelector), fixture.gateway.queriedSelectors)

        fixture.clock.now = 500
        advanceTimeBy(380)
        runCurrent()
        fixture.engine.awaitIdle()
        fixture.advance(this, 150)

        assertTrue(secondSelector in fixture.gateway.queriedSelectors)
        assertEquals("second-rule", fixture.store.current.records.single().ruleId)
        fixture.engine.close()
    }

    @Test
    fun denseContentEventsDoNotPostponeEarliestQuery() = runTest {
        val fixture = fixture()
        fixture.createEngine(this)
        fixture.engine.onForegroundSignal(ForegroundSignal.TargetActive(IDENTITY, 0))
        fixture.engine.awaitIdle()

        fixture.clock.now = 100
        advanceTimeBy(100)
        runCurrent()
        repeat(20) { fixture.engine.onContentChanged(IDENTITY, 100) }
        fixture.engine.awaitIdle()

        fixture.clock.now = 120
        advanceTimeBy(20)
        runCurrent()
        fixture.engine.awaitIdle()

        assertEquals(1, fixture.gateway.queryCalls)
        fixture.engine.close()
    }

    @Test
    fun disablingTargetBeforeDueProducesZeroActions() = runTest {
        val fixture = fixture()
        fixture.createEngine(this)
        fixture.engine.onForegroundSignal(ForegroundSignal.TargetActive(IDENTITY, 0))
        fixture.engine.awaitIdle()
        fixture.store.setAppEnabled(TargetAppId.QQ_MUSIC, false)

        fixture.clock.now = 120
        advanceTimeBy(120)
        runCurrent()
        fixture.engine.awaitIdle()

        assertEquals(0, fixture.gateway.queryCalls)
        assertEquals(0, fixture.gateway.clickCalls)
        fixture.engine.close()
    }

    @Test
    fun mailboxOverflowInvalidatesExistingGeneration() = runTest {
        val fixture = fixture()
        fixture.createEngine(this)
        fixture.engine.onForegroundSignal(ForegroundSignal.TargetActive(IDENTITY, 0))
        fixture.engine.awaitIdle()
        repeat(100) { fixture.engine.onContentChanged(IDENTITY, it.toLong()) }

        runCurrent()
        fixture.engine.awaitIdle()
        fixture.clock.now = 120
        advanceTimeBy(120)
        runCurrent()
        fixture.engine.awaitIdle()

        assertEquals(0, fixture.gateway.queryCalls)
        assertTrue(fixture.store.current.records.isEmpty())
        fixture.engine.close()
    }

    @Test
    fun capturePauseCancelsPendingWorkAndProducesZeroNodeActions() = runTest {
        val fixture = fixture()
        fixture.createEngine(this)
        fixture.engine.onForegroundSignal(ForegroundSignal.TargetActive(IDENTITY, 0))
        fixture.engine.awaitIdle()
        fixture.engine.setCapturePaused(true)

        fixture.gateway.queryResults.add(QueryResult.Unique)
        fixture.gateway.clickResults.add(ClickResult.Clicked)
        fixture.engine.onContentChanged(IDENTITY, 10)
        fixture.clock.now = 500
        advanceTimeBy(500)
        runCurrent()
        fixture.engine.awaitIdle()

        assertEquals(0, fixture.gateway.queryCalls)
        assertEquals(0, fixture.gateway.clickCalls)
        assertEquals(0, fixture.gateway.gestureCalls)
        fixture.engine.close()
    }

    @Test
    fun capturePauseCancelsPendingClickVerificationAndPreventsRetries() = runTest {
        val fixture = fixture()
        fixture.gateway.queryResults.add(QueryResult.Unique)
        fixture.gateway.clickResults.add(ClickResult.Clicked)
        fixture.startAndAdvance(this, 120)

        fixture.engine.setCapturePaused(true)
        fixture.advance(this, 500)

        assertEquals(1, fixture.gateway.queryCalls)
        assertEquals(1, fixture.gateway.clickCalls)
        assertEquals(0, fixture.gateway.gestureCalls)
        assertTrue(fixture.store.current.records.isEmpty())
        fixture.engine.close()
    }

    @Test
    fun localRuleOnlyArmsForItsExactApplicationVersion() = runTest {
        val version = AppVersion("13.2.0", 42)
        val localRule = testRule(
            id = "local-versioned",
            startupDelayMs = 0,
            selectors = listOf(NodeSelector(resourceId = "close")),
        ).copy(targetAppVersion = version)
        val mismatch = fixture(rules = listOf(localRule))
        mismatch.createEngine(this)
        mismatch.engine.onForegroundSignal(
            ForegroundSignal.TargetActive(
                IDENTITY.copy(appVersion = version.copy(versionCode = 43)),
                0,
            ),
        )
        mismatch.engine.awaitIdle()
        mismatch.clock.now = 500
        advanceTimeBy(500)
        runCurrent()
        mismatch.engine.awaitIdle()
        assertEquals(0, mismatch.gateway.queryCalls)
        mismatch.engine.close()

        val matching = fixture(rules = listOf(localRule))
        matching.createEngine(this)
        matching.gateway.queryResults.add(QueryResult.Unique)
        matching.gateway.clickResults.add(ClickResult.Clicked)
        matching.engine.onForegroundSignal(
            ForegroundSignal.TargetActive(IDENTITY.copy(appVersion = version), 0),
        )
        matching.engine.awaitIdle()
        matching.clock.now = 120
        advanceTimeBy(120)
        runCurrent()
        matching.engine.awaitIdle()
        assertEquals(1, matching.gateway.clickCalls)
        matching.engine.close()
    }

    private fun fixture(
        startupDelayMs: Long = 0,
        selectors: List<NodeSelector> = listOf(
            NodeSelector(text = TextCondition.Exact("test-close")),
        ),
        rules: List<AdRule>? = null,
    ): Fixture {
        val clock = FakeClock()
        val store = FakeStore()
        val gateway = FakeGateway()
        val resolvedRules = rules ?: listOf(
            testRule(
                id = "test-rule",
                startupDelayMs = startupDelayMs,
                selectors = selectors,
            ),
        )
        return Fixture(clock, store, gateway, resolvedRules)
    }

    private fun testRule(
        id: String,
        startupDelayMs: Long,
        selectors: List<NodeSelector>,
    ) = AdRule(
        id = id,
        targetAppId = TargetAppId.QQ_MUSIC,
        packageName = "com.tencent.qqmusic",
        allowedActivities = setOf("com.example.TestSplashActivity"),
        selectors = selectors,
        startupDelayMs = startupDelayMs,
        cooldownMs = 500,
    )

    private data class Fixture(
        val clock: FakeClock,
        val store: FakeStore,
        val gateway: FakeGateway,
        val rules: List<AdRule>,
    ) {
        lateinit var engine: AutomationEngine

        fun createEngine(scope: TestScope) {
            engine = AutomationEngine(
                catalog = BuiltinRuleCatalog(rules),
                store = store,
                pageGateway = gateway,
                monotonicClock = clock,
                wallClock = clock,
                scope = scope.backgroundScope,
            )
        }

        suspend fun startAndAdvance(scope: TestScope, elapsedMs: Long) {
            createEngine(scope)
            engine.onForegroundSignal(ForegroundSignal.TargetActive(IDENTITY, 0))
            engine.awaitIdle()
            clock.now = elapsedMs
            scope.advanceTimeBy(elapsedMs)
            scope.runCurrent()
            engine.awaitIdle()
        }

        suspend fun advance(scope: TestScope, elapsedMs: Long) {
            clock.now += elapsedMs
            scope.advanceTimeBy(elapsedMs)
            scope.runCurrent()
            engine.awaitIdle()
        }

    }

    private class FakeClock : MonotonicClock, WallClock {
        var now: Long = 0
        override fun elapsedRealtimeMs(): Long = now
        override fun currentTimeMillis(): Long = 1_000_000 + now
    }

    private class FakeStore : FlashSkipStore {
        private val state = MutableStateFlow(
            FlashSkipPreferences(
                enabledApps = TargetAppId.entries.associateWith { it == TargetAppId.QQ_MUSIC },
            ),
        )
        val current: FlashSkipPreferences
            get() = state.value
        override val preferences = state

        override suspend fun setAppEnabled(targetAppId: TargetAppId, enabled: Boolean) {
            state.value = state.value.copy(
                enabledApps = state.value.enabledApps + (targetAppId to enabled),
            )
        }

        override suspend fun isAppEnabled(targetAppId: TargetAppId): Boolean =
            state.value.enabledApps[targetAppId] == true

        override suspend fun appendRecord(record: com.zhenghuaizhi.flashskip.domain.model.ActionRecord) {
            state.value = state.value.copy(records = listOf(record) + state.value.records)
        }
    }

    private class FakeGateway : PageGateway {
        val queryResults = ArrayDeque<QueryResult>()
        val clickResults = ArrayDeque<ClickResult>()
        val gestureResults = ArrayDeque<GestureResult>()
        var queryCalls = 0
        var clickCalls = 0
        var gestureCalls = 0
        val queriedSelectors = mutableListOf<NodeSelector>()

        override suspend fun query(identity: PageIdentity, selector: NodeSelector): QueryResult {
            queryCalls++
            queriedSelectors += selector
            return queryResults.pollFirst() ?: QueryResult.None
        }

        override suspend fun click(identity: PageIdentity, selector: NodeSelector): ClickResult {
            clickCalls++
            return clickResults.pollFirst() ?: ClickResult.NodeDisappeared
        }

        override suspend fun gestureAtCenter(
            identity: PageIdentity,
            selector: NodeSelector,
        ): GestureResult {
            gestureCalls++
            return gestureResults.pollFirst() ?: GestureResult.Rejected
        }
    }

    private companion object {
        val IDENTITY = PageIdentity(
            packageName = "com.tencent.qqmusic",
            activityName = "com.example.TestSplashActivity",
            windowId = 7,
        )
    }
}
