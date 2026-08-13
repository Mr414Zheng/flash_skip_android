package com.zhenghuaizhi.flashskip.data.preferences

import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailureDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CaptureEvidence
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryOutcome
import com.zhenghuaizhi.flashskip.domain.model.ObservedDismissCandidate
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.SnapshotRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.SnapshotRecoveryOutcome
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowRecognitionOutcome
import com.zhenghuaizhi.flashskip.domain.model.TraversalLimitReason
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

    @Test
    fun sampleWithoutEvidenceDecodesAsAccessibilityClick() {
        val legacy = RuleCaptureCodec.encode(RuleCaptureState(samples = listOf(sample(7))))
            .replace(",\"evidence\":\"ACCESSIBILITY_CLICK\"", "")

        val decoded = RuleCaptureCodec.decodeOrEmpty(legacy)

        assertEquals(CaptureEvidence.ACCESSIBILITY_CLICK, decoded.samples.single().evidence)
    }

    @Test
    fun roundTripPreservesStructuredFailureDiagnostics() {
        val diagnostics = CaptureFailureDiagnostics(
            sourceAvailable = false,
            eventTextCount = 1,
            eventHasContentDescription = false,
            immediateRecovery = ClickRecoveryDiagnostics(
                outcome = ClickRecoveryOutcome.TRAVERSAL_LIMIT,
                visitedNodeCount = 501,
                maxDepth = 17,
                elapsedMs = 82,
                semanticNodeCount = 420,
                matchedNodeCount = 0,
                knownDismissNodeCount = 1,
                selectorCandidateNodeCount = 0,
                traversalLimitReason = TraversalLimitReason.NODE_COUNT,
            ),
            preClickProbe = ClickRecoveryDiagnostics(
                outcome = ClickRecoveryOutcome.NO_MATCH,
                visitedNodeCount = 300,
                maxDepth = 12,
                elapsedMs = 45,
                semanticNodeCount = 250,
            ),
            snapshotRecovery = SnapshotRecoveryDiagnostics(
                outcome = SnapshotRecoveryOutcome.NOT_AVAILABLE,
            ),
            lastWindowVerifiedAgeMs = 1_500,
            lastContentChangedAgeMs = null,
        )
        val state = RuleCaptureState(
            activeSession = CaptureSession(
                id = 9,
                targetAppId = TargetAppId("com.example.maps"),
                packageName = "com.example.maps",
                phase = CapturePhase.FAILED,
                phaseStartedAtEpochMs = 500,
                deadlineAtEpochMs = 500,
                failure = CaptureFailure.CLICK_SOURCE_UNAVAILABLE,
                failureDiagnostics = diagnostics,
                targetWindowDiagnostics = TargetWindowDiagnostics(
                    windowStateEventCount = 2,
                    contentChangedEventCount = 8,
                    lastPackageName = "com.example.maps",
                    lastClassName = "com.example.maps.MainActivity",
                    lastEventWindowId = 4,
                    lastWindowCount = 3,
                    lastMatchingWindowCount = 1,
                    lastMatchingWindowType = 1,
                    lastMatchingWindowActive = true,
                    lastOutcome = TargetWindowRecognitionOutcome.VERIFIED,
                    lastEventAgeMs = 25,
                ),
            ),
        )

        assertEquals(state, RuleCaptureCodec.decodeOrEmpty(RuleCaptureCodec.encode(state)))
    }

    @Test
    fun roundTripPreservesUnconfirmedCandidateWithoutPublishingSample() {
        val sourceSample = sample(10)
        val candidate = ObservedDismissCandidate(
            id = 10,
            targetAppId = sourceSample.targetAppId,
            targetAppName = "示例地图",
            activityName = sourceSample.activityName,
            observedAfterTargetMs = 700,
            observedAtEpochMs = 20_000,
            nodes = sourceSample.nodes,
            preClickUniqueSelectors = sourceSample.preClickUniqueSelectors,
        )
        val state = RuleCaptureState(
            activeSession = CaptureSession(
                id = 10,
                targetAppId = candidate.targetAppId,
                targetAppName = candidate.targetAppName,
                packageName = candidate.targetAppId.packageName,
                phase = CapturePhase.AWAITING_CONFIRMATION,
                phaseStartedAtEpochMs = 20_000,
                deadlineAtEpochMs = 620_000,
                activityName = candidate.activityName,
                pendingObservedCandidate = candidate,
            ),
        )

        val decoded = RuleCaptureCodec.decodeOrEmpty(RuleCaptureCodec.encode(state))

        assertEquals(state, decoded)
        assertEquals(emptyList<CaptureSample>(), decoded.samples)
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
