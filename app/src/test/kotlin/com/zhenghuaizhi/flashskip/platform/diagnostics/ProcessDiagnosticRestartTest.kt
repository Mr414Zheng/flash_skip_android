package com.zhenghuaizhi.flashskip.platform.diagnostics

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** 子进程只接收固定合成输入，不接收父进程密钥；退出后由另一个 OS 进程重新初始化单例。 */
class ProcessDiagnosticRestartTest {
    @Test fun realProcessRestartChangesScopeAndTokensButPreservesWithinProcessCorrelation() {
        val first = probe()
        val second = probe()
        assertNotEquals(first[0], second[0])
        for (row in listOf(first, second)) {
            assertEquals(6, row.size)
            assertEquals(row[2], row[3])
            assertEquals(row[4], row[5])
            assertTrue(row[1].matches(Regex("[a-f0-9]{32}")))
            assertTrue(row[2].matches(Regex("[a-f0-9]{32}")))
        }
        assertNotEquals(first[1], second[1])
        assertNotEquals(first[2], second[2])
        assertNotEquals(first[4], second[4])
        println("fresh-jvm first=${first.joinToString(",")} second=${second.joinToString(",")}")
    }

    private fun probe(): List<String> {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = requireNotNull(System.getProperty("flashskip.testRuntimeClasspath"))
        val child = ProcessBuilder(java, "-cp", classpath, ProcessIdentifierProbe::class.java.name)
            .redirectErrorStream(true).start()
        try {
            assertTrue("标识探针子进程超时", child.waitFor(30, TimeUnit.SECONDS))
            val output = child.inputStream.bufferedReader().readText().trim()
            assertEquals(output, 0, child.exitValue())
            return output.lines().single().split(",")
        } finally {
            if (child.isAlive) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }
}

object ProcessIdentifierProbe {
    @JvmStatic fun main(args: Array<String>) {
        val identifiers = ProcessDiagnosticIdentifiers.Process
        println(listOf(
            ProcessHandle.current().pid(), identifiers.scope,
            identifiers.token("target", "synthetic-fixture"), identifiers.token("target", "synthetic-fixture"),
            identifiers.sessionFileId(42), identifiers.sessionFileId(42),
        ).joinToString(","))
    }
}
