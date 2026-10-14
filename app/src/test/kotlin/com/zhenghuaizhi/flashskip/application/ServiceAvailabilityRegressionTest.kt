package com.zhenghuaizhi.flashskip.application

import com.zhenghuaizhi.flashskip.domain.model.ServiceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceAvailabilityRegressionTest {
    @Test fun connectedButRestoringCannotStartCapture() = runTest {
        val registry = ServiceConnectionRegistry()
        val cycle = registry.beginConnection()
        val status = ServiceStatusCoordinator({ true }, registry, backgroundScope)
        status.refresh(); runCurrent()
        assertTrue(registry.connected.value); assertFalse(status.isReady)
        assertEquals(ServiceStatus.CONFIRMING, status.status.value)
        registry.setReady(cycle, true); runCurrent(); assertTrue(status.isReady)
        assertEquals(ServiceStatus.RUNNING, status.status.value)
    }
    @Test fun unavailableStateBlocksStartImmediatelyBeforeCollectorRuns() = runTest {
        val registry = ServiceConnectionRegistry()
        val cycle = registry.beginConnection(); registry.setReady(cycle, true)
        val status = ServiceStatusCoordinator({ true }, registry, backgroundScope)
        status.refresh(); runCurrent(); assertTrue(status.isReady)
        registry.setReady(cycle, false)
        assertFalse(status.isReady)
        runCurrent(); assertEquals(ServiceStatus.ABNORMAL, status.status.value)
    }
    @Test fun repeatedReadinessFailuresCannotRestartExpiredWindow() = runTest {
        val registry = ServiceConnectionRegistry(); val cycle = registry.beginConnection()
        val status = ServiceStatusCoordinator({ true }, registry, backgroundScope)
        status.refresh(); runCurrent(); advanceTimeBy(2_000); runCurrent()
        repeat(3) { registry.setReady(cycle, false); status.refresh(); runCurrent() }
        assertEquals(ServiceStatus.ABNORMAL, status.status.value)
    }
    @Test fun oldConnectionCannotBecomeReadyOrDisconnectNewConnection() = runTest {
        val registry = ServiceConnectionRegistry(); val old = registry.beginConnection(); val current = registry.beginConnection()
        registry.setReady(old, true); assertFalse(registry.availability.value.ready)
        registry.setReady(current, true); registry.endConnection(old)
        assertTrue(registry.connected.value); assertTrue(registry.availability.value.ready)
        registry.setReady(old, false); assertTrue(registry.availability.value.ready)
    }
    @Test fun newConnectionGetsItsOwnDeadlineEvenWithoutAnObservedDisconnectedGap() = runTest {
        val registry = ServiceConnectionRegistry(); registry.beginConnection()
        val status = ServiceStatusCoordinator({ true }, registry, backgroundScope)
        status.refresh(); runCurrent(); advanceTimeBy(1_500)
        registry.beginConnection(); runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(ServiceStatus.CONFIRMING, status.status.value)
        advanceTimeBy(1_500); runCurrent(); assertEquals(ServiceStatus.ABNORMAL, status.status.value)
    }
    @Test fun systemReadFailureCanRecoverWithoutFalselyDisablingConnection() = runTest {
        var fail = true
        val registry = ServiceConnectionRegistry().also { it.setConnected(true) }
        val status = ServiceStatusCoordinator({ if (fail) error("unavailable") else true }, registry, backgroundScope)
        status.refresh(); runCurrent(); assertEquals(ServiceStatus.ABNORMAL, status.status.value)
        assertFalse(status.isReady); assertTrue(registry.connected.value)
        fail = false; status.refresh(); runCurrent(); assertEquals(ServiceStatus.RUNNING, status.status.value)
    }
    @Test fun systemReaderCancellationStillPropagates() = runTest {
        val status = ServiceStatusCoordinator({ throw CancellationException("cancelled") }, ServiceConnectionRegistry(), backgroundScope)
        assertThrows(CancellationException::class.java) { status.refresh() }
    }
}
