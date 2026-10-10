package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityRecord
import android.view.accessibility.AccessibilityWindowInfo
import com.zhenghuaizhi.flashskip.application.rulecapture.CaptureOperationResult
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleCaptureCoordinator
import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.CaptureEvidence
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipPreferences
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipStore
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.RuleCaptureStore
import com.zhenghuaizhi.flashskip.domain.ports.WallClock
import com.zhenghuaizhi.flashskip.domain.rules.RuleCatalog
import com.zhenghuaizhi.flashskip.domain.rules.RuleCatalogSnapshot
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION")
class AccessibilityCaptureAdapterCandidateClickTest {
    private lateinit var controller: ServiceController<TestAccessibilityService>
    private lateinit var window: AccessibilityWindowInfo
    private lateinit var root: AccessibilityNodeInfo
    private lateinit var skip: AccessibilityNodeInfo
    private val nodes = mutableListOf<AccessibilityNodeInfo>()

    @Before
    fun setUp() {
        clearAccessibilityPools()
        controller = Robolectric.buildService(TestAccessibilityService::class.java).create()
        window = AccessibilityWindowInfo.obtain()
        shadowOf(window).apply {
            setId(WINDOW_ID)
            setType(AccessibilityWindowInfo.TYPE_APPLICATION)
            setActive(true)
        }
        root = node("root", null)
        skip = node("skip", "跳过 05", clickable = true)
        shadowOf(root).addChild(skip)
        shadowOf(window).setRoot(root)
        controller.get().windowTemplate = window
    }

    @After
    fun tearDown() {
        controller.destroy()
        nodes.forEach(AccessibilityNodeInfo::recycle)
        nodes.clear()
        window.recycle()
        clearAccessibilityPools()
    }

    private fun clearAccessibilityPools() {
        // Android 对象池会复用携带默认 shadow 的实例，避免污染使用计数 shadow 的后续测试。
        listOf(
            AccessibilityEvent::class.java,
            AccessibilityRecord::class.java,
            AccessibilityWindowInfo::class.java,
            AccessibilityNodeInfo::class.java,
        ).forEach { type ->
            val field = type.declaredFields.firstOrNull { it.name == "sPool" } ?: return@forEach
            field.isAccessible = true
            if (AccessibilityRecord::class.java.isAssignableFrom(field.type)) {
                field.set(null, null)
                type.declaredFields.firstOrNull { it.name == "sPoolSize" }?.apply {
                    isAccessible = true
                    setInt(null, 0)
                }
                return@forEach
            }
            val pool = field.get(null) ?: return@forEach
            val acquire = pool.javaClass.methods.firstOrNull {
                it.name == "acquire" && it.parameterCount == 0
            } ?: return@forEach
            acquire.isAccessible = true
            while (acquire.invoke(pool) != null) {
                // 排空即可；不替换系统字段或修改其他测试的 shadow 配置。
            }
        }
    }

    @Test
    fun observedCandidateKeepsListeningAndUpgradesCountdownClickWithActualClickTime() = runTest {
        val fixture = fixture()
        fixture.observeWindow()
        runCurrent()
        val candidate = fixture.store.state.value.activeSession!!.pendingObservedCandidate
        assertTrue(fixture.adapter.timeoutDiagnostics(SESSION_ID).toString(), fixture.store.state.value.activeSession!!.listensForCandidateClick)
        assertEquals("跳过 05", candidate?.nodes?.first()?.text)
        fixture.adapter.onStateChanged(fixture.store.state.value)

        advanceTimeBy(2_000)
        skip.text = "跳过 03"
        fixture.click(skip)
        runCurrent()

        val session = fixture.store.state.value.activeSession!!
        assertEquals(
            ShadowLog.getLogsForTag(CaptureTraceLogger.TAG).joinToString("\n") { it.msg },
            CapturePhase.CAPTURED,
            session.phase,
        )
        assertFalse(session.listensForCandidateClick)
        assertNull(session.pendingObservedCandidate)
        val sample = session.pendingSample!!
        assertEquals(CaptureEvidence.ACCESSIBILITY_CLICK, sample.evidence)
        assertEquals("跳过 03", sample.nodes.first().text)
        assertEquals(2_000L, sample.clickedAfterTargetMs)
        assertEquals(3_000L, sample.capturedAtEpochMs)
    }

    @Test
    fun fastClickBeforeCandidatePersistenceSurvivesLaterWindowAndContentEvents() = runTest {
        val fixture = fixture()
        val releaseWrite = CompletableDeferred<Unit>()
        fixture.store.beforeUpdate = { releaseWrite.await() }
        fixture.observeWindow()
        runCurrent()
        assertEquals(CapturePhase.OBSERVING_CLICK, fixture.store.state.value.activeSession?.phase)

        // 候选已在回调栈冻结，异步落盘前的后续页面事件也不能改写它的宿主。
        fixture.observeWindow(windowId = WINDOW_ID + 1, activityName = "target.example.OtherActivity")
        fixture.event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, WINDOW_ID + 1) {
            fixture.adapter.onContentChanged(it, TARGET.packageName)
        }
        advanceTimeBy(100)
        fixture.click(skip)
        releaseWrite.complete(Unit)
        runCurrent()

        val sample = fixture.store.state.value.activeSession?.pendingSample
        assertEquals(CapturePhase.CAPTURED, fixture.store.state.value.activeSession?.phase)
        assertEquals(ACTIVITY, sample?.activityName)
        assertEquals(CaptureEvidence.ACCESSIBILITY_CLICK, sample?.evidence)
        assertEquals(100L, sample?.clickedAfterTargetMs)
    }

    @Test
    fun missingForeignAndUnrelatedClickSourcesPreserveCandidateAndReleaseClickLock() = runTest {
        val fixture = fixture()
        fixture.observeWindow()
        runCurrent()
        fixture.adapter.onStateChanged(fixture.store.state.value)
        val candidate = fixture.store.state.value.activeSession!!.pendingObservedCandidate
        val unrelated = node("buy", "立即购买", clickable = true)
        val foreign = node("skip", "跳过", clickable = true, packageName = "foreign.example")

        fixture.click(null)
        fixture.click(foreign)
        fixture.click(unrelated)
        fixture.click(skip, windowId = WINDOW_ID + 1)
        runCurrent()
        assertEquals(candidate, fixture.store.state.value.activeSession?.pendingObservedCandidate)
        assertEquals(CapturePhase.AWAITING_CONFIRMATION, fixture.store.state.value.activeSession?.phase)
        assertTrue(fixture.store.state.value.activeSession!!.listensForCandidateClick)

        fixture.click(skip)
        runCurrent()
        assertEquals(CaptureEvidence.ACCESSIBILITY_CLICK, fixture.store.state.value.activeSession?.pendingSample?.evidence)
    }

    @Test
    fun returnStopsCandidateClickListeningAndKeepsManualConfirmationEvidence() = runTest {
        val fixture = fixture()
        fixture.coordinator.markLeftFlashSkip(SESSION_ID)
        fixture.observeWindow()
        runCurrent()
        fixture.adapter.onStateChanged(fixture.store.state.value)
        val candidate = fixture.store.state.value.activeSession!!.pendingObservedCandidate

        assertEquals(CaptureOperationResult.Success(SESSION_ID), fixture.coordinator.reconcileReturn(SESSION_ID))
        fixture.adapter.onStateChanged(fixture.store.state.value)
        fixture.click(skip)
        runCurrent()
        assertFalse(fixture.store.state.value.activeSession!!.listensForCandidateClick)
        assertEquals(candidate, fixture.store.state.value.activeSession?.pendingObservedCandidate)
        assertNull(fixture.store.state.value.activeSession?.pendingSample)

        assertEquals(CaptureOperationResult.Success(), fixture.coordinator.finish(SESSION_ID))
        assertEquals(CaptureEvidence.USER_CONFIRMED_UNREPORTED_CLICK, fixture.store.state.value.samples.single().evidence)
    }

    @Test
    fun failedCandidateWriteReleasesLockSoSubsequentActualClickCanStillBeCaptured() = runTest {
        val fixture = fixture()
        var failNextWrite = true
        fixture.store.beforeUpdate = {
            if (failNextWrite) {
                failNextWrite = false
                throw IOException("candidate write failed")
            }
        }
        fixture.observeWindow()
        runCurrent()
        assertEquals(CapturePhase.OBSERVING_CLICK, fixture.store.state.value.activeSession?.phase)

        fixture.click(skip)
        runCurrent()

        assertEquals(CapturePhase.CAPTURED, fixture.store.state.value.activeSession?.phase)
        assertEquals(CaptureEvidence.ACCESSIBILITY_CLICK, fixture.store.state.value.activeSession?.pendingSample?.evidence)
    }

    @Test
    fun committedCandidateWriteFailureStillUpgradesAlreadyBufferedActualClick() = runTest {
        val fixture = fixture()
        val releaseWrite = CompletableDeferred<Unit>()
        fixture.store.beforeUpdate = { releaseWrite.await() }
        var failAfterCandidateCommit = true
        fixture.store.afterUpdate = { state ->
            if (failAfterCandidateCommit && state.activeSession?.listensForCandidateClick == true) {
                failAfterCandidateCommit = false
                throw IOException("candidate already committed")
            }
        }
        fixture.observeWindow()
        runCurrent()
        advanceTimeBy(100)
        fixture.click(skip)
        releaseWrite.complete(Unit)
        runCurrent()

        assertEquals(CapturePhase.CAPTURED, fixture.store.state.value.activeSession?.phase)
        assertEquals(CaptureEvidence.ACCESSIBILITY_CLICK, fixture.store.state.value.activeSession?.pendingSample?.evidence)
        assertEquals(100L, fixture.store.state.value.activeSession?.pendingSample?.clickedAfterTargetMs)
    }

    @Test
    fun failedCandidateWriteDoesNotLoseAnAlreadyValidatedBufferedClick() = runTest {
        val fixture = fixture()
        val releaseWrite = CompletableDeferred<Unit>()
        var failCandidateWrite = true
        fixture.store.beforeUpdate = {
            releaseWrite.await()
            if (failCandidateWrite) {
                failCandidateWrite = false
                throw IOException("candidate write failed")
            }
        }
        fixture.observeWindow()
        runCurrent()
        advanceTimeBy(100)
        fixture.click(skip)
        releaseWrite.complete(Unit)
        runCurrent()

        assertEquals(CapturePhase.CAPTURED, fixture.store.state.value.activeSession?.phase)
        assertEquals(CaptureEvidence.ACCESSIBILITY_CLICK, fixture.store.state.value.activeSession?.pendingSample?.evidence)
        assertEquals(100L, fixture.store.state.value.activeSession?.pendingSample?.clickedAfterTargetMs)
    }

    @Test
    fun returnedSessionCannotBeOverwrittenByBufferedClickRecoveryAfterWriteFailure() = runTest {
        val fixture = fixture()
        fixture.coordinator.markLeftFlashSkip(SESSION_ID)
        val releaseWrite = CompletableDeferred<Unit>()
        var failCandidateWrite = true
        fixture.store.beforeUpdate = {
            releaseWrite.await()
            if (failCandidateWrite) {
                failCandidateWrite = false
                throw IOException("candidate write failed")
            }
        }
        fixture.observeWindow()
        runCurrent()
        fixture.click(skip)
        val returned = async { fixture.coordinator.reconcileReturn(SESSION_ID) }
        runCurrent()
        releaseWrite.complete(Unit)
        runCurrent()

        assertEquals(CaptureOperationResult.Success(SESSION_ID), returned.await())
        assertEquals(CapturePhase.FAILED, fixture.store.state.value.activeSession?.phase)
        assertNull(fixture.store.state.value.activeSession?.pendingSample)
        assertNull(fixture.store.state.value.activeSession?.pendingObservedCandidate)
    }

    private fun TestScope.fixture(): Fixture {
        val store = TestCaptureStore(
            RuleCaptureState(
                activeSession = CaptureSession(
                    id = SESSION_ID,
                    targetAppId = TARGET,
                    targetAppName = "Target",
                    packageName = TARGET.packageName,
                    activityName = ACTIVITY,
                    windowId = WINDOW_ID,
                    targetEnteredAtElapsedMs = 0L,
                    phase = CapturePhase.OBSERVING_CLICK,
                    phaseStartedAtEpochMs = 1_000L,
                    deadlineAtEpochMs = 16_000L,
                ),
            ),
        )
        val coordinator = RuleCaptureCoordinator(
            captureStore = store,
            flashSkipStore = TestPreferencesStore(),
            monotonicClock = object : MonotonicClock {
                override fun elapsedRealtimeMs(): Long = testScheduler.currentTime
            },
            wallClock = object : WallClock {
                override fun currentTimeMillis(): Long = 1_000L + testScheduler.currentTime
            },
            applicationScope = backgroundScope,
            ruleCatalog = object : RuleCatalog {
                override val snapshot = MutableStateFlow(RuleCatalogSnapshot())
            },
        )
        val adapter = AccessibilityCaptureAdapter(
            service = controller.get(),
            coordinator = coordinator,
            scope = this,
            stateProvider = { store.state.value },
            targetWindowDiagnosticsProvider = { null },
            targetWindowResolutionRecorder = {},
        )
        adapter.onStateChanged(store.state.value)
        return Fixture(store, coordinator, adapter)
    }

    private fun node(
        id: String,
        text: String?,
        clickable: Boolean = false,
        packageName: String = TARGET.packageName,
    ): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        this.packageName = packageName
        viewIdResourceName = "$packageName:id/$id"
        this.text = text
        isClickable = clickable
        isVisibleToUser = true
        setBoundsInScreen(Rect(10, 10, 60, 60))
        shadowOf(this).setAccessibilityWindowInfo(this@AccessibilityCaptureAdapterCandidateClickTest.window)
    }.also { nodes += it }

    private class Fixture(
        val store: TestCaptureStore,
        val coordinator: RuleCaptureCoordinator,
        val adapter: AccessibilityCaptureAdapter,
    ) {
        fun observeWindow(windowId: Int = WINDOW_ID, activityName: String = ACTIVITY) {
            event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, windowId) {
                adapter.onVerifiedTargetWindow(it, TARGET.packageName, "Target", activityName)
            }
        }

        fun click(source: AccessibilityNodeInfo?, windowId: Int = WINDOW_ID) {
            event(AccessibilityEvent.TYPE_VIEW_CLICKED, windowId) {
                shadowOf(it).setSourceNode(source)
                adapter.onClicked(it, TARGET.packageName)
            }
        }

        fun event(type: Int, windowId: Int, block: (AccessibilityEvent) -> Unit) {
            val event = ReflectionHelpers.callConstructor(AccessibilityEvent::class.java).apply {
                eventType = type
                packageName = TARGET.packageName
            }
            shadowOf(event).setWindowId(windowId)
            try {
                block(event)
            } finally {
                event.recycle()
            }
        }
    }

    private class TestCaptureStore(initial: RuleCaptureState) : RuleCaptureStore {
        override val state = MutableStateFlow(initial)
        var beforeUpdate: suspend () -> Unit = {}
        var afterUpdate: (RuleCaptureState) -> Unit = {}

        override suspend fun update(transform: (RuleCaptureState) -> RuleCaptureState) {
            beforeUpdate()
            state.value = transform(state.value)
            afterUpdate(state.value)
        }
    }

    private class TestPreferencesStore : FlashSkipStore {
        override val preferences = MutableStateFlow(FlashSkipPreferences())

        override suspend fun setAppEnabled(targetAppId: TargetAppId, enabled: Boolean) {
            preferences.value = preferences.value.copy(enabledApps = mapOf(targetAppId to enabled))
        }

        override suspend fun isAppEnabled(targetAppId: TargetAppId): Boolean = false

        override suspend fun appendRecord(record: ActionRecord) = Unit
    }

    class TestAccessibilityService : AccessibilityService() {
        lateinit var windowTemplate: AccessibilityWindowInfo

        override fun getWindows(): List<AccessibilityWindowInfo> =
            listOf(AccessibilityWindowInfo.obtain(windowTemplate))

        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

        override fun onInterrupt() = Unit
    }

    private companion object {
        val TARGET = TargetAppId("target.example")
        const val ACTIVITY = "target.example.MainActivity"
        const val WINDOW_ID = 7
        const val SESSION_ID = 42L
    }
}
