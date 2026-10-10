package com.zhenghuaizhi.flashskip.platform.settings

import android.content.ActivityNotFoundException
import android.content.Intent

enum class NavigationLaunchResult { STARTED, UNAVAILABLE }

/** 系统页面并非每台设备都可打开；只把预期的导航异常映射为可恢复提示。 */
fun launchNavigation(intent: Intent, startActivity: (Intent) -> Unit): NavigationLaunchResult = try {
    startActivity(intent)
    NavigationLaunchResult.STARTED
} catch (_: ActivityNotFoundException) {
    NavigationLaunchResult.UNAVAILABLE
} catch (_: SecurityException) {
    NavigationLaunchResult.UNAVAILABLE
}

fun homeIntent(): Intent = Intent(Intent.ACTION_MAIN)
    .addCategory(Intent.CATEGORY_HOME)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
