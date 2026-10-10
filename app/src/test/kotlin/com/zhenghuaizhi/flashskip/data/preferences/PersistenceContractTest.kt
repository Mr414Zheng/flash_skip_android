package com.zhenghuaizhi.flashskip.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zhenghuaizhi.flashskip.domain.model.ActionMethod
import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.ActionReason
import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.ActionObservationStatus
import com.zhenghuaizhi.flashskip.domain.model.CaptureEvidence
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailure
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticRecord
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticRecordKind
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticSummary
import com.zhenghuaizhi.flashskip.domain.model.CaptureDiagnosticTrace
import com.zhenghuaizhi.flashskip.domain.model.CaptureFailureDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.ObservedDismissCandidate
import com.zhenghuaizhi.flashskip.domain.model.ExecutionTiming
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.SemanticDiffEvidence
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowRecognitionOutcome
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryOutcome
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PersistenceContractTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val storeJobs = mutableListOf<Job>()
    private val target = TargetAppId("org.example.reader")

    @Test
    fun actionAcceptanceAndObservationRoundTripWithoutInvalidatingLegacyV2History() {
        for (method in ActionMethod.entries) {
            val record = ActionRecord(
                timestampEpochMs = 100, targetAppId = target, ruleId = "test-rule", method = method,
                outcome = ActionOutcome.ABANDONED, reason = ActionReason.CLICK_UNCONFIRMED,
                observation = ActionObservationStatus.TARGET_PRESENT,
            )
            val encoded = ActionRecordCodec.encode(listOf(record))
            assertEquals(listOf(record), ActionRecordCodec.decodeOrEmpty(encoded))
            val envelope = JSONObject(encoded)
            val stored = envelope.getJSONArray("records").getJSONObject(0)
            stored.remove("actionAccepted")
            stored.remove("observation")
            val legacy = ActionRecordCodec.decodeOrEmpty(envelope.toString()).single()
            assertTrue(legacy.actionAccepted)
            assertEquals(null, legacy.observation)
            stored.put("observation", "FUTURE_UNKNOWN_STATUS")
            assertEquals(legacy, ActionRecordCodec.decodeOrEmpty(envelope.toString()).single())
        }
    }

    @After
    fun tearDown() = runBlocking {
        storeJobs.forEach { it.cancelAndJoin() }
    }

    @Test
    fun legacyV2SampleWithoutNewOptionalEvidenceStillLoads() {
        val raw = """
            {
              "version": 2,
              "samples": [{
                "id": 7, "targetPackageName": "org.example.reader", "targetAppName": "Reader",
                "activityName": "org.example.reader.MainActivity",
                "clickedAfterTargetMs": 600, "capturedAtEpochMs": 1700000000000,
                "nodes": [{"resourceId": "org.example.reader:id/skip", "clickable": true,
                  "supportsActionClick": true, "visibleToUser": true, "hasUsableBounds": true}],
                "preClickUniqueSelectors": [{"resourceId": "org.example.reader:id/skip"}]
              }]
            }
        """.trimIndent()

        val state = RuleCaptureCodec.decodeOrEmpty(raw)

        assertEquals(listOf(sample(7)), state.samples)
        assertEquals(CaptureEvidence.ACCESSIBILITY_CLICK, state.samples.single().evidence)
        assertEquals(emptyList<SemanticDiffEvidence>(), state.semanticDiffEvidence)
        assertEquals(null, state.activeSession)
    }

    @Test
    fun pendingCaptureAndSemanticEvidenceSurviveV2RoundTrip() {
        val sample = sample(7).copy(evidence = CaptureEvidence.USER_CONFIRMED_UNREPORTED_CLICK)
        val state = RuleCaptureState(
            samples = listOf(sample(6)),
            activeSession = CaptureSession(
                id = 42,
                targetAppId = target,
                targetAppName = "Reader",
                packageName = target.packageName,
                phase = CapturePhase.CAPTURED,
                phaseStartedAtEpochMs = 1_700_000_000_000,
                deadlineAtEpochMs = 1_700_000_600_000,
                activityName = sample.activityName,
                windowId = 3,
                targetEnteredAtElapsedMs = 500,
                leftFlashSkipAtEpochMs = 1_700_000_010_000,
                pendingSample = sample,
            ),
            semanticDiffEvidence = listOf(
                SemanticDiffEvidence(target, sample.activityName, sample.preClickUniqueSelectors, 10),
            ),
        )

        assertEquals(state, RuleCaptureCodec.decodeOrEmpty(RuleCaptureCodec.encode(state)))
    }

    @Test
    fun candidateClickListenerIsOptInAcrossPersistence() {
        val candidate = ObservedDismissCandidate(
            id = 42,
            targetAppId = target,
            targetAppName = "Reader",
            activityName = "org.example.reader.MainActivity",
            observedAfterTargetMs = 500,
            observedAtEpochMs = 1_700_000_000_000,
            nodes = listOf(
                CapturedNode(
                    resourceId = "org.example.reader:id/skip",
                    text = "Skip",
                    clickable = true,
                    visibleToUser = true,
                    hasUsableBounds = true,
                ),
            ),
            preClickUniqueSelectors = emptyList(),
        )
        val state = RuleCaptureState(
            activeSession = CaptureSession(
                id = 42,
                targetAppId = target,
                targetAppName = "Reader",
                packageName = target.packageName,
                phase = CapturePhase.AWAITING_CONFIRMATION,
                phaseStartedAtEpochMs = 1_700_000_000_000,
                deadlineAtEpochMs = 1_700_000_600_000,
                activityName = candidate.activityName,
                candidateClickListeningEnabled = true,
                pendingObservedCandidate = candidate,
            ),
        )
        val encoded = RuleCaptureCodec.encode(state)

        assertEquals(state, RuleCaptureCodec.decodeOrEmpty(encoded))
        val legacy = JSONObject(encoded).apply {
            getJSONObject("activeSession").remove("candidateClickListeningEnabled")
        }.toString()
        val loadedLegacySession = requireNotNull(RuleCaptureCodec.decodeOrEmpty(legacy).activeSession)
        assertFalse(loadedLegacySession.candidateClickListeningEnabled)
        assertFalse(loadedLegacySession.listensForCandidateClick)
    }

    @Test
    fun returnedFailureMetadataRoundTripsAndOlderSessionWithoutDepartureLoads() {
        val trace = CaptureDiagnosticTrace.snapshot(
            summary = CaptureDiagnosticSummary(
                totalRecordCount = 1,
                scanCount = 1,
                completedScanCount = 1,
                maxEffectiveCandidateCount = 2,
                windowResolutionCount = 2,
                windowResolutionRetryCount = 1,
                windowResolutionOutcomeCounts = mapOf(
                    TargetWindowRecognitionOutcome.CHILD_WINDOW_PENDING to 1,
                    TargetWindowRecognitionOutcome.WINDOW_ROOT_MISMATCH to 1,
                ),
                hostWindowUpdateCount = 1,
            ),
            records = listOf(
                CaptureDiagnosticRecord(
                    elapsedMs = 12,
                    kind = CaptureDiagnosticRecordKind.SCAN,
                    eventId = 2048,
                    eventWindowId = 3,
                    hostWindowId = 3,
                    rootWindowId = 3,
                    scanOutcome = ClickRecoveryOutcome.NO_MATCH,
                    scanCompleted = true,
                ),
            ),
        )
        val returned = RuleCaptureState(
            activeSession = CaptureSession(
                id = 42,
                targetAppId = target,
                phase = CapturePhase.FAILED,
                phaseStartedAtEpochMs = 1_700_000_010_000,
                deadlineAtEpochMs = 1_700_000_010_000,
                failure = CaptureFailure.DISMISS_NOT_CAPTURED,
                failureDiagnostics = CaptureFailureDiagnostics(
                    sourceAvailable = false,
                    eventTextCount = 0,
                    eventHasContentDescription = false,
                    sessionTrace = trace,
                ),
                targetWindowDiagnostics = TargetWindowDiagnostics(
                    viewClickedEventCount = 1,
                ),
            ),
        )
        val legacySession = """
            {"version":2,"samples":[],"activeSession":{
              "id":42,"phase":"WAITING_FOR_TARGET","phaseStartedAtEpochMs":1,
              "deadlineAtEpochMs":90001
            }}
        """.trimIndent()
        val legacyFailureSession = """
            {"version":2,"samples":[],"activeSession":{
              "id":43,"phase":"FAILED","phaseStartedAtEpochMs":2,
              "deadlineAtEpochMs":2,"failure":"DISMISS_NOT_CAPTURED",
              "failureDiagnostics":{"sourceAvailable":false,"eventTextCount":0,
                "eventHasContentDescription":false,"copiedNodeCount":0,"selectorCandidateCount":0}
            }}
        """.trimIndent()

        assertEquals(returned, RuleCaptureCodec.decodeOrEmpty(RuleCaptureCodec.encode(returned)))
        assertEquals(null, RuleCaptureCodec.decodeOrEmpty(legacySession).activeSession?.leftFlashSkipAtEpochMs)
        assertEquals(null, RuleCaptureCodec.decodeOrEmpty(legacyFailureSession)
            .activeSession?.failureDiagnostics?.sessionTrace)
    }

    @Test
    fun malformedOptionalTraceDoesNotDiscardSamplesOrFailure() {
        val malformedTraces = listOf(
            """{"records":[]}""",
            """{"summary":{},"records":[{"elapsedMs":0,"kind":"FUTURE_RECORD"}]}""",
            """{"summary":{"windowResolutionOutcomeCounts":{"FUTURE_OUTCOME":1}},"records":[]}""",
        )
        for (traceJson in malformedTraces) {
            val raw = """
                {
                  "version":2,
                  "samples":[{
                    "id":7,"targetPackageName":"org.example.reader","targetAppName":"Reader",
                    "activityName":"org.example.reader.MainActivity","clickedAfterTargetMs":600,
                    "capturedAtEpochMs":1700000000000,
                    "nodes":[{"resourceId":"org.example.reader:id/skip","clickable":true,
                      "supportsActionClick":true,"visibleToUser":true,"hasUsableBounds":true}],
                    "preClickUniqueSelectors":[{"resourceId":"org.example.reader:id/skip"}]
                  }],
                  "activeSession":{
                    "id":42,"phase":"FAILED","phaseStartedAtEpochMs":1,"deadlineAtEpochMs":1,
                    "failure":"DISMISS_NOT_CAPTURED",
                    "failureDiagnostics":{
                      "sourceAvailable":false,"eventTextCount":0,"eventHasContentDescription":false,
                      "copiedNodeCount":0,"selectorCandidateCount":0,
                      "sessionTrace":$traceJson
                    }
                  }
                }
            """.trimIndent()
            val decoded = RuleCaptureCodec.decodeOrEmpty(raw)
            assertEquals(listOf(sample(7)), decoded.samples)
            assertEquals(CaptureFailure.DISMISS_NOT_CAPTURED, decoded.activeSession?.failure)
            assertEquals(null, decoded.activeSession?.failureDiagnostics?.sessionTrace)
        }
    }

    @Test
    fun traceDecodeAndEncodeKeepTheRecentRecordLimit() {
        val state = RuleCaptureState(
            activeSession = CaptureSession(
                id = 42,
                phase = CapturePhase.FAILED,
                phaseStartedAtEpochMs = 1,
                deadlineAtEpochMs = 1,
                failure = CaptureFailure.DISMISS_NOT_CAPTURED,
                failureDiagnostics = CaptureFailureDiagnostics(
                    sourceAvailable = false,
                    eventTextCount = 0,
                    eventHasContentDescription = false,
                ),
            ),
        )
        val encoded = JSONObject(RuleCaptureCodec.encode(state))
        val rawRecords = JSONArray().apply {
            repeat(60) { index ->
                put(JSONObject().put("elapsedMs", index).put("kind", "SCAN"))
            }
        }
        encoded.getJSONObject("activeSession")
            .getJSONObject("failureDiagnostics")
            .put(
                "sessionTrace",
                JSONObject()
                    .put("summary", JSONObject().put("totalRecordCount", 60))
                    .put("records", rawRecords),
            )

        val decoded = RuleCaptureCodec.decodeOrEmpty(encoded.toString())
        val decodedTrace = requireNotNull(decoded.activeSession?.failureDiagnostics?.sessionTrace)
        val reencoded = RuleCaptureCodec.encode(decoded)

        assertEquals(48, decodedTrace.records.size)
        assertEquals(60, decodedTrace.summary.totalRecordCount)
        assertEquals(12, decodedTrace.summary.droppedRecordCount)
        assertEquals(48, RuleCaptureCodec.decodeOrEmpty(reencoded)
            .activeSession?.failureDiagnostics?.sessionTrace?.records?.size)
    }

    @Test
    fun traceSummaryDefaultsNewWindowFieldsWhenReadingOlderData() {
        val raw = """
            {"version":2,"samples":[],"activeSession":{
              "id":42,"phase":"FAILED","phaseStartedAtEpochMs":1,"deadlineAtEpochMs":1,
              "failure":"DISMISS_NOT_CAPTURED","failureDiagnostics":{
                "sourceAvailable":false,"eventTextCount":0,"eventHasContentDescription":false,
                "copiedNodeCount":0,"selectorCandidateCount":0,
                "sessionTrace":{"summary":{},"records":[]}
              }
            }}
        """.trimIndent()

        val summary = requireNotNull(
            RuleCaptureCodec.decodeOrEmpty(raw)
                .activeSession?.failureDiagnostics?.sessionTrace?.summary,
        )

        assertEquals(0, summary.windowResolutionCount)
        assertEquals(0, summary.windowResolutionRetryCount)
        assertTrue(summary.windowResolutionOutcomeCounts.isEmpty())
        assertEquals(0, summary.hostWindowUpdateCount)
    }

    @Test
    fun malformedCaptureDataDoesNotProduceExecutableSamples() {
        for (raw in listOf(null, "", "{", """{"version":999,"samples":[]}""")) {
            assertEquals(RuleCaptureState(), RuleCaptureCodec.decodeOrEmpty(raw))
        }
    }

    @Test
    fun legacyExecutionRecordWithoutTimingStillLoads() {
        val raw = """
            {"version":2,"records":[{"timestamp":12,"packageName":"org.example.reader",
              "appName":"Reader","rule":"rule-1","method":"NODE_CLICK",
              "outcome":"CLOSED","reason":null}]}
        """.trimIndent()

        assertEquals(
            listOf(ActionRecord(12, target, "Reader", "rule-1", ActionMethod.NODE_CLICK, ActionOutcome.CLOSED)),
            ActionRecordCodec.decodeOrEmpty(raw),
        )
    }

    @Test
    fun clickedButUnconfirmedRecordRetainsItsMethodReasonAndLegacyEnvelope() {
        val unconfirmed = record(1).copy(
            method = ActionMethod.NODE_CLICK, outcome = ActionOutcome.ABANDONED,
            reason = ActionReason.NODE_UNAVAILABLE, timing = ExecutionTiming(1, 2, 3, 5, 7),
        )
        val existing = record(2)
        val raw = ActionRecordCodec.encode(listOf(unconfirmed, existing))
        assertEquals(2, JSONObject(raw).getInt("version"))
        assertEquals(listOf(unconfirmed, existing), ActionRecordCodec.decodeOrEmpty(raw))
    }

    @Test
    fun failureReasonsAndTimingSurviveRecordRoundTrip() {
        val records = ActionReason.entries.mapIndexed { index, reason ->
            record(index.toLong()).copy(
                reason = reason,
                timing = ExecutionTiming(1, 2, 3, 5, 2),
            )
        }

        assertEquals(records, ActionRecordCodec.decodeOrEmpty(ActionRecordCodec.encode(records)))
    }

    @Test
    fun appEnablementAndRecentRecordsPersistAcrossStoreReopen() = runBlocking {
        val file = File(folder.root, "preferences.preferences_pb")
        val scope = newScope()
        val store = PreferencesFlashSkipStore(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
            scope,
        )
        val other = TargetAppId("org.example.other")
        store.setAppEnabled(target, true)
        store.setAppEnabled(other, true)
        store.setAppEnabled(target, false)
        repeat(25) { store.appendRecord(record(it.toLong())) }
        scope.coroutineContext[Job]!!.cancelAndJoin()

        val reopenedScope = newScope()
        val reopened = PreferencesFlashSkipStore(
            PreferenceDataStoreFactory.create(scope = reopenedScope, produceFile = { file }),
            reopenedScope,
        )
        val preferences = reopened.preferences.first()
        assertFalse(reopened.isAppEnabled(target))
        assertTrue(reopened.isAppEnabled(other))
        assertEquals((24L downTo 5L).toList(), preferences.records.map { it.timestampEpochMs })
    }

    @Test
    fun captureStoreSerializesConcurrentUpdatesWithoutLosingSamples() = runBlocking {
        val scope = newScope()
        val store = PreferencesRuleCaptureStore(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(folder.root, "capture.preferences_pb")
            },
            scope,
        )

        coroutineScope {
            repeat(12) { index ->
                launch(Dispatchers.Default) {
                    store.update { it.copy(samples = it.samples + sample(index.toLong())) }
                }
            }
        }

        assertEquals((0L..11L).toSet(), store.state.first().samples.map { it.id }.toSet())
    }

    @Test
    fun startupCleanupPersistsWithoutRemovingValidRulesAndSurvivesReopen() = runBlocking {
        val file = File(folder.root, "legacy-capture.preferences_pb")
        val scope = newScope()
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        val valid = sample(2)
        val unsafe = numericSample()
        val key = stringPreferencesKey("rule_capture_state_v2")
        dataStore.edit { it[key] = RuleCaptureCodec.encode(RuleCaptureState(samples = listOf(unsafe, valid))) }
        val store = PreferencesRuleCaptureStore(dataStore, scope)
        assertEquals(listOf(valid), store.state.first().samples)
        val persisted = withTimeout(5_000) {
            dataStore.data.first {
                RuleCaptureCodec.decodeOrEmpty(it[key]).samples == listOf(valid)
            }
        }
        assertEquals(listOf(valid), RuleCaptureCodec.decodeOrEmpty(persisted[key]).samples)
        scope.coroutineContext[Job]!!.cancelAndJoin()

        val reopenedScope = newScope()
        val reopened = PreferencesRuleCaptureStore(
            PreferenceDataStoreFactory.create(scope = reopenedScope) { file }, reopenedScope,
        )
        assertEquals(listOf(valid), reopened.state.first().samples)
    }

    @Test
    fun storeUpdatesCannotReintroduceRejectedNumericSamples() = runBlocking {
        val scope = newScope()
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(folder.root, "guarded-capture.preferences_pb")
        }
        val store = PreferencesRuleCaptureStore(dataStore, scope)
        val valid = sample(2)
        store.update { it.copy(samples = listOf(valid, numericSample())) }
        assertEquals(listOf(valid), store.state.first().samples)
        assertEquals(listOf(valid), RuleCaptureCodec.decodeOrEmpty(
            dataStore.data.first()[stringPreferencesKey("rule_capture_state_v2")],
        ).samples)
    }

    private fun numericSample() = sample(1).copy(
        nodes = listOf(CapturedNode(
            resourceId = "org.example.reader:id/item_count", text = "30",
            clickable = true, supportsActionClick = true, visibleToUser = true, hasUsableBounds = true,
        )),
        evidence = CaptureEvidence.USER_CONFIRMED_UNREPORTED_CLICK,
    )

    private fun newScope(): CoroutineScope {
        val job = SupervisorJob().also(storeJobs::add)
        return CoroutineScope(job + Dispatchers.IO)
    }

    private fun sample(id: Long) = CaptureSample(
        id = id,
        targetAppId = target,
        targetAppName = "Reader",
        activityName = "org.example.reader.MainActivity",
        clickedAfterTargetMs = 600,
        capturedAtEpochMs = 1_700_000_000_000,
        nodes = listOf(
            CapturedNode(
                resourceId = "org.example.reader:id/skip",
                clickable = true,
                supportsActionClick = true,
                visibleToUser = true,
                hasUsableBounds = true,
            ),
        ),
        preClickUniqueSelectors = listOf(CapturedSelector(resourceId = "org.example.reader:id/skip")),
    )

    private fun record(timestamp: Long) = ActionRecord(
        timestampEpochMs = timestamp,
        targetAppId = target,
        targetAppName = "Reader",
        ruleId = "rule-1",
        method = null,
        outcome = ActionOutcome.FAILED,
        reason = ActionReason.GESTURE_CANCELLED,
    )
}
