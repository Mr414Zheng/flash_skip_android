package com.zhenghuaizhi.flashskip.application.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipStore
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.RuleCaptureStore
import com.zhenghuaizhi.flashskip.domain.ports.WallClock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class RuleCaptureCoordinator(
    private val captureStore: RuleCaptureStore,
    private val flashSkipStore: FlashSkipStore,
    private val monotonicClock: MonotonicClock,
    private val wallClock: WallClock,
    private val applicationScope: CoroutineScope,
) {
    val state: Flow<RuleCaptureState> = captureStore.state
    private val sessionSequence = AtomicLong(0)

    suspend fun requestStart(): Long {
        val now = wallClock.currentTimeMillis()
        val sessionId = sessionSequence.updateAndGet { previous -> maxOf(now, previous + 1) }
        captureStore.update { state ->
            state.copy(
                activeSession = CaptureSession(
                    id = sessionId,
                    phase = CapturePhase.CONFIGURING,
                    phaseStartedAtEpochMs = now,
                    deadlineAtEpochMs = now + CONFIGURATION_TIMEOUT_MS,
                ),
            )
        }
        return sessionId
    }

    suspend fun awaitConfigured(sessionId: Long): Boolean {
        val state = withTimeoutOrNull(CONFIGURATION_WAIT_MS) {
            captureStore.state.first { current ->
                current.activeSession?.takeIf { it.id == sessionId }?.phase in setOf(
                    CapturePhase.WAITING_FOR_TARGET,
                    CapturePhase.FAILED,
                )
            }
        }
        val configured = state?.activeSession?.phase == CapturePhase.WAITING_FOR_TARGET
        if (!configured) fail(sessionId, CaptureFailure.CONFIGURATION_TIMEOUT)
        return configured
    }

    suspend fun markConfigured(sessionId: Long) {
        transition(sessionId, CapturePhase.CONFIGURING) { session, now ->
            session.copy(
                phase = CapturePhase.WAITING_FOR_TARGET,
                phaseStartedAtEpochMs = now,
                deadlineAtEpochMs = now + TARGET_TIMEOUT_MS,
            )
        }
    }

    suspend fun observeTarget(
        sessionId: Long,
        targetAppId: TargetAppId,
        targetAppName: String,
        packageName: String,
        activityName: String,
        windowId: Int,
        enteredAtElapsedMs: Long,
    ) {
        captureStore.update { state ->
            val session = state.activeSession?.takeIf { it.id == sessionId } ?: return@update state
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
    }

    suspend fun recordCaptured(sessionId: Long, sample: CaptureSample) {
        captureStore.update { state ->
            val session = state.activeSession?.takeIf {
                it.id == sessionId && it.phase in setOf(
                    CapturePhase.WAITING_FOR_TARGET,
                    CapturePhase.OBSERVING_CLICK,
                )
            } ?: return@update state
            if (session.targetAppId != null && sample.targetAppId != session.targetAppId) {
                return@update state
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
                ),
            )
        }
    }

    suspend fun finish(sessionId: Long) {
        val sample = captureStore.state.first().activeSession?.takeIf {
            it.id == sessionId && it.phase == CapturePhase.CAPTURED
        }?.pendingSample ?: return
        captureStore.update { state ->
            val session = state.activeSession?.takeIf {
                it.id == sessionId && it.phase == CapturePhase.CAPTURED
            } ?: return@update state
            state.copy(samples = state.samples + sample, activeSession = null)
        }
        flashSkipStore.setAppEnabled(sample.targetAppId, false)
    }

    suspend fun cancel(sessionId: Long? = null) {
        captureStore.update { state ->
            val active = state.activeSession ?: return@update state
            if (sessionId != null && active.id != sessionId) state else state.copy(activeSession = null)
        }
    }

    suspend fun fail(sessionId: Long, failure: CaptureFailure) {
        captureStore.update { state ->
            val session = state.activeSession?.takeIf { it.id == sessionId } ?: return@update state
            state.copy(activeSession = failed(session, failure))
        }
    }

    suspend fun expireActive() {
        val now = wallClock.currentTimeMillis()
        captureStore.update { state ->
            val session = state.activeSession ?: return@update state
            if (now < session.deadlineAtEpochMs || session.phase == CapturePhase.FAILED) {
                return@update state
            }
            val failure = when (session.phase) {
                CapturePhase.CONFIGURING -> CaptureFailure.CONFIGURATION_TIMEOUT
                CapturePhase.WAITING_FOR_TARGET -> CaptureFailure.TARGET_TIMEOUT
                CapturePhase.OBSERVING_CLICK -> CaptureFailure.CLICK_TIMEOUT
                CapturePhase.CAPTURED -> CaptureFailure.COMPLETION_TIMEOUT
                CapturePhase.FAILED -> return@update state
            }
            state.copy(activeSession = failed(session, failure))
        }
    }

    suspend fun clearTarget(targetAppId: TargetAppId) {
        flashSkipStore.setAppEnabled(targetAppId, false)
        captureStore.update { state ->
            state.copy(
                samples = state.samples.filterNot { it.targetAppId == targetAppId },
                activeSession = state.activeSession?.takeUnless { it.targetAppId == targetAppId },
            )
        }
    }

    suspend fun clearAll() {
        captureStore.state.first().samples.map(CaptureSample::targetAppId).distinct().forEach {
            flashSkipStore.setAppEnabled(it, false)
        }
        captureStore.update { RuleCaptureState() }
    }

    fun serviceDisconnected() {
        applicationScope.launch {
            val active = captureStore.state.first().activeSession ?: return@launch
            if (active.blocksAutomation) fail(active.id, CaptureFailure.SERVICE_UNAVAILABLE)
        }
    }

    fun elapsedRealtimeMs(): Long = monotonicClock.elapsedRealtimeMs()

    fun currentTimeMillis(): Long = wallClock.currentTimeMillis()

    private suspend fun transition(
        sessionId: Long,
        requiredPhase: CapturePhase,
        transform: (CaptureSession, Long) -> CaptureSession,
    ) {
        captureStore.update { state ->
            val session = state.activeSession?.takeIf {
                it.id == sessionId && it.phase == requiredPhase
            } ?: return@update state
            state.copy(activeSession = transform(session, wallClock.currentTimeMillis()))
        }
    }

    private fun failed(session: CaptureSession, failure: CaptureFailure): CaptureSession {
        val now = wallClock.currentTimeMillis()
        return session.copy(
            phase = CapturePhase.FAILED,
            phaseStartedAtEpochMs = now,
            deadlineAtEpochMs = now,
            pendingSample = null,
            failure = failure,
        )
    }

    companion object {
        const val CONFIGURATION_TIMEOUT_MS = 2_000L
        const val TARGET_TIMEOUT_MS = 90_000L
        const val CLICK_TIMEOUT_MS = 15_000L
        const val COMPLETION_TIMEOUT_MS = 10 * 60_000L
        private const val CONFIGURATION_WAIT_MS = CONFIGURATION_TIMEOUT_MS + 500L
    }
}
