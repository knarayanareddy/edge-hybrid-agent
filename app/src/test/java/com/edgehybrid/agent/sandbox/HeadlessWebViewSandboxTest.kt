package com.edgehybrid.agent.sandbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HeadlessWebViewSandboxTest {

    @Test
    fun testHostBridgeCompleteResumesSuccessfully() = runTest {
        val deferred = CompletableDeferred<String>()
        val bridge = AndroidSandboxHostBridge(deferred)

        val sampleJson = "{\"status\": \"success\", \"result\": 42}"
        bridge.complete(sampleJson)

        val result = deferred.await()
        assertEquals(sampleJson, result)
    }

    @Test
    fun testHostBridgeFailThrowsException() = runTest {
        val deferred = CompletableDeferred<String>()
        val bridge = AndroidSandboxHostBridge(deferred)

        bridge.fail("Division by zero")

        try {
            deferred.await()
            fail("Expected exception was not thrown")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("Division by zero") == true)
        }
    }
}
