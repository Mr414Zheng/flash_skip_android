package com.zhenghuaizhi.flashskip.domain.model

data class TargetAppId(val packageName: String) {
    init {
        require(packageName.length in 3..255 && PACKAGE_NAME.matches(packageName))
    }

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    }
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
