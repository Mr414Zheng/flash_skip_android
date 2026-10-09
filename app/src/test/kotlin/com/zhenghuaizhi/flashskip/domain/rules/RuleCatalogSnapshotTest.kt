package com.zhenghuaizhi.flashskip.domain.rules

import com.zhenghuaizhi.flashskip.domain.model.AdRule
import com.zhenghuaizhi.flashskip.domain.model.AppVersion
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleCatalogSnapshotTest {
    @Test
    fun indexedQueriesMatchOriginalFilteringForEveryVersionAndPreserveOrder() {
        val version = AppVersion("1.0", 1)
        val rules = listOf(
            rule("local-any", "test.reader"),
            rule("local-version", "test.reader", version),
            rule("other-app", "test.other"),
            rule("builtin-any", "test.reader"),
            rule("only-version", "test.versioned", version),
            rule("different-target", "test.reader", targetAppId = TargetAppId("test.other")),
        )
        val snapshot = RuleCatalogSnapshot(rules)
        val versions = listOf(null, version, AppVersion("2.0", 2), AppVersion("renamed", 1))
        for (requestedVersion in versions) {
            for (packageName in listOf("test.reader", "test.other", "test.versioned", "test.missing")) {
                val expected = rules.filter { rule ->
                    rule.packageName == packageName &&
                        (rule.targetAppVersion == null ||
                            requestedVersion != null && rule.targetAppVersion == requestedVersion)
                }
                assertEquals(expected, snapshot.rulesForPackage(packageName, requestedVersion))
            }
            for (target in listOf("test.reader", "test.other", "test.versioned", "test.missing")) {
                val targetId = TargetAppId(target)
                val expected = rules.filter { rule ->
                    rule.targetAppId == targetId &&
                        (rule.targetAppVersion == null ||
                            requestedVersion != null && rule.targetAppVersion == requestedVersion)
                }
                assertEquals(expected, snapshot.rulesForTarget(targetId, requestedVersion))
            }
        }

        assertEquals(
            listOf("local-any", "local-version", "builtin-any", "different-target"),
            snapshot.rulesForPackage("test.reader", version).map(AdRule::id),
        )
        assertEquals(
            setOf("test.reader", "test.other", "test.versioned"),
            snapshot.targetPackages,
        )
        assertTrue(snapshot.rulesForPackage("test.versioned").isEmpty())
    }

    @Test
    fun dataClassCopyBuildsIndexesForNewRulesWithoutChangingOriginalSnapshot() {
        val originalRule = rule("original", "test.original")
        val replacement = rule("replacement", "test.replacement")
        val original = RuleCatalogSnapshot(listOf(originalRule))
        val copied = original.copy(rules = listOf(replacement))

        assertEquals(setOf("test.original"), original.targetPackages)
        assertEquals(listOf(originalRule), original.rulesForPackage("test.original"))
        assertEquals(setOf("test.replacement"), copied.targetPackages)
        assertEquals(listOf(replacement), copied.rulesForPackage("test.replacement"))
        assertEquals(listOf(replacement), copied.rulesForTarget(replacement.targetAppId))
        assertTrue(copied.rulesForPackage("test.original").isEmpty())
        assertTrue(copied.rulesForTarget(originalRule.targetAppId).isEmpty())
        assertEquals(original, original.copy())
    }

    @Test
    fun emptySnapshotHasNoTargetsOrMatches() {
        val snapshot = RuleCatalogSnapshot()

        assertTrue(snapshot.targetPackages.isEmpty())
        assertTrue(snapshot.rulesForPackage("test.missing").isEmpty())
        assertTrue(snapshot.rulesForTarget(TargetAppId("test.missing"), AppVersion("1", 1)).isEmpty())
    }

    @Test
    fun repeatedQueriesDoNotTraverseTheFullRuleListAfterSnapshotConstruction() {
        val rules = CountingList(
            List(1_000) { index -> rule("rule-$index", "test.app${index % 100}") },
        )
        val snapshot = RuleCatalogSnapshot(rules)
        assertTrue(rules.elementReads >= rules.size)
        rules.elementReads = 0

        repeat(1_000) {
            assertEquals(10, snapshot.rulesForPackage("test.app50").size)
            assertEquals(10, snapshot.rulesForTarget(TargetAppId("test.app60")).size)
            assertEquals(100, snapshot.targetPackages.size)
            assertTrue(snapshot.rulesForPackage("test.missing").isEmpty())
        }

        assertEquals(0, rules.elementReads)
    }

    private class CountingList(private val values: List<AdRule>) : AbstractList<AdRule>() {
        var elementReads = 0

        override val size: Int
            get() = values.size

        override fun get(index: Int): AdRule {
            elementReads++
            return values[index]
        }
    }

    private fun rule(
        id: String,
        packageName: String,
        version: AppVersion? = null,
        targetAppId: TargetAppId = TargetAppId(packageName),
    ) = AdRule(
        id = id,
        targetAppId = targetAppId,
        packageName = packageName,
        allowedActivities = setOf("$packageName.MainActivity"),
        selectors = listOf(NodeSelector(resourceId = "$packageName:id/skip")),
        startupDelayMs = 0,
        cooldownMs = 1_000,
        targetAppVersion = version,
    )
}
