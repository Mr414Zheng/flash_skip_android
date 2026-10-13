package com.zhenghuaizhi.flashskip.domain.automation

import com.zhenghuaizhi.flashskip.domain.model.ActionOutcome
import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.ports.QueryResult
import com.zhenghuaizhi.flashskip.domain.ports.TargetObservation
import com.zhenghuaizhi.flashskip.domain.rules.BuiltinRuleCatalog
import com.zhenghuaizhi.flashskip.domain.rules.CompositeRuleCatalog
import com.zhenghuaizhi.flashskip.testing.AutomationFixture
import com.zhenghuaizhi.flashskip.testing.TestRuleCaptureStore
import com.zhenghuaizhi.flashskip.testing.captureSample
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationCatalogIntegrationTest {
    @Test fun ac03_realCatalogDeletionWhileQuerySuspendedPreventsClick() = runTest {
        val store = TestRuleCaptureStore(RuleCaptureState(samples = listOf(captureSample())), mutableListOf()) {}
        val catalog = CompositeRuleCatalog(BuiltinRuleCatalog(), store, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertTrue(catalog.snapshot.value.rules.isNotEmpty())
        val f = AutomationFixture(this, suppliedCatalog = catalog)
        val query = CompletableDeferred<QueryResult>()
        f.gateway.onQuery = { _, _ -> query.await() }
        f.enter()
        f.advance(120)
        store.update { it.copy(samples = emptyList()) }
        runCurrent()
        assertTrue(catalog.snapshot.value.rules.isEmpty())
        query.complete(QueryResult.Unique)
        runCurrent()
        assertTrue("真实目录删除后挂起查询不得提交旧规则动作", f.gateway.clicks.isEmpty())
    }

    @Test fun ac03_ac08_realCatalogDowngradePreservesAcceptedButUnconfirmedRecord() = runTest {
        val sample = captureSample()
        val store = TestRuleCaptureStore(RuleCaptureState(samples = listOf(sample)), mutableListOf()) {}
        val catalog = CompositeRuleCatalog(BuiltinRuleCatalog(), store, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        val f = AutomationFixture(this, suppliedCatalog = catalog)
        f.gateway.onObserve = { TargetObservation.Present(true) }
        f.enter()
        f.advance(120)
        assertEquals(1, f.gateway.clicks.size)
        store.update { it.copy(samples = listOf(sample.copy(nodes = listOf(sample.nodes.single().copy(resourceId = null))))) }
        runCurrent()
        assertTrue(catalog.snapshot.value.rules.isEmpty())
        assertEquals(1, store.state.value.samples.size)
        f.content()
        f.advance(1100)
        assertEquals("目录降级不得再补点", 1, f.gateway.clicks.size)
        assertEquals(ActionOutcome.ABANDONED, f.records.single().outcome)
        assertTrue(f.records.single().actionAccepted)
    }
}
