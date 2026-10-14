package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureObservationLoopLifecycleTest {
    @Test fun configuringPreparesOnceButDoesNotInspectUntilWaiting() = runTest {
        val f = ObservationFixture(backgroundScope, testScheduler, CapturePhase.CONFIGURING)
        f.loop.update(f.session)
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(1L), f.prepared)
        assertEquals(0, f.inspections)

        f.session = f.session!!.copy(phase = CapturePhase.WAITING_FOR_TARGET)
        f.loop.update(f.session)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(1, f.inspections)
        assertEquals(listOf(1L), f.observed)
        assertEquals(listOf(1L), f.prepared)
    }

    @Test fun verifiedHostTicksOnMainWithoutProvisionalInspection() = runTest {
        val f = ObservationFixture(backgroundScope, testScheduler)
        f.verified = true
        f.loop.update(f.session)
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(1L, 1L), f.ticks)
        assertEquals(0, f.inspections)
        assertTrue(f.observed.isEmpty())
    }

    @Test fun preparationFinishingForReplacedSessionCannotDeliverOldReadyNotice() = runTest {
        val f = ObservationFixture(backgroundScope, testScheduler)
        f.load = { f.session = f.session!!.copy(id = 2); setOf("home") }
        f.loop.update(f.session)
        runCurrent()
        assertTrue(f.prepared.isEmpty())
        assertEquals(0, f.inspections)
    }

    @Test fun inspectionReturningAfterSessionReplacementCannotPublishOldObservation() = runTest {
        val f = ObservationFixture(backgroundScope, testScheduler)
        f.inspect = { f.session = f.session!!.copy(id = 2); f.page }
        f.loop.update(f.session)
        runCurrent()
        assertEquals(1, f.inspections)
        assertTrue(f.observed.isEmpty())
        assertTrue(f.unavailable.isEmpty())
    }

    @Test fun replacementCancelsOldLoopAndOnlyNewIdContinues() = runTest {
        val f = ObservationFixture(backgroundScope, testScheduler)
        f.loop.update(f.session)
        runCurrent()
        f.session = f.session!!.copy(id = 2)
        f.loop.update(f.session)
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(1L, 2L), f.prepared)
        assertEquals(listOf(1L, 2L, 2L), f.observed)
    }

    @Test fun endingPhaseOrExplicitCancelPreventsLaterTicks() = runTest {
        val f = ObservationFixture(backgroundScope, testScheduler)
        f.loop.update(f.session)
        runCurrent()
        f.session = f.session!!.copy(phase = CapturePhase.AWAITING_CONFIRMATION)
        f.loop.update(f.session)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(1L), f.observed)

        f.session = f.session!!.copy(id = 2, phase = CapturePhase.OBSERVING_CLICK)
        f.loop.update(f.session)
        runCurrent()
        f.loop.cancel()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(1L, 2L), f.observed)
    }

    @Test fun slowInspectionFinishingAtTotalBudgetCannotPublishOrContinue() = runTest {
        val f = ObservationFixture(backgroundScope, testScheduler)
        var elapsed = 0L
        f.clock = { elapsed }
        f.inspect = { elapsed = 107_000; f.page }
        f.loop.update(f.session)
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(1, f.inspections)
        assertTrue(f.observed.isEmpty())
        assertTrue(f.unavailable.isEmpty())
    }

    @Test fun unexpectedInspectionFailureReportsUnavailableOnlyForCurrentSession() = runTest {
        val f = ObservationFixture(backgroundScope, testScheduler)
        f.inspect = { throw IllegalStateException("读取失败") }
        f.loop.update(f.session)
        runCurrent()
        assertEquals(listOf(1L), f.unavailable)
        assertTrue(f.observed.isEmpty())
        assertEquals(CapturePhase.WAITING_FOR_TARGET, f.session!!.phase)
    }

    @Test fun inspectionCancellationDoesNotBecomeUnavailable() = runTest {
        val f = ObservationFixture(backgroundScope, testScheduler)
        f.inspect = { throw CancellationException("取消观察") }
        f.loop.update(f.session)
        runCurrent()
        assertTrue(f.unavailable.isEmpty())
        assertTrue(f.observed.isEmpty())
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, f.inspections)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class ObservationFixture(
    scope: CoroutineScope,
    scheduler: TestCoroutineScheduler,
    phase: CapturePhase = CapturePhase.WAITING_FOR_TARGET,
) {
    var session: CaptureSession? = CaptureSession(1, phase = phase, phaseStartedAtEpochMs = 1, deadlineAtEpochMs = 90_001)
    var verified = false
    var inspections = 0
    val prepared = mutableListOf<Long>()
    val observed = mutableListOf<Long>()
    val ticks = mutableListOf<Long>()
    val unavailable = mutableListOf<Long>()
    val page = StartupPageObservation(StartupObservationOutcome.NO_ACTIVE_WINDOW)
    var load: () -> Set<String> = { setOf("home") }
    var inspect: () -> StartupPageObservation = { page }
    var clock: () -> Long = { scheduler.currentTime }
    val loop = CaptureObservationLoop(
        scope = scope,
        workerDispatcher = StandardTestDispatcher(scheduler),
        currentSession = { session },
        isHostVerified = { verified },
        loadIgnoredPackages = { load() },
        onPrepared = { id, ignored -> assertEquals(setOf("home"), ignored); prepared += id },
        observerFactory = { { ignored -> assertEquals(setOf("home"), ignored); inspections++; inspect() } },
        onObservation = { id, _ -> observed += id },
        onVerifiedTick = { ticks += it },
        onUnavailable = { unavailable += it },
        elapsedClock = { clock() },
    )
}
