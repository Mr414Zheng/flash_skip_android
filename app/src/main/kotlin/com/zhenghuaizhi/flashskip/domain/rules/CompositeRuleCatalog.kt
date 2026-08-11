package com.zhenghuaizhi.flashskip.domain.rules

import com.zhenghuaizhi.flashskip.domain.model.AdRule
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.RuleCaptureStore
import com.zhenghuaizhi.flashskip.domain.rulecapture.RuleCandidateAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class CompositeRuleCatalog(
    builtinCatalog: BuiltinRuleCatalog,
    captureStore: RuleCaptureStore,
    scope: CoroutineScope,
) : RuleCatalog {
    override val snapshot: StateFlow<RuleCatalogSnapshot> = combine(
        builtinCatalog.snapshot,
        captureStore.state,
    ) { builtin, captureState ->
        val localRules = TargetAppId.entries.flatMap { targetAppId ->
            captureState.samples
                .asSequence()
                .filter { it.targetAppId == targetAppId }
                .map { it.appVersion }
                .distinct()
                .mapNotNull { appVersion ->
                    RuleCandidateAnalyzer.analyze(
                        samples = captureState.samples,
                        targetAppId = targetAppId,
                        currentVersion = appVersion,
                    ).rule
                }
                .toList()
        }
        val validatedLocalRules = localRules.filter(::isValidRule)
        val combinedRules = validatedLocalRules + builtin.rules
        RuleCatalogSnapshot(
            // 本机规则优先；如果它没有命中，自动化引擎会继续检查内置规则。
            rules = if (RuleValidator.validate(combinedRules).isEmpty()) {
                combinedRules
            } else {
                builtin.rules
            },
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = builtinCatalog.snapshot.value,
    )

    private fun isValidRule(rule: AdRule): Boolean =
        RuleValidator.validate(listOf(rule)).isEmpty()
}
