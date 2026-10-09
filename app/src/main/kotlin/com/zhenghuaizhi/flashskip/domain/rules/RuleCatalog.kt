package com.zhenghuaizhi.flashskip.domain.rules

import com.zhenghuaizhi.flashskip.domain.model.AdRule
import com.zhenghuaizhi.flashskip.domain.model.AppVersion
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.RuleCandidateAnalysis
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import kotlinx.coroutines.flow.StateFlow

/** 同一批样本的规则与展示分析共同发布，调用者仅以只读方式使用快照集合。 */
data class RuleCatalogSnapshot(
    val rules: List<AdRule> = emptyList(),
    val capturedTargets: List<CapturedTargetRules> = emptyList(),
    val sourceSamples: List<CaptureSample> = emptyList(),
) {
    // 索引归属于本次快照，保留输入顺序；data class copy 会依据新 rules 重新构建。
    private val rulesByPackage = rules.groupBy(AdRule::packageName)
    private val rulesByTarget = rules.groupBy(AdRule::targetAppId)

    /** 包含所有规则的目标包，包括仅适用于特定版本的规则；调用方以只读方式使用。 */
    val targetPackages: Set<String> = rulesByPackage.keys.toSet()

    fun rulesForPackage(packageName: String, appVersion: AppVersion? = null): List<AdRule> =
        rulesByPackage[packageName].orEmpty().filter { it.appliesTo(appVersion) }

    fun rulesForTarget(targetAppId: TargetAppId, appVersion: AppVersion? = null): List<AdRule> =
        rulesByTarget[targetAppId].orEmpty().filter { it.appliesTo(appVersion) }

    private fun AdRule.appliesTo(appVersion: AppVersion?): Boolean =
        targetAppVersion == null || appVersion != null && targetAppVersion == appVersion
}

/** 展示元数据与规则分析来自同一组样本，按最近采集时间排列在目录快照中。 */
data class CapturedTargetRules(
    val targetAppId: TargetAppId,
    val displayName: String,
    val latestCapturedAtEpochMs: Long,
    val analysis: RuleCandidateAnalysis,
)

interface RuleCatalog {
    val snapshot: StateFlow<RuleCatalogSnapshot>

    fun rulesForPackage(packageName: String): List<AdRule> =
        snapshot.value.rulesForPackage(packageName)

    fun rulesForTarget(targetAppId: TargetAppId): List<AdRule> =
        snapshot.value.rulesForTarget(targetAppId)
}
