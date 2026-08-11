package com.zhenghuaizhi.flashskip.domain.rules

import com.zhenghuaizhi.flashskip.domain.model.RuleAvailability
import com.zhenghuaizhi.flashskip.domain.model.TargetApp
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId

object TargetAppRegistry {
    val targets: List<TargetApp> = listOf(
        target(TargetAppId.QQ_MUSIC, "QQ音乐", "com.tencent.qqmusic"),
        target(TargetAppId.TAOBAO, "淘宝", "com.taobao.taobao"),
        target(TargetAppId.JD, "京东", "com.jingdong.app.mall"),
    )

    fun target(targetAppId: TargetAppId): TargetApp =
        targets.first { it.id == targetAppId }

    fun targetForPackage(packageName: String): TargetApp? =
        targets.firstOrNull { it.packageName == packageName }

    fun expectedPackage(targetAppId: TargetAppId): String = target(targetAppId).packageName

    private fun target(id: TargetAppId, displayName: String, packageName: String) = TargetApp(
        id = id,
        displayName = displayName,
        packageName = packageName,
        ruleAvailability = RuleAvailability.Unavailable,
    )
}
