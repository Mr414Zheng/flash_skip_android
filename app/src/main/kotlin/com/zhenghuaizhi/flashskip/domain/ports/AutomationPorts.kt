package com.zhenghuaizhi.flashskip.domain.ports

import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.model.AppVersion

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
    data object None : QueryResult
    data object Unique : QueryResult
    data object Ambiguous : QueryResult
    data object PageChanged : QueryResult
    data object TraversalLimit : QueryResult
}

sealed interface ClickResult {
    data object Clicked : ClickResult
    data object NotActionable : ClickResult
    data object NotSupported : ClickResult
    data object NodeDisappeared : ClickResult
    data object Ambiguous : ClickResult
    data object PageChanged : ClickResult
    data object TraversalLimit : ClickResult
}

sealed interface GestureResult {
    data object Completed : GestureResult
    data object Rejected : GestureResult
    data object Cancelled : GestureResult
    data object NodeDisappeared : GestureResult
    data object Ambiguous : GestureResult
    data object PageChanged : GestureResult
    data object TraversalLimit : GestureResult
}

interface PageGateway {
    suspend fun query(identity: PageIdentity, selector: NodeSelector): QueryResult

    suspend fun click(identity: PageIdentity, selector: NodeSelector): ClickResult

    suspend fun gestureAtCenter(identity: PageIdentity, selector: NodeSelector): GestureResult
}
