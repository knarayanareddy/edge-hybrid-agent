package com.edgehybrid.agent.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Standard ChatGPT Plugin Manifest specification (`ai-plugin.json`).
 *
 * Compatible with the official OpenAI Plugin / GPT Action manifest schema.
 */
@Serializable
data class PluginManifest(
    @SerialName("schema_version")
    val schemaVersion: String = "v1",

    @SerialName("name_for_model")
    val nameForModel: String,

    @SerialName("name_for_human")
    val nameForHuman: String,

    @SerialName("description_for_model")
    val descriptionForModel: String,

    @SerialName("description_for_human")
    val descriptionForHuman: String,

    val auth: PluginAuth = PluginAuth(),

    val api: PluginApi,

    @SerialName("logo_url")
    val logoUrl: String? = null,

    @SerialName("contact_email")
    val contactEmail: String? = null,

    @SerialName("legal_info_url")
    val legalInfoUrl: String? = null
)

@Serializable
data class PluginAuth(
    val type: String = "none",
    @SerialName("authorization_type")
    val authorizationType: String? = null,
    @SerialName("verification_tokens")
    val verificationTokens: Map<String, String>? = null
)

@Serializable
data class PluginApi(
    val type: String = "openapi",
    val url: String,
    @SerialName("is_user_authenticated")
    val isUserAuthenticated: Boolean = false
)
