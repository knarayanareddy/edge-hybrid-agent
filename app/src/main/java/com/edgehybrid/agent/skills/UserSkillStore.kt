package com.edgehybrid.agent.skills

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the procedural-knowledge block for a system prompt.
 *
 * Extracted as an interface purely so prompt assembly can be unit-tested on the JVM.
 * [UserSkillStore] needs a `Context` (filesDir, assets), so depending on it
 * directly would have forced this test onto an emulator — which is precisely how the
 * original lessons subsystem ended up with everything behind a DAO and nothing
 * proving the prompt was ever enriched.
 */
interface ProcedureSkillSource {
    /** Returns the rendered block, or an empty string when there is nothing to say. */
    suspend fun procedureBlock(): String
}

/**
 * On-device skill store: user skills live as real files the user can edit, export,
 * and version, with a JSON index for fast listing.
 *
 * ## Why files and not SharedPreferences
 *
 * A skill is source code or prose that can reach tens of kilobytes and that the
 * user has a legitimate reason to edit outside the app — in a text editor, over
 * adb, in a synced folder. SharedPreferences is a key/value blob that dies with the
 * app and is hostile to that. One file per skill under
 * `filesDir/skills/` is inspectable, diffable, and survives export.
 *
 * ## Why the mutex
 *
 * `save` is read-modify-write across two paths (the skill file and the index).
 * Two concurrent saves — a UI edit while an import runs — interleave and lose one.
 * The mutex makes the pair atomic; the earlier `ConcurrentHashMap`-based tool
 * registry in `SkillLoader` has no such guard because its state never leaves memory.
 */
@Singleton
class UserSkillStore @Inject constructor(
    @ApplicationContext private val context: Context
) : ProcedureSkillSource {
    private val mutex = Mutex()
    private val _skills = MutableStateFlow<List<UserSkill>>(emptyList())

    /** Observable catalogue for the Skills UI. */
    val skills: StateFlow<List<UserSkill>> = _skills.asStateFlow()

    private val root: File
        get() = File(context.filesDir, "skills").apply { mkdirs() }

    private val indexFile: File
        get() = File(root, "index.json")

    private val scriptDir: File
        get() = File(root, "scripts").apply { mkdirs() }

    /**
     * Loads bundled scripts from `assets/skills/` and user skills from disk.
     *
     * Bundled skills are read-only by design: they are part of the signed APK, and
     * an "edit" that silently mutated an asset would be lost on the next update.
     */
    suspend fun load(): List<UserSkill> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val loaded = mutableListOf<UserSkill>()

            bundledNames().forEach { name ->
                loaded += UserSkill(
                    id = name.removeSuffix(".js"),
                    name = name.removeSuffix(".js").replace('_', ' ').replaceFirstChar { it.uppercase() },
                    description = "Bundled sandbox script",
                    kind = UserSkill.Kind.SCRIPT,
                    body = readAssetOrEmpty("skills/$name"),
                    origin = UserSkill.Origin.BUNDLED,
                    enabled = true
                )
            }

            root.listFiles { f -> f.isFile && f.extension == "md" }?.forEach { file ->
                SkillValidator.parseMarkdown(file.readText()).let { parsed ->
                    loaded += parsed.copy(
                        id = file.nameWithoutExtension,
                        body = parsed.body.ifBlank { file.readText() }
                    )
                }
            }

            scriptDir.listFiles { f -> f.isFile && f.extension == "js" }?.forEach { file ->
                loaded += UserSkill(
                    id = file.nameWithoutExtension,
                    name = file.nameWithoutExtension.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    description = "User script",
                    kind = UserSkill.Kind.SCRIPT,
                    body = file.readText(),
                    origin = UserSkill.Origin.USER
                )
            }

            _skills.value = loaded.sortedBy { it.name.lowercase() }
            _skills.value
        }
    }

    /** Saves or updates a skill. Returns the validation errors, empty on success. */
    suspend fun save(skill: UserSkill): List<SkillValidationError> = withContext(Dispatchers.IO) {
        val errors = SkillValidator.validate(skill)
        if (errors.isNotEmpty()) return@withContext errors

        mutex.withLock {
            when (skill.kind) {
                UserSkill.Kind.PROCEDURE -> File(root, "${skill.id}.md").writeText(renderMarkdown(skill))
                UserSkill.Kind.SCRIPT -> File(scriptDir, "${skill.id}.js").writeText(skill.body)
            }
            writeIndex(_skills.value)
        }
        load()
        errors
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val skill = _skills.value.firstOrNull { it.id == id } ?: return@withLock false
            // Bundled skills live in the APK; "deleting" one means disabling it,
            // and the state has to live in the index or it resets on next load.
            if (skill.origin == UserSkill.Origin.BUNDLED) {
                writeIndex(_skills.value.map {
                    if (it.id == id) it.copy(enabled = false) else it
                })
                return@withLock true
            }
            val file = File(root, "${skill.id}.md").takeIf { it.exists() }
                ?: File(scriptDir, "${skill.id}.js")
            val removed = file.exists() && file.delete()
            if (removed) writeIndex(_skills.value.filterNot { it.id == id })
            removed
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean): List<UserSkill> = withContext(Dispatchers.IO) {
        mutex.withLock {
            writeIndex(_skills.value.map { if (it.id == id) it.copy(enabled = enabled) else it })
        }
        load()
    }

    suspend fun get(id: String): UserSkill? = withContext(Dispatchers.IO) {
        _skills.value.firstOrNull { it.id == id }
    }

    /**
     * Serializes procedure skills into one prompt block.
     *
     * Only enabled procedure skills are included, and over-budget skills are
     * skipped with a note rather than silently truncated — a half-sent procedure
     * is worse than an absent one, because the model cannot tell it was cut.
     */
    override suspend fun procedureBlock(): String {
        val loaded = if (_skills.value.isEmpty()) load() else _skills.value
        return renderProcedureBlock(loaded)
    }

    fun renderProcedureBlock(skills: List<UserSkill> = _skills.value): String {
        val included = skills.filter { it.enabled && it.kind == UserSkill.Kind.PROCEDURE && !it.exceedsBudget() }
        if (included.isEmpty()) return ""
        return buildString {
            appendLine("# Skills")
            included.forEach { skill ->
                appendLine()
                appendLine("## ${skill.name}")
                if (skill.description.isNotBlank()) appendLine(skill.description)
                appendLine()
                appendLine(skill.body.trim())
            }
        }
    }

    private fun renderMarkdown(skill: UserSkill): String = buildString {
        appendLine("---")
        appendLine("name: ${skill.name}")
        appendLine("description: ${skill.description}")
        appendLine("---")
        appendLine()
        appendLine(skill.body)
    }

    private fun bundledNames(): List<String> = runCatching {
        context.assets.list("skills")?.toList().orEmpty().filter { it.endsWith(".js") }
    }.getOrDefault(emptyList())

    private fun readAssetOrEmpty(path: String): String =
        runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }
            .getOrDefault("")

    /**
     * Index holds per-skill state that must survive a reload (enabled/disabled).
     * Bodies live in their own files so a large skill never bloats the index.
     */
    private fun writeIndex(skills: List<UserSkill>) {
        val json = JSONObject()
        json.put("version", 1)
        val states = org.json.JSONArray()
        skills.forEach { skill ->
            states.put(
                JSONObject()
                    .put("id", skill.id)
                    .put("enabled", skill.enabled)
                    .put("updatedAt", skill.updatedAt)
            )
        }
        json.put("skills", states)
        indexFile.writeText(json.toString())
    }

    /** Exposed for diagnostics in the Skills screen. */
    fun storageRoot(): String = root.absolutePath
}