package com.edgehybrid.agent.plugins

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trust & Permission Governance for Plugins and Remote MCP Servers.
 *
 * Implements the ChatGPT permission model:
 * - [ALWAYS_ALLOW]: User previously authorized this domain/server. Auto-approved.
 * - [ASK_EVERY_TIME]: Default fail-closed state. Presents interactive confirmation dialog.
 * - [DENY]: Action execution blocked.
 */
@Singleton
class PluginPermissionStore(
    private val context: Context? = null
) {
    enum class TrustLevel {
        ALWAYS_ALLOW,
        ASK_EVERY_TIME,
        DENY
    }

    private val inMemoryGrants = ConcurrentHashMap<String, TrustLevel>()
    private val prefs by lazy {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getTrustLevel(targetId: String): TrustLevel {
        inMemoryGrants[targetId]?.let { return it }
        val persisted = prefs?.getString(targetId, null)
        if (persisted != null) {
            val level = runCatching { TrustLevel.valueOf(persisted) }.getOrDefault(TrustLevel.ASK_EVERY_TIME)
            inMemoryGrants[targetId] = level
            return level
        }
        return TrustLevel.ASK_EVERY_TIME
    }

    fun setTrustLevel(targetId: String, level: TrustLevel) {
        inMemoryGrants[targetId] = level
        prefs?.edit()?.putString(targetId, level.name)?.apply()
    }

    fun isAutoApproved(targetId: String): Boolean =
        getTrustLevel(targetId) == TrustLevel.ALWAYS_ALLOW

    fun revoke(targetId: String) {
        inMemoryGrants.remove(targetId)
        prefs?.edit()?.remove(targetId)?.apply()
    }

    fun listAllGrants(): Map<String, TrustLevel> {
        val map = mutableMapOf<String, TrustLevel>()
        prefs?.all?.forEach { (k, v) ->
            if (v is String) {
                runCatching { map[k] = TrustLevel.valueOf(v) }
            }
        }
        map.putAll(inMemoryGrants)
        return map
    }

    private companion object {
        const val PREFS_NAME = "edge_plugin_permissions"
    }
}
