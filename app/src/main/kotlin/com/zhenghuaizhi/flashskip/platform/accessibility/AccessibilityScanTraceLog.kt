package com.zhenghuaizhi.flashskip.platform.accessibility

import android.util.Log
import com.zhenghuaizhi.flashskip.platform.diagnostics.ProcessDiagnosticIdentifiers
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.ports.AutomationTraceResult
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import java.util.concurrent.atomic.AtomicLong

internal data class CandidateCheck(
    val cachedMatch: Boolean,
    val refreshed: Boolean?,
    val result: CandidateMatchResult,
    val prefixLike: Boolean,
    val resourceIdHash: Int?,
    val classHash: Int?,
    val visible: Boolean,
    val clickable: Boolean,
)

internal enum class ScanOperation { QUERY, CLICK, GESTURE }

internal enum class SearchPhase { INDEX, FALLBACK }

internal enum class SearchLimit { TIME, NODE_COUNT, DEPTH }

/** 单次查询的数值诊断，与返回结论分开；未完成扫描的零候选不能证明节点消失。 */
internal class SearchDiagnostics {
    var phase: SearchPhase? = null
    var indexedNodes: Int? = null
    var indexLookupMs = 0L
    var visitedNodes = 0
    var maxDepth = 0
    var searchMs = 0L
    var limit: SearchLimit? = null
}

/** 每次扫描仅保留有限的节点特征计数，不记录页面文本、节点或位置。 */
internal class AccessibilityScanTraceLog(
    private val enabled: Boolean,
    identity: PageIdentity,
    selector: NodeSelector,
    operation: ScanOperation,
    private val monotonicClock: MonotonicClock = AndroidMonotonicClock,
) {
    private val startedAt = if (enabled) monotonicClock.elapsedRealtimeMs() else 0L
    private val identifiers = ProcessDiagnosticIdentifiers.Process
    private val scanId = nextScan.incrementAndGet()
    private val prefix = if (!enabled) "" else "source=platform process=${identifiers.scope} scan=$scanId operation=$operation " +
        "targetToken=${identifiers.token("target", identity.packageName.hashCode())} " +
        "windowId=${identity.windowId} selectorToken=${identifiers.token("selector", selector.hashCode())}"
    private var candidates = 0

    val candidateSink: ((CandidateCheck) -> Unit)? = if (enabled) ::candidate else null
    val searchDiagnostics: SearchDiagnostics? = if (enabled) SearchDiagnostics() else null

    private fun candidate(check: CandidateCheck) {
        if (++candidates > MAX_CANDIDATE_LOGS) return
        write("stage=CANDIDATE_CHECK cachedMatch=${check.cachedMatch} " +
            "refreshed=${check.refreshed} result=${check.result} prefixLike=${check.prefixLike} " +
            "resourceIdToken=${check.resourceIdHash?.let { identifiers.token("resource", it) }} " +
            "classToken=${check.classHash?.let { identifiers.token("class", it) }} " +
            "visible=${check.visible} clickable=${check.clickable}")
    }

    fun finish(result: AutomationTraceResult) {
        if (enabled && (candidates > 0 || result != AutomationTraceResult.NONE)) {
            val diagnostics = searchDiagnostics
            val searchFields = diagnostics?.let {
                " phase=${it.phase} indexedNodes=${it.indexedNodes} indexLookupMs=${it.indexLookupMs}" +
                    " visitedNodes=${it.visitedNodes} maxDepth=${it.maxDepth}" +
                    " searchMs=${it.searchMs} limit=${it.limit}"
            }.orEmpty()
            write("stage=SCAN_RESULT result=$result candidates=$candidates " +
                "suppressed=${(candidates - MAX_CANDIDATE_LOGS).coerceAtLeast(0)} " +
                "durationMs=${monotonicClock.elapsedRealtimeMs() - startedAt}$searchFields")
        }
    }

    private fun write(fields: String) {
        runCatching { Log.d(AutomationTraceLog.TAG, "$prefix $fields") }
    }

    companion object {
        private const val MAX_CANDIDATE_LOGS = 8
        private val nextScan = AtomicLong()
    }
}
