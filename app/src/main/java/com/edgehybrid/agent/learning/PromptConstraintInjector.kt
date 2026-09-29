package com.edgehybrid.agent.learning

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Injects learned constraints and anti-patterns dynamically into system prompts.
 */
@Singleton
class PromptConstraintInjector @Inject constructor(
    private val lessonsLedgerManager: LessonsLedgerManager
) {

    suspend fun injectConstraints(baseSystemPrompt: String, limit: Int = 8): String {
        val activeLessons = lessonsLedgerManager.getActiveLessons(limit)
        if (activeLessons.isEmpty()) {
            return baseSystemPrompt
        }

        val constraintsBlock = buildString {
            append("\n\n### MANDATORY CONSTRAINTS FROM PAST LESSONS (DO NOT VIOLATE):\n")
            activeLessons.forEachIndexed { index, lesson ->
                append("${index + 1}. [${lesson.category}] ${lesson.rule}\n")
            }
            append("Ensure full adherence to the above rules.")
        }

        return baseSystemPrompt + constraintsBlock
    }
}
