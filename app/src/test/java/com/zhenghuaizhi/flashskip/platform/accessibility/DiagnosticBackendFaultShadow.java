package com.zhenghuaizhi.flashskip.platform.accessibility;

import android.util.Log;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLog;

/** 保留标准 ShadowLog 的平台行为，只在生产诊断实际调用日志后端后注入故障。 */
@Implements(Log.class)
public class DiagnosticBackendFaultShadow extends ShadowLog {
    @Implementation
    public static int println(int priority, String tag, String message) {
        ServiceDiagnosticFaults.INSTANCE.backend(tag, message);
        if (priority == Log.WARN) return ShadowLog.w(tag, message);
        if (priority == Log.INFO) return ShadowLog.i(tag, message);
        return ShadowLog.d(tag, message);
    }

    @Implementation
    public static int d(String tag, String message) {
        ServiceDiagnosticFaults.INSTANCE.backend(tag, message);
        return ShadowLog.d(tag, message);
    }

    @Implementation
    public static int i(String tag, String message) {
        ServiceDiagnosticFaults.INSTANCE.backend(tag, message);
        return ShadowLog.i(tag, message);
    }

    @Implementation
    public static int w(String tag, String message) {
        ServiceDiagnosticFaults.INSTANCE.backend(tag, message);
        return ShadowLog.w(tag, message);
    }
}
