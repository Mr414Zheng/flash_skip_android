package com.zhenghuaizhi.flashskip.domain.rules

import com.zhenghuaizhi.flashskip.domain.model.AdRule
import com.zhenghuaizhi.flashskip.domain.model.AppVersion
import com.zhenghuaizhi.flashskip.domain.model.RuleAvailability
import com.zhenghuaizhi.flashskip.domain.model.TargetApp
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import kotlinx.coroutines.flow.StateFlow

data class RuleCatalogSnapshot(
    val rules: List<AdRule> = emptyList(),
) {
    fun rulesForPackage(packageName: String, appVersion: AppVersion? = null): List<AdRule> =
        rules.filter { rule ->
            rule.packageName == packageName && rule.appliesTo(appVersion)
        }

    fun rulesForTarget(targetAppId: TargetAppId, appVersion: AppVersion? = null): List<AdRule> =
        rules.filter { rule ->
            rule.targetAppId == targetAppId && rule.appliesTo(appVersion)
        }

    fun targets(appVersions: Map<TargetAppId, AppVersion?> = emptyMap()): List<TargetApp> =
        TargetAppRegistry.targets.map { target ->
            val ruleCount = rulesForTarget(target.id, appVersions[target.id]).size
            target.copy(
                ruleAvailability = if (ruleCount > 0) {
                    RuleAvailability.Available(ruleCount)
                } else {
                    RuleAvailability.Unavailable
                },
            )
        }

    private fun AdRule.appliesTo(appVersion: AppVersion?): Boolean =
        targetAppVersion == null || appVersion != null && targetAppVersion == appVersion
}

interface RuleCatalog {
    val snapshot: StateFlow<RuleCatalogSnapshot>

    val targets: List<TargetApp>
        get() = snapshot.value.targets()

    fun rulesForPackage(packageName: String): List<AdRule> =
        snapshot.value.rulesForPackage(packageName)

    fun rulesForTarget(targetAppId: TargetAppId): List<AdRule> =
        snapshot.value.rulesForTarget(targetAppId)
}
