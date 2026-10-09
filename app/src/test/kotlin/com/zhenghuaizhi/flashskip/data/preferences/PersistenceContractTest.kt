package com.zhenghuaizhi.flashskip.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.zhenghuaizhi.flashskip.domain.model.ActionMethod
import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.ActionReason
import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.CaptureEvidence
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.ExecutionTiming
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.SemanticDiffEvidence
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
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
                pendingSample = sample,
            ),
            semanticDiffEvidence = listOf(
                SemanticDiffEvidence(target, sample.activityName, sample.preClickUniqueSelectors, 10),
            ),
        )

        assertEquals(state, RuleCaptureCodec.decodeOrEmpty(RuleCaptureCodec.encode(state)))
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
