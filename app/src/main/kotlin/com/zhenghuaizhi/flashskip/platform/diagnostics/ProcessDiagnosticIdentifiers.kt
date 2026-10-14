package com.zhenghuaizhi.flashskip.platform.diagnostics

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 随机密钥只存于本进程；输出代号不可由公开内容散列跨进程复现，不是匿名化承诺。 */
internal class ProcessDiagnosticIdentifiers(
    secret: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) },
) {
    private val key = SecretKeySpec(secret.copyOf(), "HmacSHA256")
    val scope: String = token("process", "scope")

    fun token(namespace: String, value: Any): String = digest(namespace, value)
        .take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** 保留 journal 的数值文件名和索引格式，但不把业务会话 ID 写到磁盘。 */
    fun sessionFileId(sessionId: Long): Long =
        ByteBuffer.wrap(digest("capture-session", sessionId)).long and Long.MAX_VALUE

    private fun digest(namespace: String, value: Any): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal("$namespace\u0000$value".toByteArray(Charsets.UTF_8))
    }

    companion object {
        val Process: ProcessDiagnosticIdentifiers by lazy { ProcessDiagnosticIdentifiers() }
    }
}
