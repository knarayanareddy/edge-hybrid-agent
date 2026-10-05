package com.edgehybrid.agent.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A durable fact the agent learned about the user, their projects, or their
 * preferences.
 *
 * Why this is separate from [com.edgehybrid.agent.data.local.LessonEntity]:
 *
 *  - A **lesson** is a rule about *how to behave* ("always ask before sending"),
 *    learned from a failure the agent caused. It is corrective and adversarial.
 *  - A **memory** is a fact about *the world or the user* ("deploys to Fly.io,
 *    not Vercel"), learned from the user telling us. It is additive.
 *
 * Mixing them produces two failure modes seen in practice: corrections crowding
 * out facts until the prompt is all rules and no knowledge, and a rule learned
 * once shadowing a fact stated fifty times. Keeping two stores lets each be
 * retrieved, capped, and forgotten on its own terms.
 */
@Entity(tableName = "user_memories")
data class UserMemoryEntity(
    @PrimaryKey
    val id: String,

    /** The fact itself. Written to be self-contained — it is read without its source. */
    val content: String,

    /**
     * Coarse bucket used for scoping and for the "what do you remember" view.
     * One of: PROFILE, PROJECT, PREFERENCE, GLOSSARY, DECISION.
     */
    val category: String = "PROFILE",

    /**
     * Optional free text used for retrieval only — a keyword, project name, or
     * short phrase. Never injected into the prompt directly; a raw field that
     * reaches the prompt is a prompt-injection surface.
     */
    val keywords: String = "",

    /**
     * How many times the user has affirmed this. Drives ranking and lets a
     * repeated correction outrank a one-off guess.
     */
    val frequency: Int = 1,

    /**
     * False once superseded or withdrawn. Rows are kept rather than deleted so
     * "you told me X then Y" stays answerable, and so a delete is auditable.
     */
    val isActive: Boolean = true,

    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis()
)

/**
 * A chunk of prior conversation the agent chose to keep.
 *
 * Stored rather than re-derived because the alternative — replaying full history
 * every turn — grows the prompt without bound and re-pays for tokens the model
 * has already seen. Retrieval is by recency plus keyword overlap, so the prompt
 * carries the relevant past rather than all of it.
 */
@Entity(tableName = "conversation_memories")
data class ConversationMemoryEntity(
    @PrimaryKey
    val id: String,

    /** Rolling digest of the exchange. Small on purpose: this is what gets injected. */
    val summary: String,

    /** Extra retrieval surface: topic words, file names, entity names. */
    val keywords: String = "",

    /** Where it came from, so a user question can be traced back to a conversation. */
    val sessionId: String = "",
    val role: String = "exchange",
    val createdAt: Long = System.currentTimeMillis()
)