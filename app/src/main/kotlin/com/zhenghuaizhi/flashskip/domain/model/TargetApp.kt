package com.zhenghuaizhi.flashskip.domain.model

enum class TargetAppId {
    QQ_MUSIC,
    TAOBAO,
    JD,
}

data class TargetApp(
    val id: TargetAppId,
    val displayName: String,
    val packageName: String,
    val ruleAvailability: RuleAvailability,
)

sealed interface RuleAvailability {
    data class Available(val ruleCount: Int) : RuleAvailability
    data object Unavailable : RuleAvailability
}
