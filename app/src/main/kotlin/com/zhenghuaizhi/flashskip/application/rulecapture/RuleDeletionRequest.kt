package com.zhenghuaizhi.flashskip.application.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.RuleCaptureState
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId

/** 一次确认及其失败重试共用此上下文；不落盘，新确认必须创建新实例。 */
internal class RuleDeletionRequest(val target: TargetAppId?) {
    var started = false
    var captureScope: RuleCaptureState? = null
    var enabledTargets: Set<TargetAppId> = emptySet()
    var targets: List<TargetAppId> = emptyList()
    // 写入可能已经提交但未返回，只允许这一次不确定停用造成的差异。
    var uncertainDisable: TargetAppId? = null

    fun scopeOf(state: RuleCaptureState): RuleCaptureState = if (target == null) state else state.copy(
        samples = state.samples.filter { it.targetAppId == target },
        activeSession = state.activeSession?.takeIf { it.targetAppId == target },
        semanticDiffEvidence = state.semanticDiffEvidence.filter { it.targetAppId == target },
    )
}
