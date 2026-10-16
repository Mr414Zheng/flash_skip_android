package com.zhenghuaizhi.flashskip.platform.accessibility

import android.app.Application
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleManagementTraceSink
import com.zhenghuaizhi.flashskip.platform.diagnostics.CaptureDiagnosticSink
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** 同一组真实服务／实际 DataStore 断言作为正控制和故障实验，避免弱化业务结果。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class,
    shadows = [P3FlashServiceShadow::class, FaultableAccessibilityNodeShadow::class,
        P3ActivityPackageManagerShadow::class, DiagnosticBackendFaultShadow::class])
class DiagnosticServiceBackendFaultTest : FlashSkipAccessibilityServiceLifecycleTest() {
    @get:Rule val faultTestName = TestName()
    @Before fun inject() { ServiceDiagnosticFaults.install(ServiceDiagnosticFaults.Mode.BACKEND) }
    @After fun verifyFaultReached() { ServiceDiagnosticFaults.finish(faultTestName.methodName, requireHits = true) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class,
    shadows = [P3FlashServiceShadow::class, FaultableAccessibilityNodeShadow::class,
        P3ActivityPackageManagerShadow::class, DiagnosticBackendFaultShadow::class])
class DiagnosticServiceSinkFaultTest : FlashSkipAccessibilityServiceLifecycleTest() {
    @get:Rule val faultTestName = TestName()
    @Before fun inject() { ServiceDiagnosticFaults.install(ServiceDiagnosticFaults.Mode.SINK) }
    @After fun verifyFaultReached() { ServiceDiagnosticFaults.finish(faultTestName.methodName, requireHits = true) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class,
    shadows = [P3FlashServiceShadow::class, FaultableAccessibilityNodeShadow::class,
        P3ActivityPackageManagerShadow::class, DiagnosticBackendFaultShadow::class])
class DiagnosticServiceProviderFaultTest : FlashSkipAccessibilityServiceLifecycleTest() {
    @get:Rule val faultTestName = TestName()
    @Before fun inject() { ServiceDiagnosticFaults.install(ServiceDiagnosticFaults.Mode.PROVIDERS) }
    @After fun reportReachability() { ServiceDiagnosticFaults.finish(faultTestName.methodName, requireHits = faultTestName.methodName !in NO_SUMMARY_PATHS) }
}

private val NO_SUMMARY_PATHS = setOf(
    "p5WaitingForFirstStorageReadMustNotReportReady",
    "sameSessionEmissionCannotBypassRecoveryWhileSettlementWriteStillFails",
    "p5FailedRecoveryMustNotReportReady",
    "p4CaptureReadIoAtConnectDoesNotCrashAndSameSubscriptionRecovers",
    "p4DamagedSessionKeepsValidRulesNonExecutableUntilExplicitRecovery",
    "p4UnknownCaptureVersionBlocksRealServiceUntilSupportedDataReturns",
    "p4PreferencesReadIoAtConnectKeepsEnginePausedUntilValidRead",
    "p5UnavailablePreferencesMustNotReportReady",
)

internal object ServiceDiagnosticFaults {
    enum class Mode { NONE, BACKEND, SINK, PROVIDERS, JOURNAL }
    var mode = Mode.NONE
        private set
    private val observations = linkedSetOf<String>()
    private var failures = 0
    private val noSink = object : CaptureDiagnosticSink {
        override fun append(sessionId: Long?, message: String, essential: Boolean) = Unit
    }
    fun managementTrace(): RuleManagementTraceSink =
        if (mode == Mode.NONE) RuleManagementTraceSink.None else RuleManagementTraceLog(true)

    fun install(selected: Mode) {
        mode = selected; observations.clear(); failures = 0
        bindSink()
    }
    fun bindSink() {
        CaptureTraceLog.installSink(object : CaptureDiagnosticSink {
            override fun append(sessionId: Long?, message: String, essential: Boolean) {
                if (mode == Mode.SINK) fail("sink", message)
            }
            override fun beginSession(sessionId: Long) {
                if (mode == Mode.SINK) fail("sink.beginSession", "stage=begin_session")
            }
        })
    }
    fun configure(service: FlashSkipAccessibilityService) {
        if (mode == Mode.PROVIDERS) {
            service.diagnosticSnapshotProvider = { fail("provider.snapshot", "stage=summary_provider") }
            service.diagnosticWindowProvider = { fail("provider.window", "stage=window_provider") }
        }
    }
    @Volatile var backendObservation: ((String) -> Unit)? = null
    fun backend(tag: String, message: String) {
        try {
            if (mode == Mode.BACKEND && tag.startsWith("FlashSkip")) fail("backend", message)
        } finally { backendObservation?.invoke(message) }
    }
    @Synchronized fun reached(stage: String) = observations.any { "|$stage|" in it }
    @Synchronized private fun fail(outlet: String, message: String): Nothing {
        failures++
        val stage = Regex("(?:^| )stage=([^ ]+)").find(message)?.groupValues?.get(1) ?: "unknown"
        val frames = Throwable().stackTrace.filter {
            it.className.startsWith("com.zhenghuaizhi.flashskip.") &&
                !it.className.contains("ServiceDiagnosticFaults") && !it.className.contains("DiagnosticBackendFaultShadow") &&
                !it.className.contains("Test") && !it.methodName.startsWith("access$")
        }.take(6).joinToString(" <- ") { "${it.fileName}:${it.lineNumber}:${it.methodName}" }
        observations += "$outlet|$stage|$frames"
        // 交替验证普通异常和诊断自己的取消异常均不能改变业务。
        if (failures % 2 == 0) throw CancellationException("diagnostic-fixture-secret")
        throw IOException("diagnostic-fixture-secret")
    }
    @Synchronized fun finish(testName: String, requireHits: Boolean) {
        val count = failures
        try {
            println("FAULT_MATRIX mode=$mode test=$testName injected=$count")
            observations.forEach { println("FAULT_POINT $it") }
            if (requireHits) assertTrue("故障注入未到达实际出口：$testName", count > 0)
            if (mode == Mode.PROVIDERS && requireHits) {
                assertTrue(observations.any { it.startsWith("provider.snapshot|") })
                assertTrue(observations.any { it.startsWith("provider.window|") })
            }
            if (mode == Mode.PROVIDERS && !requireHits) assertEquals(0, count)
            assertTrue(ShadowLog.getLogs().none { "diagnostic-fixture-secret" in it.msg })
        } finally {
            backendObservation = null
            mode = Mode.NONE
            CaptureTraceLog.installSink(noSink)
        }
    }
}
