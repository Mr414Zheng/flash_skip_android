package com.zhenghuaizhi.flashskip.platform.accessibility

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.zhenghuaizhi.flashskip.AppContainerRuntime
import com.zhenghuaizhi.flashskip.BuildConfig
import com.zhenghuaizhi.flashskip.platform.diagnostics.CaptureDiagnosticJournal
import java.io.File
import java.util.concurrent.Executor
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DiagnosticBuildBoundaryTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun productionRuntimeDiagnosticFlagsMatchActualBuildType() {
        val runtime = AppContainerRuntime.production(RuntimeEnvironment.getApplication())
        try {
            val expected = BuildConfig.BUILD_TYPE == "debug"
            assertEquals(expected, BuildConfig.DEBUG)
            assertEquals(expected, runtime.diagnosticsEnabled)
            assertEquals(expected, runtime.traceSink.enabled)
        } finally { runtime.applicationScope.cancel() }
    }
    @Test fun actualGlobalCaptureLoggerHonorsBuildGateIncludingFileCreation() {
        val directory = File(temporary.newFolder(), "diagnostics")
        val tasks = ArrayDeque<Runnable>()
        CaptureTraceLog.installSink(CaptureDiagnosticJournal(directory, Executor { tasks.addLast(it) }))
        ShadowLog.clear(); CaptureTraceLog.lifecycle("build_boundary")
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
        if (BuildConfig.BUILD_TYPE == "debug") {
            assertTrue(File(directory, "runtime.log").readText().contains("build_boundary"))
            assertTrue(ShadowLog.getLogs().any { "build_boundary" in it.msg })
        } else {
            assertFalse(directory.exists()); assertTrue(ShadowLog.getLogs().isEmpty())
        }
    }
    @Test fun packagedManifestDisablesBackupAndHasNoNetworkPermission() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES)
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        val service = info.services.orEmpty().single { it.name == FlashSkipAccessibilityService::class.java.name }
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", service.permission)
    }
}
