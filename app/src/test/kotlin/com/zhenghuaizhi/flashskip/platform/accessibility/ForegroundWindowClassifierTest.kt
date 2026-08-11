package com.zhenghuaizhi.flashskip.platform.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundWindowClassifierTest {
    private val classifier = ForegroundWindowClassifier(
        targetPackages = setOf(TARGET_PACKAGE),
        applicationWindowType = TYPE_APPLICATION,
    )

    @Test
    fun activeApplicationWindowClassifiesTargetAndOutsideWithoutPayload() {
        val windows = listOf(WindowMetadata(id = 7, type = TYPE_APPLICATION, isActive = true))

        assertEquals(
            BoundaryClassification.Target,
            classifier.classify(TARGET_PACKAGE, 7, windows),
        )
        assertEquals(
            BoundaryClassification.OutsideTargets,
            classifier.classify("com.example.outside", 7, windows),
        )
    }

    @Test
    fun inactiveAndNonApplicationWindowsAreIgnored() {
        assertEquals(
            BoundaryClassification.Ignore,
            classifier.classify(
                TARGET_PACKAGE,
                7,
                listOf(WindowMetadata(7, TYPE_APPLICATION, isActive = false)),
            ),
        )
        listOf(TYPE_INPUT_METHOD, TYPE_SYSTEM, TYPE_ACCESSIBILITY_OVERLAY).forEach { type ->
            assertEquals(
                BoundaryClassification.Ignore,
                classifier.classify(
                    TARGET_PACKAGE,
                    7,
                    listOf(WindowMetadata(7, type, isActive = true)),
                ),
            )
        }
    }

    @Test
    fun unknownWindowAndBlankPackageAreIgnored() {
        val windows = listOf(WindowMetadata(id = 8, type = TYPE_APPLICATION, isActive = true))

        assertEquals(
            BoundaryClassification.Ignore,
            classifier.classify(TARGET_PACKAGE, 7, windows),
        )
        assertEquals(BoundaryClassification.Ignore, classifier.classify("", 8, windows))
        assertEquals(BoundaryClassification.Ignore, classifier.classify(null, 8, windows))
    }

    private companion object {
        const val TARGET_PACKAGE = "com.tencent.qqmusic"
        const val TYPE_APPLICATION = 1
        const val TYPE_INPUT_METHOD = 2
        const val TYPE_SYSTEM = 3
        const val TYPE_ACCESSIBILITY_OVERLAY = 4
    }
}
