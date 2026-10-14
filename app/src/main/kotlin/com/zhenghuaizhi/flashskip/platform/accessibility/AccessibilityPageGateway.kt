package com.zhenghuaizhi.flashskip.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import com.zhenghuaizhi.flashskip.platform.diagnostics.ProcessDiagnosticIdentifiers
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.zhenghuaizhi.flashskip.BuildConfig
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.ports.ActionTargetEvidence
import com.zhenghuaizhi.flashskip.domain.ports.AbsenceProof
import com.zhenghuaizhi.flashskip.domain.ports.GestureActionSubmission
import com.zhenghuaizhi.flashskip.domain.ports.NodeActionSubmission
import com.zhenghuaizhi.flashskip.domain.ports.TargetObservation
import com.zhenghuaizhi.flashskip.domain.model.ActionReason
import com.zhenghuaizhi.flashskip.domain.ports.ClickResult
import com.zhenghuaizhi.flashskip.domain.ports.GestureResult
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.PageGateway
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.ports.QueryResult
import com.zhenghuaizhi.flashskip.domain.ports.AutomationTraceResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class AccessibilityPageGateway(
    private val service: AccessibilityService,
    private val targetPackages: () -> Set<String>,
    private val monotonicClock: MonotonicClock = AndroidMonotonicClock,
    private val currentIdentity: () -> PageIdentity?,
) : PageGateway {
    private val selectorMatcher = AccessibilityNodeSelectorMatcher(service)
    private val candidateFinder = AccessibilityCandidateFinder(selectorMatcher)
    private val actionEvidence = AccessibilityActionEvidence(selectorMatcher, monotonicClock)
    private val rootResolver = AccessibilityWindowRootResolver(service, targetPackages)

    override suspend fun query(identity: PageIdentity, selector: NodeSelector): QueryResult =
        when (scan(identity, selector, ScanOperation.QUERY) { Unit }) {
            CandidateSearchResult.Unavailable -> QueryResult.Unavailable
            CandidateSearchResult.None -> QueryResult.None
            CandidateSearchResult.Ambiguous -> QueryResult.Ambiguous
            CandidateSearchResult.PageChanged -> QueryResult.PageChanged
            CandidateSearchResult.TraversalLimit -> QueryResult.TraversalLimit
            is CandidateSearchResult.Value -> QueryResult.Unique
        }

    override suspend fun click(identity: PageIdentity, selector: NodeSelector): ClickResult =
        submitClick(identity, selector).result

    override suspend fun submitClick(
        identity: PageIdentity,
        selector: NodeSelector,
        expectedTarget: ActionTargetEvidence?,
        deadlineElapsedMs: Long,
    ): NodeActionSubmission {
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        if (monotonicClock.elapsedRealtimeMs() >= deadlineElapsedMs) return NodeActionSubmission(ClickResult.Expired, null)
        var evidence: ActionTargetEvidence? = null
        var submittedAt: Long? = null
        val result = when (val found = scan(identity, selector, ScanOperation.CLICK) { node ->
            if (expectedTarget != null && (expectedTarget.identity != identity ||
                    !actionEvidence.matchesOriginal(node, expectedTarget))) {
                return@scan ActionClickResult.NotActionable
            }
            val before = actionEvidence.capture(node, identity, selector)
            if (!revalidateActionTarget(node, identity, selector, before)) return@scan ActionClickResult.NotActionable
            withActionTarget(node, selector, ActionClickResult.NotActionable) { actionNode ->
                coroutineContext.ensureActive()
                if (monotonicClock.elapsedRealtimeMs() >= deadlineElapsedMs) return@withActionTarget ActionClickResult.Expired
                if (currentIdentity() != identity || actionNode.windowId != identity.windowId || !actionNode.isVisibleToUser) {
                    return@withActionTarget ActionClickResult.NotActionable
                }
                val actionable = actionNode.isClickable || actionNode.actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_CLICK
                }
                val actionStartedAt = monotonicClock.elapsedRealtimeMs()
                if (actionStartedAt >= deadlineElapsedMs) return@withActionTarget ActionClickResult.Expired
                when {
                    !actionable -> ActionClickResult.NotActionable
                    actionNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) -> {
                        evidence = before
                        submittedAt = actionStartedAt
                        ActionClickResult.Clicked
                    }
                    else -> ActionClickResult.NotSupported
                }
            }
        }) {
            CandidateSearchResult.Unavailable -> ClickResult.Unavailable
            CandidateSearchResult.None -> ClickResult.NodeDisappeared
            CandidateSearchResult.Ambiguous -> ClickResult.Ambiguous
            CandidateSearchResult.PageChanged -> ClickResult.PageChanged
            CandidateSearchResult.TraversalLimit -> ClickResult.TraversalLimit
            is CandidateSearchResult.Value -> when (found.value) {
                ActionClickResult.Expired -> ClickResult.Expired
                ActionClickResult.Clicked -> ClickResult.Clicked
                ActionClickResult.NotActionable -> ClickResult.NotActionable
                ActionClickResult.NotSupported -> ClickResult.NotSupported
            }
        }
        return NodeActionSubmission(result, evidence, submittedAt)
    }

    override suspend fun gestureAtCenter(
        identity: PageIdentity,
        selector: NodeSelector,
        deadlineElapsedMs: Long,
    ): GestureResult = dispatchCenterGesture(identity, selector, deadlineElapsedMs, waitForCallback = true).result

    override suspend fun submitGesture(
        identity: PageIdentity,
        selector: NodeSelector,
        deadlineElapsedMs: Long,
    ): GestureActionSubmission = dispatchCenterGesture(identity, selector, deadlineElapsedMs, waitForCallback = false)

    private suspend fun dispatchCenterGesture(
        identity: PageIdentity,
        selector: NodeSelector,
        deadlineElapsedMs: Long,
        waitForCallback: Boolean,
    ): GestureActionSubmission {
        var evidence: ActionTargetEvidence? = null
        var submittedAt: Long? = null
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        if (monotonicClock.elapsedRealtimeMs() >= deadlineElapsedMs) return GestureActionSubmission(GestureResult.TimedOut, null)
        val completion = CompletableDeferred<GestureResult>()
        try {
            val scan = scan(identity, selector, ScanOperation.GESTURE) { node ->
                val before = actionEvidence.capture(node, identity, selector)
                if (!revalidateActionTarget(node, identity, selector, before)) return@scan GestureDispatchResult.Rejected
                withActionTarget(node, selector, GestureDispatchResult.Rejected) { actionNode ->
                    val bounds = Rect().also(actionNode::getBoundsInScreen)
                    val metrics = service.resources.displayMetrics
                    if (!actionNode.isVisibleToUser ||
                        bounds.width() <= 0 ||
                        bounds.height() <= 0 ||
                        bounds.centerX() !in 0 until metrics.widthPixels ||
                        bounds.centerY() !in 0 until metrics.heightPixels
                    ) {
                        return@withActionTarget GestureDispatchResult.Rejected
                    }
                    val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, GESTURE_DURATION_MS))
                        .build()
                    // 主线程同步扫描期间，协程超时任务可能无法调度，必须在提交前复核绝对期限。
                    coroutineContext.ensureActive()
                    if (currentIdentity() != identity || actionNode.windowId != identity.windowId) {
                        return@withActionTarget GestureDispatchResult.Rejected
                    }
                    if (monotonicClock.elapsedRealtimeMs() >= deadlineElapsedMs) {
                        return@withActionTarget GestureDispatchResult.TimedOut
                    }
                    val actionStartedAt = monotonicClock.elapsedRealtimeMs()
                    val accepted = service.dispatchGesture(
                        gesture,
                        object : AccessibilityService.GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription) {
                                traceGestureCallback(identity, selector, "COMPLETED")
                                completion.complete(GestureResult.Completed)
                            }

                            override fun onCancelled(gestureDescription: GestureDescription) {
                                traceGestureCallback(identity, selector, "CANCELLED")
                                completion.complete(GestureResult.Cancelled)
                            }
                        },
                        null,
                    )
                    if (accepted) {
                        evidence = before
                        submittedAt = actionStartedAt
                        GestureDispatchResult.Accepted
                    } else GestureDispatchResult.Rejected
                }
            }
            val result = when (scan) {
                CandidateSearchResult.Unavailable -> GestureResult.Unavailable
                CandidateSearchResult.None -> GestureResult.NodeDisappeared
                CandidateSearchResult.Ambiguous -> GestureResult.Ambiguous
                CandidateSearchResult.PageChanged -> GestureResult.PageChanged
                CandidateSearchResult.TraversalLimit -> GestureResult.TraversalLimit
                is CandidateSearchResult.Value -> when (scan.value) {
                    GestureDispatchResult.Accepted -> if (waitForCallback) completion.await() else GestureResult.Accepted
                    GestureDispatchResult.Rejected -> GestureResult.Rejected
                    GestureDispatchResult.TimedOut -> GestureResult.TimedOut
                }
            }
            return GestureActionSubmission(result, evidence, submittedAt)
        } finally {
            // 超时、关闭或调用方取消后，平台的迟到回调只能被忽略，不能恢复等待。
            completion.cancel()
        }
    }

    private fun revalidateActionTarget(
        node: AccessibilityNodeInfo,
        identity: PageIdentity,
        selector: NodeSelector,
        evidence: ActionTargetEvidence,
    ): Boolean {
        val refreshed = runCatching { node.refresh() }.getOrDefault(false)
        val boundary = currentIdentity() == identity && node.windowId == identity.windowId &&
            node.packageName?.toString() == identity.packageName
        val semantic = refreshed && boundary &&
            selectorMatcher.check(node, selector, refreshAncestors = true) == CandidateMatchResult.MATCH
        val original = semantic && actionEvidence.matchesOriginal(node, evidence)
        if (BuildConfig.DEBUG) runCatching {
            val identifiers = ProcessDiagnosticIdentifiers.Process
            Log.d(AutomationTraceLog.TAG,
                "source=platform process=${identifiers.scope} stage=ACTION_REVALIDATED targetToken=${identifiers.token("target", identity.packageName.hashCode())} " +
                    "windowId=${identity.windowId} selectorToken=${identifiers.token("selector", selector.hashCode())} " +
                    "refreshed=$refreshed boundary=$boundary semantic=$semantic original=$original")
        }
        return original
    }

    private fun traceGestureCallback(identity: PageIdentity, selector: NodeSelector, result: String) {
        if (BuildConfig.DEBUG) runCatching {
            val identifiers = ProcessDiagnosticIdentifiers.Process
            Log.d(AutomationTraceLog.TAG, "source=platform process=${identifiers.scope} stage=GESTURE_CALLBACK " +
                "targetToken=${identifiers.token("target", identity.packageName.hashCode())} windowId=${identity.windowId} " +
                "selectorToken=${identifiers.token("selector", selector.hashCode())} result=$result")
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun observeAction(
        identity: PageIdentity,
        evidence: ActionTargetEvidence,
        allowPageFallback: Boolean,
    ): TargetObservation {
        if (identity.packageName !in targetPackages() || currentIdentity() != identity ||
            identity.packageName != evidence.identity.packageName || identity.appVersion != evidence.identity.appVersion
        ) {
            return TargetObservation.Insufficient(ActionReason.PAGE_CHANGED)
        }
        val originalWindowRemoved = identity.windowId != evidence.identity.windowId &&
            identity.activityName == evidence.identity.activityName && originalWindowAbsent(identity, evidence)
        if (identity != evidence.identity && !originalWindowRemoved) {
            return TargetObservation.Insufficient(ActionReason.PAGE_CHANGED)
        }
        val root = when (val resolved = rootResolver.resolve(identity)) {
            is AccessibilityWindowRootResolver.Result.Success -> resolved.root
            AccessibilityWindowRootResolver.Result.NoRoot -> return TargetObservation.Insufficient(ActionReason.NODE_UNAVAILABLE)
            else -> return TargetObservation.Insufficient(ActionReason.PAGE_CHANGED)
        }
        try {
            if (!root.refresh()) return TargetObservation.Insufficient(ActionReason.NODE_UNAVAILABLE)
            if (root.packageName?.toString() != identity.packageName || root.windowId != identity.windowId) {
                return TargetObservation.Insufficient(ActionReason.PAGE_CHANGED)
            }
            if (!root.isVisibleToUser) return TargetObservation.Insufficient(ActionReason.NODE_UNAVAILABLE)
            val observation = actionEvidence.observe(root, evidence.copy(identity = identity), allowPageFallback)
            // 原窗口消失仍须证明其广告区域已被移除；替换窗口中还存在同一目标时不能报关闭。
            return if (originalWindowRemoved && observation is TargetObservation.Absent) observation.copy(
                proof = AbsenceProof.ORIGINAL_WINDOW_REMOVED,
            ) else observation
        } finally {
            root.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun originalWindowAbsent(identity: PageIdentity, evidence: ActionTargetEvidence): Boolean {
        val windows = runCatching { service.windows }.getOrNull() ?: return false
        try {
            return windows.none { it.id == evidence.identity.windowId } &&
                windows.count { it.id == identity.windowId && it.isActive &&
                    it.type == AccessibilityWindowInfo.TYPE_APPLICATION } == 1
        } finally {
            windows.forEach(AccessibilityWindowInfo::recycle)
        }
    }

    @Suppress("DEPRECATION")
    private fun <T> scan(
        expected: PageIdentity,
        selector: NodeSelector,
        operation: ScanOperation,
        onUnique: (AccessibilityNodeInfo) -> T,
    ): CandidateSearchResult<T> {
        // 包白名单必须先于身份、根节点及任何节点读取，避免上层误调用扩大数据访问范围。
        val trace = AccessibilityScanTraceLog(BuildConfig.DEBUG, expected, selector, operation)
        if (expected.packageName !in targetPackages() || currentIdentity() != expected) {
            trace.finish(AutomationTraceResult.PAGE_CHANGED)
            return CandidateSearchResult.PageChanged
        }
        val root = when (val resolved = rootResolver.resolve(expected)) {
            is AccessibilityWindowRootResolver.Result.Success -> resolved.root
            AccessibilityWindowRootResolver.Result.NoRoot -> {
                trace.finish(AutomationTraceResult.UNAVAILABLE)
                return CandidateSearchResult.Unavailable
            }
            AccessibilityWindowRootResolver.Result.OutsideTargets,
            AccessibilityWindowRootResolver.Result.PageChanged,
            -> {
                trace.finish(AutomationTraceResult.PAGE_CHANGED)
                return CandidateSearchResult.PageChanged
            }
        }
        try {
            // 索引和窗口缓存可能保留已退出的开屏页，必须刷新后再用于判断目标消失或执行动作。
            if (!runCatching { root.refresh() }.getOrDefault(false)) {
                trace.finish(AutomationTraceResult.UNAVAILABLE)
                return CandidateSearchResult.Unavailable
            }
            if (root.packageName?.toString() != expected.packageName || root.windowId != expected.windowId) {
                trace.finish(AutomationTraceResult.PAGE_CHANGED)
                return CandidateSearchResult.PageChanged
            }
            val result = candidateFinder.findUnique(
                expectedPackage = expected.packageName,
                root = root,
                selector = selector,
                onUnique = onUnique,
                trace = trace.candidateSink,
                diagnostics = trace.searchDiagnostics,
            )
            trace.finish(when (result) {
                CandidateSearchResult.None -> AutomationTraceResult.NONE
                CandidateSearchResult.Unavailable -> AutomationTraceResult.UNAVAILABLE
                CandidateSearchResult.Ambiguous -> AutomationTraceResult.AMBIGUOUS
                CandidateSearchResult.PageChanged -> AutomationTraceResult.PAGE_CHANGED
                CandidateSearchResult.TraversalLimit -> AutomationTraceResult.TRAVERSAL_LIMIT
                is CandidateSearchResult.Value -> AutomationTraceResult.UNIQUE
            })
            return result
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
                if (!runCatching { parent.refresh() }.getOrDefault(false)) return unavailable
                if (parent.packageName?.toString() != expectedPackage || parent.windowId != matchedNode.windowId ||
                    !parent.isVisibleToUser
                ) return unavailable
            }
            return action(requireNotNull(ownedAncestor))
        } finally {
            ownedAncestor?.recycle()
        }
    }

    private companion object {
        const val GESTURE_DURATION_MS = 60L
    }

    private enum class ActionClickResult { Clicked, NotActionable, NotSupported, Expired }

    private enum class GestureDispatchResult { Accepted, Rejected, TimedOut }
}
