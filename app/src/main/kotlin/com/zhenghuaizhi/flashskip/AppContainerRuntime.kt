package com.zhenghuaizhi.flashskip

import android.content.Context
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleManagementTraceSink
import com.zhenghuaizhi.flashskip.data.preferences.PreferencesFlashSkipStore
import com.zhenghuaizhi.flashskip.data.preferences.PreferencesRuleCaptureStore
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.WallClock
import com.zhenghuaizhi.flashskip.platform.accessibility.AndroidMonotonicClock
import com.zhenghuaizhi.flashskip.platform.accessibility.AndroidWallClock
import com.zhenghuaizhi.flashskip.platform.accessibility.RuleManagementTraceLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 内部装配输入；平台生命周期测试可拥有真实存储、调度器与时钟的生命周期。 */
internal data class AppContainerRuntime(
    val applicationScope: CoroutineScope,
    val store: PreferencesFlashSkipStore,
    val captureStore: PreferencesRuleCaptureStore,
    val monotonicClock: MonotonicClock,
    val wallClock: WallClock,
    val captureObserverDispatcher: CoroutineDispatcher,
    val catalogComputationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val traceSink: RuleManagementTraceSink = RuleManagementTraceSink.None,
    val diagnosticsEnabled: Boolean = false,
) {
    companion object {
        fun production(context: Context): AppContainerRuntime {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val trace = RuleManagementTraceLog(BuildConfig.DEBUG)
            return AppContainerRuntime(
                applicationScope = scope,
                store = PreferencesFlashSkipStore(context.applicationContext, scope, trace),
                captureStore = PreferencesRuleCaptureStore(context.applicationContext, scope, trace),
                monotonicClock = AndroidMonotonicClock,
                wallClock = AndroidWallClock,
                captureObserverDispatcher = Dispatchers.IO.limitedParallelism(1),
                traceSink = trace,
                diagnosticsEnabled = BuildConfig.DEBUG,
            )
        }
    }
}
