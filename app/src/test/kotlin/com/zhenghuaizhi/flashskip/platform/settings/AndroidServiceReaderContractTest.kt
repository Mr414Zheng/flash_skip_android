package com.zhenghuaizhi.flashskip.platform.settings

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.view.accessibility.AccessibilityManager
import com.zhenghuaizhi.flashskip.platform.accessibility.FlashSkipAccessibilityService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AndroidServiceReaderContractTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun enabled(vararg components: Pair<String, String>) {
        val entries = components.map { (pkg, name) -> AccessibilityServiceInfo().apply {
            org.robolectric.util.ReflectionHelpers.setField(this, "mResolveInfo",
                ResolveInfo().apply { serviceInfo = ServiceInfo().apply { packageName = pkg; this.name = name } })
        } }
        shadowOf(context.getSystemService(AccessibilityManager::class.java)).setEnabledAccessibilityServiceList(entries)
    }
    @Test fun emptyEnabledListIsDisabled() { enabled(); assertFalse(AndroidSystemServiceStatusReader(context).isFlashSkipEnabled()) }
    @Test fun exactComponentIsEnabled() {
        enabled(context.packageName to FlashSkipAccessibilityService::class.java.name)
        assertTrue(AndroidSystemServiceStatusReader(context).isFlashSkipEnabled())
    }
    @Test fun sameClassInOtherPackageDoesNotGrantAccess() {
        enabled("other.package" to FlashSkipAccessibilityService::class.java.name)
        assertFalse(AndroidSystemServiceStatusReader(context).isFlashSkipEnabled())
    }
    @Test fun samePackageDifferentServiceDoesNotGrantAccess() {
        enabled(context.packageName to "other.Service")
        assertFalse(AndroidSystemServiceStatusReader(context).isFlashSkipEnabled())
    }
    @Test fun readerObservesRevocationOnNextRead() {
        val reader = AndroidSystemServiceStatusReader(context)
        enabled(context.packageName to FlashSkipAccessibilityService::class.java.name); assertTrue(reader.isFlashSkipEnabled())
        enabled(); assertFalse(reader.isFlashSkipEnabled())
    }
}
