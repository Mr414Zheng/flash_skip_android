package com.zhenghuaizhi.flashskip.domain.rules

import com.zhenghuaizhi.flashskip.domain.model.AdRule
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class BuiltinRuleCatalog(
    rules: List<AdRule> = emptyList(),
) : RuleCatalog {
    val validationIssues: Set<RuleValidationIssue> = RuleValidator.validate(rules)
    private val validatedRules = if (validationIssues.isEmpty()) rules else emptyList()
    private val backingSnapshot = MutableStateFlow(RuleCatalogSnapshot(validatedRules))

    override val snapshot: StateFlow<RuleCatalogSnapshot> = backingSnapshot

    override fun rulesForPackage(packageName: String): List<AdRule> =
        validatedRules.filter { it.packageName == packageName }

    override fun rulesForTarget(targetAppId: TargetAppId): List<AdRule> =
        validatedRules.filter { it.targetAppId == targetAppId }
}
