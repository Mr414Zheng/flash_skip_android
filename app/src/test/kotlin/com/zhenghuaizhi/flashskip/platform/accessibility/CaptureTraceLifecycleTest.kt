package com.zhenghuaizhi.flashskip.platform.accessibility

import android.os.Looper
import com.zhenghuaizhi.flashskip.FlashSkipApplication
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleCaptureCoordinator
import com.zhenghuaizhi.flashskip.domain.model.CaptureEvidence
import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryOutcome
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticScanPurpose
import com.zhenghuaizhi.flashskip.domain.model.ObservedDismissCandidate
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.RuleCaptureStore
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipPreferences
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = FlashSkipApplication::class)
class CaptureTraceLifecycleTest {
    private lateinit var service: FlashSkipAccessibilityService
    private lateinit var adapter: AccessibilityCaptureAdapter
    private lateinit var scope: CoroutineScope
    private lateinit var tracker: TargetWindowDiagnosticsTracker
    private var current = RuleCaptureState()

    @Before
    fun setUp() {
        CaptureTraceLog.state(null)
        service = Robolectric.buildService(FlashSkipAccessibilityService::class.java).get()
        val application = RuntimeEnvironment.getApplication() as FlashSkipApplication
        assertTrue(application.filesDir.canonicalPath.startsWith(File(requireNotNull(System.getProperty("java.io.tmpdir"))).canonicalPath))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        adapter = AccessibilityCaptureAdapter(
            service,
            application.container.ruleCaptureCoordinator,
            scope,
            stateProvider = { current },
            targetWindowDiagnosticsProvider = { null },
            targetWindowResolutionRecorder = {},
        )
        ReflectionHelpers.setField(service, "captureAdapter", adapter)
        tracker = ReflectionHelpers.getField(service, "targetWindowDiagnosticsTracker")
        enter(session())
        val trail: CaptureDiagnosticTrail = ReflectionHelpers.getField(adapter, "diagnosticTrail")
        trail.recordScan(SESSION_ID, 30, 32, 4, 3, 4, CaptureDiagnosticScanPurpose.PRE_CLICK_REFRESH, ClickRecoveryDiagnostics(ClickRecoveryOutcome.SUCCESS, explicitDismissNodeCount = 1))
        tracker.recordContentChanged("private-package", 30)
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        scope.cancel()
        service.onDestroy()
        CaptureTraceLog.state(null)
        val container = (RuntimeEnvironment.getApplication() as FlashSkipApplication).container
        ReflectionHelpers.getField<CoroutineScope>(container, "applicationScope").cancel()
    }

    @Test
    fun failedThenCancelKeepsTrailAndRepeatedStateDoesNotRepeatConclusion() {
        val failed = session().copy(phase = CapturePhase.FAILED, failure = CaptureFailure.CLICK_TIMEOUT)
        transition(RuleCaptureState(activeSession = failed))
        transition(current)
        transition(RuleCaptureState())

        assertEquals(listOf("FAILED", "CANCELLED"), conclusions())
        assertEquals(1, logs("capture_scan_summary").size)
        assertEquals(1, logs("capture_recent_record").size)
        assertTrue(logs("capture_scan_summary").single().contains("scans=1 complete=1"))
        assertTrue(logs("capture_summary").last().contains("trailUnchanged=true"))
        assertTrue(logs("target_window_summary").last().contains("contentEvents=1"))
        assertTrue(logs("target_window_summary").first().contains("contentEvents=1 clickEvents=0"))
    }

    @Test
    @Config(shadows = [FlashSkipAccessibilityServiceTest.ConfigurationShadow::class])
    fun actualStateCollectorSummarizesBeforeResettingTheCaptureContext() {
        val container = (RuntimeEnvironment.getApplication() as FlashSkipApplication).container
        val now = AndroidWallClock.currentTimeMillis()
        val states = MutableStateFlow(
            RuleCaptureState(activeSession = session().copy(
                phase = CapturePhase.CONFIGURING,
                phaseStartedAtEpochMs = now,
                deadlineAtEpochMs = now + 30_000,
            )),
        )
        val captureStore = object : RuleCaptureStore {
            override val state = states
            override suspend fun update(transform: (RuleCaptureState) -> RuleCaptureState) {
                states.value = transform(states.value)
            }
        }
        val preferencesStore = object : FlashSkipStore {
            override val preferences = MutableStateFlow(FlashSkipPreferences())

            override suspend fun setAppEnabled(targetAppId: TargetAppId, enabled: Boolean) {
                preferences.value = preferences.value.copy(
                    enabledApps = preferences.value.enabledApps + (targetAppId to enabled),
                )
            }

            override suspend fun isAppEnabled(targetAppId: TargetAppId): Boolean =
                preferences.value.enabledApps[targetAppId] == true

            override suspend fun appendRecord(record: ActionRecord) {
                preferences.value = preferences.value.copy(records = preferences.value.records + record)
            }
        }
        val coordinator = RuleCaptureCoordinator(
            captureStore,
            preferencesStore,
            AndroidMonotonicClock,
            AndroidWallClock,
            scope,
            container.ruleCatalog,
        )
        ReflectionHelpers.setField(container, "ruleCaptureCoordinator", coordinator)
        ReflectionHelpers.callInstanceMethod<Unit>(
            service,
            "observeCaptureState",
            ReflectionHelpers.ClassParameter.from(CoroutineScope::class.java, scope),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            CapturePhase.WAITING_FOR_TARGET,
            ReflectionHelpers.getField<RuleCaptureState>(service, "latestCaptureState").activeSession?.phase,
        )
        ShadowLog.clear()

        states.value = states.value.copy(activeSession = states.value.activeSession?.copy(
            phase = CapturePhase.FAILED,
            failure = CaptureFailure.CLICK_TIMEOUT,
        ))
        // 每个状态都须先被真实 collector 消费，避免 StateFlow 合并连续赋值掩盖终结顺序。
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            CapturePhase.FAILED,
            ReflectionHelpers.getField<RuleCaptureState>(service, "latestCaptureState").activeSession?.phase,
        )
        states.value = RuleCaptureState()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(null, ReflectionHelpers.getField<RuleCaptureState>(service, "latestCaptureState").activeSession)

        assertEquals(listOf("FAILED", "CANCELLED"), conclusions())
        assertEquals(1, logs("capture_scan_summary").size)
        assertEquals(1, logs("capture_recent_record").size)
        assertTrue(logs("capture_scan_summary").single().contains("scans=1 complete=1"))
        assertTrue(logs("capture_summary").last().contains("trailUnchanged=true"))
        assertTrue(logs("target_window_summary").last().contains("contentEvents=1"))
    }

    @Test
    fun candidateReturnThenSaveLogsActualManualSampleWithoutDuplicateTrail() {
        val candidate = candidate()
        enter(session().copy(phase = CapturePhase.AWAITING_CONFIRMATION, pendingObservedCandidate = candidate, candidateClickListeningEnabled = true))
        ShadowLog.clear()
        transition(current.copy(activeSession = current.activeSession?.copy(candidateClickListeningEnabled = false)))
        transition(RuleCaptureState(samples = listOf(sample(CaptureEvidence.USER_CONFIRMED_UNREPORTED_CLICK))))

        assertEquals(listOf("CANDIDATE_LISTENING_STOPPED", "SAVED"), conclusions())
        assertEquals(1, logs("capture_scan_summary").size)
        val saved = logs("sample_saved").single()
        assertTrue(saved.contains("evidence=USER_CONFIRMED_UNREPORTED_CLICK"))
        assertTrue(saved.contains("resourceIdNodes=0"))
        assertTrue(saved.contains("descriptionNodes=1"))
        assertTrue(saved.contains("pendingCandidate=false"))
        assertFalse(output().contains("private-"))
    }

    @Test
    fun actualClickCaptureThenSaveLogsBothConclusionsAndActualEvidence() {
        transition(RuleCaptureState(activeSession = session().copy(phase = CapturePhase.CAPTURED, pendingSample = sample(CaptureEvidence.ACCESSIBILITY_CLICK))))
        transition(RuleCaptureState(samples = listOf(sample(CaptureEvidence.ACCESSIBILITY_CLICK))))

        assertEquals(listOf("CLICK_CAPTURED", "SAVED"), conclusions())
        assertEquals(1, logs("capture_recent_record").size)
        assertTrue(logs("sample_saved").single().contains("evidence=ACCESSIBILITY_CLICK"))
    }

    @Test
    fun replacementSummarizesOldContextBeforeItsCountersReset() {
        transition(RuleCaptureState(activeSession = session().copy(id = SESSION_ID + 1)))

        assertEquals(listOf("REPLACED"), conclusions())
        assertTrue(logs("capture_scan_summary").single().contains("session=$SESSION_ID"))
        assertTrue(logs("target_window_summary").single().contains("contentEvents=1"))
        assertEquals(0, requireNotNull(tracker.snapshot(SESSION_ID + 1, 40)).contentChangedEventCount)
    }

    @Test
    fun destroySummarizesLiveTrailBeforeAdapterAndTrackerAreCleared() {
        service.onDestroy()

        assertEquals(listOf("SERVICE_DESTROYED"), conclusions())
        assertEquals(1, logs("capture_recent_record").size)
        assertTrue(logs("target_window_summary").single().contains("contentEvents=1"))
    }

    @Test
    fun reconnectSummarizesLiveTrailBeforeReplacingTheAdapter() {
        ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")

        assertTrue(conclusions().contains("SERVICE_RECONNECTED"))
        assertEquals(1, logs("capture_recent_record").size)
        assertTrue(logs("target_window_summary").first().contains("contentEvents=1"))
    }

    private fun enter(session: CaptureSession) {
        current = RuleCaptureState(activeSession = session)
        ReflectionHelpers.setField(service, "latestCaptureState", current)
        CaptureTraceLog.state(session)
        tracker.onSessionChanged(session.id)
        adapter.onStateChanged(current)
    }

    private fun transition(next: RuleCaptureState) {
        // 复用服务的实际结局判断，再模拟 collector 随后的上下文清理顺序。
        ReflectionHelpers.callInstanceMethod<Unit>(service, "traceCaptureTransition", ReflectionHelpers.ClassParameter.from(RuleCaptureState::class.java, current), ReflectionHelpers.ClassParameter.from(RuleCaptureState::class.java, next))
        current = next
        ReflectionHelpers.setField(service, "latestCaptureState", next)
        CaptureTraceLog.state(next.activeSession)
        tracker.onSessionChanged(next.activeSession?.id)
        adapter.onStateChanged(next)
    }

    private fun session() = CaptureSession(SESSION_ID, phase = CapturePhase.OBSERVING_CLICK, phaseStartedAtEpochMs = 0, deadlineAtEpochMs = 30_000)

    private fun candidate() = ObservedDismissCandidate(SESSION_ID, TargetAppId("private.package"), "private-app", "private-activity", 20, 0, listOf(CapturedNode(contentDescription = "private-description", clickable = true)), emptyList())

    private fun sample(evidence: CaptureEvidence) = CaptureSample(SESSION_ID, TargetAppId("private.package"), "private-app", "private-activity", 20, 0, candidate().nodes, emptyList(), evidence)

    private fun logs(stage: String): List<String> = ShadowLog.getLogsForTag(CaptureTraceLogger.TAG).map { it.msg }.filter { it.contains("stage=$stage ") }

    private fun output(): String = ShadowLog.getLogsForTag(CaptureTraceLogger.TAG).joinToString("\n") { it.msg }

    private fun conclusions(): List<String> = logs("capture_summary").map { it.substringAfter("conclusion=").substringBefore(' ') }

    private companion object {
        const val SESSION_ID = 7L
    }
}
