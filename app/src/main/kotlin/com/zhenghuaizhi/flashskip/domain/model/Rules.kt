package com.zhenghuaizhi.flashskip.domain.model

data class AdRule(
    val id: String,
    val targetAppId: TargetAppId,
    val packageName: String,
    val targetAppName: String = packageName,
    val allowedActivities: Set<String>,
    val selectors: List<NodeSelector>,
    val startupDelayMs: Long,
    val cooldownMs: Long,
    val targetAppVersion: AppVersion? = null,
)

data class NodeSelector(
    val resourceId: String? = null,
    val text: TextCondition? = null,
    val contentDescription: String? = null,
    val ancestorResourceId: String? = null,
    val ancestorDistance: Int? = null,
    val actionAncestorDistance: Int? = null,
) {
    val hasConditions: Boolean
        get() = resourceId != null || text != null || contentDescription != null

    val hasAncestorConstraint: Boolean
        get() = ancestorResourceId != null || ancestorDistance != null

    companion object {
        const val MAX_ANCESTOR_DISTANCE = 4
    }
}

sealed interface TextCondition {
    data class Exact(val value: String) : TextCondition
    data class Prefix(val value: String) : TextCondition

    data object NumericCountdown : TextCondition {
        fun matches(value: String?): Boolean = value
            ?.trim()
            ?.let(PATTERN::matches)
            ?: false

        private val PATTERN = Regex("^[0-9０-９]{1,2}\\s*(?:秒|[sS])?$")
    }
}

data class NodeSnapshot(
    val resourceId: String?,
    val text: String?,
    val contentDescription: String?,
    val visibleToUser: Boolean,
    val hasUsableBounds: Boolean,
)
