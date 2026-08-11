package com.zhenghuaizhi.flashskip.platform.accessibility

internal data class WindowMetadata(
    val id: Int,
    val type: Int,
    val isActive: Boolean,
)

internal sealed interface BoundaryClassification {
    data object Target : BoundaryClassification
    data object OutsideTargets : BoundaryClassification
    data object Ignore : BoundaryClassification
}

internal class ForegroundWindowClassifier(
    targetPackages: Set<String>,
    private val applicationWindowType: Int,
) {
    private val targetPackages = targetPackages.toSet()

    fun classify(
        eventPackageName: String?,
        eventWindowId: Int,
        windows: List<WindowMetadata>,
    ): BoundaryClassification {
        if (eventPackageName.isNullOrBlank()) return BoundaryClassification.Ignore
        val eventWindow = windows.singleOrNull { it.id == eventWindowId }
            ?: return BoundaryClassification.Ignore
        if (!eventWindow.isActive || eventWindow.type != applicationWindowType) {
            return BoundaryClassification.Ignore
        }
        return if (eventPackageName in targetPackages) {
            BoundaryClassification.Target
        } else {
            BoundaryClassification.OutsideTargets
        }
    }
}
