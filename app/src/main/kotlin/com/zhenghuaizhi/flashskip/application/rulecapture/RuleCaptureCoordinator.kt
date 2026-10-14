package com.zhenghuaizhi.flashskip.application.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailureDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CaptureEvidence
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.ObservedDismissCandidate
import com.zhenghuaizhi.flashskip.domain.model.RuleCardStatus
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.SemanticDiffEvidence
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowDiagnostics
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipStore
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.ports.RuleCaptureStore
import com.zhenghuaizhi.flashskip.domain.ports.WallClock
import com.zhenghuaizhi.flashskip.domain.rulecapture.CaptureRuleSafetyPolicy
import com.zhenghuaizhi.flashskip.domain.rules.RuleCatalog
import com.zhenghuaizhi.flashskip.domain.rulecapture.ObservedCandidateClickMatcher
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 用户操作的结果；过期操作不修改状态，存储异常可重试，协程取消仍向调用者传播。 */
sealed interface CaptureOperationResult {
    data class Success(val sessionId: Long? = null) : CaptureOperationResult
    data object Stale : CaptureOperationResult
    data object Pending : CaptureOperationResult
    data class Failure(val reason: CaptureOperationFailure) : CaptureOperationResult
}

enum class CaptureOperationFailure {
    STORAGE,
    CAPTURE_IN_PROGRESS,
    RULE_NOT_READY,
    RULES_UPDATING,
}

/** 只供当前会话重试提示使用，不写入持久化状态。 */
data class CaptureServiceFailure(
    val sessionId: Long?,
    val operation: CaptureServiceOperation,
    val reason: CaptureOperationFailure,
)

enum class CaptureServiceOperation {
    CONFIGURE, OBSERVE_TARGET, RECORD_CAPTURED, RECORD_CANDIDATE, UPGRADE_CANDIDATE,
    RECORD_SEMANTIC_DIFF, FAIL, EXPIRE, SERVICE_DISCONNECTED,
}

/** 完整阶段键使迟到的定时器无法终止新阶段或替换会话。 */
data class CaptureExpiryKey(
    val sessionId: Long,
    val phase: CapturePhase,
    val phaseStartedAtEpochMs: Long,
    val deadlineAtEpochMs: Long,
) {
    companion object {
        fun from(session: CaptureSession) = CaptureExpiryKey(
            session.id, session.phase, session.phaseStartedAtEpochMs, session.deadlineAtEpochMs,
        )
    }
}

data class CaptureExpiryPlan(val key: CaptureExpiryKey, val deadlineAtElapsedMs: Long)

/** 服务在终止采集监听前提供的有限计数与耗时诊断。 */
data class CaptureDiagnosticSnapshot(
    val failure: CaptureFailureDiagnostics?,
    val targetWindow: TargetWindowDiagnostics?,
)

class RuleCaptureCoordinator(
    private val captureStore: RuleCaptureStore,
    private val flashSkipStore: FlashSkipStore,
    private val monotonicClock: MonotonicClock,
    private val wallClock: WallClock,
    private val applicationScope: CoroutineScope,
    private val ruleCatalog: RuleCatalog,
    private val traceSink: RuleManagementTraceSink = RuleManagementTraceSink.None,
) {
    val state: Flow<RuleCaptureState> = captureStore.state
    private val mutableServiceFailure = MutableStateFlow<CaptureServiceFailure?>(null)
    val serviceFailure: StateFlow<CaptureServiceFailure?> = mutableServiceFailure.asStateFlow()
    private var phaseExpiry: CaptureExpiryPlan? = null
    private val sessionSequence = AtomicLong(0)
    // 两份存储没有跨库事务；所有入口共享锁，保证先关闭自动执行，再提交规则。
    private val operationMutex = Mutex()
    private val diagnosticsProviderSequence = AtomicLong(0)

    @Volatile
    private var returnDiagnosticsProvider: RegisteredDiagnosticsProvider? = null

    /** 有未结束的采集时拒绝重复开始，避免迟到的界面操作替换当前会话。 */
    suspend fun requestStart(): CaptureOperationResult = operation(RuleManagementOperation.REQUEST_CAPTURE) {
        val previous = captureStore.state.first().activeSession
        if (previous?.blocksAutomation == true) return@operation CaptureOperationResult.Stale
        val now = wallClock.currentTimeMillis()
        val sessionId = sessionSequence.updateAndGet { sequence ->
            maxOf(now, sequence + 1, (previous?.id ?: 0L) + 1)
        }
        persistState { state ->
            state.copy(
                activeSession = CaptureSession(
                    id = sessionId,
                    phase = CapturePhase.CONFIGURING,
                    phaseStartedAtEpochMs = now,
                    deadlineAtEpochMs = now + CONFIGURATION_TIMEOUT_MS,
                ),
            )
        }
        CaptureOperationResult.Success(sessionId)
    }

    suspend fun awaitConfigured(sessionId: Long): Boolean {
        val state = withTimeoutOrNull(CONFIGURATION_WAIT_MS) {
            captureStore.state.first { current ->
                current.activeSession?.let { session ->
                    session.id != sessionId || session.phase != CapturePhase.CONFIGURING
                } ?: true
            }
        }
        val configured = state?.activeSession?.let {
            it.id == sessionId && it.phase in setOf(
                CapturePhase.WAITING_FOR_TARGET,
                CapturePhase.OBSERVING_CLICK,
                CapturePhase.CAPTURED,
                CapturePhase.AWAITING_CONFIRMATION,
            )
        } == true
        if (!configured) {
            transition(sessionId, CapturePhase.CONFIGURING) { session, _ ->
                failed(session, CaptureFailure.CONFIGURATION_TIMEOUT)
            }
        }
        trace(RuleManagementOperation.AWAIT_CONFIGURATION,
            if (configured) RuleManagementOutcome.SUCCESS else RuleManagementOutcome.REJECTED, sessionId)
        return configured
    }

    suspend fun markConfigured(sessionId: Long): CaptureOperationResult =
        runServiceUpdate(CaptureServiceOperation.CONFIGURE, sessionId) {
            if (!hasRemainingStageTime(sessionId)) return@runServiceUpdate CaptureOperationResult.Stale
            transitionUnlocked(sessionId, CapturePhase.CONFIGURING) { session, now ->
                session.copy(
                    phase = CapturePhase.WAITING_FOR_TARGET,
                    phaseStartedAtEpochMs = now,
                    deadlineAtEpochMs = now + TARGET_TIMEOUT_MS,
                )
            }
        }

    /** 服务仅在采集期间提供瞬时诊断；注册令牌确保旧服务不能注销新服务的提供者。 */
    @Synchronized
    fun registerReturnDiagnosticsProvider(
        provider: (Long) -> CaptureDiagnosticSnapshot?,
    ): Long {
        val registrationId = diagnosticsProviderSequence.incrementAndGet()
        returnDiagnosticsProvider = RegisteredDiagnosticsProvider(registrationId, provider)
        return registrationId
    }

    /** 仅允许注册者注销自己的诊断提供者，避免旧服务销毁时清除新服务状态。 */
    @Synchronized
    fun unregisterReturnDiagnosticsProvider(registrationId: Long) {
        if (returnDiagnosticsProvider?.registrationId == registrationId) {
            returnDiagnosticsProvider = null
        }
    }

    /** 标记真实离开；调用者必须传入发起本次外跳的 session ID。 */
    suspend fun markLeftFlashSkip(sessionId: Long): CaptureOperationResult = operation(RuleManagementOperation.LEAVE, sessionId) {
        val active = captureStore.state.first().activeSession?.takeIf {
            it.id == sessionId && it.phase != CapturePhase.FAILED
        } ?: return@operation CaptureOperationResult.Stale
        if (active.leftFlashSkipAtEpochMs != null) {
            return@operation CaptureOperationResult.Success(sessionId)
        }
        var marked = false
        persistState { state ->
            val session = state.activeSession?.takeIf {
                it.id == sessionId && it.phase != CapturePhase.FAILED &&
                    it.leftFlashSkipAtEpochMs == null
            } ?: return@persistState state
            marked = true
            state.copy(
                activeSession = session.copy(
                    leftFlashSkipAtEpochMs = wallClock.currentTimeMillis(),
                ),
            )
        }
        if (marked) CaptureOperationResult.Success(sessionId) else CaptureOperationResult.Stale
    }

    /**
     * 返回时只结算明确离开过的同一会话。持久化快照是状态依据，observedDeparture 用于本进程中
     * 离开标记写入失败后仍可在真实返回时完成结算。
     */
    suspend fun reconcileReturn(
        expectedSessionId: Long? = null,
        observedDeparture: Boolean = false,
    ): CaptureOperationResult = operation(RuleManagementOperation.RETURN, expectedSessionId) {
        val active = captureStore.state.first().activeSession ?: return@operation CaptureOperationResult.Stale
        if (expectedSessionId != null && active.id != expectedSessionId) {
            return@operation CaptureOperationResult.Stale
        }
        if (active.leftFlashSkipAtEpochMs == null && !observedDeparture) {
            return@operation CaptureOperationResult.Stale
        }

        val shouldFailWithoutEvidence = active.phase in setOf(
            CapturePhase.CONFIGURING,
            CapturePhase.WAITING_FOR_TARGET,
            CapturePhase.OBSERVING_CLICK,
        )
        val diagnostics = if (shouldFailWithoutEvidence) {
            synchronized(this) { returnDiagnosticsProvider?.provider }
                ?.let { provider ->
                    try {
                        provider(active.id)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
        } else {
            null
        }
        val returnFailureDiagnostics = diagnostics?.failure?.let { returned ->
            returned.copy(
                sessionTrace = returned.sessionTrace ?: active.failureDiagnostics?.sessionTrace,
            )
        } ?: active.failureDiagnostics
        var reconciled = false
        persistState { state ->
            val session = state.activeSession?.takeIf {
                it.id == active.id &&
                    (it.leftFlashSkipAtEpochMs != null || observedDeparture)
            } ?: return@persistState state
            val nextSession = when (session.phase) {
                CapturePhase.CONFIGURING,
                CapturePhase.WAITING_FOR_TARGET,
                -> failed(
                    session = session,
                    failure = CaptureFailure.RETURNED_BEFORE_TARGET,
                    diagnostics = returnFailureDiagnostics,
                    targetWindowDiagnostics = diagnostics?.targetWindow ?: session.targetWindowDiagnostics,
                )

                CapturePhase.OBSERVING_CLICK -> failed(
                    session = session,
                    failure = CaptureFailure.DISMISS_NOT_CAPTURED,
                    diagnostics = returnFailureDiagnostics,
                    targetWindowDiagnostics = diagnostics?.targetWindow ?: session.targetWindowDiagnostics,
                )

                CapturePhase.CAPTURED,
                -> session.copy(leftFlashSkipAtEpochMs = null)

                CapturePhase.AWAITING_CONFIRMATION -> session.copy(
                    leftFlashSkipAtEpochMs = null,
                    candidateClickListeningEnabled = false,
                )

                CapturePhase.FAILED -> session.copy(leftFlashSkipAtEpochMs = null)
            }
            reconciled = true
            state.copy(activeSession = nextSession)
        }
        if (reconciled) CaptureOperationResult.Success(active.id) else CaptureOperationResult.Stale
    }

    suspend fun observeTarget(
        sessionId: Long,
        targetAppId: TargetAppId,
        targetAppName: String,
        packageName: String,
        activityName: String,
        windowId: Int,
        enteredAtElapsedMs: Long,
    ): CaptureOperationResult = updateState(CaptureServiceOperation.OBSERVE_TARGET, sessionId, checkDeadline = true) { state ->
        val session = state.activeSession?.takeIf { it.id == sessionId } ?: return@updateState state
        when (session.phase) {
            CapturePhase.WAITING_FOR_TARGET -> {
                val now = wallClock.currentTimeMillis()
                state.copy(
                    activeSession = session.copy(
                        targetAppId = targetAppId,
                        targetAppName = targetAppName,
                        packageName = packageName,
                        phase = CapturePhase.OBSERVING_CLICK,
                        phaseStartedAtEpochMs = now,
                        deadlineAtEpochMs = now + CLICK_TIMEOUT_MS,
                        activityName = activityName,
                        windowId = windowId,
                        targetEnteredAtElapsedMs = enteredAtElapsedMs,
                    ),
                )
            }

            CapturePhase.OBSERVING_CLICK -> if (session.targetAppId == targetAppId) {
                state.copy(activeSession = session.copy(
                    activityName = activityName,
                    windowId = windowId,
                ))
            } else state

            else -> state
        }
    }

    suspend fun recordCaptured(sessionId: Long, sample: CaptureSample): CaptureOperationResult {
        if (!CaptureRuleSafetyPolicy.accepts(sample.nodes)) return CaptureOperationResult.Stale
        return updateState(CaptureServiceOperation.RECORD_CAPTURED, sessionId, checkDeadline = true) { state ->
            val session = state.activeSession?.takeIf {
                it.id == sessionId && it.phase in setOf(
                    CapturePhase.WAITING_FOR_TARGET,
                    CapturePhase.OBSERVING_CLICK,
                )
            } ?: return@updateState state
            if (session.targetAppId != null && sample.targetAppId != session.targetAppId) {
                return@updateState state
            }
            val now = wallClock.currentTimeMillis()
            state.copy(
                activeSession = session.copy(
                    targetAppId = sample.targetAppId,
                    targetAppName = sample.targetAppName,
                    packageName = sample.targetAppId.packageName,
                    phase = CapturePhase.CAPTURED,
                    phaseStartedAtEpochMs = now,
                    deadlineAtEpochMs = now + COMPLETION_TIMEOUT_MS,
                    activityName = sample.activityName,
                    targetEnteredAtElapsedMs = session.targetEnteredAtElapsedMs
                        ?: (monotonicClock.elapsedRealtimeMs() - sample.clickedAfterTargetMs)
                            .coerceAtLeast(0),
                    pendingSample = sample,
                    pendingObservedCandidate = null,
                    candidateClickListeningEnabled = false,
                ),
            )
        }
    }

    suspend fun upgradeObservedCandidateClick(
        sessionId: Long,
        candidate: ObservedDismissCandidate,
        candidateIdentity: PageIdentity,
        clickedIdentity: PageIdentity,
        sample: CaptureSample,
    ): CaptureOperationResult = runServiceUpdate(CaptureServiceOperation.UPGRADE_CANDIDATE, sessionId) {
        if (!CaptureRuleSafetyPolicy.accepts(sample.nodes)) return@runServiceUpdate CaptureOperationResult.Stale
        if (!hasRemainingStageTime(sessionId)) return@runServiceUpdate CaptureOperationResult.Stale
        val now = wallClock.currentTimeMillis()
        var upgraded = false
        persistState { state ->
            val session = state.activeSession?.takeIf {
                it.id == sessionId &&
                    it.phase == CapturePhase.AWAITING_CONFIRMATION &&
                    it.listensForCandidateClick &&
                    it.pendingObservedCandidate == candidate
            } ?: return@persistState state
            if (candidate.id != sessionId ||
                sample.id != sessionId ||
                sample.evidence != CaptureEvidence.ACCESSIBILITY_CLICK ||
                sample.targetAppId != candidate.targetAppId ||
                session.targetAppId != candidate.targetAppId ||
                sample.activityName != candidate.activityName ||
                session.activityName != candidate.activityName ||
                candidateIdentity.packageName != candidate.targetAppId.packageName ||
                candidateIdentity.activityName != candidate.activityName ||
                clickedIdentity != candidateIdentity ||
                !ObservedCandidateClickMatcher.matches(
                    candidate = candidate,
                    candidateIdentity = candidateIdentity,
                    clickedIdentity = clickedIdentity,
                    clickedNodes = sample.nodes,
                )
            ) {
                return@persistState state
            }
            upgraded = true
            state.copy(
                activeSession = session.copy(
                    targetAppName = sample.targetAppName,
                    phase = CapturePhase.CAPTURED,
                    phaseStartedAtEpochMs = now,
                    deadlineAtEpochMs = now + COMPLETION_TIMEOUT_MS,
                    activityName = sample.activityName,
                    windowId = candidateIdentity.windowId,
                    pendingSample = sample,
                    pendingObservedCandidate = null,
                    candidateClickListeningEnabled = false,
                ),
            )
        }
        if (upgraded) CaptureOperationResult.Success(sessionId) else CaptureOperationResult.Stale
    }

    suspend fun recordObservedCandidate(
        sessionId: Long,
        candidate: ObservedDismissCandidate,
    ): Boolean = recordObservedCandidateResult(sessionId, candidate) is CaptureOperationResult.Success

    suspend fun recordObservedCandidateResult(
        sessionId: Long,
        candidate: ObservedDismissCandidate,
    ): CaptureOperationResult {
        if (!CaptureRuleSafetyPolicy.accepts(candidate.nodes)) return CaptureOperationResult.Stale
        return updateState(CaptureServiceOperation.RECORD_CANDIDATE, sessionId, checkDeadline = true) { state ->
            val session = state.activeSession?.takeIf {
                it.id == sessionId && it.phase in setOf(
                    CapturePhase.WAITING_FOR_TARGET,
                    CapturePhase.OBSERVING_CLICK,
                )
            } ?: return@updateState state
            if (session.targetAppId != null && candidate.targetAppId != session.targetAppId) {
                return@updateState state
            }
            val now = wallClock.currentTimeMillis()
            state.copy(
                activeSession = session.copy(
                    targetAppId = candidate.targetAppId,
                    targetAppName = candidate.targetAppName,
                    packageName = candidate.targetAppId.packageName,
                    phase = CapturePhase.AWAITING_CONFIRMATION,
                    phaseStartedAtEpochMs = now,
                    deadlineAtEpochMs = now + COMPLETION_TIMEOUT_MS,
                    activityName = candidate.activityName,
                    targetEnteredAtElapsedMs = session.targetEnteredAtElapsedMs
                        ?: (monotonicClock.elapsedRealtimeMs() - candidate.observedAfterTargetMs)
                            .coerceAtLeast(0),
                    pendingSample = null,
                    pendingObservedCandidate = candidate,
                    candidateClickListeningEnabled = true,
                ),
            )
        }
    }

    suspend fun recordSemanticDiffEvidence(
        sessionId: Long,
        evidence: SemanticDiffEvidence,
    ): CaptureOperationResult = updateState(CaptureServiceOperation.RECORD_SEMANTIC_DIFF, sessionId) { state ->
        val session = state.activeSession?.takeIf {
            it.id == sessionId && it.phase in setOf(
                CapturePhase.WAITING_FOR_TARGET,
                CapturePhase.OBSERVING_CLICK,
            )
        } ?: return@updateState state
        if (session.targetAppId != null && session.targetAppId != evidence.targetAppId) {
            return@updateState state
        }
        val sanitized = evidence.copy(
            activityName = evidence.activityName.take(MAX_ACTIVITY_LENGTH),
            selectors = evidence.selectors.distinct().take(MAX_DIFF_EVIDENCE_SELECTORS),
        )
        if (sanitized.activityName.isBlank() || sanitized.selectors.isEmpty()) {
            return@updateState state
        }
        val retained = state.semanticDiffEvidence.filterNot {
            it.targetAppId == sanitized.targetAppId &&
                it.activityName == sanitized.activityName
        }
        state.copy(
            semanticDiffEvidence = (retained + sanitized).takeLast(MAX_DIFF_EVIDENCE_TARGETS),
        )
    }


    /** 完成前必须先持久化关闭目标开关；任一存储失败都保留采集状态以阻止自动执行。 */
    suspend fun finish(sessionId: Long): CaptureOperationResult = operation(RuleManagementOperation.FINISH, sessionId) {
        val active = captureStore.state.first().activeSession?.takeIf { it.id == sessionId }
            ?: return@operation CaptureOperationResult.Stale
        val sample = when (active.phase) {
            CapturePhase.CAPTURED -> active.pendingSample
            CapturePhase.AWAITING_CONFIRMATION -> active.pendingObservedCandidate?.toSample()
            else -> null
        } ?: return@operation CaptureOperationResult.Stale
        flashSkipStore.setAppEnabled(sample.targetAppId, false)
        persistState { state ->
            state.copy(
                samples = state.samples + sample,
                activeSession = null,
                semanticDiffEvidence = state.semanticDiffEvidence.filterNot {
                    it.targetAppId == sample.targetAppId
                },
            )
        }
        CaptureOperationResult.Success()
    }

    /** 取消只作用于调用者看到的会话，避免旧页面关闭后续新会话。 */
    suspend fun cancel(sessionId: Long): CaptureOperationResult = operation(RuleManagementOperation.CANCEL, sessionId) {
        if (captureStore.state.first().activeSession?.id != sessionId) {
            return@operation CaptureOperationResult.Stale
        }
        persistState { state -> state.copy(activeSession = null) }
        CaptureOperationResult.Success()
    }

    suspend fun fail(
        sessionId: Long,
        failure: CaptureFailure,
        diagnostics: CaptureFailureDiagnostics? = null,
        targetWindowDiagnostics: TargetWindowDiagnostics? = null,
        operation: CaptureServiceOperation = CaptureServiceOperation.FAIL,
    ): CaptureOperationResult = updateState(operation, sessionId) { state ->
        val session = state.activeSession?.takeIf {
            it.id == sessionId && it.blocksAutomation
        } ?: return@updateState state
        state.copy(activeSession = failed(session, failure, diagnostics, targetWindowDiagnostics))
    }

    suspend fun expireActive(
        targetWindowDiagnostics: TargetWindowDiagnostics? = null,
        failureDiagnostics: CaptureFailureDiagnostics? = null,
    ): CaptureOperationResult = runServiceUpdate(CaptureServiceOperation.EXPIRE, null) {
        val session = captureStore.state.first().activeSession
            ?: return@runServiceUpdate CaptureOperationResult.Stale
        expireUnlocked(CaptureExpiryKey.from(session), targetWindowDiagnostics, failureDiagnostics)
    }

    /** 首次恢复只读取一次墙钟，之后同一阶段只根据单调时钟结算。 */
    suspend fun expiryPlan(expectedKey: CaptureExpiryKey): CaptureExpiryPlan? = operationMutex.withLock {
        val session = captureStore.state.first().activeSession ?: return@withLock null
        if (CaptureExpiryKey.from(session) != expectedKey || session.phase == CapturePhase.FAILED) {
            return@withLock null
        }
        ensureExpiry(session)
    }

    suspend fun expireExpected(
        key: CaptureExpiryKey,
        targetWindowDiagnostics: TargetWindowDiagnostics? = null,
        failureDiagnostics: CaptureFailureDiagnostics? = null,
    ): CaptureOperationResult = runServiceUpdate(CaptureServiceOperation.EXPIRE, key.sessionId) {
        expireUnlocked(key, targetWindowDiagnostics, failureDiagnostics)
    }

    private suspend fun expireUnlocked(
        key: CaptureExpiryKey,
        targetWindowDiagnostics: TargetWindowDiagnostics?,
        failureDiagnostics: CaptureFailureDiagnostics?,
    ): CaptureOperationResult {
        val session = captureStore.state.first().activeSession
            ?: return CaptureOperationResult.Stale
        if (CaptureExpiryKey.from(session) != key || session.phase == CapturePhase.FAILED) {
            return CaptureOperationResult.Stale
        }
        val deadlineAtElapsedMs = ensureExpiry(session).deadlineAtElapsedMs
        if (monotonicClock.elapsedRealtimeMs() < deadlineAtElapsedMs) {
            return CaptureOperationResult.Pending
        }
        val failure = when (session.phase) {
            CapturePhase.CONFIGURING -> CaptureFailure.CONFIGURATION_TIMEOUT
            CapturePhase.WAITING_FOR_TARGET -> CaptureFailure.TARGET_TIMEOUT
            CapturePhase.OBSERVING_CLICK -> CaptureFailure.CLICK_TIMEOUT
            CapturePhase.AWAITING_CONFIRMATION, CapturePhase.CAPTURED -> CaptureFailure.COMPLETION_TIMEOUT
            CapturePhase.FAILED -> return CaptureOperationResult.Stale
        }
        persistState { state ->
            state.copy(activeSession = failed(
                session, failure,
                failureDiagnostics?.copy(clickDiagnosticApplicable = session.phase == CapturePhase.OBSERVING_CLICK),
                targetWindowDiagnostics,
            ))
        }
        return CaptureOperationResult.Success(session.id)
    }

    /** 清除前先停用目标；失败时保留规则，允许用户安全重试。 */
    suspend fun clearTarget(targetAppId: TargetAppId): CaptureOperationResult = operation(RuleManagementOperation.DELETE_TARGET) {
        flashSkipStore.setAppEnabled(targetAppId, false)
        persistState { state ->
            state.copy(
                samples = state.samples.filterNot { it.targetAppId == targetAppId },
                activeSession = state.activeSession?.takeUnless { it.targetAppId == targetAppId },
                semanticDiffEvidence = state.semanticDiffEvidence.filterNot {
                    it.targetAppId == targetAppId
                },
            )
        }
        CaptureOperationResult.Success()
    }

    /** 清除全部状态前先关闭所有保存过的开关，包括没有对应样本的残留开关。 */
    suspend fun clearAll(): CaptureOperationResult = operation(RuleManagementOperation.DELETE_ALL) {
        val state = captureStore.state.first()
        val targets = state.samples.map(CaptureSample::targetAppId) +
            flashSkipStore.preferences.first().enabledApps.keys +
            listOfNotNull(state.activeSession?.targetAppId)
        targets.distinct().forEach { flashSkipStore.setAppEnabled(it, false) }
        persistState { RuleCaptureState() }
        CaptureOperationResult.Success()
    }

    /** 开启时在同一把锁内校验当前规则，避免清除或采集与旧界面开关事件竞争。 */
    suspend fun setAppEnabled(targetAppId: TargetAppId, enabled: Boolean): CaptureOperationResult =
        operation(if (enabled) RuleManagementOperation.ENABLE else RuleManagementOperation.DISABLE) {
            if (enabled) {
                val state = captureStore.state.first()
                if (state.activeSession?.blocksAutomation == true) {
                    return@operation CaptureOperationResult.Failure(
                        CaptureOperationFailure.CAPTURE_IN_PROGRESS,
                    )
                }
                val snapshot = ruleCatalog.snapshot.value
                if (snapshot.sourceSamples != state.samples) {
                    return@operation CaptureOperationResult.Failure(
                        CaptureOperationFailure.RULES_UPDATING,
                    )
                }
                // 只使用目录最终发布的整组校验结果，避免 ID 冲突触发回退后仍开启无效规则。
                val analysis = snapshot.capturedTargets.firstOrNull {
                    it.targetAppId == targetAppId
                }?.analysis
                if (analysis?.status != RuleCardStatus.READY ||
                    snapshot.rulesForTarget(targetAppId).isEmpty()
                ) {
                    return@operation CaptureOperationResult.Failure(
                        CaptureOperationFailure.RULE_NOT_READY,
                    )
                }
            }
            flashSkipStore.setAppEnabled(targetAppId, enabled)
            CaptureOperationResult.Success()
        }

    /** 断连只结算该服务看到的会话；有效待保存载荷继续等待用户明确保存。 */
    fun serviceDisconnected(
        expectedSessionId: Long?,
        diagnostics: CaptureDiagnosticSnapshot? = null,
    ) {
        if (expectedSessionId == null) return
        applicationScope.launch {
            reconcileServiceSession(expectedSessionId, restoring = false, diagnostics = diagnostics)
        }
    }

    /**
     * 服务首次状态发射复用断连分类；配置阶段保留原剩余时间供服务重新配置。
     * 无状态变化时仍可能返回成功，调用方需继续处理当前状态，不能等待下一次发射。
     */
    internal suspend fun restoreServiceSession(
        expectedSessionId: Long,
        diagnostics: CaptureDiagnosticSnapshot? = null,
    ): CaptureOperationResult = reconcileServiceSession(expectedSessionId, restoring = true, diagnostics = diagnostics)

    private suspend fun reconcileServiceSession(
        expectedSessionId: Long,
        restoring: Boolean,
        diagnostics: CaptureDiagnosticSnapshot?,
    ): CaptureOperationResult = runServiceUpdate(CaptureServiceOperation.SERVICE_DISCONNECTED, expectedSessionId) {
        val session = captureStore.state.first().activeSession ?: return@runServiceUpdate CaptureOperationResult.Stale
        if (session.phase == CapturePhase.FAILED) return@runServiceUpdate CaptureOperationResult.Stale
        val pendingPhase = session.phase in setOf(CapturePhase.CAPTURED, CapturePhase.AWAITING_CONFIRMATION)
        if (pendingPhase || restoring && session.phase == CapturePhase.CONFIGURING) {
            if (!hasRemainingStageTime(expectedSessionId)) {
                return@runServiceUpdate expireUnlocked(
                    CaptureExpiryKey.from(session), diagnostics?.targetWindow, diagnostics?.failure,
                )
            }
            if (restoring && session.phase == CapturePhase.CONFIGURING) {
                return@runServiceUpdate CaptureOperationResult.Success(expectedSessionId)
            }
            val validPayload = when (session.phase) {
                CapturePhase.CAPTURED -> session.pendingSample?.nodes
                CapturePhase.AWAITING_CONFIRMATION -> session.pendingObservedCandidate?.nodes
                else -> null
            }?.let(CaptureRuleSafetyPolicy::accepts) == true
            if (validPayload) {
                if (session.candidateClickListeningEnabled) {
                    persistState { state -> state.copy(activeSession = session.copy(candidateClickListeningEnabled = false)) }
                }
                return@runServiceUpdate CaptureOperationResult.Success(expectedSessionId)
            }
        }
        persistState { state ->
            state.copy(activeSession = failed(
                session, CaptureFailure.SERVICE_UNAVAILABLE, diagnostics?.failure, diagnostics?.targetWindow,
            ))
        }
        CaptureOperationResult.Success(expectedSessionId)
    }

    fun elapsedRealtimeMs(): Long = monotonicClock.elapsedRealtimeMs()

    fun currentTimeMillis(): Long = wallClock.currentTimeMillis()

    private suspend fun transition(
        sessionId: Long,
        requiredPhase: CapturePhase,
        transform: (CaptureSession, Long) -> CaptureSession,
    ): CaptureOperationResult = runServiceUpdate(CaptureServiceOperation.CONFIGURE, sessionId) {
        transitionUnlocked(sessionId, requiredPhase, transform)
    }

    private suspend fun transitionUnlocked(
        sessionId: Long,
        requiredPhase: CapturePhase,
        transform: (CaptureSession, Long) -> CaptureSession,
    ): CaptureOperationResult {
        val session = captureStore.state.first().activeSession?.takeIf {
            it.id == sessionId && it.phase == requiredPhase
        } ?: return CaptureOperationResult.Stale
        persistState { state -> state.copy(activeSession = transform(session, wallClock.currentTimeMillis())) }
        return CaptureOperationResult.Success(sessionId)
    }

    private suspend fun updateState(
        operation: CaptureServiceOperation,
        sessionId: Long,
        checkDeadline: Boolean = false,
        transform: (RuleCaptureState) -> RuleCaptureState,
    ): CaptureOperationResult = runServiceUpdate(operation, sessionId) {
        if (checkDeadline && !hasRemainingStageTime(sessionId)) return@runServiceUpdate CaptureOperationResult.Stale
        var changed = false
        persistState { state -> transform(state).also { changed = it != state } }
        if (changed) CaptureOperationResult.Success(sessionId) else CaptureOperationResult.Stale
    }

    // 只在操作锁内的证据/阶段入口检查；取得锁后的存储等待继续消耗预算，保存不套用此截止。
    private suspend fun hasRemainingStageTime(sessionId: Long): Boolean {
        val session = captureStore.state.first().activeSession?.takeIf {
            it.id == sessionId && it.phase != CapturePhase.FAILED
        } ?: return false
        val deadlineAtElapsedMs = ensureExpiry(session).deadlineAtElapsedMs
        return monotonicClock.elapsedRealtimeMs() < deadlineAtElapsedMs
    }

    /**
     * 服务更新只吞掉预期的磁盘异常；保留原状态供重试，不递归写失败状态。
     * block 在操作锁内执行，不得再次调用协调器的写入口。
     */
    suspend fun runServiceUpdate(
        operation: CaptureServiceOperation,
        expectedSessionId: Long?,
        block: suspend () -> CaptureOperationResult,
    ): CaptureOperationResult = operationMutex.withLock {
        var active: CaptureSession? = null
        try {
            active = captureStore.state.first().activeSession
            if (expectedSessionId != null && active?.id != expectedSessionId) {
                trace(RuleManagementOperation.SERVICE_UPDATE, RuleManagementOutcome.REJECTED,
                    expectedSessionId, serviceOperation = operation)
                return@withLock CaptureOperationResult.Stale
            }
            block().also { result ->
                trace(RuleManagementOperation.SERVICE_UPDATE,
                    if (result is CaptureOperationResult.Success) RuleManagementOutcome.SUCCESS else RuleManagementOutcome.REJECTED,
                    active?.id ?: expectedSessionId, serviceOperation = operation)
                val failure = mutableServiceFailure.value
                if (result is CaptureOperationResult.Success && failure?.sessionId == active?.id &&
                    (operation == failure?.operation ||
                        operation in CAPTURE_EVIDENCE_OPERATIONS && failure?.operation in OBSERVATION_OPERATIONS)
                ) {
                    mutableServiceFailure.value = null
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            val sessionId = active?.id ?: expectedSessionId
            // 保留本会话首个未恢复错误，普通窗口更新不能掩盖尚未保存的采集证据。
            if (mutableServiceFailure.value?.sessionId != sessionId || mutableServiceFailure.value == null) {
                mutableServiceFailure.value = CaptureServiceFailure(
                    sessionId, operation, CaptureOperationFailure.STORAGE,
                )
            }
            trace(RuleManagementOperation.SERVICE_UPDATE, RuleManagementOutcome.STORAGE_FAILURE,
                active?.id ?: expectedSessionId, serviceOperation = operation)
            CaptureOperationResult.Failure(CaptureOperationFailure.STORAGE)
        }
    }

    private suspend fun operation(
        traceOperation: RuleManagementOperation? = null,
        traceSessionId: Long? = null,
        action: suspend () -> CaptureOperationResult,
    ): CaptureOperationResult = operationMutex.withLock {
        try {
            action().also { result ->
                traceOperation?.let { trace(it,
                    if (result is CaptureOperationResult.Success) RuleManagementOutcome.SUCCESS else RuleManagementOutcome.REJECTED,
                    (result as? CaptureOperationResult.Success)?.sessionId ?: traceSessionId) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            traceOperation?.let { trace(it, RuleManagementOutcome.STORAGE_FAILURE, traceSessionId) }
            CaptureOperationResult.Failure(CaptureOperationFailure.STORAGE)
        }
    }

    // 转换前保存单调时间；只有写成功才发布新期限，磁盘延迟也消耗阶段预算。
    private suspend fun persistState(transform: (RuleCaptureState) -> RuleCaptureState) {
        val before = captureStore.state.first().activeSession
        before?.takeUnless { it.phase == CapturePhase.FAILED }?.let(::ensureExpiry)
        val startedAtElapsedMs = monotonicClock.elapsedRealtimeMs()
        var committed: CaptureSession? = before
        captureStore.update { state -> transform(state).also { committed = it.activeSession } }
        val after = committed
        // 与服务订阅独立，在提交成功后留证；会话清除也关联原 ID。
        trace(RuleManagementOperation.COMMIT_CAPTURE, RuleManagementOutcome.SUCCESS,
            after?.id ?: before?.id, phase = after?.phase, failure = after?.failure,
            newSession = after != null && before?.id != after.id)
        if (after == null || after.phase == CapturePhase.FAILED) {
            phaseExpiry = null
        } else if (before == null || CaptureExpiryKey.from(before) != CaptureExpiryKey.from(after)) {
            phaseExpiry = CaptureExpiryPlan(CaptureExpiryKey.from(after), startedAtElapsedMs + phaseBudget(after.phase))
        }
        if (before?.id != after?.id || after == null || after.phase == CapturePhase.FAILED) {
            mutableServiceFailure.value = null
        }
    }

    private fun ensureExpiry(session: CaptureSession): CaptureExpiryPlan {
        val key = CaptureExpiryKey.from(session)
        return phaseExpiry?.takeIf { it.key == key } ?: CaptureExpiryPlan(
            key,
            monotonicClock.elapsedRealtimeMs() +
                remainingOnRestore(session),
        ).also { phaseExpiry = it }
    }

    private fun remainingOnRestore(session: CaptureSession): Long {
        val now = wallClock.currentTimeMillis()
        if (session.phaseStartedAtEpochMs > now) return 0L
        return (session.deadlineAtEpochMs - now).coerceIn(0, phaseBudget(session.phase))
    }

    private fun phaseBudget(phase: CapturePhase): Long = when (phase) {
        CapturePhase.CONFIGURING -> CONFIGURATION_TIMEOUT_MS
        CapturePhase.WAITING_FOR_TARGET -> TARGET_TIMEOUT_MS
        CapturePhase.OBSERVING_CLICK -> CLICK_TIMEOUT_MS
        CapturePhase.CAPTURED, CapturePhase.AWAITING_CONFIRMATION -> COMPLETION_TIMEOUT_MS
        CapturePhase.FAILED -> 0L
    }

    private fun trace(
        operation: RuleManagementOperation,
        outcome: RuleManagementOutcome,
        sessionId: Long? = null,
        phase: CapturePhase? = null,
        failure: CaptureFailure? = null,
        serviceOperation: CaptureServiceOperation? = null,
        newSession: Boolean = false,
    ) {
        if (!traceSink.enabled) return
        try {
            traceSink.record(RuleManagementTraceEvent(operation, outcome, sessionId?.hashCode(),
                sessionId = sessionId, phase = phase, failure = failure,
                serviceOperation = serviceOperation, newSession = newSession))
        } catch (_: Exception) {
            // 诊断后端失效不得影响业务结果。
        }
    }

    private fun failed(
        session: CaptureSession,
        failure: CaptureFailure,
        diagnostics: CaptureFailureDiagnostics? = null,
        targetWindowDiagnostics: TargetWindowDiagnostics? = null,
    ): CaptureSession {
        val now = wallClock.currentTimeMillis()
        return session.copy(
            phase = CapturePhase.FAILED,
            phaseStartedAtEpochMs = now,
            deadlineAtEpochMs = now,
            leftFlashSkipAtEpochMs = null,
            pendingSample = null,
            pendingObservedCandidate = null,
            failure = failure,
            failureDiagnostics = diagnostics,
            targetWindowDiagnostics = targetWindowDiagnostics,
            candidateClickListeningEnabled = false,
        )
    }

    companion object {
        const val CONFIGURATION_TIMEOUT_MS = 2_000L
        const val TARGET_TIMEOUT_MS = 90_000L
        const val CLICK_TIMEOUT_MS = 15_000L
        const val COMPLETION_TIMEOUT_MS = 10 * 60_000L
        private const val CONFIGURATION_WAIT_MS = CONFIGURATION_TIMEOUT_MS + 500L
        private const val MAX_ACTIVITY_LENGTH = 240
        private const val MAX_DIFF_EVIDENCE_SELECTORS = 64
        private const val MAX_DIFF_EVIDENCE_TARGETS = 10
        private val CAPTURE_EVIDENCE_OPERATIONS = setOf(
            CaptureServiceOperation.RECORD_CAPTURED,
            CaptureServiceOperation.RECORD_CANDIDATE,
            CaptureServiceOperation.UPGRADE_CANDIDATE,
        )
        private val OBSERVATION_OPERATIONS = setOf(
            CaptureServiceOperation.CONFIGURE,
            CaptureServiceOperation.OBSERVE_TARGET,
            CaptureServiceOperation.RECORD_CAPTURED,
            CaptureServiceOperation.RECORD_CANDIDATE,
            CaptureServiceOperation.UPGRADE_CANDIDATE,
            CaptureServiceOperation.RECORD_SEMANTIC_DIFF,
        )
    }

    private fun ObservedDismissCandidate.toSample() = CaptureSample(
        id = id,
        targetAppId = targetAppId,
        targetAppName = targetAppName,
        activityName = activityName,
        clickedAfterTargetMs = observedAfterTargetMs,
        capturedAtEpochMs = observedAtEpochMs,
        nodes = nodes,
        preClickUniqueSelectors = preClickUniqueSelectors,
        evidence = CaptureEvidence.USER_CONFIRMED_UNREPORTED_CLICK,
    )

    private data class RegisteredDiagnosticsProvider(
        val registrationId: Long,
        val provider: (Long) -> CaptureDiagnosticSnapshot?,
    )
}
