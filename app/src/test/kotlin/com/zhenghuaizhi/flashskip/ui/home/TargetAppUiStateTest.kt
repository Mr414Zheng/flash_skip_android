package com.zhenghuaizhi.flashskip.ui.home

import com.zhenghuaizhi.flashskip.domain.model.RuleAvailability
import com.zhenghuaizhi.flashskip.domain.model.ServiceStatus
import com.zhenghuaizhi.flashskip.domain.model.TargetApp
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import org.junit.Assert.assertEquals
import org.junit.Test

class TargetAppUiStateTest {
    @Test
    fun enabledTargetIsDeferredForEveryUnavailableServiceState() {
        val unavailableStatuses = listOf(
            ServiceStatus.NOT_ENABLED,
            ServiceStatus.CONFIRMING,
            ServiceStatus.ABNORMAL,
        )

        unavailableStatuses.forEach { serviceStatus ->
            assertEquals(
                TargetAppStatus.ENABLED_BUT_SERVICE_UNAVAILABLE,
                targetAppUiState(target(), enabled = true, serviceStatus = serviceStatus).status,
            )
        }
    }

    @Test
    fun runningAndDisabledStatesRemainDistinct() {
        assertEquals(
            TargetAppStatus.ENABLED_AND_RUNNING,
            targetAppUiState(target(), enabled = true, serviceStatus = ServiceStatus.RUNNING).status,
        )
        assertEquals(
            TargetAppStatus.DISABLED,
            targetAppUiState(target(), enabled = false, serviceStatus = ServiceStatus.RUNNING).status,
        )
    }

    private fun target() = TargetApp(
        id = TargetAppId.QQ_MUSIC,
        displayName = "QQ音乐",
        packageName = "com.tencent.qqmusic",
        ruleAvailability = RuleAvailability.Available(1),
    )
}
