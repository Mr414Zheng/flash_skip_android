package com.zhenghuaizhi.flashskip.ui.home

import com.zhenghuaizhi.flashskip.domain.model.CaptureFailureDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryOutcome
import com.zhenghuaizhi.flashskip.domain.model.SnapshotRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.SnapshotRecoveryOutcome
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowRecognitionOutcome
import com.zhenghuaizhi.flashskip.domain.model.TraversalLimitReason
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureDiagnosticsFormatterTest {
    @Test
    fun sourceUnavailableDiagnosticsExposeRecoveryAndTimingWithoutRawSemantics() {
        val summary = formatCaptureFailureDiagnostics(
            CaptureFailureDiagnostics(
                sourceAvailable = false,
                eventTextCount = 1,
                eventHasContentDescription = false,
                immediateRecovery = ClickRecoveryDiagnostics(
                    outcome = ClickRecoveryOutcome.TRAVERSAL_LIMIT,
                    visitedNodeCount = 501,
                    maxDepth = 9,
                    elapsedMs = 61,
                    semanticNodeCount = 400,
                    knownDismissNodeCount = 2,
                    traversalLimitReason = TraversalLimitReason.NODE_COUNT,
                ),
                snapshotRecovery = SnapshotRecoveryDiagnostics(
                    outcome = SnapshotRecoveryOutcome.NOT_AVAILABLE,
                ),
                lastWindowVerifiedAgeMs = 800,
            ),
        )

        assertTrue(summary.contains("点击源：为空"))
        assertTrue(summary.contains("即时扫描：遍历超限（节点数）"))
        assertTrue(summary.contains("访问 501 节点"))
        assertTrue(summary.contains("关闭语义 2"))
        assertTrue(summary.contains("点击前快照：未建立"))
        assertTrue(summary.contains("内容变化 无记录"))
    }

    @Test
    fun targetWindowDiagnosticsExposeActivityVerificationBoundary() {
        val summary = formatTargetWindowDiagnostics(
            TargetWindowDiagnostics(
                windowStateEventCount = 2,
                contentChangedEventCount = 14,
                viewClickedEventCount = 0,
                lastPackageName = "com.example.maps",
                lastClassName = "android.view.View",
                lastEventWindowId = 9,
                lastWindowCount = 3,
                lastMatchingWindowCount = 1,
                lastMatchingWindowType = 1,
                lastMatchingWindowActive = true,
                lastOutcome = TargetWindowRecognitionOutcome.ACTIVITY_UNVERIFIED,
                lastEventAgeMs = 200,
            ),
        )

        assertTrue(summary.contains("窗口 2，内容 14，点击 0"))
        assertTrue(summary.contains("className 无法验证为目标 Activity"))
        assertTrue(summary.contains("包名：com.example.maps"))
        assertTrue(summary.contains("类型 应用(1)，活动 是"))
    }
}
