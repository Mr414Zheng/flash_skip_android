package com.zhenghuaizhi.flashskip.domain.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.AdRule
import com.zhenghuaizhi.flashskip.domain.model.AppVersion
import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.RuleCandidateAnalysis
import com.zhenghuaizhi.flashskip.domain.model.RuleCardStatus
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.rules.TargetAppRegistry
import java.util.Locale

object RuleCandidateAnalyzer {
    const val REQUIRED_STABLE_SAMPLES = 1
    const val REQUIRED_UNIQUE_PROBES = 0
    const val COOLDOWN_MS = 10_000L

    fun analyze(
        samples: List<CaptureSample>,
        targetAppId: TargetAppId,
        currentVersion: AppVersion?,
    ): RuleCandidateAnalysis {
        val targetSamples = samples.filter { it.targetAppId == targetAppId }
        val currentSamples = currentVersion?.let { version ->
            targetSamples.filter {
                it.appVersion == version &&
                    it.clickedAfterTargetMs >= 0 &&
                    it.activityName.isNotBlank()
            }
        }.orEmpty()
        val ranked = rankSelectors(currentSamples)
        val primary = ranked.firstOrNull { it.ready } ?: ranked.firstOrNull()
        val readyCandidates = ranked.filter(SelectorRank::ready)
        val status = when {
            currentVersion == null && targetSamples.isNotEmpty() -> RuleCardStatus.STALE
            currentVersion != null && currentSamples.isEmpty() && targetSamples.isNotEmpty() ->
                RuleCardStatus.STALE
            readyCandidates.isNotEmpty() -> RuleCardStatus.READY
            else -> RuleCardStatus.CANDIDATE
        }
        val primarySamples = primary?.samples.orEmpty()
        val activities = primarySamples.map(CaptureSample::activityName).toSet()
        val minimumDelay = primarySamples.minOfOrNull(CaptureSample::clickedAfterTargetMs)
        val rule = if (status == RuleCardStatus.READY && currentVersion != null) {
            AdRule(
                id = localRuleId(targetAppId, currentVersion, requireNotNull(primary).selector),
                targetAppId = targetAppId,
                packageName = TargetAppRegistry.expectedPackage(targetAppId),
                allowedActivities = activities,
                selectors = readyCandidates.take(MAX_RULE_SELECTORS).map {
                    it.selector.toRuntimeSelector()
                },
                // 人工点击时序包含反应时间，仅作为证据；Ready 规则从首个页面事件开始安全匹配。
                startupDelayMs = 0L,
                cooldownMs = COOLDOWN_MS,
                targetAppVersion = currentVersion,
            )
        } else {
            null
        }
        return RuleCandidateAnalysis(
            targetAppId = targetAppId,
            appVersion = currentVersion,
            status = status,
            currentVersionSampleCount = currentSamples.size,
            stableSampleCount = primarySamples.size,
            uniqueEvidenceCount = primary?.uniqueEvidenceCount ?: 0,
            primarySelector = primary?.selector,
            probeSelectors = ranked.filter { it.strongEnough }.take(MAX_PROBE_SELECTORS).map {
                it.selector
            },
            allowedActivities = activities,
            minimumClickDelayMs = minimumDelay,
            rule = rule,
        )
    }

    fun selectorCandidates(nodes: List<CapturedNode>): List<CapturedSelector> {
        val actionTargetIndex = nodes.indexOfFirst { node ->
            node.visibleToUser &&
                node.hasUsableBounds &&
                (node.clickable || node.supportsActionClick)
        }
        if (actionTargetIndex < 0) return emptyList()
        val targetSelectors = selectorsForTarget(nodes[actionTargetIndex])
        val ancestorSelectors = nodes
            .drop(actionTargetIndex + 1)
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
                    supporting.size == samples.size &&
                    uniqueCount >= REQUIRED_UNIQUE_PROBES &&
                    strongEnough &&
                    supporting.all { it.activityName.isNotBlank() },
            )
        }.sortedWith(
            compareByDescending<SelectorRank> { it.ready }
                .thenByDescending { it.samples.size }
                .thenByDescending { it.uniqueEvidenceCount }
                .thenByDescending { it.selector.conditionCount }
                .thenByDescending { it.selector.resourceId != null }
                .thenByDescending { it.selector.contentDescription != null },
        )
    }

    private fun CapturedSelector.isStrongEnough(): Boolean {
        if (conditionCount >= 2 || resourceId != null) return true
        val semantic = text ?: contentDescription ?: return false
        return !DismissSemantics.isKnown(semantic)
    }

    private fun MutableList<CapturedSelector>.addIfUsable(selector: CapturedSelector) {
        if (selector.conditionCount > 0) add(selector)
    }

    private fun localRuleId(
        targetAppId: TargetAppId,
        version: AppVersion,
        selector: CapturedSelector,
    ): String {
        val versionFingerprint = "${version.versionCode}:${version.versionName.orEmpty()}"
            .hashCode().toUInt().toString(16)
        val fingerprint = selector.stableKey().hashCode().toUInt().toString(16)
        return "local-${targetAppId.name.lowercase(Locale.ROOT)}-$versionFingerprint-$fingerprint"
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
