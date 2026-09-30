package com.edgehybrid.agent.mcp

/**
 * One MCP server the app knows about.
 *
 * [url] is always `https://`. [enabled] is the user's decision; a server is only dialled
 * when enabled. No credential field exists here by design — tokens live in the encrypted
 * keystore and are looked up by [id].
 */
data class McpServerEntry(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = false,
    val category: String = "custom",
    val description: String = "",
    val requiresApiKey: Boolean = false
) {
    val isCurated: Boolean get() = id in CuratedMcpServers.IDS
}

data class McpServerCatalog(
    val servers: List<McpServerEntry>,
    val enabledCount: Int
) {
    val total: Int get() = servers.size
}

/**
 * Key derivation for per-server credentials.
 *
 * Shared by the registration path and the call path so a token written for a server is
 * the same token read when calling it.
 */
object McpServerKeys {
    fun forId(id: String): String = id.trim().lowercase().ifBlank { "default" }
}

/**
 * Curated MCP servers.
 *
 * Provenance: every entry below was probed from the development host with a real MCP
 * JSON-RPC `initialize` handshake (`tools/probe_mcp.sh`). Results on 2026-09-30:
 *
 *   mcp.deepwiki.com/mcp    MCP_OK  http=200  server=DeepWiki   (no auth)
 *   mcp.exa.ai/mcp          MCP_OK  http=200  server=exa-search-server (no auth)
 *   mcp.context7.com/mcp    MCP_OK  http=200  server=Context7   (no auth for basics)
 *   mcp.figma.com/mcp       http=401 — exists, OAuth required
 *   mcp.linear.app/mcp      http=401 — exists, OAuth required
 *   mcp.supabase.com/mcp    http=401 — exists, OAuth required
 *   mcp.notion.com/mcp      http=401 — exists, OAuth required
 *
 * Entries whose host did not resolve during probing (Stripe, Vercel, Hugging Face,
 * Atlassian, GitMCP) are **not** listed: shipping an endpoint that could not be reached
 * would make the connection test fail on a URL that was never real.
 *
 * All entries are **disabled by default**. Enabling one is an explicit user action, so
 * installing the app never causes outbound traffic on its own. A server whose probe
 * returned `authRequired` is additionally marked so the UI can explain that it will need
 * credentials before it will work.
 */
object CuratedMcpServers {

    private fun entry(
        id: String,
        name: String,
        url: String,
        category: String,
        description: String,
        probe: McpProbeResult
    ) = McpServerEntry(
        id = id,
        name = name,
        url = url,
        // Disabled by default; the user opts in from Settings.
        enabled = false,
        category = category,
        description = description,
        requiresApiKey = probe == McpProbeResult.AUTH_REQUIRED
    )

    // ---- verified working without authentication ----

    val DEEPWIKI = entry(
        "deepwiki", "DeepWiki",
        "https://mcp.deepwiki.com/mcp", "developer",
        "Ask questions about any public GitHub repository and read its generated " +
            "documentation structure. Free, no login required.",
        McpProbeResult.VERIFIED_OK
    )

    val EXA_SEARCH = entry(
        "exa_search", "Exa Search",
        "https://mcp.exa.ai/mcp", "search",
        "Neural web and code search, page fetching, and content extraction. No API key " +
            "needed to start.",
        McpProbeResult.VERIFIED_OK
    )

    val CONTEXT7 = entry(
        "context7", "Context7",
        "https://mcp.context7.com/mcp", "developer",
        "Version-pinned library and framework documentation with real code examples, " +
            "so answers match the installed version.",
        McpProbeResult.VERIFIED_OK
    )

    // ---- endpoints confirmed to exist, but requiring credentials ----

    val FIGMA = entry(
        "figma", "Figma",
        "https://mcp.figma.com/mcp", "design",
        "Read Figma design files and pull design context into the conversation.",
        McpProbeResult.AUTH_REQUIRED
    )

    val LINEAR = entry(
        "linear", "Linear",
        "https://mcp.linear.app/mcp", "productivity",
        "Query and update Linear issues from the agent.",
        McpProbeResult.AUTH_REQUIRED
    )

    val SUPABASE = entry(
        "supabase", "Supabase",
        "https://mcp.supabase.com/mcp", "data",
        "Inspect projects, run SQL, and manage a Supabase backend.",
        McpProbeResult.AUTH_REQUIRED
    )

    val NOTION = entry(
        "notion", "Notion",
        "https://mcp.notion.com/mcp", "productivity",
        "Search and read Notion pages and databases.",
        McpProbeResult.AUTH_REQUIRED
    )

    val ALL: List<McpServerEntry> = listOf(
        DEEPWIKI, EXA_SEARCH, CONTEXT7, FIGMA, LINEAR, SUPABASE, NOTION
    )

    val IDS: Set<String> = ALL.map { it.id }.toSet()

    /** Servers probed as reachable with no credentials; safe to suggest enabling. */
    val NO_AUTH_IDS: Set<String> =
        ALL.filter { !it.requiresApiKey }.map { it.id }.toSet()
}

/** Outcome of probing a curated endpoint from the development host. */
enum class McpProbeResult {
    /** Completed a JSON-RPC initialize handshake and returned a result. */
    VERIFIED_OK,

    /** Endpoint responded, but requires credentials (HTTP 401). */
    AUTH_REQUIRED,

    /** Host did not resolve, or the path was not an MCP endpoint. Not shipped. */
    NOT_REACHABLE
}