package com.zhenghuaizhi.flashskip.domain.ports

import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

/** 可展示的恢复数据不代表存储可写或自动执行可用；此状态只在内存中传递。 */
data class StorageRead<T>(val value: T?, val failure: IOException? = null) {
    val available: Boolean get() = value != null && failure == null

    /** 命令必须获得有效读取；不能使用不可用快照的恢复值进行覆盖写入。 */
    fun requireAvailable(): T {
        failure?.let { throw it }
        return value ?: throw IOException("Storage has no available value")
    }
}

internal fun <T> Flow<T>.observeStorageReads(): Flow<StorageRead<T>> =
    map { StorageRead(it) }.retryStorageReads()

/** IO 故障先发布不可用，再限速重订阅；取消和程序错误不重试。 */
internal fun <T> Flow<StorageRead<T>>.retryStorageReads(): Flow<StorageRead<T>> = retryWhen { error, _ ->
    if (error !is IOException) return@retryWhen false
    emit(StorageRead(null, error))
    delay(1_000)
    true
}
