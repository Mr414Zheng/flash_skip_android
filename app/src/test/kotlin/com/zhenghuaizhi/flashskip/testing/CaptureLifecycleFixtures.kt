package com.zhenghuaizhi.flashskip.testing

import com.zhenghuaizhi.flashskip.application.rulecapture.CaptureExpiryKey
import com.zhenghuaizhi.flashskip.application.rulecapture.CaptureOperationResult
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleCaptureCoordinator
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.ObservedDismissCandidate
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.SemanticDiffEvidence
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipPreferences
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.ports.WallClock
import kotlinx.coroutines.CoroutineScope

const val LIFECYCLE_EPOCH_MS = 1_000_000L

/** 两个时钟独立变化，避免同步推进掩盖恢复时的墙钟跳变问题。 */
class DualCaptureClock(
    var wallMs: Long = LIFECYCLE_EPOCH_MS,
    var elapsedMs: Long = 1_000L,
) : WallClock, MonotonicClock {
    override fun currentTimeMillis(): Long = wallMs
    override fun elapsedRealtimeMs(): Long = elapsedMs
}

fun capturePhaseBudget(phase: CapturePhase): Long = when (phase) {
    CapturePhase.CONFIGURING -> RuleCaptureCoordinator.CONFIGURATION_TIMEOUT_MS
    CapturePhase.WAITING_FOR_TARGET -> RuleCaptureCoordinator.TARGET_TIMEOUT_MS
    CapturePhase.OBSERVING_CLICK -> RuleCaptureCoordinator.CLICK_TIMEOUT_MS
    CapturePhase.AWAITING_CONFIRMATION, CapturePhase.CAPTURED -> RuleCaptureCoordinator.COMPLETION_TIMEOUT_MS
    CapturePhase.FAILED -> 0L
}

class CaptureLifecycleFixture(
    scope: CoroutineScope,
    phase: CapturePhase = CapturePhase.CONFIGURING,
    val clock: DualCaptureClock = DualCaptureClock(),
    sessionTransform: (CaptureSession) -> CaptureSession = { it },
) {
    val sample = captureSample()
    val candidate = ObservedDismissCandidate(
        id = sample.id,
        targetAppId = sample.targetAppId,
        targetAppName = sample.targetAppName,
        activityName = sample.activityName,
        observedAfterTargetMs = sample.clickedAfterTargetMs,
        observedAtEpochMs = sample.capturedAtEpochMs,
        nodes = sample.nodes,
        preClickUniqueSelectors = sample.preClickUniqueSelectors,
    )
    val identity = PageIdentity(TARGET.packageName, sample.activityName, 7)
    val initial = RuleCaptureState(
        samples = listOf(captureSample(10L), captureSample(11L, OTHER_TARGET)),
        activeSession = sessionTransform(CaptureSession(
            id = SESSION_ID,
            phase = phase,
            phaseStartedAtEpochMs = LIFECYCLE_EPOCH_MS,
            deadlineAtEpochMs = LIFECYCLE_EPOCH_MS + capturePhaseBudget(phase),
            targetAppId = TARGET.takeUnless { phase in setOf(CapturePhase.CONFIGURING, CapturePhase.WAITING_FOR_TARGET) },
            packageName = TARGET.packageName,
            activityName = sample.activityName,
            targetEnteredAtElapsedMs = 500L,
            pendingSample = sample.takeIf { phase == CapturePhase.CAPTURED },
            pendingObservedCandidate = candidate.takeIf { phase == CapturePhase.AWAITING_CONFIRMATION },
            candidateClickListeningEnabled = phase == CapturePhase.AWAITING_CONFIRMATION,
        )),
        semanticDiffEvidence = listOf(TARGET, OTHER_TARGET).map { target ->
            SemanticDiffEvidence(target, "${target.packageName}.LaunchActivity", captureSample(target = target).preClickUniqueSelectors, LIFECYCLE_EPOCH_MS - 1)
        },
    )
    val events = mutableListOf<String>()
    val store = TestRuleCaptureStore(initial, events) {}
    val initialPreferences = FlashSkipPreferences(enabledApps = mapOf(TARGET to true, OTHER_TARGET to true))
    val preferences = TestFlashSkipStore(initialPreferences, events) {}
    val catalog = MutableRuleCatalog().also { it.publish(initial.samples) }
    val coordinator = RuleCaptureCoordinator(store, preferences, clock, clock, scope, catalog)
    val session: CaptureSession get() = requireNotNull(store.state.value.activeSession)

    suspend fun deadline(): Long = requireNotNull(coordinator.expiryPlan(CaptureExpiryKey.from(session))).deadlineAtElapsedMs

    suspend fun observe(id: Long = SESSION_ID): CaptureOperationResult = coordinator.observeTarget(
        id, TARGET, "测试目标", TARGET.packageName, sample.activityName, 7, clock.elapsedMs,
    )

    suspend fun upgrade(id: Long = SESSION_ID): CaptureOperationResult = coordinator.upgradeObservedCandidateClick(
        id, candidate, identity, identity, sample,
    )
}

enum class LifecycleTransition(val source: CapturePhase, val destination: CapturePhase) {
    CONFIGURE(CapturePhase.CONFIGURING, CapturePhase.WAITING_FOR_TARGET),
    TARGET(CapturePhase.WAITING_FOR_TARGET, CapturePhase.OBSERVING_CLICK),
    TARGET_UPDATE(CapturePhase.OBSERVING_CLICK, CapturePhase.OBSERVING_CLICK),
    CLICK(CapturePhase.OBSERVING_CLICK, CapturePhase.CAPTURED),
    FAST_CLICK(CapturePhase.WAITING_FOR_TARGET, CapturePhase.CAPTURED),
    CANDIDATE(CapturePhase.OBSERVING_CLICK, CapturePhase.AWAITING_CONFIRMATION),
    FAST_CANDIDATE(CapturePhase.WAITING_FOR_TARGET, CapturePhase.AWAITING_CONFIRMATION),
    UPGRADE(CapturePhase.AWAITING_CONFIRMATION, CapturePhase.CAPTURED),
}

suspend fun CaptureLifecycleFixture.perform(transition: LifecycleTransition, id: Long = SESSION_ID): CaptureOperationResult =
    when (transition) {
        LifecycleTransition.CONFIGURE -> coordinator.markConfigured(id)
        LifecycleTransition.TARGET, LifecycleTransition.TARGET_UPDATE -> observe(id)
        LifecycleTransition.CLICK, LifecycleTransition.FAST_CLICK -> coordinator.recordCaptured(id, sample)
        LifecycleTransition.CANDIDATE, LifecycleTransition.FAST_CANDIDATE -> coordinator.recordObservedCandidateResult(id, candidate)
        LifecycleTransition.UPGRADE -> upgrade(id)
    }
