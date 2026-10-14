package com.zhenghuaizhi.flashskip.domain.rules

import com.zhenghuaizhi.flashskip.domain.model.AdRule
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.ports.RuleCaptureStore
import com.zhenghuaizhi.flashskip.domain.rulecapture.RuleCandidateAnalyzer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class CompositeRuleCatalog(
    builtinCatalog: BuiltinRuleCatalog,
    captureStore: RuleCaptureStore,
    scope: CoroutineScope,
    computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : RuleCatalog {
    private val capturedRules = captureStore.reads
        .map { it.value?.samples.orEmpty() to it.available }
        .distinctUntilChanged()
        .mapLatest { (samples, available) ->
            val targets = samples.groupBy(CaptureSample::targetAppId).map { (targetAppId, group) ->
                yield()
                val latest = group.maxBy(CaptureSample::capturedAtEpochMs)
                CapturedTargetRules(
                    targetAppId = targetAppId,
                    displayName = latest.targetAppName,
                    latestCapturedAtEpochMs = latest.capturedAtEpochMs,
                    analysis = RuleCandidateAnalyzer.analyze(group, targetAppId),
                )
            }
            // 分组之间及发布前让出执行权，使上游有机会接收新样本并取消旧计算。
            yield()
            currentCoroutineContext().ensureActive()
            CapturedRules(samples.toList(), targets, available)
        }

    override val snapshot: StateFlow<RuleCatalogSnapshot> = combine(
        builtinCatalog.snapshot,
        capturedRules,
    ) { builtin, captured ->
        val validatedLocalRules = captured.targets
            .flatMap { it.analysis.rules }
            .filter(::isValidRule)
        val combinedRules = validatedLocalRules + builtin.rules
        RuleCatalogSnapshot(
            // 本机规则优先；如果它没有命中，自动化引擎会继续检查内置规则。
            rules = if (!captured.available) {
                emptyList()
            } else if (RuleValidator.validate(combinedRules).isEmpty()) {
                combinedRules
            } else {
                builtin.rules
            },
            capturedTargets = captured.targets.sortedByDescending { it.latestCapturedAtEpochMs },
            sourceSamples = captured.samples,
        )
    }.flowOn(computationDispatcher).stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = RuleCatalogSnapshot(),
    )

    private fun isValidRule(rule: AdRule): Boolean =
        RuleValidator.validate(listOf(rule)).isEmpty()

    private data class CapturedRules(
        val samples: List<CaptureSample>,
        val targets: List<CapturedTargetRules>,
        val available: Boolean,
    )
}
