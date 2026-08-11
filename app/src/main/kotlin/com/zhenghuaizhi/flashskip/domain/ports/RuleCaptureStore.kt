package com.zhenghuaizhi.flashskip.domain.ports

import com.zhenghuaizhi.flashskip.domain.model.AppVersion
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import kotlinx.coroutines.flow.Flow

interface RuleCaptureStore {
    val state: Flow<RuleCaptureState>

    suspend fun update(transform: (RuleCaptureState) -> RuleCaptureState)
}

interface TargetAppVersionReader {
    fun versionFor(targetAppId: TargetAppId): AppVersion?

    fun installedVersions(): Map<TargetAppId, AppVersion?> =
        TargetAppId.entries.associateWith(::versionFor)
}
