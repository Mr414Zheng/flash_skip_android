package com.zhenghuaizhi.flashskip.domain.automation

import com.zhenghuaizhi.flashskip.domain.ports.PageIdentity

sealed interface ForegroundSignal {
    data class TargetActive(
        val identity: PageIdentity,
        val occurredAtElapsedMs: Long,
    ) : ForegroundSignal

    // 非目标前台信号只表达边界，不携带包名、窗口或页面数据。
    data class OutsideTargets(val occurredAtElapsedMs: Long) : ForegroundSignal
}
