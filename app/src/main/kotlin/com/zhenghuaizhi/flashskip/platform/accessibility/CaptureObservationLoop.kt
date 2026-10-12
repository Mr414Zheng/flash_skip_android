package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleCaptureCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 预验证循环独立于主线程推进；正式采集切回主线程，跨线程只传纯诊断值。 */
internal class CaptureObservationLoop(
    private val scope: CoroutineScope,
    private val workerDispatcher: CoroutineDispatcher,
    private val currentSession: () -> CaptureSession?,
    private val isHostVerified: () -> Boolean,
    private val loadIgnoredPackages: () -> Set<String>,
    private val onPrepared: (Long, Set<String>) -> Unit,
    private val observerFactory: () -> ((Set<String>) -> StartupPageObservation),
    private val onObservation: (Long, StartupPageObservation) -> Unit,
    private val onVerifiedTick: (Long) -> Unit,
    private val onUnavailable: (Long) -> Unit,
    private val elapsedClock: () -> Long = AndroidMonotonicClock::elapsedRealtimeMs,
) {
    private var sessionId: Long? = null
    private var job: Job? = null

    fun update(session: CaptureSession?) {
        val next = session?.takeIf { it.phase in ACTIVE_PHASES }?.id
        if (next == sessionId) return
        cancel()
        sessionId = next
        if (next == null) return
        job = scope.launch(workerDispatcher) {
            val startedAt = elapsedClock()
            fun observationCurrent(): Boolean = isCurrent(next) &&
                elapsedClock() - startedAt in 0 until MAX_SESSION_OBSERVATION_MS
            try {
                val ignored = loadIgnoredPackages()
                currentCoroutineContext().ensureActive()
                if (!observationCurrent()) return@launch
                val mainContext = scope.coroutineContext.minusKey(Job)
                // 不等待主线程处理准备通知，否则事件回调拥塞会再次推迟首次页面观测。
                launch(mainContext) { if (observationCurrent()) onPrepared(next, ignored) }
                val inspect = observerFactory()
                while (observationCurrent()) {
                    if (currentSession()?.phase != CapturePhase.CONFIGURING) {
                        if (isHostVerified()) withContext(mainContext) {
                            if (observationCurrent()) onVerifiedTick(next)
                        }
                        else {
                            val observation = inspect(ignored)
                            currentCoroutineContext().ensureActive()
                            if (!observationCurrent()) return@launch
                            onObservation(next, observation)
                        }
                    }
                    // 每轮完成后再等待；慢 Binder 只延长本轮，不积压定时任务。
                    delay(200)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                if (observationCurrent()) withContext(scope.coroutineContext.minusKey(Job)) {
                    if (observationCurrent()) onUnavailable(next)
                }
            } finally {
                // 主线程长期无响应时，总预算也能停止读取，并清理尚未执行的准备通知。
                currentCoroutineContext().cancelChildren()
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        sessionId = null
    }

    private fun isCurrent(expected: Long): Boolean = currentSession()?.let {
        it.id == expected && it.phase in ACTIVE_PHASES
    } == true

    private companion object {
        val ACTIVE_PHASES = setOf(CapturePhase.CONFIGURING, CapturePhase.WAITING_FOR_TARGET, CapturePhase.OBSERVING_CLICK)
        val MAX_SESSION_OBSERVATION_MS = RuleCaptureCoordinator.CONFIGURATION_TIMEOUT_MS +
            RuleCaptureCoordinator.TARGET_TIMEOUT_MS + RuleCaptureCoordinator.CLICK_TIMEOUT_MS
    }
}
