package com.zhenghuaizhi.flashskip.domain.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.CapturedNode

data class ClickSemanticHint(
    val texts: Set<String> = emptySet(),
    val contentDescription: String? = null,
    val allowBareNumericCountdown: Boolean = false,
) {
    val hasEventSemantics: Boolean
        get() = texts.isNotEmpty() || contentDescription != null
}

object ClickSourceRecoveryMatcher {
    fun matches(node: CapturedNode, hint: ClickSemanticHint): Boolean {
        if (!node.visibleToUser || !node.hasUsableBounds) return false
        if (hint.hasEventSemantics) {
            if (hint.texts.isNotEmpty() && node.text !in hint.texts) return false
            if (hint.contentDescription != null &&
                node.contentDescription != hint.contentDescription
            ) {
                return false
            }
            return true
        }
        return DismissSemantics.isKnown(node.text) ||
            DismissSemantics.isKnown(node.contentDescription) ||
            (hint.allowBareNumericCountdown &&
                DismissSemantics.isBareNumericCountdown(node.text))
    }
}
