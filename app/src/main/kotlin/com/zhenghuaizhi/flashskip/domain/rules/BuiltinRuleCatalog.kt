package com.zhenghuaizhi.flashskip.domain.rules

import com.zhenghuaizhi.flashskip.domain.model.AdRule
import com.zhenghuaizhi.flashskip.domain.model.RuleAvailability
import com.zhenghuaizhi.flashskip.domain.model.TargetApp
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId

class BuiltinRuleCatalog(
    rules: List<AdRule> = emptyList(),
) {
    val validationIssues: Set<RuleValidationIssue> = RuleValidator.validate(rules)
    private val validatedRules = if (validationIssues.isEmpty()) rules else emptyList()

    val targets: List<TargetApp> = TARGETS.map { target ->
        val ruleCount = validatedRules.count { it.targetAppId == target.id }
        target.copy(
            ruleAvailability = if (ruleCount > 0) {
                RuleAvailability.Available(ruleCount)
            } else {
                RuleAvailability.Unavailable
            },
        )
    }

    fun rulesForPackage(packageName: String): List<AdRule> =
        validatedRules.filter { it.packageName == packageName }

    fun rulesForTarget(targetAppId: TargetAppId): List<AdRule> =
        validatedRules.filter { it.targetAppId == targetAppId }

    companion object {
        private val TARGETS = listOf(
            TargetApp(
                id = TargetAppId.QQ_MUSIC,
                displayName = "QQ音乐",
                packageName = "com.tencent.qqmusic",
                ruleAvailability = RuleAvailability.Unavailable,
            ),
            TargetApp(
                id = TargetAppId.TAOBAO,
                displayName = "淘宝",
                packageName = "com.taobao.taobao",
                ruleAvailability = RuleAvailability.Unavailable,
            ),
            TargetApp(
                id = TargetAppId.JD,
                displayName = "京东",
                packageName = "com.jingdong.app.mall",
                ruleAvailability = RuleAvailability.Unavailable,
            ),
        )
    }
}
