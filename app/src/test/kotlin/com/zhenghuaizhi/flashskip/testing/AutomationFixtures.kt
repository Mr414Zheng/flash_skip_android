package com.zhenghuaizhi.flashskip.testing

import com.zhenghuaizhi.flashskip.domain.automation.AutomationEngine
import com.zhenghuaizhi.flashskip.domain.automation.ForegroundSignal
import com.zhenghuaizhi.flashskip.domain.model.*
import com.zhenghuaizhi.flashskip.domain.ports.*
import com.zhenghuaizhi.flashskip.domain.rules.RuleCatalog
import com.zhenghuaizhi.flashskip.domain.rules.RuleCatalogSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

fun executableRule(id: String = "rule", target: TargetAppId = TARGET): AdRule = AdRule(
    id = id,
    targetAppId = target,
    packageName = target.packageName,
    allowedActivities = setOf("${target.packageName}.LaunchActivity"),
    selectors = listOf(NodeSelector(resourceId = "${target.packageName}:id/ad_skip")),
    startupDelayMs = 0,
    cooldownMs = 10_000,
)

class ExecutionCatalog(rules: List<AdRule>) : RuleCatalog {
    override val snapshot = MutableStateFlow(RuleCatalogSnapshot(rules))
}

class ExecutionStore(enabled: Boolean = true) : FlashSkipStore {
    override val preferences = MutableStateFlow(FlashSkipPreferences(enabledApps = mapOf(TARGET to enabled)))
    val attemptedRecords = mutableListOf<ActionRecord>()
    var beforeRecord: suspend (ActionRecord) -> Unit = {}

    override suspend fun isAppEnabled(targetAppId: TargetAppId): Boolean = preferences.value.enabledApps[targetAppId] == true

    override suspend fun setAppEnabled(targetAppId: TargetAppId, enabled: Boolean) {
        preferences.value = preferences.value.copy(enabledApps = preferences.value.enabledApps + (targetAppId to enabled))
    }

    override suspend fun appendRecord(record: ActionRecord) {
        attemptedRecords += record
        beforeRecord(record)
        preferences.value = preferences.value.copy(records = preferences.value.records + record)
    }
}

/** 替身只控制平台输入、挂起点和提交事实；准入、补点及确认由真实引擎判断。 */
class ExecutionGateway(private val now: () -> Long) : PageGateway {
    val queries = mutableListOf<Pair<PageIdentity, NodeSelector>>()
    val clicks = mutableListOf<Triple<PageIdentity, NodeSelector, ActionTargetEvidence?>>()
    val gestures = mutableListOf<Long>()
    val fallbacks = mutableListOf<Boolean>()
    var onQuery: suspend (PageIdentity, NodeSelector) -> QueryResult = { _, _ -> QueryResult.Unique }
    var onClick: suspend (PageIdentity, NodeSelector, ActionTargetEvidence?) -> ClickResult = { _, _, _ -> ClickResult.Clicked }
    var onGesture: suspend () -> GestureResult = { GestureResult.Accepted }
    var onObserve: suspend () -> TargetObservation = { TargetObservation.Insufficient(ActionReason.NODE_UNAVAILABLE) }
    var includeEvidence = true

    override suspend fun query(identity: PageIdentity, selector: NodeSelector): QueryResult {
        queries += identity to selector
        return onQuery(identity, selector)
    }

    override suspend fun click(identity: PageIdentity, selector: NodeSelector): ClickResult =
        error("引擎应使用带证据的 submitClick")

    override suspend fun submitClick(
        identity: PageIdentity,
        selector: NodeSelector,
        expectedTarget: ActionTargetEvidence?,
        deadlineElapsedMs: Long,
    ): NodeActionSubmission {
        clicks += Triple(identity, selector, expectedTarget)
        val result = onClick(identity, selector, expectedTarget)
        return NodeActionSubmission(result, evidence(identity, selector), now())
    }

    override suspend fun gestureAtCenter(identity: PageIdentity, selector: NodeSelector, deadlineElapsedMs: Long): GestureResult =
        error("引擎应使用带证据的 submitGesture")

    override suspend fun submitGesture(identity: PageIdentity, selector: NodeSelector, deadlineElapsedMs: Long): GestureActionSubmission {
        gestures += deadlineElapsedMs
        return GestureActionSubmission(onGesture(), evidence(identity, selector), now())
    }

    override suspend fun observeAction(identity: PageIdentity, evidence: ActionTargetEvidence, allowPageFallback: Boolean): TargetObservation {
        fallbacks += allowPageFallback
        return onObserve()
    }

    private fun evidence(identity: PageIdentity, selector: NodeSelector): ActionTargetEvidence? =
        if (includeEvidence) ActionTargetEvidence(identity, selector, 42) else null
}

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationFixture(
    private val test: TestScope,
    rules: List<AdRule> = listOf(executableRule()),
    enabled: Boolean = true,
    suppliedCatalog: RuleCatalog? = null,
) {
    val catalog = ExecutionCatalog(rules)
    val store = ExecutionStore(enabled)
    val gateway = ExecutionGateway { test.testScheduler.currentTime }
    var identity = PageIdentity(TARGET.packageName, "${TARGET.packageName}.LaunchActivity", 7)
    val engine = AutomationEngine(
        suppliedCatalog ?: catalog, store, gateway,
        object : MonotonicClock { override fun elapsedRealtimeMs() = test.testScheduler.currentTime },
        object : WallClock { override fun currentTimeMillis() = 1_000_000L + test.testScheduler.currentTime },
        test.backgroundScope,
    )
    val records get() = store.preferences.value.records

    fun enter(enteredAt: Long = test.testScheduler.currentTime) {
        engine.onForegroundSignal(ForegroundSignal.TargetActive(identity, enteredAt))
        test.runCurrent()
    }

    fun content(at: Long = test.testScheduler.currentTime) {
        engine.onContentChanged(identity, at)
        test.runCurrent()
    }

    fun outside() {
        engine.onForegroundSignal(ForegroundSignal.OutsideTargets(test.testScheduler.currentTime))
        test.runCurrent()
    }

    fun advance(ms: Long) {
        test.advanceTimeBy(ms)
        test.runCurrent()
    }
}
