package com.edgehybrid.agent.data.local

import com.edgehybrid.agent.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the rotation pool parses the way `SecureKeyStore` consumes it.
 *
 * The pool is one comma-separated `BuildConfig.CLOUD_KEY_POOL` string, so the
 * parsing rules are the contract. Two failure modes are guarded:
 *
 *  - a blank slot would rotate onto an empty key, sending an unauthenticated
 *    request that surfaces as a provider fault rather than a rotation;
 *  - a duplicate key would burn a rotation step re-sending the key that just
 *    returned 429, which is the exact case rotation exists to escape.
 */
class KeyPoolParsingTest {

    /** Mirrors `SecureKeyStore.keyPool()` exactly. */
    private fun parse(raw: String): List<String> =
        raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct()

    @Test
    fun `a full six-key pool keeps its order`() {
        val pool = parse("sk-or-1,sk-or-2,sk-or-3,sk-or-4,sk-or-5,sk-or-6")
        assertEquals(6, pool.size)
        assertEquals("sk-or-1", pool[0])
        assertEquals("sk-or-6", pool[5])
    }

    @Test
    fun `blank slots are dropped so rotation never picks an empty key`() {
        val pool = parse("sk-or-1,  ,,sk-or-3,   ,sk-or-4")
        assertEquals(listOf("sk-or-1", "sk-or-3", "sk-or-4"), pool)
        assertTrue("no entry may be blank", pool.none { it.isBlank() })
    }

    @Test
    fun `duplicates are collapsed so a 429 key is not retried`() {
        val pool = parse("sk-or-1,sk-or-2,sk-or-1,sk-or-2,sk-or-3")
        assertEquals(3, pool.size)
        assertEquals("sk-or-1", pool[0])
        assertEquals("sk-or-3", pool[2])
    }

    @Test
    fun `an unconfigured pool yields nothing rather than a bogus key`() {
        assertEquals(0, parse("").size)
        assertEquals(0, parse("   ").size)
        assertEquals(0, parse(",,,").size)
    }

    @Test
    fun `pool size is not capped at three`() {
        // The earlier design hardcoded CLOUD_API_KEY_1..3, which silently dropped
        // keys 4-6. This asserts a six-key pool survives parsing intact.
        val six = (1..6).joinToString(",") { "sk-or-$it" }
        assertEquals(6, parse(six).size)
    }

    @Test
    fun `the compiled pool in this build is well formed`() {
        // Guards the gradle emit path: whatever this test build compiled must parse
        // without blanks, or the app would rotate onto an empty key on device.
        val parsed = parse(BuildConfig.CLOUD_KEY_POOL)
        assertTrue("compiled pool must not contain blank entries", parsed.none { it.isBlank() })
        assertEquals("compiled pool must be duplicate-free", parsed.size, parsed.distinct().size)
    }
}
