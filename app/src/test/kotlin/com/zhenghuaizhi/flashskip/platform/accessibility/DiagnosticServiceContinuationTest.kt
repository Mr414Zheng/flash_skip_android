package com.zhenghuaizhi.flashskip.platform.accessibility

import android.app.Application
import android.view.accessibility.AccessibilityNodeInfo
import com.zhenghuaizhi.flashskip.application.rulecapture.CaptureOperationResult
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.ports.GestureResult
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.platform.diagnostics.CaptureDiagnosticJournal
import com.zhenghuaizhi.flashskip.platform.diagnostics.CaptureJournalFailure
import com.zhenghuaizhi.flashskip.testing.SESSION_ID
import com.zhenghuaizhi.flashskip.testing.TARGET
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [P3FlashServiceShadow::class,
    FaultableAccessibilityNodeShadow::class, P3ActivityPackageManagerShadow::class, DiagnosticBackendFaultShadow::class])
class DiagnosticServiceContinuationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun healthyJournalPreservesBusinessAndWritesRecoveryControl() = journalScenario(Fault.NONE)
    @Test fun journalIoAndThrowingFailureCallbackDoNotStopServiceOrLaterWrites() = journalScenario(Fault.IO)
    @Test fun journalExecutorRejectionAndThrowingFailureCallbackDoNotStopServiceOrLaterWrites() = journalScenario(Fault.EXECUTOR)
    @Test fun businessCancellationPropagatesWithoutDiagnosticFault() = cancellationScenario(ServiceDiagnosticFaults.Mode.NONE)
    @Test fun businessCancellationPropagatesWithBackendFault() = cancellationScenario(ServiceDiagnosticFaults.Mode.BACKEND)
    @Test fun businessCancellationPropagatesWithSinkFault() = cancellationScenario(ServiceDiagnosticFaults.Mode.SINK)
    @Test fun gestureCallbacksCompleteAndCancelWithHealthyDiagnostics() = gestureScenario(ServiceDiagnosticFaults.Mode.NONE)
    @Test fun gestureCallbacksCompleteAndCancelWithBackendFault() = gestureScenario(ServiceDiagnosticFaults.Mode.BACKEND)

    @Test fun freshCaptureEventsAndCancellationHaveHealthyDiagnosticControl() = captureScenario(ServiceDiagnosticFaults.Mode.NONE)
    @Test fun freshCaptureEventsAndCancellationContinueWithBackendFault() = captureScenario(ServiceDiagnosticFaults.Mode.BACKEND)
    @Test fun freshCaptureEventsAndCancellationContinueWithSinkFault() = captureScenario(ServiceDiagnosticFaults.Mode.SINK)

    private fun captureScenario(mode: ServiceDiagnosticFaults.Mode) = runTest {
        ServiceDiagnosticFaults.install(mode)
        withService { f ->
            val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
            val home = android.content.pm.ResolveInfo().apply {
                activityInfo = android.content.pm.ActivityInfo().apply { packageName = "fixture.home"; name = "fixture.home.Launcher" }
            }
            shadowOf(org.robolectric.RuntimeEnvironment.getApplication().packageManager).addResolveInfoForIntent(homeIntent, home)
            f.connect(); f.installTargetWindow()
            assertTrue(f.container.ruleCaptureCoordinator.cancel(SESSION_ID) is CaptureOperationResult.Success)
            runCurrent()
            val started = f.container.ruleCaptureCoordinator.requestStart() as CaptureOperationResult.Success
            val id = started.sessionId!!
            runCurrent()
            assertEquals(CapturePhase.WAITING_FOR_TARGET, f.state().activeSession!!.phase)
            assertTrue(f.container.ruleCaptureCoordinator.awaitConfigured(id))
            f.container.ruleCaptureCoordinator.markLeftFlashSkip(id)
            runCurrent()
            f.sendTargetWindowEvent()
            runCurrent()
            assertEquals(CapturePhase.AWAITING_CONFIRMATION, f.state().activeSession!!.phase)
            assertEquals(TARGET.packageName, f.state().activeSession!!.packageName)
            assertNotNull(f.state().activeSession!!.pendingObservedCandidate)
            val event = android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
            event.packageName = TARGET.packageName
            shadowOf(event).setWindowId(7)
            shadowOf(event).setSourceNode(f.nodes.target)
            try { f.service.onAccessibilityEvent(event) } finally { event.recycle() }
            runCurrent()
            assertEquals(CapturePhase.CAPTURED, f.state().activeSession!!.phase)
            assertEquals(com.zhenghuaizhi.flashskip.domain.model.CaptureEvidence.ACCESSIBILITY_CLICK, f.state().activeSession!!.pendingSample!!.evidence)
            assertNull(f.state().activeSession!!.pendingObservedCandidate)
            assertTrue(f.container.ruleCaptureCoordinator.cancel(id) is CaptureOperationResult.Success)
            runCurrent()
            assertNull(f.state().activeSession)
            assertEquals(f.initial.samples, f.state().samples)
        }
    }

    private enum class Fault { NONE, IO, EXECUTOR }
    private fun journalScenario(fault: Fault) = runTest {
        ServiceDiagnosticFaults.install(ServiceDiagnosticFaults.Mode.JOURNAL)
        val directory = File(temporary.newFolder(), "journal")
        if (fault == Fault.IO) directory.writeText("blocked")
        val queue = ArrayDeque<Runnable>()
        var reject = fault == Fault.EXECUTOR
        val notifications = mutableListOf<CaptureJournalFailure>()
        val journal = CaptureDiagnosticJournal(directory, executor = Executor {
            if (reject) throw RejectedExecutionException("synthetic-executor")
            queue.addLast(it)
        }, onFailure = {
            notifications += it
            throw CancellationException("diagnostic-notification")
        })
        CaptureTraceLog.installSink(journal)
        withService { f ->
            f.connect()
            assertEquals(CapturePhase.CAPTURED, f.state().activeSession!!.phase)
            assertTrue(f.container.ruleCaptureCoordinator.cancel(SESSION_ID) is CaptureOperationResult.Success)
            runCurrent()
            while (queue.isNotEmpty()) queue.removeFirst().run()
            if (fault == Fault.NONE) assertTrue(notifications.isEmpty()) else {
                assertEquals(listOf(if (fault == Fault.IO) CaptureJournalFailure.IO else CaptureJournalFailure.OTHER), notifications)
            }
            // 同一服务、同一存储、同一 journal 实例恢复后仍执行真实动作并继续写入。
            reject = false
            if (fault == Fault.IO) { assertTrue(directory.delete()); assertTrue(directory.mkdir()) }
            f.installTargetWindow(); f.sendTargetWindowEvent()
            advanceTimeBy(120); runCurrent()
            assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(f.nodes.target).performedActions)
            assertEquals(f.initial.samples, f.state().samples)
            assertTrue(f.container.ruleCaptureCoordinator.setAppEnabled(TARGET, false) is CaptureOperationResult.Success)
            runCurrent()
            while (queue.isNotEmpty()) queue.removeFirst().run()
            val logs = directory.walkTopDown().filter { it.extension == "log" }.joinToString("\n") { it.readText() }
            assertTrue(logs.contains("stage=rule_management"))
            if (fault != Fault.NONE) assertTrue(logs.contains("event=journal_write_failures"))
            assertFalse(logs.contains("diagnostic-notification"))
            println("JOURNAL_SERVICE fault=$fault notifications=${notifications.size} action=CLICK persisted=true recovered=true")
        }
    }

    private fun cancellationScenario(mode: ServiceDiagnosticFaults.Mode) = runTest {
        ServiceDiagnosticFaults.install(mode)
        withService { f ->
            f.connect()
            val before = f.state()
            val entered = CompletableDeferred<Unit>()
            f.captureData.beforeUpdate = { entered.complete(Unit); awaitCancellation() }
            val operation = async { f.container.ruleCaptureCoordinator.cancel(SESSION_ID) }
            runCurrent(); entered.await()
            operation.cancel(CancellationException("business-cancel")); runCurrent()
            try { operation.await(); fail("业务取消必须传播") } catch (_: CancellationException) { }
            assertEquals(before, f.state())
            f.captureData.beforeUpdate = {}
            assertTrue(f.container.ruleCaptureCoordinator.cancel(SESSION_ID) is CaptureOperationResult.Success)
            runCurrent()
            assertNull(f.state().activeSession)
            f.installTargetWindow(); f.sendTargetWindowEvent(); advanceTimeBy(120); runCurrent()
            assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(f.nodes.target).performedActions)
        }
    }

    private fun gestureScenario(mode: ServiceDiagnosticFaults.Mode) = runTest {
        ServiceDiagnosticFaults.install(mode)
        withService { f ->
            f.connect(); f.installTargetWindow(); f.shadow.setCanDispatchGestures(true)
            val gateway = AccessibilityPageGateway(f.service, { setOf(TARGET.packageName) },
                object : MonotonicClock { override fun elapsedRealtimeMs() = f.now() }, { f.identity })
            val completed = async { gateway.gestureAtCenter(f.identity, f.nodes.selector, f.now() + 1000) }
            runCurrent()
            val first = f.shadow.gesturesDispatched.last()
            first.callback()!!.onCompleted(first.description())
            assertEquals(GestureResult.Completed, completed.await())
            val cancelled = async { gateway.gestureAtCenter(f.identity, f.nodes.selector, f.now() + 1000) }
            runCurrent()
            val second = f.shadow.gesturesDispatched.last()
            second.callback()!!.onCancelled(second.description())
            assertEquals(GestureResult.Cancelled, cancelled.await())
            val abandoned = async { gateway.gestureAtCenter(f.identity, f.nodes.selector, f.now() + 1000) }
            runCurrent()
            val third = f.shadow.gesturesDispatched.last()
            abandoned.cancel(); runCurrent()
            third.callback()!!.onCompleted(third.description())
            assertTrue(abandoned.isCancelled)
            assertTrue(f.container.ruleCaptureCoordinator.cancel(SESSION_ID) is CaptureOperationResult.Success)
            runCurrent()
            assertNull(f.state().activeSession)
        }
    }

    private suspend fun TestScope.withService(block: suspend TestScope.(FlashSkipAccessibilityServiceLifecycleTest.ServiceFixture) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = FlashSkipAccessibilityServiceLifecycleTest.ServiceFixture(this, temporary.newFolder(), CapturePhase.CAPTURED, 60_000)
        val mode = ServiceDiagnosticFaults.mode
        try { f.seed(); block(f) } finally {
            f.captureData.beforeUpdate = {}
            f.destroy(); runCurrent(); f.job.cancelAndJoin(); Dispatchers.resetMain()
            ServiceDiagnosticFaults.finish("continuation-$mode", mode == ServiceDiagnosticFaults.Mode.BACKEND || mode == ServiceDiagnosticFaults.Mode.SINK)
        }
    }
}
