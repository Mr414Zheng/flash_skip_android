package com.zhenghuaizhi.flashskip.domain.automation

import com.zhenghuaizhi.flashskip.domain.model.ActionObservationStatus
import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.ports.AbsenceProof
import com.zhenghuaizhi.flashskip.domain.ports.ClickResult
import com.zhenghuaizhi.flashskip.domain.ports.TargetObservation
import com.zhenghuaizhi.flashskip.domain.rules.RuleCatalogSnapshot
import com.zhenghuaizhi.flashskip.testing.AutomationFixture
import com.zhenghuaizhi.flashskip.testing.executableRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationEngineObservationTest {
    private val absent = TargetObservation.Absent(AbsenceProof.LOCAL_TARGET_REMOVED, 42)

    @Test fun ac05_ac08_acceptedClickNeedsStableAbsenceAndLaterEventBeforeClosedRecord() = runTest {
        val f = AutomationFixture(this)
        f.gateway.onObserve = { absent }
        f.enter()
        f.advance(120)
        assertTrue(f.records.isEmpty())
        f.advance(1)
        f.content()
        f.advance(249)
        assertTrue(f.records.isEmpty())
        f.advance(400)
        val record = f.records.single()
        assertEquals(ActionOutcome.CLOSED, record.outcome)
        assertEquals(ActionObservationStatus.CONFIRMED, record.observation)
        assertTrue(record.actionAccepted)
        assertEquals(120L, record.timing!!.targetToActionMs)
        assertEquals(770L, record.timing.targetToCompletedMs)
    }

    @Test fun ac05_withoutLaterEventStableAbsenceRemainsUnconfirmedUntilDeadline() = runTest {
        val f = AutomationFixture(this)
        f.gateway.onObserve = { absent }
        f.enter()
        f.advance(120)
        f.advance(2399)
        assertTrue(f.records.isEmpty())
        f.advance(1)
        assertEquals(ActionOutcome.ABANDONED, f.records.single().outcome)
        assertTrue(f.records.single().actionAccepted)
        f.advance(1)
        assertEquals(1, f.records.size)
    }

    @Test fun ac01_ac05_observationCannotExtendOriginalForegroundDeadline() = runTest {
        val f = AutomationFixture(this)
        f.enter(-9_500)
        f.advance(120)
        assertEquals(1, f.gateway.clicks.size)
        f.advance(379)
        assertTrue(f.records.isEmpty())
        f.advance(1)
        assertEquals(ActionOutcome.ABANDONED, f.records.single().outcome)
        assertEquals(10_000L, f.records.single().timing!!.targetToCompletedMs)
    }

    @Test fun ac06_presentTargetCanBeRetriedOnlyOnceAndFailedRetryConsumesAttempt() = runTest {
        listOf(ClickResult.Clicked, ClickResult.NotSupported).forEach { retryResult ->
            val f = AutomationFixture(this)
            f.gateway.onObserve = { TargetObservation.Present(true) }
            f.gateway.onClick = { _, _, expected -> if (expected == null) ClickResult.Clicked else retryResult }
            val enteredAt = testScheduler.currentTime
            f.enter()
            f.advance(120)
            f.advance(1_049)
            assertEquals(1, f.gateway.clicks.size)
            f.advance(1)
            assertEquals(2, f.gateway.clicks.size)
            assertNotNull(f.gateway.clicks[1].third)
            f.advance(1350)
            assertEquals(2, f.gateway.clicks.size)
            assertTrue(f.gateway.gestures.isEmpty())
            assertEquals(ActionOutcome.ABANDONED, f.records.single().outcome)
            assertEquals(enteredAt + 2520, testScheduler.currentTime)
            assertEquals(2520L, f.records.single().timing!!.targetToCompletedMs)
        }
    }

    @Test fun ac06_changedPageIdentityPreventsRetryEvenIfTargetReportedPresent() = runTest {
        val f = AutomationFixture(this)
        f.gateway.onObserve = { TargetObservation.Present(true) }
        f.enter()
        f.advance(120)
        f.advance(1)
        f.identity = f.identity.copy(windowId = 8)
        f.content()
        f.advance(2399)
        assertEquals(1, f.gateway.clicks.size)
        assertEquals(ActionOutcome.ABANDONED, f.records.single().outcome)
    }

    @Test fun ac06_eventBeforeAcceptedRetryCannotCloseAfterRetry() = runTest {
        val f = AutomationFixture(this)
        f.gateway.onObserve = { TargetObservation.Present(true) }
        f.enter()
        f.advance(121)
        f.content()
        f.advance(1049)
        assertEquals(2, f.gateway.clicks.size)
        f.gateway.onObserve = { absent }
        f.advance(800)
        assertTrue("补点后还没有新事件，不得确认关闭", f.records.isEmpty())
        f.advance(550)
        assertEquals(ActionOutcome.ABANDONED, f.records.single().outcome)
    }

    @Test fun ac06_newEventAfterRetryCanSupportCloseButDeadlineStillStartsAtFirstClick() = runTest {
        val f = AutomationFixture(this)
        f.gateway.onObserve = { TargetObservation.Present(true) }
        f.enter()
        f.advance(1170)
        assertEquals(2, f.gateway.clicks.size)
        f.gateway.onObserve = { absent }
        f.advance(1)
        f.content()
        f.advance(520)
        assertEquals(ActionOutcome.CLOSED, f.records.single().outcome)
        assertEquals(2, f.gateway.clicks.size)
        assertTrue(f.records.single().timing!!.targetToCompletedMs < 2520)
    }

    @Test fun ac05_pageFallbackIsDelayedAndLimitedToTwoReads() = runTest {
        val f = AutomationFixture(this)
        f.enter()
        f.advance(1320)
        assertTrue(f.gateway.fallbacks.isNotEmpty())
        assertTrue(f.gateway.fallbacks.none { it })
        f.advance(1200)
        assertEquals(2, f.gateway.fallbacks.count { it })
        assertEquals(ActionOutcome.ABANDONED, f.records.single().outcome)
    }

    @Test fun ac08_onlyConfirmedCloseStartsRuleCooldownAndOtherRulesRemainIndependent() = runTest {
        val f = AutomationFixture(this)
        f.gateway.onObserve = { absent }
        f.enter()
        f.advance(121)
        f.content()
        f.advance(649)
        assertEquals(ActionOutcome.CLOSED, f.records.single().outcome)
        f.outside()
        f.enter()
        f.advance(120)
        assertEquals(1, f.gateway.clicks.size)
        f.outside()
        f.catalog.snapshot.value = RuleCatalogSnapshot(listOf(executableRule("other-rule")))
        f.enter()
        f.advance(120)
        assertEquals(2, f.gateway.clicks.size)
        f.outside()
        f.catalog.snapshot.value = RuleCatalogSnapshot(listOf(executableRule()))
        f.advance(10650 - testScheduler.currentTime)
        f.enter()
        f.advance(119)
        assertEquals(2, f.gateway.clicks.size)
        f.advance(1)
        assertEquals(3, f.gateway.clicks.size)
    }

    @Test fun ac08_unconfirmedActionDoesNotStartCooldown() = runTest {
        val f = AutomationFixture(this)
        f.enter()
        f.advance(2520)
        assertEquals(ActionOutcome.ABANDONED, f.records.single().outcome)
        f.outside()
        f.enter()
        f.advance(120)
        assertEquals(2, f.gateway.clicks.size)
    }
}
