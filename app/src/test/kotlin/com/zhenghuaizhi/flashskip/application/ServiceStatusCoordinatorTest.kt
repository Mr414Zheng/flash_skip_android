package com.zhenghuaizhi.flashskip.application

import com.zhenghuaizhi.flashskip.domain.model.ServiceStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceStatusCoordinatorTest {
    @Test
    fun resolverCoversAllFourStates() {
        assertEquals(ServiceStatus.NOT_ENABLED, ServiceStatusResolver.resolve(false, false, false))
        assertEquals(ServiceStatus.CONFIRMING, ServiceStatusResolver.resolve(true, false, true))
        assertEquals(ServiceStatus.RUNNING, ServiceStatusResolver.resolve(true, true, true))
        assertEquals(ServiceStatus.ABNORMAL, ServiceStatusResolver.resolve(true, false, false))
    }

    @Test
    fun confirmationExpiresAndConnectionCanRecover() = runTest {
        var systemEnabled = true
        val connections = ServiceConnectionRegistry()
        val coordinator = ServiceStatusCoordinator(
            statusReader = { systemEnabled },
            connectionRegistry = connections,
            scope = backgroundScope,
        )

        coordinator.refresh()
        runCurrent()
        assertEquals(ServiceStatus.CONFIRMING, coordinator.status.value)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(ServiceStatus.ABNORMAL, coordinator.status.value)

        connections.setConnected(true)
        runCurrent()
        assertEquals(ServiceStatus.RUNNING, coordinator.status.value)

        systemEnabled = false
        coordinator.refresh()
        runCurrent()
        assertEquals(ServiceStatus.NOT_ENABLED, coordinator.status.value)
    }
}
