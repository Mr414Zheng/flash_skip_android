package com.zhenghuaizhi.flashskip.application

import com.zhenghuaizhi.flashskip.domain.model.ServiceStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceStatusContractTest {
    @Test fun disabledAlwaysTakesPrecedence() {
        for (connected in listOf(false, true)) for (confirming in listOf(false, true)) {
            assertEquals(ServiceStatus.NOT_ENABLED, ServiceStatusResolver.resolve(false, connected, confirming))
        }
    }
    @Test fun enabledDisconnectedTruthTable() {
        assertEquals(ServiceStatus.CONFIRMING, ServiceStatusResolver.resolve(true, false, true))
        assertEquals(ServiceStatus.ABNORMAL, ServiceStatusResolver.resolve(true, false, false))
    }
    @Test fun connectionRegistryStartsDisconnectedAndPublishesChanges() {
        val registry = ServiceConnectionRegistry()
        assertFalse(registry.connected.value)
        registry.setConnected(true); assertTrue(registry.connected.value)
        registry.setConnected(false); assertFalse(registry.connected.value)
    }
    @Test fun confirmationLastsExactlyTwoSeconds() = runTest {
        val c = ServiceStatusCoordinator({ true }, ServiceConnectionRegistry(), backgroundScope)
        c.refresh(); runCurrent()
        assertEquals(ServiceStatus.CONFIRMING, c.status.value)
        advanceTimeBy(1_999); runCurrent(); assertEquals(ServiceStatus.CONFIRMING, c.status.value)
        advanceTimeBy(1); runCurrent(); assertEquals(ServiceStatus.ABNORMAL, c.status.value)
    }
    @Test fun repeatedRefreshDoesNotExtendCurrentDeadline() = runTest {
        val c = ServiceStatusCoordinator({ true }, ServiceConnectionRegistry(), backgroundScope)
        c.refresh(); runCurrent(); advanceTimeBy(1_500)
        c.refresh(); runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(ServiceStatus.ABNORMAL, c.status.value)
    }
    @Test fun expiredCycleCannotBeReopenedByRefresh() = runTest {
        val c = ServiceStatusCoordinator({ true }, ServiceConnectionRegistry(), backgroundScope)
        c.refresh(); runCurrent(); advanceTimeBy(2_000); runCurrent()
        c.refresh(); runCurrent()
        assertEquals(ServiceStatus.ABNORMAL, c.status.value)
    }
    @Test fun disableCancelsPendingConfirmation() = runTest {
        var enabled = true
        val c = ServiceStatusCoordinator({ enabled }, ServiceConnectionRegistry(), backgroundScope)
        c.refresh(); runCurrent(); advanceTimeBy(100)
        enabled = false; c.refresh(); runCurrent()
        advanceTimeBy(2_000); runCurrent(); assertEquals(ServiceStatus.NOT_ENABLED, c.status.value)
    }
    @Test fun reenableStartsFreshDeadlineAndOldTimerCannotFinishIt() = runTest {
        var enabled = true
        val c = ServiceStatusCoordinator({ enabled }, ServiceConnectionRegistry(), backgroundScope)
        c.refresh(); runCurrent(); advanceTimeBy(1_000)
        enabled = false; c.refresh(); runCurrent(); enabled = true; c.refresh(); runCurrent()
        advanceTimeBy(1_000); runCurrent(); assertEquals(ServiceStatus.CONFIRMING, c.status.value)
        advanceTimeBy(1_000); runCurrent(); assertEquals(ServiceStatus.ABNORMAL, c.status.value)
    }
    @Test fun lateConnectionClearsAbnormalState() = runTest {
        val registry = ServiceConnectionRegistry()
        val c = ServiceStatusCoordinator({ true }, registry, backgroundScope)
        c.refresh(); runCurrent(); advanceTimeBy(2_000); runCurrent()
        registry.setConnected(true); runCurrent(); assertEquals(ServiceStatus.RUNNING, c.status.value)
    }
    @Test fun connectedCycleThenDisconnectHasFreshConfirmationWindow() = runTest {
        val registry = ServiceConnectionRegistry()
        val c = ServiceStatusCoordinator({ true }, registry, backgroundScope)
        c.refresh(); runCurrent(); registry.setConnected(true); runCurrent()
        registry.setConnected(false); c.refresh(); runCurrent()
        assertEquals(ServiceStatus.CONFIRMING, c.status.value)
        advanceTimeBy(2_000); runCurrent(); assertEquals(ServiceStatus.ABNORMAL, c.status.value)
    }
    @Test fun successfulConnectionCancelsOldDeadline() = runTest {
        val registry = ServiceConnectionRegistry()
        val c = ServiceStatusCoordinator({ true }, registry, backgroundScope)
        c.refresh(); runCurrent(); advanceTimeBy(1_999)
        registry.setConnected(true); runCurrent(); advanceTimeBy(1); runCurrent()
        assertEquals(ServiceStatus.RUNNING, c.status.value)
    }
    @Test fun systemReadFailureDoesNotThrowAndCannotKeepRunning() = runTest {
        var fail = false
        val registry = ServiceConnectionRegistry().also { it.setConnected(true) }
        val c = ServiceStatusCoordinator({ if (fail) throw SecurityException("settings denied") else true }, registry, backgroundScope)
        c.refresh(); runCurrent(); fail = true
        c.refresh(); runCurrent()
        assertNotEquals(ServiceStatus.RUNNING, c.status.value)
    }
}
