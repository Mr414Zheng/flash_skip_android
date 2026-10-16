package com.zhenghuaizhi.flashskip.platform.accessibility

import android.app.Application
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.view.accessibility.AccessibilityEvent
import com.zhenghuaizhi.flashskip.AppContainer
import com.zhenghuaizhi.flashskip.AppContainerRuntime
import com.zhenghuaizhi.flashskip.BuildConfig
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.platform.diagnostics.CaptureDiagnosticSink
import java.io.File
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Before
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
class ReleaseDiagnosticBoundaryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val session = CaptureSession(42, phase = CapturePhase.CONFIGURING, phaseStartedAtEpochMs = 0, deadlineAtEpochMs = 2000)

    @Before fun requireActualRelease() {
        assertEquals("release", BuildConfig.BUILD_TYPE)
        assertFalse(BuildConfig.DEBUG)
        ShadowLog.clear()
        println("build-variant=${BuildConfig.BUILD_TYPE}; debug=${BuildConfig.DEBUG}")
    }
    @Test fun globalLoggerDoesNotReadSummaryProvidersOrInvokeAnySinkMethod() {
        CaptureTraceLog.installSink(object : CaptureDiagnosticSink {
            override fun append(sessionId: Long?, message: String, essential: Boolean) { fail("Release append") }
            override fun beginSession(sessionId: Long) { fail("Release beginSession") }
        })
        CaptureTraceLog.beginSession(session.id)
        CaptureTraceLog.state(session)
        CaptureTraceLog.stage(session.id, "release_probe", "unsafe" to object {
            override fun toString(): String = error("Release stringify")
        })
        CaptureTraceLog.summary(session, CaptureTraceConclusion.FAILED,
            diagnostics = { error("Release diagnostics provider") },
            targetWindow = { error("Release target window provider") })
        CaptureTraceLog.persistedState(null, RuleCaptureState(activeSession = session))
        CaptureTraceLog.lifecycle("release_probe")
        CaptureTraceLog.boundary(session.id, "release_probe")
        CaptureTraceLog.management(session.id, "count" to 1)
        CaptureTraceLog.flush()
        assertTrue(ShadowLog.getLogs().isEmpty())
    }
    @Config(shadows = [ReleaseDiagnosticClockShadow::class])
    @Test fun actualReleaseGlobalStageDoesNotReadDiagnosticClock() {
        ReleaseDiagnosticClockShadow.reads = 0
        ReleaseDiagnosticClockShadow.failOnRead = true
        try {
            CaptureTraceLog.state(session)
            CaptureTraceLog.stage(session.id, "release_clock")
            CaptureTraceLog.summary(session, CaptureTraceConclusion.CANCELLED, { error("provider") }, { error("provider") })
            CaptureTraceLog.flush()
            assertEquals(0, ReleaseDiagnosticClockShadow.reads)
            // 正控制：证明同一个时钟屏障确实生效，避免未安装 shadow 的假绿。
            assertThrows(IllegalStateException::class.java) { android.os.SystemClock.elapsedRealtime() }
            assertEquals(1, ReleaseDiagnosticClockShadow.reads)
        } finally { ReleaseDiagnosticClockShadow.failOnRead = false }
    }
    @Test fun productionRuntimeAndContainerDoNotCreateDiagnosticDirectory() {
        val application = RuntimeEnvironment.getApplication()
        val root = temporary.newFolder()
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext() = this
            override fun getApplicationInfo() = ApplicationInfo(application.applicationInfo).apply { dataDir = root.absolutePath }
            override fun getFilesDir() = File(root, "files").also { it.mkdirs() }
        }
        val runtime = AppContainerRuntime.production(context)
        try {
            assertFalse(runtime.diagnosticsEnabled); assertFalse(runtime.traceSink.enabled)
            AppContainer(context, runtime)
            CaptureTraceLog.lifecycle("release_container")
            assertFalse(File(root, "no_backup/capture-diagnostics").exists())
            assertTrue(ShadowLog.getLogs().none { it.tag.startsWith("FlashSkip") })
        } finally { runtime.applicationScope.cancel() }
    }
}

/** 对静态平台时钟建立可观测屏障；这里只约束诊断入口，不能影响 Robolectric 生命周期。 */
@org.robolectric.annotation.Implements(android.os.SystemClock::class)
class ReleaseDiagnosticClockShadow {
    companion object {
        var failOnRead = false
        var reads = 0
        @JvmStatic @org.robolectric.annotation.Implementation
        fun elapsedRealtime(): Long {
            if (failOnRead) { reads++; error("Release diagnostic clock read") }
            return 0L
        }
    }
}
