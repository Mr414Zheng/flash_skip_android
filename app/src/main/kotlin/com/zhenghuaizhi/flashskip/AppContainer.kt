package com.zhenghuaizhi.flashskip

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.zhenghuaizhi.flashskip.platform.accessibility.CaptureTraceLog
import com.zhenghuaizhi.flashskip.platform.accessibility.CaptureTraceError
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.platform.diagnostics.CaptureDiagnosticJournal
import java.io.File
import com.zhenghuaizhi.flashskip.application.ServiceConnectionRegistry
import com.zhenghuaizhi.flashskip.application.ServiceStatusCoordinator
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleCaptureCoordinator
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleManagementOperation
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleManagementOutcome
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleManagementTraceEvent
import com.zhenghuaizhi.flashskip.application.rulecapture.recordSafely
import com.zhenghuaizhi.flashskip.domain.automation.AutomationEngine
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.PageGateway
import com.zhenghuaizhi.flashskip.domain.ports.WallClock
import com.zhenghuaizhi.flashskip.domain.rules.BuiltinRuleCatalog
import com.zhenghuaizhi.flashskip.domain.rules.CompositeRuleCatalog
import com.zhenghuaizhi.flashskip.platform.accessibility.AutomationTraceLog
import com.zhenghuaizhi.flashskip.platform.packages.AndroidTargetAppVersionReader
import com.zhenghuaizhi.flashskip.platform.settings.AndroidSystemServiceStatusReader
import com.zhenghuaizhi.flashskip.ui.home.HomeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class AppContainer internal constructor(context: Context, runtime: AppContainerRuntime) {
    constructor(context: Context) : this(context, AppContainerRuntime.production(context))

    private val applicationScope = runtime.applicationScope
    internal val monotonicClock = runtime.monotonicClock
    internal val wallClock = runtime.wallClock
    internal val captureObserverDispatcher = runtime.captureObserverDispatcher

    init {
        if (runtime.diagnosticsEnabled) {
            CaptureTraceLog.installSink(CaptureDiagnosticJournal(
                // 使用 no_backup 目录；目录创建与历史文件检查由后台写入器完成。
                directory = File(context.applicationInfo.dataDir, "no_backup/capture-diagnostics"),
                onFailure = { failure ->
                    Log.w("FlashSkipCapture", "stage=journal_write_failed reason=${failure.name}")
                },
            ))
            CaptureTraceLog.lifecycle("capture_diagnostics_ready",
                "schema" to 1, "debug" to true, "pid" to Process.myPid(),
                "versionCode" to BuildConfig.VERSION_CODE, "sdk" to Build.VERSION.SDK_INT)
        }
    }

    private val ruleManagementTrace = runtime.traceSink
    val store = runtime.store
    val ruleCaptureStore = runtime.captureStore
    init {
        if (runtime.diagnosticsEnabled) applicationScope.launch {
            CaptureTraceLog.lifecycle("application_capture_observer_started")
            var previous: RuleCaptureState? = null
            try {
                ruleCaptureStore.state.collect { current ->
                    CaptureTraceLog.persistedState(previous, current)
                    previous = current
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CaptureTraceLog.lifecycle("application_capture_observer_failed", "error" to CaptureTraceError.from(error))
            } finally {
                CaptureTraceLog.lifecycle("application_capture_observer_completed")
            }
        }
    }
    private val builtinRuleCatalog = BuiltinRuleCatalog()
    val ruleCatalog = CompositeRuleCatalog(
        builtinCatalog = builtinRuleCatalog,
        captureStore = ruleCaptureStore,
        scope = applicationScope,
        computationDispatcher = runtime.catalogComputationDispatcher,
    )
    init {
        if (ruleManagementTrace.enabled) applicationScope.launch {
            ruleCatalog.snapshot.collect { snapshot ->
                ruleManagementTrace.recordSafely {
                    RuleManagementTraceEvent(
                        RuleManagementOperation.PUBLISH, RuleManagementOutcome.SUCCESS,
                        ruleCount = snapshot.rules.size,
                    )
                }
            }
        }
    }

    val targetAppVersionReader = AndroidTargetAppVersionReader(context.applicationContext)
    val ruleCaptureCoordinator = RuleCaptureCoordinator(
        captureStore = ruleCaptureStore,
        flashSkipStore = store,
        monotonicClock = monotonicClock,
        wallClock = wallClock,
        applicationScope = applicationScope,
        ruleCatalog = ruleCatalog,
        traceSink = ruleManagementTrace,
    )
    val serviceConnections = ServiceConnectionRegistry()

    fun createAutomationEngine(
        pageGateway: PageGateway,
        monotonicClock: MonotonicClock,
        wallClock: WallClock,
        scope: CoroutineScope,
    ) = AutomationEngine(
        catalog = ruleCatalog,
        store = store,
        pageGateway = pageGateway,
        monotonicClock = monotonicClock,
        wallClock = wallClock,
        scope = scope,
        traceSink = AutomationTraceLog(enabled = BuildConfig.DEBUG),
    )

    private val serviceStatusCoordinator = ServiceStatusCoordinator(
        statusReader = AndroidSystemServiceStatusReader(context),
        connectionRegistry = serviceConnections,
        scope = applicationScope,
    )

    val homeViewModelFactory = HomeViewModel.Factory(
        store = store,
        ruleCatalog = ruleCatalog,
        serviceStatusCoordinator = serviceStatusCoordinator,
        captureCoordinator = ruleCaptureCoordinator,
    )
}
