package com.zhenghuaizhi.flashskip.domain.rules

import com.zhenghuaizhi.flashskip.domain.model.AppVersion
import com.zhenghuaizhi.flashskip.testing.OTHER_TARGET
import com.zhenghuaizhi.flashskip.testing.TARGET
import com.zhenghuaizhi.flashskip.testing.executableRule
import org.junit.Assert.*
import org.junit.Test

class RuleCatalogSnapshotTest {
    @Test fun ac06_boundRulesRequireExactNameAndCodeWhileUnboundRulesRemainApplicable() {
        val unbound = executableRule("unbound")
        val bound = executableRule("bound").copy(targetAppVersion = AppVersion("1.0", 10))
        val snapshot = RuleCatalogSnapshot(listOf(bound, unbound))
        listOf(null, AppVersion("1.0", 11), AppVersion("2.0", 10), AppVersion(null, 10)).forEach { version ->
            assertEquals(listOf(unbound), snapshot.rulesForPackage(TARGET.packageName, version))
            assertEquals(listOf(unbound), snapshot.rulesForTarget(TARGET, version))
        }
        assertEquals(listOf(bound, unbound), snapshot.rulesForTarget(TARGET, AppVersion("1.0", 10)))
        assertEquals(setOf(TARGET.packageName), snapshot.targetPackages)
    }

    @Test fun ac06_copyRebuildsIndexesAndPreservesPriorityAndTargetIsolation() {
        val a = executableRule("a")
        val b = executableRule("b")
        val other = executableRule("other", OTHER_TARGET)
        val original = RuleCatalogSnapshot(listOf(a, other, b))
        assertEquals(listOf(a, b), original.rulesForTarget(TARGET))
        val copy = original.copy(rules = listOf(other, b))
        assertEquals(listOf(b), copy.rulesForTarget(TARGET))
        assertEquals(listOf(other), copy.rulesForTarget(OTHER_TARGET))
        assertEquals(listOf(a, b), original.rulesForPackage(TARGET.packageName))
        assertTrue(copy.rulesForPackage("missing").isEmpty())
    }
}
