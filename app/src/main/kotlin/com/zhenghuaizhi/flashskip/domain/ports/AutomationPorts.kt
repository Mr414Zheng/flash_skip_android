package com.zhenghuaizhi.flashskip.domain.ports

import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.AppVersion
import com.zhenghuaizhi.flashskip.domain.model.ActionReason

interface MonotonicClock {
    fun elapsedRealtimeMs(): Long
}

interface WallClock {
    fun currentTimeMillis(): Long
}

data class PageIdentity(
    val packageName: String,
    val activityName: String?,
    val windowId: Int,
    val appVersion: AppVersion? = null,
)

sealed interface QueryResult {
    /** 根节点或候选无法刷新，未获得足以证明存在或消失的当前页面证据。 */
    data object Unavailable : QueryResult
    data object None : QueryResult
    data object Unique : QueryResult
    data object Ambiguous : QueryResult
    data object PageChanged : QueryResult
    data object TraversalLimit : QueryResult
}

sealed interface ClickResult {
    data object Expired : ClickResult
    data object Unavailable : ClickResult
    data object Clicked : ClickResult
    data object NotActionable : ClickResult
    data object NotSupported : ClickResult
    data object NodeDisappeared : ClickResult
    data object Ambiguous : ClickResult
    data object PageChanged : ClickResult
    data object TraversalLimit : ClickResult
}

sealed interface GestureResult {
    data object Accepted : GestureResult
    data object Unavailable : GestureResult
    data object Completed : GestureResult
    data object Rejected : GestureResult
    data object Cancelled : GestureResult
    data object TimedOut : GestureResult
    data object NodeDisappeared : GestureResult
    data object Ambiguous : GestureResult
    data object PageChanged : GestureResult
    data object TraversalLimit : GestureResult
}

interface PageGateway {
    suspend fun query(identity: PageIdentity, selector: NodeSelector): QueryResult

    suspend fun click(identity: PageIdentity, selector: NodeSelector): ClickResult

    /**
     * deadlineElapsedMs 使用单调时钟；平台必须在实际提交手势前复核期限与协程取消状态。
     * 调用方同时限制回调等待时长，取消后迟到回调不得恢复该次等待。
     */
    suspend fun gestureAtCenter(
        identity: PageIdentity,
        selector: NodeSelector,
        deadlineElapsedMs: Long,
    ): GestureResult

    /** 动作与动作前的有限语义证据在同一次校验内取得，不保留平台节点。 */
    suspend fun submitClick(
        identity: PageIdentity,
        selector: NodeSelector,
        expectedTarget: ActionTargetEvidence? = null,
        deadlineElapsedMs: Long = Long.MAX_VALUE,
    ): NodeActionSubmission = if (expectedTarget != null) {
        NodeActionSubmission(ClickResult.Unavailable, null)
    } else {
        NodeActionSubmission(click(identity, selector), null)
    }

    suspend fun submitGesture(
        identity: PageIdentity,
        selector: NodeSelector,
        deadlineElapsedMs: Long,
    ): GestureActionSubmission = GestureActionSubmission(
        gestureAtCenter(identity, selector, deadlineElapsedMs), null,
    )

    /** 观察原动作目标；空索引、边界变化和不完整读取都不能作为消失证据。 */
    suspend fun observeAction(
        identity: PageIdentity,
        evidence: ActionTargetEvidence,
        allowPageFallback: Boolean,
    ): TargetObservation = TargetObservation.Insufficient(ActionReason.NODE_UNAVAILABLE)
}

data class NodeActionSubmission(
    val result: ClickResult,
    val evidence: ActionTargetEvidence?,
    val submittedAtElapsedMs: Long? = null,
)

data class GestureActionSubmission(
    val result: GestureResult,
    val evidence: ActionTargetEvidence?,
    val submittedAtElapsedMs: Long? = null,
)

/** 仅在动作后的观察时间窗内保留规则选择器及数值摘要，不附加页面文本、位置或平台节点。 */
data class ActionTargetEvidence(
    val identity: PageIdentity,
    val selector: NodeSelector,
    val targetSignature: Long,
    val targetStructuralSignature: Long? = null,
    val containerResourceId: String? = null,
    val containerSignature: Long? = null,
    val containerContext: Long? = null,
    val parentResourceId: String? = null,
    val parentSignature: Long? = null,
    val parentContext: Long? = null,
    val parentToContainerDepth: Int? = null,
)

sealed interface TargetObservation {
    /** 已刷新并重新校验原窗口、语义和容器，证明原动作目标仍然存在。 */
    data class Present(val unique: Boolean) : TargetObservation
    data class Absent(val proof: AbsenceProof, val contextSignature: Long) : TargetObservation
    data class Insufficient(val reason: ActionReason) : TargetObservation
}

enum class AbsenceProof { LOCAL_TARGET_REMOVED, LOCAL_CONTAINER_REMOVED, COMPLETE_PAGE, ORIGINAL_WINDOW_REMOVED }
