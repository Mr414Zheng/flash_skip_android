package com.zhenghuaizhi.flashskip.domain.automation

import com.zhenghuaizhi.flashskip.domain.model.ActionReason
import com.zhenghuaizhi.flashskip.domain.model.AdRule
import com.zhenghuaizhi.flashskip.domain.model.NodeSelector
import com.zhenghuaizhi.flashskip.domain.ports.ClickResult
import com.zhenghuaizhi.flashskip.domain.ports.PageGateway
import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity
import com.zhenghuaizhi.flashskip.domain.ports.QueryResult

internal class NodeClickVerifier(
    private val pageGateway: PageGateway,
) {
    suspend fun verify(
        identity: PageIdentity,
        pending: PendingNodeClick,
    ): NodeClickVerification = when (pageGateway.query(identity, pending.selector)) {
        QueryResult.None,
        QueryResult.PageChanged,
        -> NodeClickVerification.Confirmed

        QueryResult.Ambiguous -> NodeClickVerification.Abandoned(ActionReason.AMBIGUOUS_MATCH)
        // 首次节点点击已经返回成功；复查超限通常表示广告关闭后进入了复杂首页。
        // 此时不能安全重试，也不应把已经执行的点击误记为“未执行”。
        QueryResult.TraversalLimit -> NodeClickVerification.Confirmed

        QueryResult.Unique -> retry(identity, pending)
    }

    private suspend fun retry(
        identity: PageIdentity,
        pending: PendingNodeClick,
    ): NodeClickVerification {
        if (pending.attempts >= MAX_ATTEMPTS) {
            return NodeClickVerification.Failed(ActionReason.CLICK_UNCONFIRMED)
        }
        return when (pageGateway.click(identity, pending.selector)) {
            ClickResult.Clicked -> NodeClickVerification.Retry(
                pending.copy(attempts = pending.attempts + 1),
            )

            ClickResult.NodeDisappeared,
            ClickResult.PageChanged,
            -> NodeClickVerification.Confirmed

            ClickResult.Ambiguous ->
                NodeClickVerification.Abandoned(ActionReason.AMBIGUOUS_MATCH)

            ClickResult.TraversalLimit ->
                NodeClickVerification.Abandoned(ActionReason.TRAVERSAL_LIMIT)

            ClickResult.NotActionable,
            ClickResult.NotSupported,
            ->
                NodeClickVerification.Failed(ActionReason.CLICK_UNCONFIRMED)
        }
    }

    companion object {
        const val VERIFY_DELAY_MS = 150L
        private const val MAX_ATTEMPTS = 3
    }
}

internal data class PendingNodeClick(
    val id: Long,
    val rule: AdRule,
    val selector: NodeSelector,
    val attempts: Int,
)

internal sealed interface NodeClickVerification {
    data object Confirmed : NodeClickVerification
    data class Retry(val pending: PendingNodeClick) : NodeClickVerification
    data class Abandoned(val reason: ActionReason) : NodeClickVerification
    data class Failed(val reason: ActionReason) : NodeClickVerification
}
