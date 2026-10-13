package com.zhenghuaizhi.flashskip.domain.rulecapture

import com.zhenghuaizhi.flashskip.domain.model.CapturedSelector
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.TextCondition

/** 把采集证据转换为运行时 selector；只有稳定结构约束存在时才放宽倒计时文本。 */
internal fun CapturedSelector.toRuntimeSelector(): NodeSelector {
    val hasStableStructure = hasStableResourceId() || hasStableAncestor()
    val skipPrefix = if (
        hasStableAncestor()
    ) {
        DismissSemantics.skipPrefix(text)
    } else {
        null
    }
    return NodeSelector(
        resourceId = resourceId,
        text = text?.let { value ->
            when {
                hasStableStructure && DismissSemantics.isBareNumericCountdown(value) ->
                    TextCondition.NumericCountdown
                skipPrefix != null -> TextCondition.SkipCountdown(skipPrefix)
                else -> TextCondition.Exact(value)
            }
        },
        contentDescription = contentDescription,
        ancestorResourceId = ancestorResourceId,
        ancestorDistance = ancestorDistance,
        actionAncestorDistance = actionAncestorDistance,
    )
}

internal fun CapturedSelector.hasStableAncestor(): Boolean =
    !ancestorResourceId.isNullOrBlank() &&
        !ancestorResourceId.startsWith(ANDROID_RESOURCE_PREFIX) &&
        ancestorDistance != null &&
        ancestorDistance in 1..NodeSelector.MAX_ANCESTOR_DISTANCE

internal fun CapturedSelector.hasStableResourceId(): Boolean =
    !resourceId.isNullOrBlank() && !resourceId.startsWith(ANDROID_RESOURCE_PREFIX)

private const val ANDROID_RESOURCE_PREFIX = "android:"
