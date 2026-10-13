package com.zhenghuaizhi.flashskip.testing

import com.zhenghuaizhi.flashskip.application.rulecapture.RuleCaptureCoordinator
import com.zhenghuaizhi.flashskip.domain.model.ActionMethod
import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.CaptureEvidence
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.ObservedDismissCandidate
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.SemanticDiffEvidence
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipPreferences
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipStore
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.RuleCaptureStore
import com.zhenghuaizhi.flashskip.domain.ports.WallClock
import com.zhenghuaizhi.flashskip.domain.rulecapture.RuleCandidateAnalyzer
import com.zhenghuaizhi.flashskip.domain.rules.CapturedTargetRules
import com.zhenghuaizhi.flashskip.domain.rules.RuleCatalog
import com.zhenghuaizhi.flashskip.domain.rules.RuleCatalogSnapshot
import com.zhenghuaizhi.flashskip.domain.rules.RuleValidator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestCoroutineScheduler

val TARGET = TargetAppId("com.example.target")
val OTHER_TARGET = TargetAppId("com.example.other")
const val SESSION_ID = 100L
private const val EPOCH_BASE_MS = 1_000_000L

fun captureSample(id: Long = SESSION_ID, target: TargetAppId = TARGET): CaptureSample = CaptureSample(
    id = id,
    targetAppId = target,
    activityName = "${target.packageName}.LaunchActivity",
    clickedAfterTargetMs = 500L,
    capturedAtEpochMs = EPOCH_BASE_MS + id,
    nodes = listOf(CapturedNode(
        resourceId = "${target.packageName}:id/ad_skip",
        text = "跳过",
        clickable = true,
        supportsActionClick = true,
        visibleToUser = true,
        hasUsableBounds = true,
    )),
    preClickUniqueSelectors = listOf(CapturedSelector(resourceId = "${target.packageName}:id/ad_skip")),
)

/** 屏障仅控制测试调度；取消等待不会释放其他调用，也不会伪造一次存储提交。 */
class CommitGate {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    suspend fun pause() {
        entered.complete(Unit)
        release.await()
    }
}

data class SaveSnapshot(
    val point: String,
    val capture: RuleCaptureState,
    val preferences: FlashSkipPreferences,
)

/** 只模拟单次原子写入；协调器的业务顺序、去重及会话校验均由真实实现负责。 */
class TestRuleCaptureStore(
    initial: RuleCaptureState,
    private val events: MutableList<String>,
    private val onCommit: () -> Unit,
) : RuleCaptureStore {
    override val state = MutableStateFlow(initial)
    var attempts = 0
        private set
    val writes = mutableListOf<RuleCaptureState>()
    var beforeCommit: suspend () -> Unit = {}
    var afterCommit: suspend () -> Unit = {}
    private val mutex = Mutex()

    override suspend fun update(transform: (RuleCaptureState) -> RuleCaptureState) = mutex.withLock {
        attempts++
        events += "capture:attempt"
        beforeCommit()
        currentCoroutineContext().ensureActive()
        val updated = transform(state.value)
        state.value = updated
        writes += updated
        events += "capture:commit"
        onCommit()
        // 提交后取消只影响调用方取得结果，不能回滚已经发布的状态。
        afterCommit()
    }
}

class TestFlashSkipStore(
    initial: FlashSkipPreferences,
    private val events: MutableList<String>,
    private val onCommit: () -> Unit,
) : FlashSkipStore {
    override val preferences = MutableStateFlow(initial)
    val attempts = mutableListOf<Pair<TargetAppId, Boolean>>()
    val writes = mutableListOf<Pair<TargetAppId, Boolean>>()
    var beforeCommit: suspend () -> Unit = {}
    var afterCommit: suspend () -> Unit = {}
    private val mutex = Mutex()

    override suspend fun setAppEnabled(targetAppId: TargetAppId, enabled: Boolean) = mutex.withLock {
        attempts += targetAppId to enabled
        events += "switch:attempt"
        beforeCommit()
        currentCoroutineContext().ensureActive()
        preferences.value = preferences.value.copy(
            enabledApps = preferences.value.enabledApps + (targetAppId to enabled),
        )
        writes += targetAppId to enabled
        events += "switch:commit"
        onCommit()
        afterCommit()
    }

    override suspend fun isAppEnabled(targetAppId: TargetAppId): Boolean =
        preferences.value.enabledApps[targetAppId] == true

    override suspend fun appendRecord(record: ActionRecord) = mutex.withLock {
        preferences.value = preferences.value.copy(records = listOf(record) + preferences.value.records)
    }
}

/** 手动发布用于控制目录滞后，READY 规则必须由生产分析器和校验器生成。 */
class MutableRuleCatalog : RuleCatalog {
    override val snapshot = MutableStateFlow(RuleCatalogSnapshot())

    fun publish(samples: List<CaptureSample>) {
        val targets = samples.groupBy(CaptureSample::targetAppId).map { (id, group) ->
            val latest = group.maxBy(CaptureSample::capturedAtEpochMs)
            CapturedTargetRules(
                targetAppId = id,
                displayName = latest.targetAppName,
                latestCapturedAtEpochMs = latest.capturedAtEpochMs,
                analysis = RuleCandidateAnalyzer.analyze(group, id),
            )
        }
        val rules = targets.flatMap { it.analysis.rules }
        check(RuleValidator.validate(rules).isEmpty())
        snapshot.value = RuleCatalogSnapshot(rules, targets, samples.toList())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureSaveFixture(
    scope: CoroutineScope,
    scheduler: TestCoroutineScheduler,
    phase: CapturePhase = CapturePhase.CAPTURED,
    targetEnabled: Boolean = true,
) {
    val expectedSample = captureSample().copy(
        evidence = if (phase == CapturePhase.AWAITING_CONFIRMATION) {
            CaptureEvidence.USER_CONFIRMED_UNREPORTED_CLICK
        } else CaptureEvidence.ACCESSIBILITY_CLICK,
    )
    private val candidate = ObservedDismissCandidate(
        id = expectedSample.id,
        targetAppId = TARGET,
        targetAppName = expectedSample.targetAppName,
        activityName = expectedSample.activityName,
        observedAfterTargetMs = expectedSample.clickedAfterTargetMs,
        observedAtEpochMs = expectedSample.capturedAtEpochMs,
        nodes = expectedSample.nodes,
        preClickUniqueSelectors = expectedSample.preClickUniqueSelectors,
    )
    val initialCapture = RuleCaptureState(
        samples = listOf(captureSample(10L), captureSample(11L, OTHER_TARGET)),
        activeSession = CaptureSession(
            id = SESSION_ID,
            targetAppId = TARGET,
            targetAppName = expectedSample.targetAppName,
            packageName = TARGET.packageName,
            phase = phase,
            phaseStartedAtEpochMs = EPOCH_BASE_MS,
            deadlineAtEpochMs = EPOCH_BASE_MS + RuleCaptureCoordinator.COMPLETION_TIMEOUT_MS,
            activityName = expectedSample.activityName,
            pendingSample = expectedSample.takeIf { phase == CapturePhase.CAPTURED },
            pendingObservedCandidate = candidate.takeIf { phase == CapturePhase.AWAITING_CONFIRMATION },
        ),
        semanticDiffEvidence = listOf(TARGET, OTHER_TARGET).map { target ->
            SemanticDiffEvidence(
                targetAppId = target,
                activityName = "${target.packageName}.LaunchActivity",
                selectors = captureSample(target = target).preClickUniqueSelectors,
                capturedAtEpochMs = EPOCH_BASE_MS - 1L,
            )
        },
    )
    val initialPreferences = FlashSkipPreferences(
        enabledApps = mapOf(TARGET to targetEnabled, OTHER_TARGET to true),
        records = listOf(ActionRecord(
            timestampEpochMs = EPOCH_BASE_MS - 1L,
            targetAppId = OTHER_TARGET,
            ruleId = "previous-rule",
            method = ActionMethod.NODE_CLICK,
            outcome = ActionOutcome.CLOSED,
        )),
    )
    val events = mutableListOf<String>()
    val commits = mutableListOf<SaveSnapshot>()
    val captureStore = TestRuleCaptureStore(initialCapture, events) { snapshot("capture:commit") }
    val flashStore = TestFlashSkipStore(initialPreferences, events) { snapshot("switch:commit") }
    val catalog = MutableRuleCatalog().also { it.publish(initialCapture.samples) }
    private val clock = object : MonotonicClock, WallClock {
        override fun elapsedRealtimeMs(): Long = scheduler.currentTime
        override fun currentTimeMillis(): Long = EPOCH_BASE_MS + scheduler.currentTime
    }
    val coordinator = RuleCaptureCoordinator(
        captureStore = captureStore,
        flashSkipStore = flashStore,
        monotonicClock = clock,
        wallClock = clock,
        applicationScope = scope,
        ruleCatalog = catalog,
    )

    private fun snapshot(point: String) {
        commits += SaveSnapshot(point, captureStore.state.value, flashStore.preferences.value)
    }
}
