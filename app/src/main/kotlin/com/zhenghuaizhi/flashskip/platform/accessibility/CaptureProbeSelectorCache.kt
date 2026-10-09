package com.zhenghuaizhi.flashskip.platform.accessibility

import com.zhenghuaizhi.flashskip.domain.model.CaptureSample
import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.TargetAppId
import com.zhenghuaizhi.flashskip.domain.rulecapture.RuleCandidateAnalyzer

/** 仅缓存历史样本的纯数据分析；当前窗口中的唯一性必须由调用者每次重新查询。 */
internal class CaptureProbeSelectorCache {
    private var cachedSamples: List<CaptureSample>? = null
    private var cachedTarget: TargetAppId? = null
    private var cachedSelectors: List<CapturedSelector> = emptyList()

    fun selectorsFor(samples: List<CaptureSample>, targetAppId: TargetAppId): List<CapturedSelector> {
        if (cachedTarget == targetAppId && cachedSamples == samples) return cachedSelectors
        val selectors = RuleCandidateAnalyzer.analyze(samples, targetAppId).probeSelectors
        cachedSamples = samples.toList()
        cachedTarget = targetAppId
        cachedSelectors = selectors
        return selectors
    }

    fun clear() {
        cachedSamples = null
        cachedTarget = null
        cachedSelectors = emptyList()
    }
}
