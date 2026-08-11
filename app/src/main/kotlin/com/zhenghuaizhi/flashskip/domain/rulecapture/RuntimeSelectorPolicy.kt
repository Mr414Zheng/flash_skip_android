package com.zhenghuaizhi.flashskip.domain.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.TextCondition

/** 把采集证据转换为运行时 selector；只有稳定结构约束存在时才放宽倒计时文本。 */
internal fun CapturedSelector.toRuntimeSelector(): NodeSelector {
    val skipPrefix = if (
        ancestorResourceId != null &&
        !ancestorResourceId.startsWith(ANDROID_RESOURCE_PREFIX) &&
        ancestorDistance != null &&
        ancestorDistance in 1..NodeSelector.MAX_ANCESTOR_DISTANCE
    ) {
        DismissSemantics.skipPrefix(text)
    } else {
        null
    }
    return NodeSelector(
        resourceId = resourceId,
        text = text?.let { value ->
            skipPrefix?.let(TextCondition::Prefix) ?: TextCondition.Exact(value)
        },
        contentDescription = contentDescription,
        ancestorResourceId = ancestorResourceId,
        ancestorDistance = ancestorDistance,
    )
}

private const val ANDROID_RESOURCE_PREFIX = "android:"
