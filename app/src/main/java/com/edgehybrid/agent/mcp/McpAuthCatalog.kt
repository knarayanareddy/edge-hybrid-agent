package com.edgehybrid.agent.mcp

/**
 * Token-lookup seam over the OAuth store.
 *
 * The router only needs "give me a valid access token for this server", so that is the
 * whole contract, and it can be faked in tests without a Context.
 */
fun interface McpTokenSource {
    /** Returns a usable access token for [serverId], or null when there is none. */
    suspend fun accessTokenFor(serverId: String): String?
}

/**
 * How a third-party MCP server can actually be authenticated by a native app.
 *
 * Researched against vendor docs (2026-09-30) rather than assumed:
 *
 *  - **Linear** (`mcp.linear.app`) — OAuth 2.1 with *dynamic client registration*, and also
 *    accepts a Linear API key directly as `Authorization: Bearer`. Read-only access is a
 *    separate endpoint (`/mcp/readonly`) or the `read` scope.
 *  - **Notion** (`mcp.notion.com`) — dynamic client registration per RFC 7591, discovered
 *    from the server's OAuth metadata. No client id is published for third-party clients.
 *  - **Figma** — the remote MCP server is OAuth-only and gated behind a catalog-approval
 *    process; Figma staff confirm personal access tokens are *not* supported. A third-party
 *    client cannot self-provision.
 *
 * The consequence is important and is encoded in [authMode]: none of these servers can be
 * onboarded by embedding a client id in the APK, which is what the previous
 * hand-written `OAuthServerConfig` implied. Two paths actually work today:
 * paste a bearer token, or implement DCR.
 */
enum class McpAuthMode {
    /** No credential needed; the endpoint serves tools to any client. */
    NONE,

    /** Paste a personal access token or API key, sent as `Authorization: Bearer`. */
    BEARER_TOKEN,

    /**
     * Requires dynamic client registration (RFC 7591) plus PKCE, with endpoints discovered
     * from the server's OAuth protected-resource metadata.
     *
     * Not yet implemented in the app, so servers in this mode are catalogued but cannot
     * be connected from Settings.
     */
    DYNAMIC_REGISTRATION
}

/**
 * Authentication requirements for one curated server.
 *
 * No client id or endpoint is hardcoded, because publishing one would be inventing a
 * credential for a server that never issued it to this app.
 */
data class McpServerAuth(
    val serverId: String,
    val mode: McpAuthMode,
    /** Why this mode was chosen; shown to the user instead of a button that cannot work. */
    val explanation: String,
    /** Alternate endpoint offering a narrower permission set, when the vendor provides one. */
    val readOnlyUrl: String? = null,
    /** Scope that reduces a bearer token to read-only, when the vendor supports it. */
    val readOnlyScopeHint: String? = null
)

/**
 * Curated auth requirements, sourced from vendor documentation.
 *
 * Provenance: read from each vendor's own docs on 2026-09-30. See [McpAuthMode] for the
 * quoted reasoning. Nothing here is a guessed client id.
 */
object McpAuthCatalog {

    val LINEAR = McpServerAuth(
        serverId = "linear",
        mode = McpAuthMode.BEARER_TOKEN,
        explanation = "Paste a Linear API key. Linear's MCP server accepts it directly as " +
            "a bearer token, and also supports an OAuth sign-in flow this app does not " +
            "yet implement.",
        readOnlyUrl = "https://mcp.linear.app/mcp/readonly",
        readOnlyScopeHint = "read"
    )

    val NOTION = McpServerAuth(
        serverId = "notion",
        mode = McpAuthMode.DYNAMIC_REGISTRATION,
        explanation = "Notion's MCP server uses dynamic client registration, which this app " +
            "does not implement yet. A Notion integration token will not work against the " +
            "MCP endpoint."
    )

    val FIGMA = McpServerAuth(
        serverId = "figma",
        mode = McpAuthMode.DYNAMIC_REGISTRATION,
        explanation = "Figma's remote MCP server is OAuth-only and limited to catalog-" +
            "approved clients; personal access tokens are not supported. This app cannot " +
            "self-provision access."
    )

    val SUPABASE = McpServerAuth(
        serverId = "supabase",
        mode = McpAuthMode.DYNAMIC_REGISTRATION,
        explanation = "Supabase requires a confidential OAuth client with a client secret, " +
            "which cannot be held safely in an app. Use the Management API directly instead."
    )

    val ALL: List<McpServerAuth> = listOf(LINEAR, NOTION, FIGMA, SUPABASE)

    fun forId(serverId: String): McpServerAuth? = ALL.firstOrNull { it.serverId == serverId }
}