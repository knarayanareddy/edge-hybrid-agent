package com.edgehybrid.agent.scheduled

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduledTaskStore(
    private val baseDir: File?
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.filesDir)

    constructor() : this(null as File?)

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _tasks = MutableStateFlow<List<ScheduledTask>>(emptyList())
    val tasks: StateFlow<List<ScheduledTask>> = _tasks.asStateFlow()

    private val storageFile: File?
        get() = baseDir?.let { File(it, "scheduled_tasks.json") }

    init {
        loadTasks()
    }

    private fun loadTasks() {
        val file = storageFile
        if (file != null && file.exists()) {
            try {
                val content = file.readText()
                val loaded = json.decodeFromString<List<ScheduledTask>>(content)
                val presetIds = loaded.map { it.id }.toSet()
                // Merge loaded with any un-enabled presets so user still sees the full catalogue
                val merged = loaded.toMutableList()
                ScheduledTaskPresets.allPresets.forEach { preset ->
                    if (preset.id !in presetIds) {
                        merged.add(preset)
                    }
                }
                _tasks.value = merged
                return
            } catch (_: Exception) {
                // Fall back to default presets on deserialization failure
            }
        }
        _tasks.value = ScheduledTaskPresets.allPresets
    }

    suspend fun saveTask(task: ScheduledTask) = mutex.withLock {
        val current = _tasks.value.toMutableList()
        val index = current.indexOfFirst { it.id == task.id }
        if (index >= 0) {
            current[index] = task
        } else {
            current.add(0, task)
        }
        _tasks.value = current
        persist()
    }

    suspend fun toggleTask(taskId: String, enabled: Boolean) = mutex.withLock {
        val current = _tasks.value.map {
            if (it.id == taskId) it.copy(isEnabled = enabled) else it
        }
        _tasks.value = current
        persist()
    }

    suspend fun deleteTask(taskId: String) = mutex.withLock {
        val current = _tasks.value.toMutableList()
        val index = current.indexOfFirst { it.id == taskId }
        if (index >= 0) {
            val item = current[index]
            if (item.isPreset) {
                // If it's a preset, disable it rather than deleting from the catalogue
                current[index] = item.copy(isEnabled = false, lastRunTime = null, lastResultSnippet = null)
            } else {
                current.removeAt(index)
            }
        }
        _tasks.value = current
        persist()
    }

    suspend fun recordRun(taskId: String, outcomeSnippet: String) = mutex.withLock {
        val current = _tasks.value.map {
            if (it.id == taskId) {
                it.copy(
                    lastRunTime = System.currentTimeMillis(),
                    lastResultSnippet = outcomeSnippet
                )
            } else it
        }
        _tasks.value = current
        persist()
    }

    fun getActiveTasks(): List<ScheduledTask> =
        _tasks.value.filter { it.isEnabled }

    private fun persist() {
        val file = storageFile ?: return
        try {
            // Save only customized or enabled items to save space
            val toSave = _tasks.value.filter { it.isEnabled || !it.isPreset || it.lastRunTime != null }
            file.writeText(json.encodeToString(toSave))
        } catch (_: Exception) {
            // Silent error on write
        }
    }
}
