package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.ports.ClickResult
import com.zhenghuaizhi.flashskip.domain.ports.GestureResult
import com.zhenghuaizhi.flashskip.domain.ports.PageGateway
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.ports.QueryResult
import kotlinx.coroutines.CompletableDeferred

class AccessibilityPageGateway(
    private val service: AccessibilityService,
    targetPackages: Set<String>,
    private val currentIdentity: () -> PageIdentity?,
) : PageGateway {
    private val targetPackages = targetPackages.toSet()
    private val selectorMatcher = AccessibilityNodeSelectorMatcher(service)
    private val candidateFinder = AccessibilityCandidateFinder(selectorMatcher)

    override suspend fun query(identity: PageIdentity, selector: NodeSelector): QueryResult =
        when (scan(identity, selector) { Unit }) {
            CandidateSearchResult.None -> QueryResult.None
            CandidateSearchResult.Ambiguous -> QueryResult.Ambiguous
            CandidateSearchResult.PageChanged -> QueryResult.PageChanged
            CandidateSearchResult.TraversalLimit -> QueryResult.TraversalLimit
            is CandidateSearchResult.Value -> QueryResult.Unique
        }

    override suspend fun click(identity: PageIdentity, selector: NodeSelector): ClickResult =
        when (val result = scan(identity, selector) { node ->
            val actionable = node.isClickable || node.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_CLICK
            }
            when {
                !actionable -> ActionClickResult.NotActionable
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK) -> ActionClickResult.Clicked
                else -> ActionClickResult.NotSupported
            }
        }) {
            CandidateSearchResult.None -> ClickResult.NodeDisappeared
            CandidateSearchResult.Ambiguous -> ClickResult.Ambiguous
            CandidateSearchResult.PageChanged -> ClickResult.PageChanged
            CandidateSearchResult.TraversalLimit -> ClickResult.TraversalLimit
            is CandidateSearchResult.Value -> when (result.value) {
                ActionClickResult.Clicked -> ClickResult.Clicked
                ActionClickResult.NotActionable -> ClickResult.NotActionable
                ActionClickResult.NotSupported -> ClickResult.NotSupported
            }
        }

    override suspend fun gestureAtCenter(
        identity: PageIdentity,
        selector: NodeSelector,
    ): GestureResult {
        val completion = CompletableDeferred<GestureResult>()
        val scan = scan(identity, selector) { node ->
            val bounds = Rect().also(node::getBoundsInScreen)
            val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, GESTURE_DURATION_MS))
                .build()
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        completion.complete(GestureResult.Completed)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription) {
                        completion.complete(GestureResult.Cancelled)
                    }
                },
                null,
            )
        }
        return when (scan) {
            CandidateSearchResult.None -> GestureResult.NodeDisappeared
            CandidateSearchResult.Ambiguous -> GestureResult.Ambiguous
            CandidateSearchResult.PageChanged -> GestureResult.PageChanged
            CandidateSearchResult.TraversalLimit -> GestureResult.TraversalLimit
            is CandidateSearchResult.Value ->
                if (scan.value) completion.await() else GestureResult.Rejected
        }
    }

    @Suppress("DEPRECATION")
    private fun <T> scan(
        expected: PageIdentity,
        selector: NodeSelector,
        onUnique: (AccessibilityNodeInfo) -> T,
    ): CandidateSearchResult<T> {
        // 包白名单必须先于身份、根节点及任何节点读取，避免上层误调用扩大数据访问范围。
        if (expected.packageName !in targetPackages) return CandidateSearchResult.PageChanged
        if (currentIdentity() != expected) return CandidateSearchResult.PageChanged
        val root = service.rootInActiveWindow ?: return CandidateSearchResult.None
        if (root.packageName?.toString() != expected.packageName || root.windowId != expected.windowId) {
            root.recycle()
            return CandidateSearchResult.PageChanged
        }
        try {
            return candidateFinder.findUnique(
                expectedPackage = expected.packageName,
                root = root,
                selector = selector,
                onUnique = onUnique,
            )
        } finally {
            root.recycle()
        }
    }

    private companion object {
        const val GESTURE_DURATION_MS = 60L
    }

    private enum class ActionClickResult { Clicked, NotActionable, NotSupported }
}
