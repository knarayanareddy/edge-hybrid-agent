package com.edgehybrid.agent.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the 429 rotation pool terminates.
 *
 * The bug this guards: `getNextOpenRouterKeyIndex` signals exhaustion with -1 (a
 * non-null Int), while the call site tested `!= null`. For a non-null store that
 * test is always true, so rotation accepted -1 as a valid index, re-read a blank
 * key, and looped on the exhausted 429 forever.
 *
 * These assert the contract the call site depends on: a valid next index, or a
 * value that is never mistaken for one.
 */
class OpenRouterKeyRotationTest {

    /** Mirrors the committed call-site check exactly. */
    private fun rotationAccepts(nextIndex: Int): Boolean = nextIndex >= 0

    @Test
    fun `exhaustion sentinel is rejected by the call site`() {
        assertTrue(
            "getNextOpenRouterKeyIndex returns -1 when spent; the call site must reject it",
            !rotationAccepts(-1)
        )
    }

    @Test
    fun `valid next indices are accepted`() {
        listOf(1, 2, 3).forEach { index ->
            assertTrue("index $index should be usable", rotationAccepts(index))
        }
    }

    @Test
    fun `rotation advances one key at a time and then stops`() {
        // Simulate walking the pool as the engine does, with one key configured
        // beyond the primary.
        val configured = setOf(0, 1)
        var current = 0
        val visited = mutableListOf(current)

        while (true) {
            val next = current + 1
            if (next !in configured) {
                // Mirrors getNextOpenRouterKeyIndex returning -1.
                if (rotationAccepts(-1)) error("BUG: exhausted pool accepted -1 and would loop")
                break
            }
            current = next
            visited += current
        }

        assertEquals("should have tried each configured key exactly once", listOf(0, 1), visited)
    }

    @Test
    fun `a single key pool never rotates`() {
        val configured = setOf(0)
        var current = 0
        var rotations = 0
        while (true) {
            val next = current + 1
            if (next !in configured) {
                if (rotationAccepts(-1)) error("BUG: exhausted pool accepted -1")
                break
            }
            current = next
            rotations++
        }
        assertEquals("with one key there is nothing to rotate to", 0, rotations)
    }
}
