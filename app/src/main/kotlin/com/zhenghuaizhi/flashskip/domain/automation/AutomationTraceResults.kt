package com.zhenghuaizhi.flashskip.domain.automation

import com.zhenghuaizhi.flashskip.domain.ports.AutomationTraceResult
import com.zhenghuaizhi.flashskip.domain.ports.ClickResult
import com.zhenghuaizhi.flashskip.domain.ports.GestureResult
import com.zhenghuaizhi.flashskip.domain.ports.QueryResult

internal fun QueryResult.traceResult(): AutomationTraceResult = when (this) {
    QueryResult.Unavailable -> AutomationTraceResult.UNAVAILABLE
    QueryResult.None -> AutomationTraceResult.NONE
    QueryResult.Unique -> AutomationTraceResult.UNIQUE
    QueryResult.Ambiguous -> AutomationTraceResult.AMBIGUOUS
    QueryResult.PageChanged -> AutomationTraceResult.PAGE_CHANGED
    QueryResult.TraversalLimit -> AutomationTraceResult.TRAVERSAL_LIMIT
}

internal fun ClickResult.traceResult(): AutomationTraceResult = when (this) {
    ClickResult.Expired -> AutomationTraceResult.TIMED_OUT
    ClickResult.Unavailable -> AutomationTraceResult.UNAVAILABLE
    ClickResult.Clicked -> AutomationTraceResult.CLICKED
    ClickResult.NotActionable -> AutomationTraceResult.NOT_ACTIONABLE
    ClickResult.NotSupported -> AutomationTraceResult.NOT_SUPPORTED
    ClickResult.NodeDisappeared -> AutomationTraceResult.NODE_DISAPPEARED
    ClickResult.Ambiguous -> AutomationTraceResult.AMBIGUOUS
    ClickResult.PageChanged -> AutomationTraceResult.PAGE_CHANGED
    ClickResult.TraversalLimit -> AutomationTraceResult.TRAVERSAL_LIMIT
}

internal fun GestureResult.traceResult(): AutomationTraceResult = when (this) {
    GestureResult.Accepted -> AutomationTraceResult.ACCEPTED
    GestureResult.Unavailable -> AutomationTraceResult.UNAVAILABLE
    GestureResult.Completed -> AutomationTraceResult.COMPLETED
    GestureResult.Rejected -> AutomationTraceResult.REJECTED
    GestureResult.Cancelled -> AutomationTraceResult.CANCELLED
    GestureResult.TimedOut -> AutomationTraceResult.TIMED_OUT
    GestureResult.NodeDisappeared -> AutomationTraceResult.NODE_DISAPPEARED
    GestureResult.Ambiguous -> AutomationTraceResult.AMBIGUOUS
    GestureResult.PageChanged -> AutomationTraceResult.PAGE_CHANGED
    GestureResult.TraversalLimit -> AutomationTraceResult.TRAVERSAL_LIMIT
}
