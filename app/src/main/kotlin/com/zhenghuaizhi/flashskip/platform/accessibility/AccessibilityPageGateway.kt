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
    private val targetPackages: () -> Set<String>,
    private val currentIdentity: () -> PageIdentity?,
) : PageGateway {
    private val selectorMatcher = AccessibilityNodeSelectorMatcher(service)
    private val candidateFinder = AccessibilityCandidateFinder(selectorMatcher)
    private val rootResolver = AccessibilityWindowRootResolver(service, targetPackages)

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
            withActionTarget(node, selector, ActionClickResult.NotActionable) { actionNode ->
                val actionable = actionNode.isClickable || actionNode.actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_CLICK
                }
                when {
                    !actionable -> ActionClickResult.NotActionable
                    actionNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) ->
                        ActionClickResult.Clicked
                    else -> ActionClickResult.NotSupported
                }
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
            withActionTarget(node, selector, false) { actionNode ->
                val bounds = Rect().also(actionNode::getBoundsInScreen)
                val metrics = service.resources.displayMetrics
                if (!actionNode.isVisibleToUser ||
                    bounds.width() <= 0 ||
                    bounds.height() <= 0 ||
                    bounds.centerX() !in 0 until metrics.widthPixels ||
                    bounds.centerY() !in 0 until metrics.heightPixels
                ) {
                    return@withActionTarget false
                }
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
        if (expected.packageName !in targetPackages()) return CandidateSearchResult.PageChanged
        if (currentIdentity() != expected) return CandidateSearchResult.PageChanged
        val root = when (val resolved = rootResolver.resolve(expected)) {
            is AccessibilityWindowRootResolver.Result.Success -> resolved.root
            AccessibilityWindowRootResolver.Result.NoRoot -> return CandidateSearchResult.None
            AccessibilityWindowRootResolver.Result.OutsideTargets,
            AccessibilityWindowRootResolver.Result.PageChanged,
            -> return CandidateSearchResult.PageChanged
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

    /** selector 可以用子节点语义定位，但动作必须落在采集时验证过的可执行祖先上。 */
    @Suppress("DEPRECATION")
    private fun <T> withActionTarget(
        matchedNode: AccessibilityNodeInfo,
        selector: NodeSelector,
        unavailable: T,
        action: (AccessibilityNodeInfo) -> T,
    ): T {
        val distance = selector.actionAncestorDistance ?: return action(matchedNode)
        if (distance !in 1..NodeSelector.MAX_ANCESTOR_DISTANCE) return unavailable
        val expectedPackage = matchedNode.packageName?.toString() ?: return unavailable
        var ownedAncestor: AccessibilityNodeInfo? = null
        try {
            repeat(distance) {
                val parent = runCatching { (ownedAncestor ?: matchedNode).parent }.getOrNull()
                    ?: return unavailable
                ownedAncestor?.recycle()
                ownedAncestor = parent
                if (parent.packageName?.toString() != expectedPackage) return unavailable
            }
            return action(requireNotNull(ownedAncestor))
        } finally {
            ownedAncestor?.recycle()
        }
    }

    private companion object {
        const val GESTURE_DURATION_MS = 60L
    }

    private enum class ActionClickResult { Clicked, NotActionable, NotSupported }
}
