package com.zhenghuaizhi.flashskip.ui.home

import androidx.lifecycle.viewModelScope
import com.zhenghuaizhi.flashskip.application.ServiceConnectionRegistry
import com.zhenghuaizhi.flashskip.application.ServiceStatusCoordinator
import com.zhenghuaizhi.flashskip.application.SystemServiceStatusReader
import com.zhenghuaizhi.flashskip.application.rulecapture.RuleCaptureCoordinator
import com.zhenghuaizhi.flashskip.domain.model.ActionRecord
import com.zhenghuaizhi.flashskip.domain.model.CapturePhase
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CaptureSession
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipPreferences
import com.zhenghuaizhi.flashskip.domain.ports.FlashSkipStore
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.RuleCaptureStore
import com.zhenghuaizhi.flashskip.domain.ports.WallClock
import com.zhenghuaizhi.flashskip.domain.rules.RuleCatalog
import com.zhenghuaizhi.flashskip.domain.rules.CapturedTargetRules
import com.zhenghuaizhi.flashskip.domain.rulecapture.RuleCandidateAnalyzer
import com.zhenghuaizhi.flashskip.domain.rules.RuleCatalogSnapshot
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @Test
    fun failedWriteShowsRetryGuidanceAndReleasesControls() = runTest {
        withFixture { fixture ->
            fixture.preferences.beforeWrite = { throw IOException("disk full") }
            fixture.viewModel.setAppEnabled(TARGET, false)
            runCurrent()

            assertFalse(fixture.viewModel.uiState.value.operationInProgress)
            assertTrue(fixture.viewModel.uiState.value.operationMessage!!.contains("重试"))
        }
    }

    @Test
    fun repeatedSubmissionDoesNotQueueAnotherWrite() = runTest {
        withFixture { fixture ->
            val release = CompletableDeferred<Unit>()
            fixture.preferences.beforeWrite = { release.await() }
            fixture.viewModel.setAppEnabled(TARGET, false)
            fixture.viewModel.setAppEnabled(TARGET, false)
            runCurrent()
            assertEquals(1, fixture.preferences.writeCount)
            assertTrue(fixture.viewModel.uiState.value.operationInProgress)

            release.complete(Unit)
            runCurrent()
            assertEquals(1, fixture.preferences.writeCount)
            assertFalse(fixture.viewModel.uiState.value.operationInProgress)
        }
    }

    @Test
    fun cancelUsesDisplayedSessionAndCannotRemoveReplacement() = runTest {
        withFixture { fixture ->
            val first = session(1L)
            val replacement = session(2L)
            fixture.capture.state.value = RuleCaptureState(activeSession = first)
            runCurrent()
            assertEquals(first.id, fixture.viewModel.uiState.value.capture.sessionId)

            fixture.viewModel.cancelCapture()
            fixture.capture.state.value = RuleCaptureState(activeSession = replacement)
            runCurrent()

            assertEquals(replacement, fixture.capture.state.value.activeSession)
            assertNotNull(fixture.viewModel.uiState.value.operationMessage)
            assertFalse(fixture.viewModel.uiState.value.operationInProgress)
        }
    }

    @Test
    fun ruleControlsWaitForCatalogToMatchCurrentSamples() = runTest {
        withFixture { fixture ->
            val sample = sample()
            fixture.capture.state.value = RuleCaptureState(samples = listOf(sample))
            runCurrent()
            assertFalse(fixture.viewModel.uiState.value.ruleActionsEnabled)

            fixture.catalog.value = catalogFor(sample)
            runCurrent()
            assertTrue(fixture.viewModel.uiState.value.ruleActionsEnabled)
            assertEquals(1, fixture.viewModel.uiState.value.targetApps.size)

            fixture.capture.state.value = RuleCaptureState()
            runCurrent()
            assertFalse(fixture.viewModel.uiState.value.ruleActionsEnabled)
            fixture.catalog.value = RuleCatalogSnapshot()
            runCurrent()
            assertTrue(fixture.viewModel.uiState.value.ruleActionsEnabled)
            assertTrue(fixture.viewModel.uiState.value.targetApps.isEmpty())
        }
    }

    @Test
    fun preferenceAndOperationChangesReusePublishedAnalysis() = runTest {
        withFixture { fixture ->
            val nodes = CountingList(sample().nodes)
            val sample = sample().copy(nodes = nodes)
            fixture.capture.state.value = RuleCaptureState(samples = listOf(sample))
            fixture.catalog.value = catalogFor(sample)
            runCurrent()
            nodes.reads = 0

            fixture.viewModel.setAppEnabled(TARGET, false)
            runCurrent()
            fixture.viewModel.refresh()
            runCurrent()

            assertEquals(0, nodes.reads)
            assertEquals(1, fixture.viewModel.uiState.value.targetApps.single().ruleCard!!.ruleCount)
        }
    }

    @Test
    fun callbackFromOldRuleControlsCannotEnableWhileCatalogIsUpdating() = runTest {
        withFixture { fixture ->
            val first = sample()
            fixture.capture.state.value = RuleCaptureState(samples = listOf(first))
            fixture.catalog.value = catalogFor(first)
            runCurrent()
            assertTrue(fixture.viewModel.uiState.value.ruleActionsEnabled)

            val replacement = first.copy(targetAppName = "更新后的规则")
            fixture.capture.state.value = RuleCaptureState(samples = listOf(replacement))
            fixture.viewModel.setAppEnabled(TARGET, true)
            runCurrent()

            assertEquals(0, fixture.preferences.writeCount)
            assertTrue(fixture.viewModel.uiState.value.operationMessage!!.contains("正在更新"))
            fixture.catalog.value = catalogFor(replacement)
            runCurrent()
            fixture.viewModel.setAppEnabled(TARGET, true)
            runCurrent()
            assertEquals(1, fixture.preferences.writeCount)
            assertTrue(fixture.preferences.isAppEnabled(TARGET))
        }
    }

    private suspend fun TestScope.withFixture(block: suspend TestScope.(Fixture) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val capture = FakeCaptureStore()
        val preferences = FakePreferencesStore()
        val catalog = MutableStateFlow(RuleCatalogSnapshot())
        val ruleCatalog = object : RuleCatalog {
            override val snapshot = catalog
        }
        val coordinator = RuleCaptureCoordinator(
            capture,
            preferences,
            object : MonotonicClock {
                override fun elapsedRealtimeMs(): Long = testScheduler.currentTime
            },
            object : WallClock {
                override fun currentTimeMillis(): Long = 1_000L + testScheduler.currentTime
            },
            backgroundScope,
            ruleCatalog,
        )
        val viewModel = HomeViewModel(
            preferences,
            ruleCatalog,
            ServiceStatusCoordinator(
                SystemServiceStatusReader { false },
                ServiceConnectionRegistry(),
                backgroundScope,
            ),
            coordinator,
        )
        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        try {
            block(Fixture(viewModel, capture, preferences, catalog))
        } finally {
            try {
                collector.cancelAndJoin()
                // 取消的子协程仍会访问 Main，必须等全部结束后再恢复 Android dispatcher。
                requireNotNull(viewModel.viewModelScope.coroutineContext[Job]).cancelAndJoin()
                runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    private data class Fixture(
        val viewModel: HomeViewModel,
        val capture: FakeCaptureStore,
        val preferences: FakePreferencesStore,
        val catalog: MutableStateFlow<RuleCatalogSnapshot>,
    )

    private class FakeCaptureStore : RuleCaptureStore {
        override val state = MutableStateFlow(RuleCaptureState())
        override suspend fun update(transform: (RuleCaptureState) -> RuleCaptureState) {
            state.value = transform(state.value)
        }
    }

    private class FakePreferencesStore : FlashSkipStore {
        override val preferences = MutableStateFlow(FlashSkipPreferences())
        var beforeWrite: suspend () -> Unit = {}
        var writeCount = 0

        override suspend fun setAppEnabled(targetAppId: TargetAppId, enabled: Boolean) {
            writeCount++
            beforeWrite()
            preferences.value = preferences.value.copy(
                enabledApps = preferences.value.enabledApps + (targetAppId to enabled),
            )
        }

        override suspend fun isAppEnabled(targetAppId: TargetAppId): Boolean =
            preferences.value.enabledApps[targetAppId] == true

        override suspend fun appendRecord(record: ActionRecord) = Unit
    }

    private class CountingList<T>(private val values: List<T>) : AbstractList<T>() {
        var reads = 0
        override val size: Int get() = values.size
        override fun get(index: Int): T {
            reads++
            return values[index]
        }
    }

    private companion object {
        val TARGET = TargetAppId("com.example.target")

        fun sample() = CaptureSample(
            id = 1L,
            targetAppId = TARGET,
            activityName = "com.example.target.MainActivity",
            clickedAfterTargetMs = 100L,
            capturedAtEpochMs = 1_000L,
            nodes = listOf(CapturedNode(resourceId = "com.example.target:id/skip", clickable = true, visibleToUser = true, hasUsableBounds = true)),
            preClickUniqueSelectors = emptyList(),
        )

        fun catalogFor(sample: CaptureSample): RuleCatalogSnapshot {
            val analysis = RuleCandidateAnalyzer.analyze(listOf(sample), TARGET)
            return RuleCatalogSnapshot(
                rules = analysis.rules,
                capturedTargets = listOf(CapturedTargetRules(TARGET, sample.targetAppName, sample.capturedAtEpochMs, analysis)),
                sourceSamples = listOf(sample),
            )
        }

        fun session(id: Long) = CaptureSession(
            id = id,
            phase = CapturePhase.CAPTURED,
            phaseStartedAtEpochMs = 1_000L,
            deadlineAtEpochMs = 601_000L,
        )
    }
}
