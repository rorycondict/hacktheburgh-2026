package com.example.backend.world

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The agent-designed floorplan (rooms the agent decided are relevant, their kinds, files, labels and
 * links), edited through the Floorplan MCP tools and persisted in `<project>/.overworld/floorplan.json`.
 * null = no plan, the automatic layout is shown.
 */
@Service(Service.Level.PROJECT)
class FloorplanPlanService(private val project: Project) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val lock = Any()
    @Volatile private var loaded = false
    @Volatile private var current: FloorplanPlan? = null

    private val file: File? get() = project.basePath?.let { File(File(it, ".overworld"), "floorplan.json") }

    val plan: FloorplanPlan?
        get() {
            if (!loaded) synchronized(lock) { if (!loaded) { current = load(); loaded = true } }
            return current
        }

    /** Applies [change] to the current plan (empty if none), saves it and re-lays out the map. */
    fun update(author: String, change: (List<RoomSpec>) -> List<RoomSpec>): FloorplanPlan {
        val next = synchronized(lock) {
            val rooms = change(plan?.rooms.orEmpty())
            FloorplanPlan(rooms, author, System.currentTimeMillis()).also { current = it; save(it) }
        }
        project.service<WorldService>().planChanged()
        return next
    }

    /** Drops the agent's plan: the map goes back to the automatic layout. */
    fun clear() {
        synchronized(lock) {
            current = null
            loaded = true
            file?.delete()
        }
        project.service<WorldService>().planChanged()
    }

    private fun load(): FloorplanPlan? = try {
        file?.takeIf { it.isFile }?.let { json.decodeFromString(FloorplanPlan.serializer(), it.readText()) }
    } catch (e: Exception) {
        LOG.warn("Ignoring unreadable floorplan.json: ${e.message}")
        null
    }

    private fun save(p: FloorplanPlan) {
        try {
            val f = file ?: return
            f.parentFile.mkdirs()
            f.writeText(json.encodeToString(FloorplanPlan.serializer(), p))
        } catch (e: Exception) {
            LOG.warn("Could not write floorplan.json", e)
        }
    }

    companion object {
        private val LOG = logger<FloorplanPlanService>()
        fun getInstance(project: Project): FloorplanPlanService = project.service()
    }
}
