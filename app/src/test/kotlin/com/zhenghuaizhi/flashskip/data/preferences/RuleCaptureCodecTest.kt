package com.zhenghuaizhi.flashskip.data.preferences

import com.zhenghuaizhi.flashskip.domain.model.AppVersion
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import org.junit.Assert.assertEquals
import org.junit.Test

class RuleCaptureCodecTest {
    @Test
    fun roundTripPreservesSamplesAndPendingSession() {
        val sample = sample(7)
        val state = RuleCaptureState(
            samples = listOf(sample),
            activeSession = CaptureSession(
                id = 8,
                targetAppId = TargetAppId.QQ_MUSIC,
                packageName = "com.tencent.qqmusic",
                appVersion = VERSION,
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
            RuleCaptureCodec.decodeOrEmpty("{\"version\":99,\"samples\":[]}"),
        )
    }

    private fun sample(id: Long) = CaptureSample(
        id = id,
        targetAppId = TargetAppId.QQ_MUSIC,
        appVersion = VERSION,
        activityName = "com.tencent.qqmusic.activity.AppStarterActivity",
        clickedAfterTargetMs = 1_500,
        capturedAtEpochMs = 20_000,
        nodes = listOf(
            CapturedNode(
                resourceId = "com.tencent.qqmusic:id/close",
                visibleToUser = true,
                hasUsableBounds = true,
            ),
        ),
        preClickUniqueSelectors = listOf(
            CapturedSelector(resourceId = "com.tencent.qqmusic:id/close"),
        ),
    )

    private companion object {
        val VERSION = AppVersion("13.2.0", 42)
    }
}
