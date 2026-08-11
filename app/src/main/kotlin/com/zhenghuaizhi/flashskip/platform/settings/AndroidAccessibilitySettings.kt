package com.zhenghuaizhi.flashskip.platform.settings

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.zhenghuaizhi.flashskip.application.SystemServiceStatusReader
import com.zhenghuaizhi.flashskip.platform.accessibility.FlashSkipAccessibilityService

class AndroidSystemServiceStatusReader(
    context: Context,
) : SystemServiceStatusReader {
    private val appContext = context.applicationContext
    private val accessibilityManager =
        appContext.getSystemService(AccessibilityManager::class.java)
    private val expectedComponent = ComponentName(appContext, FlashSkipAccessibilityService::class.java)

    override fun isFlashSkipEnabled(): Boolean = accessibilityManager
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { service ->
            val serviceInfo = service.resolveInfo.serviceInfo
            ComponentName(serviceInfo.packageName, serviceInfo.name) == expectedComponent
        }
}

fun accessibilitySettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
