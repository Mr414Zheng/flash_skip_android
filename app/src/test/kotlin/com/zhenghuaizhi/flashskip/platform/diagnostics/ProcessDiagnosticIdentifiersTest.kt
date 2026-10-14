package com.zhenghuaizhi.flashskip.platform.diagnostics

import org.junit.Assert.*
import org.junit.Test

class ProcessDiagnosticIdentifiersTest {
    @Test fun sameProcessAndNamespaceRetainCorrelation() {
        val ids = ProcessDiagnosticIdentifiers(ByteArray(32) { 1 })
        assertEquals(ids.token("target", 12345), ids.token("target", 12345))
        assertEquals(ids.sessionFileId(987654321), ids.sessionFileId(987654321))
    }
    @Test fun differentProcessSecretsCannotReuseContentOrSessionIdentifiers() {
        val first = ProcessDiagnosticIdentifiers(ByteArray(32) { 1 })
        val second = ProcessDiagnosticIdentifiers(ByteArray(32) { 2 })
        assertNotEquals(first.scope, second.scope)
        assertNotEquals(first.token("target", "secret.package"), second.token("target", "secret.package"))
        assertNotEquals(first.sessionFileId(987654321), second.sessionFileId(987654321))
    }
    @Test fun namespacesSeparateEqualScalarIdentifiers() {
        val ids = ProcessDiagnosticIdentifiers(ByteArray(32) { 1 })
        assertNotEquals(ids.token("target", 1), ids.token("resource", 1))
        assertNotEquals(ids.token("target", 1), ids.token("target", 2))
    }
    @Test fun tokenNeverOutputsRawContentAndFileIdentityRemainsNonnegative() {
        val ids = ProcessDiagnosticIdentifiers(ByteArray(32) { 1 })
        assertTrue(ids.token("target", "secret.package").matches(Regex("[a-f0-9]{32}")))
        assertTrue(ids.sessionFileId(987654321) >= 0)
        assertNotEquals(987654321L, ids.sessionFileId(987654321))
    }
    @Test fun callerCannotMutateInstalledProcessSecret() {
        val secret = ByteArray(32) { 1 }; val ids = ProcessDiagnosticIdentifiers(secret)
        val before = ids.token("target", 3); secret.fill(2)
        assertEquals(before, ids.token("target", 3))
    }
}
