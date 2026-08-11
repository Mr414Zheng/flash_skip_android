package com.zhenghuaizhi.flashskip.domain.rulecapture

import java.util.Locale

internal object DismissSemantics {
    fun isKnown(value: String?): Boolean = normalized(value)?.let { normalized ->
        normalized in KNOWN_VALUES || COUNTDOWN_VALUE.matches(normalized)
    } ?: false

    /** 仅返回能覆盖倒计时变化、同时保持明确“跳过”语义的前缀。 */
    fun skipPrefix(value: String?): String? {
        val trimmed = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return SKIP_VALUE.matchEntire(trimmed)?.groupValues?.get(1)
    }

    private fun normalized(value: String?): String? = value
        ?.trim()
        ?.lowercase(Locale.ROOT)

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
    private val SKIP_VALUE = Regex(
        pattern = "^(跳过(?:广告)?|skip)(?:\\s*\\d+\\s*(?:秒|s)?)?$",
        option = RegexOption.IGNORE_CASE,
    )
}
