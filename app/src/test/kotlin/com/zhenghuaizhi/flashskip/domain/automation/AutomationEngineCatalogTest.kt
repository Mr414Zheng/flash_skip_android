package com.zhenghuaizhi.flashskip.domain.automation

import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.ActionMethod
import com.zhenghuaizhi.flashskip.domain.model.ActionObservationStatus
import com.zhenghuaizhi.flashskip.domain.model.AdRule
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.ports.ClickResult
import com.zhenghuaizhi.flashskip.domain.ports.AbsenceProof
import com.zhenghuaizhi.flashskip.domain.ports.GestureResult
import com.zhenghuaizhi.flashskip.domain.ports.QueryResult
import com.zhenghuaizhi.flashskip.domain.ports.TargetObservation
import com.zhenghuaizhi.flashskip.domain.rules.RuleCatalogSnapshot
import com.zhenghuaizhi.flashskip.testing.AutomationFixture
import com.zhenghuaizhi.flashskip.testing.OTHER_TARGET
import com.zhenghuaizhi.flashskip.testing.executableRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationEngineCatalogTest {
    @Test fun ac03_removalWhileQuerySuspendedPreventsFirstClick() = runTest {
        val f = AutomationFixture(this)
        val query = CompletableDeferred<QueryResult>()
        f.gateway.onQuery = { _, _ -> query.await() }
        f.enter()
        f.advance(120)
        assertEquals(1, f.gateway.queries.size)
        f.catalog.snapshot.value = RuleCatalogSnapshot()
        query.complete(QueryResult.Unique)
        f.advance(0)
        assertTrue("目录已撤销，挂起查询返回后不得点击", f.gateway.clicks.isEmpty())
        assertTrue(f.records.none { it.actionAccepted })
    }

    @Test fun ac03_reorderedRulesInvalidateOriginalPriorityBeforeClick() = runTest {
        val rules = listOf(executableRule("first"), executableRule("second"))
        val f = AutomationFixture(this, rules)
        f.enter()
        f.catalog.snapshot.value = RuleCatalogSnapshot(rules.reversed())
        f.advance(120)
        assertTrue("执行优先级变更后旧会话必须失效", f.gateway.clicks.isEmpty())
    }

    @Test fun ac03_executionFieldChangeInvalidatesBeforeClick() = runTest {
        val original = executableRule()
        val updates: List<AdRule> = listOf(
            original.copy(id = "replacement"),
            original.copy(selectors = listOf(NodeSelector(resourceId = "app:id/other"))),
            original.copy(allowedActivities = original.allowedActivities + "OtherActivity"),
            original.copy(startupDelayMs = 1), original.copy(cooldownMs = 9_999),
        )
        updates.forEach { replacement ->
            val f = AutomationFixture(this, listOf(original))
            f.enter()
            f.catalog.snapshot.value = RuleCatalogSnapshot(listOf(replacement))
            f.advance(120)
            assertTrue("可执行字段变化: $replacement", f.gateway.clicks.isEmpty())
        }
    }

    @Test fun ac03_displayMetadataAndOtherTargetChangesDoNotInvalidate() = runTest {
        val original = executableRule()
        val f = AutomationFixture(this, listOf(original))
        f.enter()
        f.catalog.snapshot.value = RuleCatalogSnapshot(listOf(
            original.copy(targetAppName = "Renamed display"), executableRule("other", OTHER_TARGET),
        ))
        f.advance(120)
        assertEquals(1, f.gateway.clicks.size)
    }

    @Test fun ac03_removalBetweenUnsupportedClickAndGesturePreventsFallback() = runTest {
        val f = AutomationFixture(this)
        f.gateway.onClick = { _, _, _ ->
            f.catalog.snapshot.value = RuleCatalogSnapshot()
            ClickResult.NotSupported
        }
        f.enter()
        f.advance(120)
        assertEquals(1, f.gateway.clicks.size)
        assertTrue("手势回退前须复核目录", f.gateway.gestures.isEmpty())
    }

    @Test fun ac03_removalAfterAcceptedActionStopsRetryAndRecordsUnconfirmed() = runTest {
        val f = AutomationFixture(this)
        f.gateway.onObserve = { TargetObservation.Present(true) }
        f.enter()
        f.advance(120)
        assertEquals(1, f.gateway.clicks.size)
        f.catalog.snapshot.value = RuleCatalogSnapshot()
        f.content()
        f.advance(1_100)
        assertEquals("目录撤销后不得补点", 1, f.gateway.clicks.size)
        assertEquals(ActionOutcome.ABANDONED, f.records.single().outcome)
        assertTrue(f.records.single().actionAccepted)
    }

    @Test fun ac03_removedRuleCannotTurnStableAbsenceIntoClosedRecord() = runTest {
        val f = AutomationFixture(this)
        f.gateway.onObserve = { TargetObservation.Absent(AbsenceProof.LOCAL_TARGET_REMOVED, 42) }
        f.enter()
        f.advance(121)
        f.content()
        f.advance(249)
        assertTrue(f.records.isEmpty())
        f.catalog.snapshot.value = RuleCatalogSnapshot()
        f.advance(400)
        assertEquals("目录失效后只能保留未确认动作", ActionOutcome.ABANDONED, f.records.single().outcome)
        assertTrue(f.records.single().actionAccepted)
    }

    @Test fun ac03_invalidatedSessionDoesNotRearmWhenRulesReturnInSameForeground() = runTest {
        val rule = executableRule()
        val f = AutomationFixture(this, listOf(rule))
        f.enter()
        f.catalog.snapshot.value = RuleCatalogSnapshot()
        f.content()
        f.advance(120)
        f.catalog.snapshot.value = RuleCatalogSnapshot(listOf(rule))
        f.enter()
        f.content()
        f.advance(120)
        assertTrue(f.gateway.clicks.isEmpty())
        f.outside()
        f.enter()
        f.advance(120)
        assertEquals(1, f.gateway.clicks.size)
    }

    @Test fun ac03_ac08_removalDuringSuspendedFirstActionPreservesReturnedAcceptance() = runTest {
        val gestureResults: List<GestureResult?> = listOf(null, GestureResult.Accepted, GestureResult.Completed)
        gestureResults.forEach { gestureResult ->
            val f = AutomationFixture(this)
            val click = CompletableDeferred<ClickResult>()
            val gesture = CompletableDeferred<GestureResult>()
            f.gateway.onClick = { _, _, _ ->
                if (gestureResult == null) click.await() else ClickResult.NotSupported
            }
            f.gateway.onGesture = { gesture.await() }
            f.enter()
            f.advance(120)
            assertEquals(1, f.gateway.clicks.size)
            assertEquals(if (gestureResult == null) 0 else 1, f.gateway.gestures.size)
            assertTrue(f.records.isEmpty())

            f.catalog.snapshot.value = RuleCatalogSnapshot()
            f.advance(0)
            if (gestureResult == null) click.complete(ClickResult.Clicked) else gesture.complete(gestureResult)
            f.advance(0)

            val record = f.records.single()
            assertEquals(ActionOutcome.ABANDONED, record.outcome)
            assertTrue(record.actionAccepted)
            assertEquals(if (gestureResult == null) ActionMethod.NODE_CLICK else ActionMethod.CENTER_GESTURE, record.method)
            assertEquals(ActionObservationStatus.INSUFFICIENT, record.observation)
            assertTrue("已撤销目录不能开始观察", f.gateway.fallbacks.isEmpty())
            f.advance(3_000)
            assertEquals(1, f.records.size)
            assertEquals(1, f.gateway.clicks.size)
            assertEquals(if (gestureResult == null) 0 else 1, f.gateway.gestures.size)
        }
    }

    @Test fun ac03_ac08_removalDuringSuspendedRetryPreservesAcceptedActionWithoutFurtherWork() = runTest {
        val f = AutomationFixture(this)
        val retry = CompletableDeferred<ClickResult>()
        f.gateway.onClick = { _, _, expectedTarget ->
            if (expectedTarget == null) ClickResult.Clicked else retry.await()
        }
        f.gateway.onObserve = { TargetObservation.Present(true) }
        f.enter()
        f.advance(1_170)
        assertEquals(2, f.gateway.clicks.size)
        assertNotNull(f.gateway.clicks.last().third)
        assertTrue(f.records.isEmpty())
        val observationsBeforeRemoval = f.gateway.fallbacks.size

        f.catalog.snapshot.value = RuleCatalogSnapshot()
        f.advance(0)
        retry.complete(ClickResult.Clicked)
        f.advance(0)

        val record = f.records.single()
        assertEquals(ActionOutcome.ABANDONED, record.outcome)
        assertTrue(record.actionAccepted)
        assertEquals(ActionMethod.NODE_CLICK, record.method)
        assertEquals(ActionObservationStatus.INSUFFICIENT, record.observation)
        f.advance(3_000)
        assertEquals(1, f.records.size)
        assertEquals(2, f.gateway.clicks.size)
        assertEquals(observationsBeforeRemoval, f.gateway.fallbacks.size)
    }

    @Test fun ac03_removalDuringSuspendedObservationPreventsCloseOrRetry() = runTest {
        val observations = listOf(
            TargetObservation.Absent(AbsenceProof.LOCAL_TARGET_REMOVED, 42),
            TargetObservation.Present(true),
        )
        observations.forEach { observation ->
            val f = AutomationFixture(this)
            val result = CompletableDeferred<TargetObservation>()
            val suspendedAt = if (observation is TargetObservation.Absent) 2 else 3
            var reads = 0
            f.gateway.onObserve = {
                reads++
                if (reads == suspendedAt) result.await() else observation
            }
            f.enter()
            f.advance(121)
            f.content()
            f.advance(if (observation is TargetObservation.Absent) 649 else 1_049)
            assertEquals(suspendedAt, reads)
            assertTrue(f.records.isEmpty())

            f.catalog.snapshot.value = RuleCatalogSnapshot()
            f.advance(0)
            result.complete(observation)
            f.advance(0)

            val record = f.records.single()
            assertEquals(ActionOutcome.ABANDONED, record.outcome)
            assertTrue(record.actionAccepted)
            assertEquals(ActionMethod.NODE_CLICK, record.method)
            assertEquals(ActionObservationStatus.INSUFFICIENT, record.observation)
            f.advance(3_000)
            assertEquals(1, f.records.size)
            assertEquals(1, f.gateway.clicks.size)
            assertEquals(suspendedAt, reads)
        }
    }

    @Test fun ac03_observedRemovalThenRestorationCannotReviveSuspendedQuery() = runTest {
        val rule = executableRule()
        val f = AutomationFixture(this, listOf(rule))
        val query = CompletableDeferred<QueryResult>()
        f.gateway.onQuery = { _, _ -> query.await() }
        f.enter()
        f.advance(120)
        assertEquals(1, f.gateway.queries.size)

        f.catalog.snapshot.value = RuleCatalogSnapshot()
        f.advance(0)
        f.catalog.snapshot.value = RuleCatalogSnapshot(listOf(rule))
        f.advance(0)
        query.complete(QueryResult.Unique)
        f.advance(0)
        f.enter()
        f.content()
        f.advance(120)
        assertTrue("观察到撤销后，即使目录恢复也不能复活旧查询", f.gateway.clicks.isEmpty())
        assertTrue(f.records.none { it.actionAccepted })

        f.outside()
        f.enter()
        f.advance(120)
        assertEquals(1, f.gateway.clicks.size)
    }
}
