package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity

internal class AccessibilityWindowRootResolver(
    private val service: AccessibilityService,
    private val targetPackages: () -> Set<String>,
) {
    @Suppress("DEPRECATION")
    fun resolve(expected: PageIdentity): Result {
        if (expected.packageName !in targetPackages()) return Result.OutsideTargets

        // 部分 OEM 的 rootInActiveWindow 会继续返回宿主 Activity；独立弹窗必须按事件窗口 ID 取根节点。
        val currentWindows = runCatching { service.windows }.getOrNull()
        if (currentWindows != null) {
            try {
                val matchingWindows = currentWindows.filter { it.id == expected.windowId }
                if (matchingWindows.size > 1) return Result.PageChanged
                matchingWindows.singleOrNull()?.let { window ->
                    if (!window.isActive || window.type != AccessibilityWindowInfo.TYPE_APPLICATION) {
                        return Result.PageChanged
                    }
                    val root = runCatching { window.root }.getOrNull() ?: return Result.NoRoot
                    return validateRoot(root, expected)
                }
            } finally {
                currentWindows.forEach(AccessibilityWindowInfo::recycle)
            }
        }

        val activeRoot = service.rootInActiveWindow ?: return Result.NoRoot
        return validateRoot(activeRoot, expected)
    }

    @Suppress("DEPRECATION")
    private fun validateRoot(root: AccessibilityNodeInfo, expected: PageIdentity): Result {
        return if (root.packageName?.toString() == expected.packageName &&
            root.windowId == expected.windowId
        ) {
            Result.Success(root)
        } else {
            root.recycle()
            Result.PageChanged
        }
    }

    sealed interface Result {
        data class Success(val root: AccessibilityNodeInfo) : Result
        data object OutsideTargets : Result
        data object NoRoot : Result
        data object PageChanged : Result
    }
}
