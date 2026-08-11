package com.zhenghuaizhi.flashskip.domain.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.CapturedNode
import java.util.Locale

data class ClickSemanticHint(
    val texts: Set<String> = emptySet(),
    val contentDescription: String? = null,
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
            DismissSemantics.isKnown(node.contentDescription)
    }
}

internal object DismissSemantics {
    fun isKnown(value: String?): Boolean = value
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.let { normalized ->
            normalized in KNOWN_VALUES || COUNTDOWN_VALUE.matches(normalized)
        }
        ?: false

    private val KNOWN_VALUES = setOf(
        "关闭",
        "关闭广告",
        "跳过",
        "跳过广告",
        "close",
        "skip",
        "x",
        "×",
    )
    private val COUNTDOWN_VALUE = Regex("^(跳过(?:广告)?|skip)\\s*\\d+\\s*(?:秒|s)?$")
}
