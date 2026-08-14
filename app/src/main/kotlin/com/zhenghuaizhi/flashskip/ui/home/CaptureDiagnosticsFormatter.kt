package com.zhenghuaizhi.flashskip.ui.home

import com.zhenghuaizhi.flashskip.domain.model.CaptureFailureDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.ClickRecoveryOutcome
import com.zhenghuaizhi.flashskip.domain.model.SnapshotRecoveryOutcome
import com.zhenghuaizhi.flashskip.domain.model.SemanticDiffDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.SemanticDiffOutcome
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowDiagnostics
import com.zhenghuaizhi.flashskip.domain.model.TargetWindowRecognitionOutcome
import com.zhenghuaizhi.flashskip.domain.model.TraversalLimitReason

internal fun formatCaptureFailureDiagnostics(
    diagnostics: CaptureFailureDiagnostics,
): String = buildList {
    add("点击源：${if (diagnostics.sourceAvailable) "可用" else "为空"}")
    add(
        "事件语义：文本 ${diagnostics.eventTextCount} 项，描述" +
            if (diagnostics.eventHasContentDescription) "存在" else "为空",
    )
    if (diagnostics.sourceAvailable) {
        add(
            "点击节点：复制 ${diagnostics.copiedNodeCount} 层，" +
                "选择器候选 ${diagnostics.selectorCandidateCount} 个",
        )
    }
    diagnostics.immediateRecovery?.let { add(it.summary("即时扫描")) }
    diagnostics.preClickProbe?.let { add(it.summary("点击前探测")) }
    diagnostics.snapshotRecovery?.let { snapshot ->
        val age = snapshot.ageMs?.let { "，年龄 ${it}ms" }.orEmpty()
        add("点击前快照：${snapshot.outcome.label()}$age")
    }
    diagnostics.semanticDiff?.let { add(it.summary()) }
    add(
        "时序：窗口验证 ${diagnostics.lastWindowVerifiedAgeMs.ageLabel()}，" +
            "内容变化 ${diagnostics.lastContentChangedAgeMs.ageLabel()}",
    )
}.joinToString(separator = "\n")

internal fun formatCaptureTimeoutDiagnostics(
    diagnostics: CaptureFailureDiagnostics,
): String = buildList {
    diagnostics.preClickProbe?.let { add(it.summary("弹窗扫描")) }
    diagnostics.semanticDiff?.let { add(it.summary()) }
    add(
        "时序：窗口验证 ${diagnostics.lastWindowVerifiedAgeMs.ageLabel()}，" +
            "内容变化 ${diagnostics.lastContentChangedAgeMs.ageLabel()}",
    )
}.joinToString(separator = "\n")

internal fun formatTargetWindowDiagnostics(
    diagnostics: TargetWindowDiagnostics,
): String = buildList {
    add(
        "事件：窗口 ${diagnostics.windowStateEventCount}，" +
            "内容 ${diagnostics.contentChangedEventCount}，" +
            "点击 ${diagnostics.viewClickedEventCount}，" +
            "忽略包 ${diagnostics.ignoredPackageEventCount}，" +
            "缺少包名 ${diagnostics.missingPackageEventCount}",
    )
    add(
        "窗口解析：尝试 ${diagnostics.windowResolutionAttemptCount}，" +
            "重试 ${diagnostics.windowRetryAttemptCount}",
    )
    add("最后结果：${diagnostics.lastOutcome.label()}")
    diagnostics.lastPackageName?.let { add("包名：$it") }
    diagnostics.lastClassName?.let { add("className：$it") }
    if (diagnostics.lastEventWindowId != null || diagnostics.lastWindowCount != null) {
        add(
            "窗口：事件ID ${diagnostics.lastEventWindowId ?: "未知"}，" +
                "总数 ${diagnostics.lastWindowCount ?: "未知"}，" +
                "同ID ${diagnostics.lastMatchingWindowCount ?: "未知"}，" +
                "类型 ${diagnostics.lastMatchingWindowType.windowTypeLabel()}，" +
                "活动 ${diagnostics.lastMatchingWindowActive.booleanLabel()}",
        )
    }
    add("最后事件：${diagnostics.lastEventAgeMs.ageLabel()}")
}.joinToString(separator = "\n")

private fun ClickRecoveryDiagnostics.summary(prefix: String): String {
    val limit = traversalLimitReason?.let { "（${it.label()}）" }.orEmpty()
    return "$prefix：${outcome.label()}$limit；访问 $visitedNodeCount 节点，" +
        "最深 $maxDepth 层，耗时 ${elapsedMs}ms，语义 $semanticNodeCount，" +
        "关闭语义 $knownDismissNodeCount，匹配 $matchedNodeCount，" +
        "有效候选 $selectorCandidateNodeCount，倒计时语义 $countdownSemanticNodeCount，" +
        "可执行链 $countdownActionChainCount"
}

private fun SemanticDiffDiagnostics.summary(): String =
    "无文案差分：${outcome.label()}；快照 $observationCount，" +
        "当前候选 $currentCandidateCount，稳定 $stableCandidateCount，" +
        "消失 $disappearedCandidateCount，访问 $visitedNodeCount 节点，" +
        "窗口转换${if (transitionSignal) "存在" else "未发现"}"

private fun SemanticDiffOutcome.label(): String = when (this) {
    SemanticDiffOutcome.BASELINE -> "已建立基线"
    SemanticDiffOutcome.TRACKING -> "正在比较"
    SemanticDiffOutcome.INSUFFICIENT_COVERAGE -> "新快照覆盖不足"
    SemanticDiffOutcome.AMBIGUOUS -> "消失候选不唯一"
    SemanticDiffOutcome.CANDIDATE -> "找到唯一消失节点"
}

private fun ClickRecoveryOutcome.label(): String = when (this) {
    ClickRecoveryOutcome.SUCCESS -> "成功"
    ClickRecoveryOutcome.OUTSIDE_TARGETS -> "目标包已不在采集范围"
    ClickRecoveryOutcome.IDENTITY_CHANGED -> "页面身份已变化"
    ClickRecoveryOutcome.NO_ROOT -> "没有活动根节点"
    ClickRecoveryOutcome.ROOT_MISMATCH -> "根节点与目标窗口不一致"
    ClickRecoveryOutcome.TRAVERSAL_LIMIT -> "遍历超限"
    ClickRecoveryOutcome.NO_MATCH -> "未找到匹配节点"
    ClickRecoveryOutcome.AMBIGUOUS -> "存在多个候选"
}

private fun SnapshotRecoveryOutcome.label(): String = when (this) {
    SnapshotRecoveryOutcome.MATCHED_EVENT -> "匹配点击事件"
    SnapshotRecoveryOutcome.MATCHED_DISMISS -> "匹配关闭语义"
    SnapshotRecoveryOutcome.NOT_AVAILABLE -> "未建立"
    SnapshotRecoveryOutcome.IDENTITY_MISMATCH -> "页面身份不一致"
    SnapshotRecoveryOutcome.STALE -> "已过期"
    SnapshotRecoveryOutcome.EMPTY -> "没有语义节点"
    SnapshotRecoveryOutcome.NO_MATCH -> "未匹配"
}

private fun TraversalLimitReason.label(): String = when (this) {
    TraversalLimitReason.NODE_COUNT -> "节点数"
    TraversalLimitReason.DEPTH -> "深度"
    TraversalLimitReason.TIME -> "耗时"
}

private fun TargetWindowRecognitionOutcome.label(): String = when (this) {
    TargetWindowRecognitionOutcome.NO_WINDOW_STATE_EVENT -> "未收到窗口状态事件"
    TargetWindowRecognitionOutcome.WINDOW_METADATA_UNAVAILABLE -> "无法读取窗口列表"
    TargetWindowRecognitionOutcome.EVENT_WINDOW_NOT_FOUND -> "窗口列表中没有事件窗口"
    TargetWindowRecognitionOutcome.EVENT_WINDOW_AMBIGUOUS -> "事件窗口不唯一"
    TargetWindowRecognitionOutcome.WINDOW_NOT_ACTIVE -> "事件窗口不是活动窗口"
    TargetWindowRecognitionOutcome.NON_APPLICATION_WINDOW -> "事件窗口不是应用窗口"
    TargetWindowRecognitionOutcome.ACTIVITY_UNVERIFIED -> "className 无法验证为目标 Activity"
    TargetWindowRecognitionOutcome.OWNED_CHILD_WINDOW -> "同包弹窗由已验证 Activity 承载"
    TargetWindowRecognitionOutcome.VERIFIED -> "目标窗口验证通过"
    TargetWindowRecognitionOutcome.CHILD_WINDOW_PENDING -> "瞬态子窗口等待补认"
    TargetWindowRecognitionOutcome.RESOLVED_FROM_WINDOW_LIST -> "从窗口列表补认成功"
    TargetWindowRecognitionOutcome.RESOLVED_FROM_ACTIVE_ROOT -> "从活动根节点补认成功"
    TargetWindowRecognitionOutcome.RESOLVED_FROM_EVENT_SOURCE -> "从事件节点补认成功"
    TargetWindowRecognitionOutcome.WINDOW_ROOT_MISMATCH -> "事件窗口根节点不匹配"
    TargetWindowRecognitionOutcome.ACTIVE_ROOT_MISMATCH -> "活动根节点已离开目标宿主"
    TargetWindowRecognitionOutcome.SOURCE_MISMATCH -> "事件节点与目标窗口不匹配"
    TargetWindowRecognitionOutcome.RETRY_EXHAUSTED -> "瞬态子窗口补认重试耗尽"
}

private fun Int?.windowTypeLabel(): String = when (this) {
    1 -> "应用(1)"
    2 -> "输入法(2)"
    3 -> "系统(3)"
    4 -> "无障碍覆盖层(4)"
    5 -> "分屏分隔器(5)"
    6 -> "放大覆盖层(6)"
    null -> "未知"
    else -> "未知($this)"
}

private fun Boolean?.booleanLabel(): String = when (this) {
    true -> "是"
    false -> "否"
    null -> "未知"
}

private fun Long?.ageLabel(): String = this?.let { "${it}ms 前" } ?: "无记录"
