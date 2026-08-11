package com.zhenghuaizhi.flashskip.platform.packages

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.zhenghuaizhi.flashskip.domain.model.AppVersion
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.ports.TargetAppVersionReader
import com.zhenghuaizhi.flashskip.domain.rules.TargetAppRegistry

class AndroidTargetAppVersionReader(
    context: Context,
) : TargetAppVersionReader {
    private val packageManager = context.applicationContext.packageManager

    override fun versionFor(targetAppId: TargetAppId): AppVersion? = runCatching {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                TargetAppRegistry.expectedPackage(targetAppId),
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(TargetAppRegistry.expectedPackage(targetAppId), 0)
        }
        AppVersion(
            versionName = packageInfo.versionName?.take(MAX_VERSION_NAME_LENGTH),
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            },
        )
    }.getOrNull()

    fun verifiedActivity(packageName: String, className: String?): String? {
        val rawClassName = className?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val activityName = when {
            rawClassName.startsWith('.') -> packageName + rawClassName
            '.' !in rawClassName -> "$packageName.$rawClassName"
            else -> rawClassName
        }.take(MAX_ACTIVITY_LENGTH)
        val component = ComponentName(packageName, activityName)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getActivityInfo(
                    component,
                    PackageManager.ComponentInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getActivityInfo(component, 0)
            }
            activityName
        }.getOrNull()
    }

    private companion object {
        const val MAX_VERSION_NAME_LENGTH = 80
        const val MAX_ACTIVITY_LENGTH = 240
    }
}
