package com.zhenghuaizhi.flashskip.domain.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.TextCondition
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

    fun isBareNumericCountdown(value: String?): Boolean =
        TextCondition.NumericCountdown.matches(value)

    /** 资源 ID 仅用于差分候选排序，不能单独绕过稳定结构和唯一性门禁。 */
    fun isKnownResourceId(value: String?): Boolean {
        val name = value?.substringAfterLast('/')?.lowercase(Locale.ROOT) ?: return false
        return DISMISS_RESOURCE_TOKEN.containsMatchIn(name)
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
    private val DISMISS_RESOURCE_TOKEN = Regex(
        pattern = "(?:^|_)(?:skip|close|dismiss)(?:_|$)",
        option = RegexOption.IGNORE_CASE,
    )
}
