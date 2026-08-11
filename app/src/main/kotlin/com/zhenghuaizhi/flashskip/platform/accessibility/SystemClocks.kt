package com.zhenghuaizhi.flashskip.platform.accessibility

import android.os.SystemClock
import com.zhenghuaizhi.flashskip.domain.ports.MonotonicClock
import com.zhenghuaizhi.flashskip.domain.ports.WallClock

object AndroidMonotonicClock : MonotonicClock {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}

object AndroidWallClock : WallClock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}
