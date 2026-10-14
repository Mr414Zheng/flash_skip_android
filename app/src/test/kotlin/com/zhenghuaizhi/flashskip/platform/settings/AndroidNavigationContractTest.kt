package com.zhenghuaizhi.flashskip.platform.settings

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AndroidNavigationContractTest {
    @Test fun settingsIntentUsesSystemAccessibilityPageAndNewTask() {
        val intent = accessibilitySettingsIntent()
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, intent.action)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertNull(intent.data); assertNull(intent.extras)
    }
    @Test fun homeIntentTargetsLauncherAndNewTask() {
        val intent = homeIntent()
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertEquals(setOf(Intent.CATEGORY_HOME), intent.categories)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertNull(intent.data); assertNull(intent.extras)
    }
    @Test fun acceptedLaunchForwardsExactIntentOnce() {
        val intent = homeIntent()
        val calls = mutableListOf<Intent>()
        assertEquals(NavigationLaunchResult.STARTED, launchNavigation(intent) { calls += it })
        assertEquals(listOf(intent), calls)
    }
    @Test fun absentActivityIsUnavailable() {
        assertEquals(NavigationLaunchResult.UNAVAILABLE, launchNavigation(homeIntent()) { throw ActivityNotFoundException() })
    }
    @Test fun securityFailureIsUnavailable() {
        assertEquals(NavigationLaunchResult.UNAVAILABLE, launchNavigation(accessibilitySettingsIntent()) { throw SecurityException() })
    }
    @Test fun unexpectedProgrammingFailurePropagates() {
        val error = IllegalArgumentException("programming")
        assertSame(error, assertThrows(IllegalArgumentException::class.java) { launchNavigation(homeIntent()) { throw error } })
    }
    @Test fun cancellationPropagates() {
        val error = kotlinx.coroutines.CancellationException()
        assertSame(error, assertThrows(kotlinx.coroutines.CancellationException::class.java) { launchNavigation(homeIntent()) { throw error } })
    }
}
