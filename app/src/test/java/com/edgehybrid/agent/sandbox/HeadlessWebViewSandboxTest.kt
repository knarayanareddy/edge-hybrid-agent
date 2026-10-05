package com.edgehybrid.agent.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-enforced network policy for the headless sandbox.
 *
 * These assertions cover the private/reserved address ranges and local-only names that
 * the network filter must refuse regardless of what the skill script believes.
 */
class HeadlessWebViewSandboxTest {

    @Test
    fun `loopback and localhost are reserved`() {
        listOf("localhost", "127.0.0.1", "127.1.2.3", "anything.localhost").forEach { host ->
            assertTrue("$host must be blocked", HeadlessWebViewSandbox.isPrivateOrReservedHost(host))
        }
    }

    @Test
    fun `the whole of RFC1918 172_16 slash 12 is blocked`() {
        // The pre-fix check only matched 172.16 and let 172.17-172.31 through.
        listOf("172.16.0.1", "172.17.0.1", "172.20.10.5", "172.31.255.254").forEach { host ->
            assertTrue("$host must be blocked", HeadlessWebViewSandbox.isPrivateOrReservedHost(host))
        }
    }

    @Test
    fun `other private and reserved ranges are blocked`() {
        listOf(
            "10.0.0.1",
            "192.168.1.1",
            "169.254.169.254",   // cloud metadata
            "169.254.1.1",
            "100.64.0.1",       // CGNAT
            "100.127.255.255",
            "0.0.0.0",
            "198.18.0.1",       // benchmarking
            "198.19.255.255",
            "224.0.0.1",        // multicast
            "255.255.255.255",
            "example.local",
            "db.internal",
            "metadata.google.internal"
        ).forEach { host ->
            assertTrue("$host must be blocked", HeadlessWebViewSandbox.isPrivateOrReservedHost(host))
        }
    }

    @Test
    fun `IPv6 literals are refused`() {
        listOf("::1", "[::1]", "fe80::1", "fc00::1", "fd00::1").forEach { host ->
            assertTrue("$host must be blocked", HeadlessWebViewSandbox.isPrivateOrReservedHost(host))
        }
    }

    @Test
    fun `malformed and empty hosts fail closed`() {
        // Note: a syntactically valid DNS name like "not-a-host" is NOT reserved — it is
        // simply unreachable unless explicitly allowlisted. These are the genuinely
        // malformed / empty cases.
        listOf(null, "", "   ", "1.2.3", "1.2.3.4.5", "999.1.1.1", "1.2.3.x", "10.0.0", "1.2.3.4.5.6")
            .forEach { host ->
                assertTrue("$host must fail closed", HeadlessWebViewSandbox.isPrivateOrReservedHost(host))
            }
    }

    @Test
    fun `public hosts are permitted`() {
        listOf("example.com", "www.example.com", "api.openai.com", "8.8.8.8", "1.1.1.1")
            .forEach { host ->
                assertFalse(
                    "$host should be allowed",
                    HeadlessWebViewSandbox.isPrivateOrReservedHost(host)
                )
            }
    }

    @Test
    fun `the three bundled scripts are shipped`() {
        assertTrue("calculator.js" in HeadlessWebViewSandbox.BUNDLED_SKILLS)
        assertTrue("device_info.js" in HeadlessWebViewSandbox.BUNDLED_SKILLS)
        assertTrue("web_extract.js" in HeadlessWebViewSandbox.BUNDLED_SKILLS)
        assertEquals(3, HeadlessWebViewSandbox.BUNDLED_SKILLS.size)
    }

    // NOTE: this list is no longer the execution gate — that moved to
    // UserSkillStore (which decides *whether*) plus UserScriptPathHandler (which
    // decides *how a file is read*). The traversal cases are still worth asserting
    // because the handler re-checks them independently.
    @Test
    fun `traversal and unknown script names are not bundled skills`() {
        listOf(
            "../../databases/app.db",
            "../secrets.js",
            "evil.js",
            "calculator.js/../../x",
            "Calculator.js",
            ""
        ).forEach { name ->
            assertFalse("$name must not be allowed", name in HeadlessWebViewSandbox.BUNDLED_SKILLS)
        }
    }

    @Test
    fun `the watchdog timeout is the documented five seconds`() {
        assertTrue(HeadlessWebViewSandbox.EXECUTION_TIMEOUT_MS == 5_000L)
    }
}
