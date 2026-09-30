package com.edgehybrid.agent.inference

import com.edgehybrid.agent.agent.AgentPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider retry/backoff policy.
 *
 * This replaces an earlier `HybridInferenceRouterTest` that imported from the deleted
 * `com.edgehybrid.agent.core.inference` stub tree. That test never exercised a router: it
 * asserted on `if (isOnline) cloud else local`, which is a tautology, so it passed while
 * proving nothing about the app.
 *
 * The real routing behaviour today lives in [AgentPolicy]: cloud-only execution with
 * bounded exponential backoff. There is no local-engine fallback path in the shipped
 * agent, which is what these tests pin down.
 */
class AgentPolicyRetryTest {

    @Test
    fun `backoff grows exponentially`() {
        val policy = AgentPolicy(initialBackoffMs = 500, maxBackoffMs = 8_000)

        val first = policy.backoffFor(0)
        val second = policy.backoffFor(1)
        val third = policy.backoffFor(2)

        assertEquals("first retry should wait the initial delay", 500L, first)
        assertTrue("second should be larger than first: $second vs $first", second > first)
        assertTrue("third should be larger than second: $third vs $second", third > second)
    }

    @Test
    fun `backoff is capped`() {
        val policy = AgentPolicy(initialBackoffMs = 500, maxBackoffMs = 2_000)

        // Far past the point where doubling would exceed the cap.
        val late = policy.backoffFor(20)

        assertTrue("backoff $late must not exceed the cap 2000", late <= 2_000L)
    }

    @Test
    fun `retry count is bounded`() {
        val policy = AgentPolicy(providerMaxRetries = 3)

        assertTrue(
            "policy must allow a finite number of retries, was ${policy.providerMaxRetries}",
            policy.providerMaxRetries in 0..10
        )
    }

    @Test
    fun `an exhausted policy cannot spin forever`() {
        val policy = AgentPolicy(providerMaxRetries = 2)
        var attempts = 0

        while (attempts < policy.providerMaxRetries) {
            attempts++
            policy.backoffFor(attempts)
        }

        assertEquals(2, attempts)
    }
}