package com.zhenghuaizhi.flashskip.domain.ports

import com.zhenghuaizhi.flashskip.domain.model.AppVersion
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import kotlinx.coroutines.flow.Flow

interface RuleCaptureStore {
    val state: Flow<RuleCaptureState>

    suspend fun update(transform: (RuleCaptureState) -> RuleCaptureState)
}

interface TargetAppVersionReader {
    fun versionFor(packageName: String): AppVersion?

    fun displayNameFor(packageName: String): String?

    fun verifiedActivity(packageName: String, className: String?): String?
}
