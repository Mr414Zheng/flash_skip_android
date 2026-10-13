package com.zhenghuaizhi.flashskip.domain.automation

import com.zhenghuaizhi.flashskip.domain.model.AppVersion
import com.zhenghuaizhi.flashskip.domain.ports.QueryResult
import com.zhenghuaizhi.flashskip.testing.AutomationFixture
import com.zhenghuaizhi.flashskip.testing.executableRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationEngineSessionTest {
    @Test fun ac01_disabledTargetAndMissingRulesDoNotQueryOrAct() = runTest {
        listOf(AutomationFixture(this, enabled = false), AutomationFixture(this, rules = emptyList())).forEach { f ->
            f.enter()
            f.advance(10_001)
            assertTrue(f.gateway.queries.isEmpty())
            assertTrue(f.gateway.clicks.isEmpty())
            assertTrue(f.records.isEmpty())
        }
    }

    @Test fun ac01_unknownOrDifferentVersionCannotStartBoundRule() = runTest {
        val rule = executableRule().copy(targetAppVersion = AppVersion("1.0", 10))
        listOf(null, AppVersion("1.0", 11), AppVersion("2.0", 10)).forEach { version ->
            val f = AutomationFixture(this, rules = listOf(rule))
            f.identity = f.identity.copy(appVersion = version)
            f.enter()
            f.advance(120)
            assertTrue(f.gateway.queries.isEmpty())
        }
        val matching = AutomationFixture(this, rules = listOf(rule))
        matching.identity = matching.identity.copy(appVersion = rule.targetAppVersion)
        matching.enter()
        matching.advance(120)
        assertEquals(1, matching.gateway.clicks.size)
    }

    @Test fun ac01_activityMustBeAllowedBeforeAnyQuery() = runTest {
        val f = AutomationFixture(this)
        val allowed = f.identity
        f.identity = allowed.copy(activityName = "OtherActivity")
        f.enter()
        f.advance(120)
        assertTrue(f.gateway.queries.isEmpty())
        f.identity = allowed
        f.content()
        f.advance(120)
        assertEquals(1, f.gateway.clicks.size)
    }

    @Test fun ac01_startupDelayUsesForegroundTimeAndExecutesAtThreshold() = runTest {
        val f = AutomationFixture(this, rules = listOf(executableRule().copy(startupDelayMs = 500)))
        f.enter()
        f.advance(499)
        assertTrue(f.gateway.queries.isEmpty())
        f.advance(1)
        assertEquals(1, f.gateway.clicks.size)
        f.advance(1)
        assertEquals(1, f.gateway.clicks.size)
    }

    @Test fun ac01_expiredEntryOrDelayAtDeadlineCannotAct() = runTest {
        val expired = AutomationFixture(this)
        expired.enter(-10_000)
        expired.advance(120)
        assertTrue(expired.gateway.clicks.isEmpty())
        val delayed = AutomationFixture(this, rules = listOf(executableRule().copy(startupDelayMs = 10_000)))
        delayed.enter()
        delayed.advance(10_001)
        assertTrue(delayed.gateway.clicks.isEmpty())
    }

    @Test fun ac01_samePackageWindowChangeDoesNotExtendOrRearmExpiredSession() = runTest {
        val f = AutomationFixture(this)
        f.gateway.onQuery = { _, _ -> QueryResult.None }
        f.enter()
        f.advance(9_999)
        f.identity = f.identity.copy(windowId = 8)
        f.enter()
        f.advance(1)
        f.enter()
        f.gateway.onQuery = { _, _ -> QueryResult.Unique }
        f.advance(120)
        assertTrue(f.gateway.clicks.isEmpty())
        f.enter()
        f.advance(120)
        assertTrue(f.gateway.clicks.isEmpty())
        f.outside()
        f.enter()
        f.advance(120)
        assertEquals(1, f.gateway.clicks.size)
    }

    @Test fun ac02_contentBurstUpdatesIdentityWithoutPostponingFirstQuery() = runTest {
        val f = AutomationFixture(this)
        f.enter()
        repeat(3) { index ->
            f.advance(30)
            f.identity = f.identity.copy(windowId = 10 + index)
            f.content()
        }
        f.advance(29)
        assertTrue(f.gateway.queries.isEmpty())
        f.advance(1)
        assertEquals(f.identity, f.gateway.queries.single().first)
        assertEquals(1, f.gateway.clicks.size)
    }
}
