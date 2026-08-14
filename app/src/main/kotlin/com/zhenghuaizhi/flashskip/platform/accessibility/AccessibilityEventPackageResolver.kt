package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityWindowInfo

internal class AccessibilityEventPackageResolver(
    private val service: AccessibilityService,
) {
    @Suppress("DEPRECATION")
    fun resolve(reportedPackageName: String, eventWindowId: Int): String {
        val currentWindows = runCatching { service.windows }.getOrNull()
            ?: return reportedPackageName
        return try {
            val matchingWindows = currentWindows.filter { it.id == eventWindowId }
            val window = matchingWindows.singleOrNull()
                ?.takeIf { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                ?: return reportedPackageName
            val root = runCatching { window.root }.getOrNull() ?: return reportedPackageName
            try {
                if (root.windowId != eventWindowId) return reportedPackageName
                effectivePackageName(
                    reportedPackageName = reportedPackageName,
                    rootPackageName = root.packageName?.toString(),
                )
            } finally {
                root.recycle()
            }
        } finally {
            currentWindows.forEach(AccessibilityWindowInfo::recycle)
        }
    }

    internal companion object {
        fun effectivePackageName(
            reportedPackageName: String,
            rootPackageName: String?,
        ): String = rootPackageName
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: reportedPackageName
    }
}
