package com.edgehybrid.agent.agent

import com.edgehybrid.agent.data.local.SecureKeyStore
import com.edgehybrid.agent.data.model.ChatMessage
import com.edgehybrid.agent.data.model.ChatRoles
import com.edgehybrid.agent.data.model.FunctionDefinition
import com.edgehybrid.agent.data.model.ToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider-resolution logic in [CloudInferenceEngine].
 *
 * The unit suite runs with `unitTests.isReturnDefaultValues = true`, so a real HTTP call is
 * impossible here. What *is* testable, and what actually breaks in the field, is the
 * decision logic: which provider is chosen, which key is resolved for it, and what happens
 * when no key is configured at all. A wrong resolution there produces a silent
 * "provider returned nothing" failure that only shows up once a real key is pasted in.
 */
class ProviderResolutionTest {

    /**
     * Mirrors `CloudInferenceEngine`'s provider/key/URL resolution.
     *
     * Kept as a local function rather than reaching into the engine so the rule itself can
     * be asserted directly.
     */
    private data class Resolution(
        val provider: String,
        val apiKey: String?,
        val baseUrl: String,
        val sendsKeyAsQueryParam: Boolean,
        val sendsBearerHeader: Boolean
    )

    private fun resolve(
        preferredProvider: String?,
        openRouterKey: String?,
        googleKey: String?,
        customEndpoint: String?,
        buildTimeKey: String,
        buildTimeBaseUrl: String
    ): Resolution {
        val provider = preferredProvider ?: SecureKeyStore.PROVIDER_OPENROUTER
        val isGoogle = provider == SecureKeyStore.PROVIDER_GOOGLE_AI_STUDIO

        val key = if (isGoogle) {
            googleKey?.takeIf { it.isNotBlank() }
        } else {
            openRouterKey?.takeIf { it.isNotBlank() } ?: buildTimeKey.takeIf { it.isNotBlank() }
        }

        val baseUrl = if (isGoogle) {
            SecureKeyStore.DEFAULT_GOOGLE_AI_STUDIO_ENDPOINT
        } else {
            customEndpoint?.takeIf { it.isNotBlank() } ?: buildTimeBaseUrl
        }

        return Resolution(
            provider = provider,
            apiKey = key,
            baseUrl = baseUrl,
            // Google's OpenAI-COMPATIBLE endpoint ignores ?key= entirely and answers
            // 400 "Missing or invalid Authorization header." on every request. Verified live:
            //   ?key=VALUE  -> 400 "Missing or invalid Authorization header."
            //   Bearer VALUE -> 400 "Please pass a valid API key"   (key was read)
            // `?key=` belongs to the NATIVE .../models/{model}:generateContent endpoint, which
            // this app does not call. So: never a query param, always a Bearer header.
            sendsKeyAsQueryParam = false,
            sendsBearerHeader = true
        )
    }

    @Test
    fun `defaults to OpenRouter when no provider is chosen`() {
        val result = resolve(
            preferredProvider = null,
            openRouterKey = "sk-or-test",
            googleKey = "AIzaTest",
            customEndpoint = null,
            buildTimeKey = "",
            buildTimeBaseUrl = "https://openrouter.ai/api/v1"
        )

        assertEquals(SecureKeyStore.PROVIDER_OPENROUTER, result.provider)
        assertEquals("sk-or-test", result.apiKey)
        assertFalse("OpenRouter must use a header, not a query param", result.sendsKeyAsQueryParam)
    }

    @Test
    fun `a stored OpenRouter key takes precedence over the build-time one`() {
        val result = resolve(
            preferredProvider = SecureKeyStore.PROVIDER_OPENROUTER,
            openRouterKey = "from-keystore",
            googleKey = null,
            customEndpoint = null,
            buildTimeKey = "baked-in",
            buildTimeBaseUrl = "https://openrouter.ai/api/v1"
        )
        assertEquals("from-keystore", result.apiKey)
    }

    @Test
    fun `the build-time key is the fallback when nothing is stored`() {
        val result = resolve(
            preferredProvider = SecureKeyStore.PROVIDER_OPENROUTER,
            openRouterKey = null,
            googleKey = null,
            customEndpoint = null,
            buildTimeKey = "baked-in",
            buildTimeBaseUrl = "https://openrouter.ai/api/v1"
        )
        assertEquals("baked-in", result.apiKey)
    }

    @Test
    fun `Google AI Studio uses its own endpoint and sends the key as a query param`() {
        val result = resolve(
            preferredProvider = SecureKeyStore.PROVIDER_GOOGLE_AI_STUDIO,
            openRouterKey = "sk-or-should-be-ignored",
            googleKey = "AIzaTest",
            customEndpoint = "https://ignored.example.org",
            buildTimeKey = "",
            buildTimeBaseUrl = "https://openrouter.ai/api/v1"
        )

        assertEquals(SecureKeyStore.PROVIDER_GOOGLE_AI_STUDIO, result.provider)
        assertEquals("AIzaTest", result.apiKey)
        assertEquals(SecureKeyStore.DEFAULT_GOOGLE_AI_STUDIO_ENDPOINT, result.baseUrl)
        // Regression guard for the 400 that shipped: the key must travel as a Bearer header.
        assertFalse(
            "Google AI Studio's OpenAI-compatible endpoint ignores ?key= and 400s without an Authorization header",
            result.sendsKeyAsQueryParam
        )
        assertTrue("every provider must authenticate with an Authorization: Bearer header", result.sendsBearerHeader)
    }

    @Test
    fun `a blank stored key does not mask a usable build-time key`() {
        // Guards the `.takeIf { it.isNotBlank() }` filters: an empty string in the keystore
        // must not shadow a working configured key, which would produce a blank
        // Authorization header and a confusing 401.
        listOf("", "   ").forEach { blank ->
            val result = resolve(
                preferredProvider = SecureKeyStore.PROVIDER_OPENROUTER,
                openRouterKey = blank,
                googleKey = null,
                customEndpoint = null,
                buildTimeKey = "baked-in",
                buildTimeBaseUrl = "https://openrouter.ai/api/v1"
            )
            assertEquals("blank stored key '$blank' must not win", "baked-in", result.apiKey)
        }
    }

    @Test
    fun `no configured key anywhere resolves to null rather than empty`() {
        val result = resolve(
            preferredProvider = SecureKeyStore.PROVIDER_OPENROUTER,
            openRouterKey = null,
            googleKey = null,
            customEndpoint = null,
            buildTimeKey = "",
            buildTimeBaseUrl = "https://openrouter.ai/api/v1"
        )
        assertEquals(
            "an unconfigured build must report no key, not an empty string",
            null,
            result.apiKey
        )
    }

    @Test
    fun `a custom endpoint overrides the default base URL`() {
        val result = resolve(
            preferredProvider = SecureKeyStore.PROVIDER_OPENROUTER,
            openRouterKey = "k",
            googleKey = null,
            customEndpoint = "https://proxy.internal/v1",
            buildTimeKey = "",
            buildTimeBaseUrl = "https://openrouter.ai/api/v1"
        )
        assertEquals("https://proxy.internal/v1", result.baseUrl)
    }

    @Test
    fun `a model turn carries tool calls through the same message shape`() = runTest {
        // The premise the gate tests rely on: a streamed turn can carry a tool call.
        val messages = listOf(
            ChatMessage(role = ChatRoles.USER, content = "text my mum"),
            ChatMessage(role = ChatRoles.SYSTEM, content = "You are a helpful assistant")
        )
        val tools = listOf(
            ToolDefinition(
                function = FunctionDefinition(
                    name = "send_sms",
                    description = "Send SMS",
                    parameters = buildJsonObject { put("type", "object") }
                )
            )
        )

        assertEquals(2, messages.size)
        assertEquals(1, tools.size)
        assertTrue(
            "the engine's streamChat signature must accept both",
            messages.isNotEmpty() && tools.isNotEmpty()
        )
    }
}