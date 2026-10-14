package com.zhenghuaizhi.flashskip.platform.accessibility

internal fun AccessibilityFixture.captureProbe() = AccessibilityCaptureProbe(
    service, { targets }, { currentIdentity }, AccessibilitySemanticSnapshotter(service), { now },
)
