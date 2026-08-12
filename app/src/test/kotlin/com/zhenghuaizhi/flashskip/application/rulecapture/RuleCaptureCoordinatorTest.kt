package com.zhenghuaizhi.flashskip.application.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuleCaptureCoordinatorTest {
    @Test
    fun genericSessionBindsFirstObservedAppAndCommitsSample() = runTest {
        val fixture = Fixture(this)
        val sessionId = fixture.coordinator.requestStart()
        fixture.coordinator.markConfigured(sessionId)
        fixture.coordinator.observeTarget(
            sessionId = sessionId,
            targetAppId = TARGET,
            targetAppName = "示例音乐",
            packageName = TARGET.packageName,
            activityName = ACTIVITY,
            windowId = 5,
            enteredAtElapsedMs = 100,
        )
        fixture.coordinator.recordCaptured(sessionId, sample(sessionId))
        fixture.coordinator.finish(sessionId)

        assertNull(fixture.captureStore.state.value.activeSession)
        assertEquals(listOf(sample(sessionId)), fixture.captureStore.state.value.samples)
        assertFalse(fixture.flashStore.state.value.enabledApps[TARGET] == true)
    }

    @Test
    fun everySuccessfulCaptureIsRetained() = runTest {
        val fixture = Fixture(this)
        repeat(11) { index ->
            val id = fixture.coordinator.requestStart()
            fixture.coordinator.markConfigured(id)
            fixture.coordinator.observeTarget(
                id,
                TARGET,
                "示例音乐",
                TARGET.packageName,
                ACTIVITY,
                5,
                100,
            )
            fixture.coordinator.recordCaptured(id, sample(id))
            fixture.coordinator.finish(id)
        }

        assertEquals(11, fixture.captureStore.state.value.samples.size)
    }

    private class Fixture(scope: kotlinx.coroutines.CoroutineScope) {
        val captureStore = FakeCaptureStore()
        val flashStore = FakeFlashStore()
        val clock = FakeClock()
        val coordinator = RuleCaptureCoordinator(captureStore, flashStore, clock, clock, scope)
    }

    private class FakeCaptureStore : RuleCaptureStore {
        override val state = MutableStateFlow(RuleCaptureState())
        override suspend fun update(transform: (RuleCaptureState) -> RuleCaptureState) {
            state.value = transform(state.value)
        }
    }

    private class FakeFlashStore : FlashSkipStore {
        val state = MutableStateFlow(FlashSkipPreferences())
        override val preferences = state
        override suspend fun setAppEnabled(targetAppId: TargetAppId, enabled: Boolean) {
            state.value = state.value.copy(enabledApps = state.value.enabledApps + (targetAppId to enabled))
        }
        override suspend fun isAppEnabled(targetAppId: TargetAppId): Boolean =
            state.value.enabledApps[targetAppId] == true
        override suspend fun appendRecord(record: ActionRecord) = Unit
    }

    private class FakeClock : MonotonicClock, WallClock {
        override fun elapsedRealtimeMs(): Long = 1_500
        override fun currentTimeMillis(): Long = 1_000
    }

    private companion object {
        val TARGET = TargetAppId("com.example.music")
        const val ACTIVITY = "com.example.music.activity.AppStarterActivity"

        fun sample(id: Long) = CaptureSample(
            id = id,
            targetAppId = TARGET,
            targetAppName = "示例音乐",
            activityName = ACTIVITY,
            clickedAfterTargetMs = 1_000,
            capturedAtEpochMs = 2_000 + id,
            nodes = listOf(
                CapturedNode(
                    resourceId = "com.example.music:id/close",
                    clickable = true,
                    visibleToUser = true,
                    hasUsableBounds = true,
                ),
            ),
            preClickUniqueSelectors = emptyList(),
        )
    }
}
