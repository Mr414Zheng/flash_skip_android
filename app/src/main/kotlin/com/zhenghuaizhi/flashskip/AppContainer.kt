package com.zhenghuaizhi.flashskip

import android.content.Context
import com.zhenghuaizhi.flashskip.application.ServiceConnectionRegistry
import com.zhenghuaizhi.flashskip.application.ServiceStatusCoordinator
import com.zhenghuaizhi.flashskip.data.preferences.PreferencesFlashSkipStore
import com.zhenghuaizhi.flashskip.domain.automation.AutomationEngine
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.PageGateway
import com.zhenghuaizhi.flashskip.domain.ports.WallClock
import com.zhenghuaizhi.flashskip.domain.rules.BuiltinRuleCatalog
import com.zhenghuaizhi.flashskip.platform.settings.AndroidSystemServiceStatusReader
import com.zhenghuaizhi.flashskip.ui.home.HomeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val store = PreferencesFlashSkipStore(context.applicationContext)
    val ruleCatalog = BuiltinRuleCatalog()
    val serviceConnections = ServiceConnectionRegistry()
    val targetPackages: Set<String> = ruleCatalog.targets.map { it.packageName }.toSet()

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
    )
}
