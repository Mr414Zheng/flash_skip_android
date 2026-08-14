package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.AppVersion
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity

internal object TargetWindowIdentityResolver {
    fun resolve(
        packageName: String,
        windowId: Int,
        verifiedActivityName: String?,
        currentIdentity: PageIdentity?,
        appVersion: AppVersion?,
    ): PageIdentity? {
        // 弹窗类不能冒充 Activity，只继承同包且不同窗口的已验证宿主 Activity。
        val activityName = verifiedActivityName ?: currentIdentity?.takeIf {
            it.packageName == packageName && it.windowId != windowId
        }?.activityName ?: return null
        return PageIdentity(
            packageName = packageName,
            activityName = activityName,
            windowId = windowId,
            appVersion = appVersion,
        )
    }
}
