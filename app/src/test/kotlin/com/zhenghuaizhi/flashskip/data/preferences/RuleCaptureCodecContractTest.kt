package com.zhenghuaizhi.flashskip.data.preferences

import com.zhenghuaizhi.flashskip.domain.model.*
import com.zhenghuaizhi.flashskip.testing.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RuleCaptureCodecContractTest {
    @Test fun capturedStateRoundTripsAllBusinessPayloads() = runTest {
        val state = CaptureSaveFixture(backgroundScope, testScheduler).initialCapture
        assertEquals(state, RuleCaptureCodec.decodeOrEmpty(RuleCaptureCodec.encode(state)))
    }

    @Test fun confirmationStateRoundTripsAllBusinessPayloads() = runTest {
        val state = CaptureSaveFixture(backgroundScope, testScheduler, CapturePhase.AWAITING_CONFIRMATION).initialCapture
        assertEquals(state, RuleCaptureCodec.decodeOrEmpty(RuleCaptureCodec.encode(state)))
    }

    @Test fun historicalV2DefaultsEvidenceAndOptionalCollections() {
        val raw = """{"version":2,"samples":[{"id":8,"targetPackageName":"com.example.target","targetAppName":"old","activityName":"Launch","clickedAfterTargetMs":3,"capturedAtEpochMs":7,"nodes":[],"preClickUniqueSelectors":[]}],"activeSession":{"id":9,"phase":"CONFIGURING","phaseStartedAtEpochMs":1,"deadlineAtEpochMs":100}}"""
        val state = RuleCaptureCodec.decodeOrEmpty(raw)
        assertEquals(CaptureEvidence.ACCESSIBILITY_CLICK, state.samples.single().evidence)
        assertFalse(state.activeSession!!.candidateClickListeningEnabled)
        assertNull(state.activeSession.pendingObservedCandidate)
        assertTrue(state.semanticDiffEvidence.isEmpty())
    }

    @Test fun firstDamagedSampleIsIsolated() = damagedSample(0)
    @Test fun middleDamagedSampleIsIsolated() = damagedSample(1)
    @Test fun lastDamagedSampleIsIsolated() = damagedSample(2)

    @Test fun damagedSemanticEvidenceDoesNotDiscardSamplesOrOtherEvidence() = runTest {
        val state = CaptureSaveFixture(backgroundScope, testScheduler).initialCapture
        val json = JSONObject(RuleCaptureCodec.encode(state))
        json.getJSONArray("semanticDiffEvidence").getJSONObject(0).remove("targetPackageName")
        val decoded = RuleCaptureCodec.decodeOrEmpty(json.toString())
        assertEquals(state.copy(semanticDiffEvidence = state.semanticDiffEvidence.drop(1)), decoded)
    }

    @Test fun damagedSessionDoesNotDiscardValidSavedSamples() = runTest {
        val state = CaptureSaveFixture(backgroundScope, testScheduler).initialCapture
        val json = JSONObject(RuleCaptureCodec.encode(state))
        json.getJSONObject("activeSession").put("phase", "FUTURE")
        assertEquals(state.samples, RuleCaptureCodec.decodeOrEmpty(json.toString()).samples)
    }

    @Test fun damagedOptionalDiagnosticsDoNotDiscardSession() = runTest {
        val state = CaptureSaveFixture(backgroundScope, testScheduler).initialCapture
        val json = JSONObject(RuleCaptureCodec.encode(state))
        json.getJSONObject("activeSession").put("failureDiagnostics", JSONObject().put("sourceAvailable", "bad"))
        json.getJSONObject("activeSession").put("targetWindowDiagnostics", JSONObject().put("outcome", "FUTURE"))
        assertEquals(state, RuleCaptureCodec.decodeOrEmpty(json.toString()))
    }

    @Test fun unknownAdditiveFieldsAreIgnored() {
        val state = RuleCaptureState(samples = listOf(captureSample()))
        val json = JSONObject(RuleCaptureCodec.encode(state)).put("future", JSONObject())
        json.getJSONArray("samples").getJSONObject(0).put("future", 3)
        assertEquals(state, RuleCaptureCodec.decodeOrEmpty(json.toString()))
    }

    @Test fun absentAndBlankStateAreEmptyWithoutInvalidDiagnostic() {
        var calls = 0
        assertEquals(RuleCaptureState(), RuleCaptureCodec.decodeOrEmpty(null) { calls++ })
        assertEquals(RuleCaptureState(), RuleCaptureCodec.decodeOrEmpty(" ") { calls++ })
        assertEquals(0, calls)
    }

    private fun damagedSample(index: Int) {
        val samples = (1L..3).map { captureSample(it) }
        val json = JSONObject(RuleCaptureCodec.encode(RuleCaptureState(samples = samples)))
        json.getJSONArray("samples").getJSONObject(index).put("targetPackageName", "!")
        assertEquals(samples.filterIndexed { i, _ -> i != index }, RuleCaptureCodec.decodeOrEmpty(json.toString()).samples)
    }
}
