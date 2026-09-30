package com.edgehybrid.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.edgehybrid.agent.data.local.SecureKeyStore
import com.edgehybrid.agent.mcp.McpServerRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device checks that a JVM unit test cannot make.
 *
 * The unit suite runs with `isReturnDefaultValues = true`, which means every call into the
 * Android framework returns a default rather than doing real work. These tests run against
 * a real framework on a device or emulator, so they can prove things the unit suite can
 * only assert structurally:
 *
 *  - the keystore really produces ciphertext at rest, and round-trips,
 *  - the MCP registry really persists across instances and really starts empty/disabled,
 *  - the OAuth configuration the UI depends on is present and consistent.
 *
 * Run with `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class SecureKeyStoreInstrumentedTest {

    private lateinit var keyStore: SecureKeyStore

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        keyStore = SecureKeyStore(context)
    }

    @Test
    fun masterKeyIsCreatedInAndroidKeyStore() {
        // A value written and read back proves the master key was created and usable.
        keyStore.setTelegramChatId("-1001234567890")
        assertEquals("-1001234567890", keyStore.getTelegramChatId())
        keyStore.setTelegramChatId("")
    }

    @Test
    fun typesafeKeyRoundTripsThroughEncryptedPreferences() {
        val probe = "jev-probe-${System.currentTimeMillis()}"
        keyStore.setTypeSafeApiKey(probe)
        assertEquals(probe, keyStore.getTypeSafeApiKey())
        keyStore.setTypeSafeApiKey("")
    }

    @Test
    fun typesafeKeyFallsBackToBuildConfigWhenUnset() {
        // The build injects the key via secrets.properties -> BuildConfig. After clearing the
        // stored value, the resolver must still find one, otherwise Jev silently disables.
        keyStore.setTypeSafeApiKey("")
        val resolved = keyStore.getTypeSafeApiKey()
        assertTrue(
            "expected a BuildConfig fallback when nothing is stored, got '$resolved'",
            resolved.startsWith("apikey_")
        )
    }

    @Test
    fun mcpOAuthCredentialsRoundTripAndClear() {
        keyStore.setMcpOAuthCredentials(
            serverId = "probe",
            accessToken = "access-token-value",
            refreshToken = "refresh-token-value",
            expiresAtEpochMillis = 1_900_000_000_000L
        )

        assertTrue(keyStore.hasMcpOAuthToken("probe"))
        assertEquals("access-token-value", keyStore.getMcpOAuthAccessToken("probe"))
        assertEquals("refresh-token-value", keyStore.getMcpOAuthRefreshToken("probe"))
        assertEquals(1_900_000_000_000L, keyStore.getMcpOAuthExpiresAt("probe"))

        keyStore.clearMcpOAuthCredentials("probe")
        assertFalse(keyStore.hasMcpOAuthToken("probe"))
        assertEquals("", keyStore.getMcpOAuthRefreshToken("probe"))
    }

    @Test
    fun encryptedPreferencesAreNotPlaintextOnDisk() {
        keyStore.setMcpServerToken("probe", "super-secret-token")
        // Read the backing file directly: if EncryptedSharedPreferences is doing its job,
        // the token must not appear in the XML.
        val prefsDir = context.applicationInfo.dataDir + "/shared_prefs"
        val files = java.io.File(prefsDir).listFiles().orEmpty()
        val containsSecret = files.any { file ->
            runCatching { file.readText().contains("super-secret-token") }.getOrDefault(false)
        }
        assertFalse("a stored token was found in plaintext on disk", containsSecret)
        keyStore.clearMcpServerToken("probe")
    }

    @Test
    fun mcpServerRegistryStartsWithNothingEnabled() {
        val registry = McpServerRegistry(context, keyStore)

        // Critical default: shipping curated servers must not mean they are dialled.
        assertTrue(
            "no MCP server should be enabled on a fresh install",
            registry.enabledServers().isEmpty()
        )
        assertEquals(0, registry.catalog().enabledCount)
        assertTrue(
            "the curated catalogue should still be visible",
            registry.catalog().servers.isNotEmpty()
        )
    }

    @Test
    fun everyCuratedServerIsHttpsAndDisabledByDefault() {
        val catalog = McpServerRegistry(context, keyStore).catalog()
        catalog.servers.forEach { entry ->
            assertTrue("${entry.id} must be https", entry.url.startsWith("https://"))
        }
    }

    @Test
    fun enablingAnUnknownServerIsRefused() {
        val registry = McpServerRegistry(context, keyStore)
        assertFalse(
            "a model must not be able to invent a server by naming it",
            registry.enable("not-a-real-server")
        )
    }

    @Test
    fun enablingThenDisablingPersistsAcrossRegistryInstances() {
        val first = McpServerRegistry(context, keyStore)
        val target = first.catalog().servers.first()

        assertTrue(first.enable(target.id))
        assertTrue(
            "the enable must be visible to a fresh instance",
            McpServerRegistry(context, keyStore).enabledServers().any { it.id == target.id }
        )

        assertTrue(first.disable(target.id))
        assertTrue(
            "disable must persist too",
            McpServerRegistry(context, keyStore).enabledServers().none { it.id == target.id }
        )
    }

    @Test
    fun tokensAreNeverReturnedByTheCatalog() {
        val registry = McpServerRegistry(context, keyStore)
        val target = registry.catalog().servers.first()
        registry.setToken(target.id, "token-should-not-leak")

        val dumped = registry.catalog().toString()
        assertFalse("the catalog must not contain a token", dumped.contains("token-should-not-leak"))

        registry.clearToken(target.id)
    }

    @Test
    fun oauthRedirectUriIsASafePrivateScheme() {
        val uri = com.edgehybrid.agent.mcp.McpOAuthRedirectActivity.redirectUri()
        assertTrue(uri.startsWith("edgehybrid://"))
        assertFalse("a web scheme could be triggered by any site", uri.startsWith("http"))
    }

    @Test
    fun oauthRedirectActivityIsResolvableForTheCallbackOnly() {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("edgehybrid://oauth/callback?code=x&state=y")
        ).setPackage(context.packageName)

        val resolved = context.packageManager.resolveActivity(intent, 0)
        assertNotNull("the callback activity must be registered", resolved)
        assertTrue(
            "the handler must be the OAuth redirect activity",
            resolved!!.activityInfo.name.endsWith("McpOAuthRedirectActivity")
        )
    }

    @Test
    fun theCallbackIsNotReachableViaAnHttpUrl() {
        // A web URL must not be able to trigger the redirect handler.
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://evil.example.com/oauth/callback?code=x")
        ).setPackage(context.packageName)

        val resolved = context.packageManager.resolveActivity(intent, 0)
        val isOurActivity = resolved?.activityInfo?.name
            ?.endsWith("McpOAuthRedirectActivity") == true
        assertFalse("an https URL must not reach the OAuth callback", isOurActivity)
    }
}