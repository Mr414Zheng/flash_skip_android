package com.zhenghuaizhi.flashskip.platform.accessibility

import android.app.Application
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.view.accessibility.AccessibilityNodeInfo
import com.zhenghuaizhi.flashskip.application.rulecapture.CaptureOperationResult
import com.zhenghuaizhi.flashskip.data.preferences.RuleCaptureCodec
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.testing.SESSION_ID
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [P3FlashServiceShadow::class,
    FaultableAccessibilityNodeShadow::class, P3ActivityPackageManagerShadow::class, DiagnosticBackendFaultShadow::class])
class DiagnosticApplicationObserverTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun enabledApplicationObserverHasHealthyBusinessControl() = scenario(ServiceDiagnosticFaults.Mode.NONE, false)
    @Test fun enabledApplicationObserverContinuesThroughBackendFault() = scenario(ServiceDiagnosticFaults.Mode.BACKEND, false)
    @Test fun enabledApplicationObserverContinuesThroughSinkFault() = scenario(ServiceDiagnosticFaults.Mode.SINK, false)
    @Test fun actualJournalFailureNotificationHasHealthyBusinessControl() = scenario(ServiceDiagnosticFaults.Mode.NONE, true)
    @Test fun actualJournalFailureNotificationBackendFailureCannotStopBusiness() = scenario(ServiceDiagnosticFaults.Mode.BACKEND, true)

    private fun scenario(mode: ServiceDiagnosticFaults.Mode, journalIo: Boolean) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        ServiceDiagnosticFaults.install(mode)
        val application = RuntimeEnvironment.getApplication()
        val root = temporary.newFolder()
        if (journalIo) File(root, "no_backup").writeText("synthetic-blocker")
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext() = this
            override fun getApplicationInfo() = ApplicationInfo(application.applicationInfo).apply { dataDir = root.absolutePath }
        }
        val notified = CountDownLatch(1)
        ServiceDiagnosticFaults.backendObservation = { if ("stage=journal_write_failed" in it) notified.countDown() }
        val f = FlashSkipAccessibilityServiceLifecycleTest.ServiceFixture(this, temporary.newFolder(), CapturePhase.CAPTURED,
            60_000, diagnosticsEnabled = true, diagnosticContext = context)
        // AppContainer 安装真实 journal 后才覆盖 sink；诊断观察协程尚未获得执行机会。
        if (!journalIo) ServiceDiagnosticFaults.bindSink()
        try {
            f.seed(); f.connect(); f.installTargetWindow()
            if (journalIo) assertTrue("实际 AppContainer journal 失败通知未到达", notified.await(5, TimeUnit.SECONDS))
            assertEquals(CapturePhase.CAPTURED, f.state().activeSession!!.phase)
            assertTrue(f.container.ruleCaptureCoordinator.cancel(SESSION_ID) is CaptureOperationResult.Success)
            runCurrent()
            assertNull(f.state().activeSession)
            // 同一个真实观察器遇到未知存储后不消费，恢复原始格式后继续观察和执行。
            f.captureData.replaceRaw("rule_capture_state_v2", """{"version":99,"samples":[]}""")
            runCurrent()
            f.sendTargetWindowEvent(); advanceTimeBy(120); runCurrent()
            assertTrue(shadowOf(f.nodes.target).performedActions.isEmpty())
            f.captureData.replaceRaw("rule_capture_state_v2", RuleCaptureCodec.encode(f.initial.copy(activeSession = null)))
            runCurrent(); f.sendTargetWindowEvent(); advanceTimeBy(120); runCurrent()
            assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(f.nodes.target).performedActions)
            assertEquals(f.initial.samples, f.state().samples)
            if (mode != ServiceDiagnosticFaults.Mode.NONE) {
                for (stage in listOf("application_capture_observer_started", "persisted_capture_state", "application_capture_storage_unavailable")) {
                    assertTrue("没有到达 $stage", ServiceDiagnosticFaults.reached(stage))
                }
                if (journalIo) assertTrue(ServiceDiagnosticFaults.reached("journal_write_failed"))
            } else {
                assertTrue(ShadowLog.getLogs().any { "stage=persisted_capture_state" in it.msg })
                assertTrue(ShadowLog.getLogs().any { "stage=application_capture_storage_unavailable" in it.msg })
            }
        } finally {
            f.destroy(); runCurrent(); f.job.cancelAndJoin(); runCurrent()
            if (mode != ServiceDiagnosticFaults.Mode.NONE) assertTrue(ServiceDiagnosticFaults.reached("application_capture_observer_completed"))
            Dispatchers.resetMain()
            ServiceDiagnosticFaults.finish("application-$mode-journalIo=$journalIo", mode != ServiceDiagnosticFaults.Mode.NONE)
        }
    }
}
