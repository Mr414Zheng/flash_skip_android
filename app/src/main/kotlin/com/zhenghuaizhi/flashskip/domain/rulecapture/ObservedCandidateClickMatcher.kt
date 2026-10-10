package com.zhenghuaizhi.flashskip.domain.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import com.zhenghuaizhi.flashskip.domain.model.ObservedDismissCandidate
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import java.util.Locale

internal object ObservedCandidateClickMatcher {
    fun matches(
        candidate: ObservedDismissCandidate,
        candidateIdentity: PageIdentity,
        clickedIdentity: PageIdentity,
        clickedNodes: List<CapturedNode>,
    ): Boolean {
        if (candidateIdentity != clickedIdentity ||
            candidateIdentity.packageName != candidate.targetAppId.packageName ||
            candidateIdentity.activityName != candidate.activityName ||
            candidateIdentity.activityName.isNullOrBlank()
        ) {
            return false
        }

        if (RuleCandidateAnalyzer.selectorCandidates(candidate.nodes).isEmpty() ||
            RuleCandidateAnalyzer.selectorCandidates(clickedNodes).isEmpty()
        ) {
            return false
        }

        val candidateSource = candidate.nodes.firstOrNull() ?: return false
        val clickedSource = clickedNodes.firstOrNull() ?: return false
        if (!candidateSource.visibleToUser || !candidateSource.hasUsableBounds ||
            !clickedSource.visibleToUser || !clickedSource.hasUsableBounds
        ) {
            return false
        }
        val candidateSemantic = candidateSource.dismissSemantic() ?: return false
        val candidateAction = candidate.nodes.firstActionableNode() ?: return false
        val candidateLabels = listOf(candidateSource, candidateAction)
            .distinct()
            .flatMap { node -> node.labels() }
        if (candidateLabels.any { dismissKey(it) != candidateSemantic }) return false

        val clickedAction = clickedNodes.firstActionableNode() ?: return false
        val clickedLabels = listOf(clickedSource, clickedAction)
            .distinct()
            .flatMap { node -> node.labels() }
        if (clickedLabels.any { dismissKey(it) != candidateSemantic }) return false

        val candidateSourceId = candidateSource.resourceId?.takeIf(::isStableResourceId)
        val clickedSourceId = clickedSource.resourceId?.takeIf(::isStableResourceId)
        val sameActionId = candidateAction.dismissSemantic() == candidateSemantic &&
            candidateAction.resourceId?.takeIf(::isStableResourceId)
                ?.let { it == clickedAction.resourceId } == true
        val candidateActionId = candidateAction.resourceId?.takeIf(::isStableResourceId)
        val stableSourceIdsDiffer = candidateSourceId != null &&
            clickedSourceId != null &&
            candidateSourceId != clickedSourceId
        if (stableSourceIdsDiffer && clickedSourceId != candidateActionId) return false
        val sameSourceId = candidateSourceId != null && candidateSourceId == clickedSourceId
        val clickedSourceIsCandidateAction = stableSourceIdsDiffer &&
            candidateActionId != null &&
            clickedSourceId == candidateActionId

        return sameSourceId || sameActionId || clickedSourceIsCandidateAction
    }

    private fun CapturedNode.dismissSemantic(): String? =
        dismissKey(text) ?: dismissKey(contentDescription)

    private fun CapturedNode.labels(): List<String> =
        listOfNotNull(text?.trim()?.takeIf(String::isNotEmpty), contentDescription?.trim()?.takeIf(String::isNotEmpty))

    private fun dismissKey(value: String?): String? {
        val trimmed = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        DismissSemantics.skipPrefix(trimmed)?.let { return it.lowercase(Locale.ROOT) }
        return trimmed.lowercase(Locale.ROOT)
            .takeIf { DismissSemantics.isKnown(trimmed) }
    }

    private fun List<CapturedNode>.firstActionableNode(): CapturedNode? =
        firstOrNull { node ->
            node.visibleToUser && node.hasUsableBounds &&
                (node.clickable || node.supportsActionClick)
        }

    private fun isStableResourceId(value: String): Boolean =
        value.isNotBlank() && !value.startsWith("android:")
}
