package com.zhenghuaizhi.flashskip.data.preferences

import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RuleCaptureCodecTest {
    @Test
    fun roundTripPreservesSamplesAndPendingSession() {
        val sample = sample(7)
        val state = RuleCaptureState(
            samples = listOf(sample),
            activeSession = CaptureSession(
                id = 8,
                targetAppId = TargetAppId("com.example.music"),
                packageName = "com.example.music",
                phase = CapturePhase.CAPTURED,
                phaseStartedAtEpochMs = 200,
                deadlineAtEpochMs = 800,
                activityName = sample.activityName,
                windowId = 3,
                targetEnteredAtElapsedMs = 100,
                pendingSample = sample.copy(id = 8),
            ),
        )

        assertEquals(state, RuleCaptureCodec.decodeOrEmpty(RuleCaptureCodec.encode(state)))
    }

    @Test
    fun corruptOrUnknownDataSafelyFallsBackToEmpty() {
        assertEquals(RuleCaptureState(), RuleCaptureCodec.decodeOrEmpty("not-json"))
        assertEquals(
            RuleCaptureState(),
            RuleCaptureCodec.decodeOrEmpty("{\"version\":1,\"samples\":[]}"),
        )
        assertEquals(
            RuleCaptureState(),
            RuleCaptureCodec.decodeOrEmpty("{\"version\":99,\"samples\":[]}"),
        )
    }

    @Test
    fun completedSamplesDoNotPersistTargetAppVersion() {
        val encoded = RuleCaptureCodec.encode(RuleCaptureState(samples = listOf(sample(7))))

        assertFalse(encoded.contains("\"appVersion\""))
    }

    private fun sample(id: Long) = CaptureSample(
        id = id,
        targetAppId = TargetAppId("com.example.music"),
        activityName = "com.example.music.activity.AppStarterActivity",
        clickedAfterTargetMs = 1_500,
        capturedAtEpochMs = 20_000,
        nodes = listOf(
            CapturedNode(
                resourceId = "com.example.music:id/close",
                visibleToUser = true,
                hasUsableBounds = true,
            ),
        ),
        preClickUniqueSelectors = listOf(
            CapturedSelector(
                text = "跳过",
                ancestorResourceId = "com.example.music:id/jap",
                ancestorDistance = 1,
            ),
        ),
    )
}
