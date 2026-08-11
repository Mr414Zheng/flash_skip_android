package com.zhenghuaizhi.flashskip.domain.rules

import com.zhenghuaizhi.flashskip.domain.model.AdRule
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.model.TextCondition

enum class RuleValidationIssue {
    DUPLICATE_ID,
    EMPTY_ID,
    PACKAGE_MISMATCH,
    NO_ACTIVITY,
    NO_SELECTOR,
    EMPTY_SELECTOR,
    NEGATIVE_DELAY,
    NEGATIVE_COOLDOWN,
}

object RuleValidator {
    fun validate(rules: List<AdRule>): Set<RuleValidationIssue> {
        val issues = mutableSetOf<RuleValidationIssue>()
        if (rules.groupingBy { it.id }.eachCount().any { it.value > 1 }) {
            issues += RuleValidationIssue.DUPLICATE_ID
        }
        rules.forEach { rule ->
            if (rule.id.isBlank()) issues += RuleValidationIssue.EMPTY_ID
            if (rule.packageName != expectedPackage(rule.targetAppId)) {
                issues += RuleValidationIssue.PACKAGE_MISMATCH
            }
            if (rule.allowedActivities.isEmpty() || rule.allowedActivities.any(String::isBlank)) {
                issues += RuleValidationIssue.NO_ACTIVITY
            }
            if (rule.selectors.isEmpty()) issues += RuleValidationIssue.NO_SELECTOR
            if (rule.selectors.any { selector ->
                    !selector.hasConditions ||
                        selector.resourceId?.isBlank() == true ||
                        selector.contentDescription?.isBlank() == true ||
                        when (val text = selector.text) {
                            is TextCondition.Exact -> text.value.isBlank()
                            is TextCondition.Prefix -> text.value.isBlank()
                            null -> false
                        }
                }
            ) {
                issues += RuleValidationIssue.EMPTY_SELECTOR
            }
            if (rule.startupDelayMs < 0) issues += RuleValidationIssue.NEGATIVE_DELAY
            if (rule.cooldownMs < 0) issues += RuleValidationIssue.NEGATIVE_COOLDOWN
        }
        return issues
    }

    private fun expectedPackage(targetAppId: TargetAppId): String = when (targetAppId) {
        TargetAppId.QQ_MUSIC -> "com.tencent.qqmusic"
        TargetAppId.TAOBAO -> "com.taobao.taobao"
        TargetAppId.JD -> "com.jingdong.app.mall"
    }
}
