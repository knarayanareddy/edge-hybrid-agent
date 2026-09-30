package com.edgehybrid.agent.tool

import java.util.Locale

/**
 * Canonical key for looking up a per-server MCP credential in
 * [com.edgehybrid.agent.data.local.SecureKeyStore].
 *
 * Both the registration path (`BuiltInSkillLoader`) and the call path
 * (`StreamableHttpMcpClient`) derive the key through this object, so a token written at
 * registration is the same one read at call time. Deriving it in two places would
 * silently break authentication.
 */
object McpServerKey {

    /**
     * Uses the endpoint's host as the key, falling back to [DEFAULT_KEY] when the URL
     * cannot be parsed or carries no host.
     *
     * The host is lowercased because DNS names are case-insensitive but `URI.host`
     * preserves the literal case. Without this, a token stored via
     * `https://Example.com/mcp` would not be found via `https://example.com/mcp`, and
     * authentication would fail silently.
     */
    fun forUrl(url: String): String = runCatching {
        java.net.URI.create(url.trim()).host
    }.getOrNull()?.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT) ?: DEFAULT_KEY

    const val DEFAULT_KEY: String = "default"

    /** Normalizes any user- or URL-derived name to the storage key format. */
    fun normalize(name: String): String = name.trim().lowercase(Locale.ROOT)
        .ifBlank { DEFAULT_KEY }
}
