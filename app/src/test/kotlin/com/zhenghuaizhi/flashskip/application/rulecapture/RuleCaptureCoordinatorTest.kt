package com.zhenghuaizhi.flashskip.application.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.AppVersion
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipPreferences
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipStore
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.RuleCaptureStore
import com.zhenghuaizhi.flashskip.domain.ports.WallClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuleCaptureCoordinatorTest {
    @Test
    fun completeFlowDisablesSwitchAndCommitsPendingSample() = runTest {
        val fixture = Fixture(this)
        val sessionId = fixture.coordinator.requestStart(TargetAppId.QQ_MUSIC, VERSION)
        assertFalse(fixture.flashStore.state.value.enabledApps.getValue(TargetAppId.QQ_MUSIC))
        fixture.coordinator.markConfigured(sessionId)
        assertEquals(CapturePhase.WAITING_FOR_TARGET, fixture.captureStore.state.value.activeSession?.phase)

        fixture.coordinator.observeTarget(
            sessionId,
            VERSION,
            ACTIVITY,
            windowId = 5,
            enteredAtElapsedMs = 100,
        )
        fixture.coordinator.recordCaptured(sessionId, sample(sessionId))
        assertEquals(CapturePhase.CAPTURED, fixture.captureStore.state.value.activeSession?.phase)
        fixture.coordinator.finish(sessionId)

        assertNull(fixture.captureStore.state.value.activeSession)
        assertEquals(listOf(sample(sessionId)), fixture.captureStore.state.value.samples)
    }

    @Test
    fun timeoutsAndVersionChangesBecomeExplicitFailures() = runTest {
        val fixture = Fixture(this)
        val timeoutId = fixture.coordinator.requestStart(TargetAppId.QQ_MUSIC, VERSION)
        fixture.coordinator.markConfigured(timeoutId)
        fixture.clock.wall = 100_000
        fixture.coordinator.expireActive()
        assertEquals(CaptureFailure.TARGET_TIMEOUT, fixture.captureStore.state.value.activeSession?.failure)

        val changedId = fixture.coordinator.requestStart(TargetAppId.QQ_MUSIC, VERSION)
        fixture.coordinator.markConfigured(changedId)
        fixture.coordinator.observeTarget(
            changedId,
            VERSION.copy(versionCode = 99),
            ACTIVITY,
            5,
            100,
        )
        assertEquals(
            CaptureFailure.APP_VERSION_CHANGED,
            fixture.captureStore.state.value.activeSession?.failure,
        )
    }

    @Test
    fun eachAppVersionKeepsOnlyMostRecentTenSamples() = runTest {
        val fixture = Fixture(this)
        repeat(11) { index ->
            fixture.clock.wall = 1_000L + index
            val id = fixture.coordinator.requestStart(TargetAppId.QQ_MUSIC, VERSION)
            fixture.coordinator.markConfigured(id)
            fixture.coordinator.observeTarget(id, VERSION, ACTIVITY, 5, 100)
            fixture.coordinator.recordCaptured(id, sample(id))
            fixture.coordinator.finish(id)
        }

        val samples = fixture.captureStore.state.value.samples
        assertEquals(10, samples.size)
        assertEquals(samples.map { it.id }.distinct().size, samples.size)
    }

    @Test
    fun sessionsStartedWithinSameMillisecondStillReceiveUniqueIds() = runTest {
        val fixture = Fixture(this)
        fixture.clock.wall = 5_000

        val first = fixture.coordinator.requestStart(TargetAppId.QQ_MUSIC, VERSION)
        val second = fixture.coordinator.requestStart(TargetAppId.QQ_MUSIC, VERSION)

        assertEquals(first + 1, second)
    }

    @Test
    fun targetPackageChangeDisablesSwitchAndInvalidatesActiveCapture() = runTest {
        val fixture = Fixture(this)
        val sessionId = fixture.coordinator.requestStart(TargetAppId.QQ_MUSIC, VERSION)
        fixture.coordinator.markConfigured(sessionId)
        fixture.coordinator.targetPackageChanged(TargetAppId.QQ_MUSIC)
        runCurrent()

        assertFalse(fixture.flashStore.state.value.enabledApps.getValue(TargetAppId.QQ_MUSIC))
        assertEquals(
            CaptureFailure.APP_VERSION_CHANGED,
            fixture.captureStore.state.value.activeSession?.failure,
        )
    }

    private class Fixture(scope: kotlinx.coroutines.CoroutineScope) {
        val captureStore = FakeCaptureStore()
        val flashStore = FakeFlashStore()
        val clock = FakeClock()
        val coordinator = RuleCaptureCoordinator(
            captureStore,
            flashStore,
            clock,
            clock,
            scope,
        )
    }

    private class FakeCaptureStore : RuleCaptureStore {
        override val state = MutableStateFlow(RuleCaptureState())
        override suspend fun update(transform: (RuleCaptureState) -> RuleCaptureState) {
            state.value = transform(state.value)
        }
    }

    private class FakeFlashStore : FlashSkipStore {
        val state = MutableStateFlow(
            FlashSkipPreferences(
                enabledApps = TargetAppId.entries.associateWith { true },
            ),
        )
        override val preferences = state
        override suspend fun setAppEnabled(targetAppId: TargetAppId, enabled: Boolean) {
            state.value = state.value.copy(enabledApps = state.value.enabledApps + (targetAppId to enabled))
        }
        override suspend fun isAppEnabled(targetAppId: TargetAppId): Boolean =
            state.value.enabledApps[targetAppId] == true
        override suspend fun appendRecord(record: ActionRecord) = Unit
    }

    private class FakeClock : MonotonicClock, WallClock {
        var elapsed = 0L
        var wall = 1_000L
        override fun elapsedRealtimeMs(): Long = elapsed
        override fun currentTimeMillis(): Long = wall
    }

    private companion object {
        val VERSION = AppVersion("13.2.0", 42)
        const val ACTIVITY = "com.tencent.qqmusic.activity.AppStarterActivity"

        fun sample(id: Long) = CaptureSample(
            id = id,
            targetAppId = TargetAppId.QQ_MUSIC,
            appVersion = VERSION,
            activityName = ACTIVITY,
            clickedAfterTargetMs = 1_000,
            capturedAtEpochMs = 2_000,
            nodes = listOf(
                CapturedNode(
                    resourceId = "com.tencent.qqmusic:id/close",
                    visibleToUser = true,
                    hasUsableBounds = true,
                ),
            ),
            preClickUniqueSelectors = emptyList(),
        )
    }
}
