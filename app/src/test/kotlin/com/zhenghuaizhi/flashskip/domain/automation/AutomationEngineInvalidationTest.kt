package com.zhenghuaizhi.flashskip.domain.automation

import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.AppVersion
import com.zhenghuaizhi.flashskip.domain.ports.QueryResult
import com.zhenghuaizhi.flashskip.testing.AutomationFixture
import com.zhenghuaizhi.flashskip.testing.TARGET
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationEngineInvalidationTest {
    @Test fun ac07_leavingTargetCancelsScheduledQueryAndOldTimerCannotAct() = runTest {
        val f = AutomationFixture(this)
        f.enter()
        f.advance(119)
        f.outside()
        f.advance(10_001)
        assertTrue(f.gateway.clicks.isEmpty())
    }

    @Test fun ac07_pauseAcknowledgementStopsPendingActionsAndResumeAloneDoesNotAct() = runTest {
        val f = AutomationFixture(this)
        f.enter()
        f.advance(119)
        val pause = async { f.engine.setCapturePaused(true) }
        runCurrent()
        pause.await()
        f.advance(1)
        assertTrue(f.gateway.clicks.isEmpty())
        val resume = async { f.engine.setCapturePaused(false) }
        runCurrent()
        resume.await()
        f.advance(500)
        assertTrue(f.gateway.clicks.isEmpty())
        f.enter()
        f.advance(120)
        assertEquals(1, f.gateway.clicks.size)
    }

    @Test fun ac07_versionChangeInvalidatesPendingQueryWithoutRearm() = runTest {
        val f = AutomationFixture(this)
        f.identity = f.identity.copy(appVersion = AppVersion("1", 1))
        f.enter()
        f.advance(119)
        f.identity = f.identity.copy(appVersion = AppVersion("2", 2))
        f.content()
        f.advance(121)
        f.enter()
        f.advance(120)
        assertTrue(f.gateway.clicks.isEmpty())
    }

    @Test fun ac07_mailboxOverflowDuringSuspendedQueryInvalidatesQueuedAction() = runTest {
        val f = AutomationFixture(this)
        val query = CompletableDeferred<QueryResult>()
        f.gateway.onQuery = { _, _ -> query.await() }
        f.enter()
        f.advance(120)
        repeat(65) { f.engine.onContentChanged(f.identity, testScheduler.currentTime) }
        query.complete(QueryResult.Unique)
        runCurrent()
        assertTrue(f.gateway.clicks.isEmpty())
        assertTrue(f.records.none { it.actionAccepted })
    }

    @Test fun ac07_closeCancelsSuspendedQueryAndLateResultCannotAct() = runTest {
        val f = AutomationFixture(this)
        val query = CompletableDeferred<QueryResult>()
        f.gateway.onQuery = { _, _ -> query.await() }
        f.enter()
        f.advance(120)
        f.engine.close()
        runCurrent()
        query.complete(QueryResult.Unique)
        f.advance(1000)
        assertTrue(f.gateway.clicks.isEmpty())
    }

    @Test fun ac07_ac08_acceptedActionIsPreservedAsUnconfirmedAcrossEachInvalidation() = runTest {
        val invalidations: List<suspend (AutomationFixture) -> Unit> = listOf(
            { it.outside() },
            { f -> f.store.setAppEnabled(TARGET, false); f.content() },
            { f -> f.identity = f.identity.copy(appVersion = AppVersion("new", 2)); f.content() },
            { f -> val paused = async { f.engine.setCapturePaused(true) }; runCurrent(); paused.await() },
            { f -> f.engine.close(); runCurrent() },
        )
        invalidations.forEach { invalidate ->
            val f = AutomationFixture(this)
            f.enter()
            f.advance(120)
            invalidate(f)
            f.advance(3000)
            val record = f.records.single()
            assertEquals(ActionOutcome.ABANDONED, record.outcome)
            assertTrue(record.actionAccepted)
            assertNotNull(record.method)
            assertEquals(1, f.gateway.clicks.size)
        }
    }

    @Test fun ac08_recordFailureDoesNotRearmTerminalSession() = runTest {
        val f = AutomationFixture(this)
        f.store.beforeRecord = { throw IOException("record unavailable") }
        f.enter()
        f.advance(120)
        f.advance(2400)
        assertEquals(1, f.store.attemptedRecords.size)
        assertTrue(f.records.isEmpty())
        f.content()
        f.enter()
        f.advance(3000)
        assertEquals(1, f.gateway.clicks.size)
        assertEquals(1, f.store.attemptedRecords.size)
    }

    @Test fun ac07_engineCloseBoundsUnconfirmedRecordCleanup() = runTest {
        val f = AutomationFixture(this)
        val blocked = CompletableDeferred<Unit>()
        f.store.beforeRecord = { blocked.await() }
        f.enter()
        f.advance(120)
        f.engine.close()
        runCurrent()
        assertEquals(1, f.store.attemptedRecords.size)
        f.advance(500)
        blocked.complete(Unit)
        runCurrent()
        assertTrue(f.records.isEmpty())
        assertEquals(1, f.gateway.clicks.size)
        assertEquals(1, f.store.attemptedRecords.size)
    }

    @Test fun ac08_recordCancellationPropagatesAndEngineRejectsFurtherBarriers() = runTest {
        val f = AutomationFixture(this)
        f.store.beforeRecord = { throw CancellationException("record cancelled") }
        f.enter()
        f.advance(120)
        f.outside()
        assertEquals(1, f.store.attemptedRecords.size)
        val barrier = async { f.engine.awaitIdle() }
        runCurrent()
        try {
            barrier.await()
            fail("取消后引擎不得报告成功空闲")
        } catch (_: CancellationException) {
            assertTrue(f.records.isEmpty())
        }
        f.enter()
        f.advance(120)
        assertEquals(1, f.gateway.clicks.size)
    }
}
