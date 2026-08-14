package com.zhenghuaizhi.flashskip.domain.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.AdRule
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.RuleCandidateAnalysis
import com.zhenghuaizhi.flashskip.domain.model.RuleCardStatus
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import java.util.Locale

object RuleCandidateAnalyzer {
    const val REQUIRED_STABLE_SAMPLES = 1
    const val REQUIRED_UNIQUE_PROBES = 0
    const val COOLDOWN_MS = 10_000L

    fun analyze(
        samples: List<CaptureSample>,
        targetAppId: TargetAppId,
    ): RuleCandidateAnalysis {
        val targetSamples = validSamples(samples, targetAppId)
        val groups = ruleGroups(targetSamples)
        val localRules = groups.map { it.toRule(targetAppId) }
        val primary = groups.firstOrNull()
        val ranked = rankSelectors(targetSamples)
        val status = when {
            localRules.isNotEmpty() -> RuleCardStatus.READY
            else -> RuleCardStatus.CANDIDATE
        }
        val primarySamples = primary?.samples.orEmpty()
        val activities = primarySamples.map(CaptureSample::activityName).toSet()
        val minimumDelay = primarySamples.minOfOrNull(CaptureSample::clickedAfterTargetMs)
        return RuleCandidateAnalysis(
            targetAppId = targetAppId,
            status = status,
            sampleCount = targetSamples.size,
            stableSampleCount = primarySamples.size,
            uniqueEvidenceCount = primary?.uniqueEvidenceCount ?: 0,
            primarySelector = primary?.primarySelector,
            probeSelectors = ranked.filter { it.strongEnough }.take(MAX_PROBE_SELECTORS).map {
                it.selector
            },
            allowedActivities = activities,
            minimumClickDelayMs = minimumDelay,
            rules = localRules,
        )
    }

    fun readyRules(
        samples: List<CaptureSample>,
        targetAppId: TargetAppId,
    ): List<AdRule> = ruleGroups(validSamples(samples, targetAppId)).map {
        it.toRule(targetAppId)
    }

    fun selectorCandidates(nodes: List<CapturedNode>): List<CapturedSelector> {
        val actionTargetIndex = nodes.indexOfFirst { node ->
            node.visibleToUser &&
                node.hasUsableBounds &&
                (node.clickable || node.supportsActionClick)
        }
        if (actionTargetIndex < 0) return emptyList()
        return buildList {
            if (actionTargetIndex > 0) {
                addAll(
                    scopedSelectors(
                        nodes = nodes,
                        targetIndex = 0,
                        actionAncestorDistance = actionTargetIndex,
                    ),
                )
            }
            addAll(scopedSelectors(nodes, actionTargetIndex))
        }.distinct().filter { selector ->
            !DismissSemantics.isBareNumericCountdown(selector.text) ||
                selector.hasStableResourceId() ||
                selector.hasStableAncestor()
        }
    }

    /** 差分路径只保留能够在下次窗口中重新查询的稳定结构，拒绝纯文本猜测。 */
    internal fun stableDiffSelectorCandidates(nodes: List<CapturedNode>): List<CapturedSelector> =
        selectorCandidates(nodes)
            .filter { selector ->
                selector.isStrongEnough() &&
                    (selector.hasStableResourceId() || selector.hasStableAncestor())
            }
            .sortedWith(
                compareByDescending<CapturedSelector> {
                    DismissSemantics.isKnownResourceId(it.resourceId)
                }.thenByDescending { it.hasStableResourceId() }
                    .thenByDescending { it.hasStableAncestor() }
                    .thenBy { it.conditionCount },
            )

    private fun scopedSelectors(
        nodes: List<CapturedNode>,
        targetIndex: Int,
        actionAncestorDistance: Int? = null,
    ): List<CapturedSelector> {
        val targetSelectors = selectorsForTarget(nodes[targetIndex]).map { selector ->
            selector.copy(actionAncestorDistance = actionAncestorDistance)
        }
        val ancestorSelectors = nodes
            .drop(targetIndex + 1)
            .take(NodeSelector.MAX_ANCESTOR_DISTANCE)
            .mapIndexedNotNull { index, ancestor ->
                ancestor.resourceId?.takeIf { resourceId ->
                    resourceId.isNotBlank() && ancestor.visibleToUser && ancestor.hasUsableBounds
                }?.let { resourceId -> resourceId to index + 1 }
            }
        return buildList {
            targetSelectors.forEach { target ->
                ancestorSelectors.forEach { (resourceId, distance) ->
                    add(
                        target.copy(
                            ancestorResourceId = resourceId,
                            ancestorDistance = distance,
                        ),
                    )
                }
                add(target)
            }
        }.distinct()
    }

    private fun selectorsForTarget(node: CapturedNode): List<CapturedSelector> = buildList {
        addIfUsable(CapturedSelector(node.resourceId, node.text, node.contentDescription))
        addIfUsable(CapturedSelector(resourceId = node.resourceId, text = node.text))
        addIfUsable(
            CapturedSelector(
                resourceId = node.resourceId,
                contentDescription = node.contentDescription,
            ),
        )
        addIfUsable(
            CapturedSelector(
                text = node.text,
                contentDescription = node.contentDescription,
            ),
        )
        addIfUsable(CapturedSelector(resourceId = node.resourceId))
        addIfUsable(CapturedSelector(contentDescription = node.contentDescription))
        addIfUsable(CapturedSelector(text = node.text))
    }.distinct()

    private fun rankSelectors(samples: List<CaptureSample>): List<SelectorRank> {
        val selectorsBySample = samples.associateWith { selectorCandidates(it.nodes).toSet() }
        return selectorsBySample.values.flatten().distinct().map { selector ->
            val supporting = samples.filter { selector in selectorsBySample.getValue(it) }
            val uniqueCount = supporting.count { selector in it.preClickUniqueSelectors }
            val strongEnough = selector.isStrongEnough()
            SelectorRank(
                selector = selector,
                samples = supporting,
                uniqueEvidenceCount = uniqueCount,
                strongEnough = strongEnough,
                ready = supporting.size >= REQUIRED_STABLE_SAMPLES &&
                    uniqueCount >= REQUIRED_UNIQUE_PROBES &&
                    strongEnough &&
                    supporting.all { it.activityName.isNotBlank() },
            )
        }.sortedWith(
            compareByDescending<SelectorRank> { it.ready }
                .thenByDescending { it.selector.hasStableResourceId() }
                .thenByDescending { it.selector.hasStableAncestor() }
                .thenByDescending { it.samples.size }
                .thenByDescending { it.uniqueEvidenceCount }
                .thenByDescending { it.selector.conditionCount }
                .thenByDescending { it.selector.resourceId != null }
                .thenByDescending { it.selector.contentDescription != null },
        )
    }

    private fun validSamples(
        samples: List<CaptureSample>,
        targetAppId: TargetAppId,
    ): List<CaptureSample> = samples.filter {
        it.targetAppId == targetAppId &&
            it.clickedAfterTargetMs >= 0 &&
            it.activityName.isNotBlank()
    }

    private fun ruleGroups(samples: List<CaptureSample>): List<RuleGroup> {
        val evidence = samples.mapNotNull { sample ->
            val readySelectors = rankSelectors(listOf(sample))
                .filter(SelectorRank::ready)
                .take(MAX_RULE_SELECTORS)
                .map(SelectorRank::selector)
            val primary = readySelectors.firstOrNull() ?: return@mapNotNull null
            RuleEvidence(sample, primary, readySelectors)
        }
        return evidence
            .groupBy { it.primarySelector.stableKey() }
            .values
            .map { matching ->
                RuleGroup(
                    primarySelector = matching.first().primarySelector,
                    selectors = matching.flatMap(RuleEvidence::selectors).distinct(),
                    samples = matching.map(RuleEvidence::sample),
                )
            }
            .sortedByDescending { group ->
                group.samples.maxOf(CaptureSample::capturedAtEpochMs)
            }
    }

    private fun RuleGroup.toRule(targetAppId: TargetAppId): AdRule = AdRule(
        id = localRuleId(targetAppId, primarySelector),
        targetAppId = targetAppId,
        packageName = targetAppId.packageName,
        targetAppName = samples.maxBy(CaptureSample::capturedAtEpochMs).targetAppName,
        allowedActivities = samples.map(CaptureSample::activityName).toSet(),
        selectors = selectors.take(MAX_RULE_SELECTORS).map { it.toRuntimeSelector() },
        // 人工点击时序只作为采集证据；本机规则从目标页面首个事件开始安全匹配。
        startupDelayMs = 0L,
        cooldownMs = COOLDOWN_MS,
        targetAppVersion = null,
    )

    private fun CapturedSelector.isStrongEnough(): Boolean {
        val hasGenericDismissSemantic = listOf(text, contentDescription).any { semantic ->
            DismissSemantics.isKnown(semantic) ||
                DismissSemantics.isBareNumericCountdown(semantic)
        }
        if (hasGenericDismissSemantic) {
            return hasStableResourceId() || hasStableAncestor()
        }
        if (hasStableResourceId()) return true
        if (hasStableAncestor() && (text != null || contentDescription != null)) return true
        if (text != null && contentDescription != null) return true
        val semantic = text ?: contentDescription ?: return false
        return semantic.isNotBlank()
    }

    private fun MutableList<CapturedSelector>.addIfUsable(selector: CapturedSelector) {
        if (selector.conditionCount > 0) add(selector)
    }

    private fun localRuleId(
        targetAppId: TargetAppId,
        selector: CapturedSelector,
    ): String {
        val fingerprint = selector.stableKey().hashCode().toUInt().toString(16)
        val packageFingerprint = targetAppId.packageName.lowercase(Locale.ROOT)
            .hashCode().toUInt().toString(16)
        return "local-$packageFingerprint-$fingerprint"
    }

    private data class RuleEvidence(
        val sample: CaptureSample,
        val primarySelector: CapturedSelector,
        val selectors: List<CapturedSelector>,
    )

    private data class RuleGroup(
        val primarySelector: CapturedSelector,
        val selectors: List<CapturedSelector>,
        val samples: List<CaptureSample>,
    ) {
        val uniqueEvidenceCount: Int
            get() = samples.count { primarySelector in it.preClickUniqueSelectors }
    }

    private data class SelectorRank(
        val selector: CapturedSelector,
        val samples: List<CaptureSample>,
        val uniqueEvidenceCount: Int,
        val strongEnough: Boolean,
        val ready: Boolean,
    )

    private const val MAX_PROBE_SELECTORS = 4
    private const val MAX_RULE_SELECTORS = 3
}
