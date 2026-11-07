package com.example.backend.mcp

import com.example.backend.world.FileInfo
import com.example.backend.world.FloorplanPlanService
import com.example.backend.world.PathNorm
import com.example.backend.world.RoomSpec
import com.example.backend.world.RoomThemes
import com.example.backend.world.WorldLayout
import com.example.backend.world.WorldService
import com.example.model.RoomKind
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.clientInfo
import com.intellij.mcpserver.project
import kotlinx.coroutines.currentCoroutineContext

/**
 * MCP tools (served by the IDE's built-in MCP server) that let the agent design the Floorplan map:
 * it decides which parts of the codebase are relevant, groups them into themed rooms, labels the
 * furniture and says which rooms are related. Every change re-draws the map live.
 */
class FloorplanToolset : McpToolset {

    @McpTool(name = "floorplan_get_codebase")
    @McpDescription(
        """
        Start here to design the Floorplan map: the IDE plugin that draws this project as a cozy pixel-art house in
        which you (the agent) appear as a character walking to the files you work on. Returns the room kinds you can
        use, the current floorplan, and the project's files (path, lines, language, recent commits) with the files
        each one refers to. Then call floorplan_design_room once per room. Pick only the rooms and files that matter
        for understanding the codebase (not one room per file or per directory), group files by the aspect of the
        code they represent, and link related rooms with connectedTo so the house puts them next door.
        """,
    )
    suspend fun floorplan_get_codebase(
        @McpDescription("Maximum number of files to list, most important first (default 250).")
        maxFiles: Int = 250,
    ): String {
        val project = currentCoroutineContext().project
        val idx = WorldService.getInstance(project).index()
        val plan = FloorplanPlanService.getInstance(project).plan
        val byPath = idx.files.associateBy { it.path }
        return buildString {
            appendLine("# Room kinds (use the KIND name; rooms of one kind share a look and furniture set)")
            appendLine("Each kind lives on its home floor: ground floor = entrance hall, living room, dining room, kitchen, bathroom,")
            appendLine("workshop, greenhouse; upstairs = bedroom, study, library, art studio; basement = lab, utility room, storage.")
            for (k in RoomKind.entries) {
                if (k == RoomKind.HALLWAY) continue
                val floor = when (k.level) { 1 -> "upstairs"; -1 -> "basement"; else -> "ground floor" }
                append("- ").append(k.name).append(" (").append(k.label).append(", ").append(floor).append("): ").append(k.meaning)
                append(". Furniture: ").appendLine(RoomThemes.furniture(k).joinToString { RoomThemes.displayName(it).lowercase() })
            }
            appendLine("LOBBY is the entrance hall and always exists; rooms of kind LOBBY add their files to it.")
            appendLine("At most ${WorldLayout.MAX_FURNITURE} files are shown per room (fewer in the lobby). The largest files get the room's")
            appendLine("signature pieces (bed, stove, fireplace...), placed sensibly (beds in corners, tables in the middle, shelves on walls).")
            appendLine("Rooms open onto a hallway; kitchen/dining room/living room and rooms you link with connectedTo also share doors.")
            appendLine("Labels are short themed captions shown on the furniture, e.g. \"Stove: cooks up sessions (SessionFactory)\".")
            appendLine()
            appendLine("# Current floorplan")
            if (plan == null || plan.rooms.isEmpty()) appendLine("(none yet: the map shows an automatic layout guessed from file names)")
            else for (r in plan.rooms) {
                append("- ").append(r.name).append(" [").append(r.kind.name).append("] ").append(r.files.size).append(" files")
                if (r.connectedTo.isNotEmpty()) append(", connected to ").append(r.connectedTo.joinToString())
                appendLine()
            }
            appendLine()
            val sorted = idx.files.sortedWith(compareByDescending<FileInfo> { it.loc + it.churn * 20 }.thenBy { it.path })
            val shown = sorted.take(maxFiles.coerceIn(1, 2000)).sortedBy { it.path }
            appendLine("# Files (${shown.size} of ${idx.files.size}; path | lines | language | commits in 30 days | refers to)")
            for (f in shown) {
                append(f.path).append(" | ").append(f.loc).append(" | ").append(f.language.ifBlank { "?" }).append(" | ").append(f.churn)
                val refs = idx.deps[f.path].orEmpty().filter { it in byPath }.sorted()
                if (refs.isNotEmpty()) append(" | ").append(refs.take(8).joinToString { it.substringAfterLast('/') })
                appendLine()
            }
        }
    }

    @McpTool(name = "floorplan_design_room")
    @McpDescription(
        """
        Create or replace one room of the Floorplan map (matched by name). The map updates immediately. The first
        call on an automatic layout starts a fresh floorplan that only contains the rooms you design.
        """,
    )
    suspend fun floorplan_design_room(
        @McpDescription("Room name shown on its plaque, e.g. \"Session kitchen\". Unique; reusing a name replaces that room.")
        name: String,
        @McpDescription("Room kind: LOBBY, LIVING_ROOM, DINING_ROOM, KITCHEN, BATHROOM, WORKSHOP, GREENHOUSE, BEDROOM, STUDY, LIBRARY, GALLERY, LAB, UTILITY_ROOM or STORAGE.")
        kind: String,
        @McpDescription("Project-relative file paths in this room, most relevant first; keep related files next to each other.")
        files: List<String>,
        @McpDescription("Optional themed furniture captions, one per file in the same order (\"\" = default).")
        labels: List<String> = emptyList(),
        @McpDescription("One short sentence: what this part of the code does.")
        summary: String = "",
        @McpDescription("Names of rooms this one is closely related to (calls, depends on, configures). They are placed next door with a connecting door.")
        connectedTo: List<String> = emptyList(),
    ): String {
        val project = currentCoroutineContext().project
        val roomKind = RoomKind.entries.filter { it != RoomKind.HALLWAY }.firstOrNull { it.name.equals(kind.trim().replace(' ', '_'), ignoreCase = true) || it.label.equals(kind.trim(), ignoreCase = true) }
            ?: throw McpExpectedError("Unknown room kind '$kind'. Use one of: ${RoomKind.entries.filter { it != RoomKind.HALLWAY }.joinToString { it.name }}")
        if (name.isBlank()) throw McpExpectedError("Room name must not be empty.")
        val idx = WorldService.getInstance(project).index()
        val known = idx.files.mapTo(HashSet()) { it.path }
        val norm = files.map(PathNorm::of).distinct()
        val (ok, unknown) = norm.partition { it in known }
        val labelMap = norm.zip(labels).filter { it.second.isNotBlank() && it.first in known }.toMap()
        val spec = RoomSpec(name.trim(), roomKind, ok, summary.trim().take(200), labelMap, connectedTo.map { it.trim() }.filter { it.isNotEmpty() })
        val author = agentName()
        val plan = FloorplanPlanService.getInstance(project).update(author) { rooms ->
            // Replace by name; a file belongs to one room only, so this room takes it from the others.
            val taken = ok.toSet()
            val others = rooms.filter { it.id != spec.id }.map { r -> r.copy(files = r.files.filter { it !in taken }) }
            val i = rooms.indexOfFirst { it.id == spec.id }
            if (i < 0) others + spec else others.toMutableList().apply { add(i.coerceAtMost(size), spec) }
        }
        return buildString {
            append("Room '").append(spec.name).append("' (").append(roomKind.label).append(") now has ").append(ok.size).append(" file(s)")
            if (ok.size > WorldLayout.MAX_FURNITURE) append("; only the first ${WorldLayout.MAX_FURNITURE} are drawn as furniture")
            append(". The floorplan has ").append(plan.rooms.size).append(" room(s): ").append(plan.rooms.joinToString { it.name }).append('.')
            if (unknown.isNotEmpty()) append("\nIgnored unknown paths (not in the project index): ").append(unknown.take(20).joinToString())
            val names = plan.rooms.map { it.name.lowercase() }.toSet()
            val dangling = spec.connectedTo.filter { it.lowercase() !in names }
            if (dangling.isNotEmpty()) append("\nconnectedTo rooms that don't exist yet (fine if you create them next): ").append(dangling.joinToString())
        }
    }

    @McpTool(name = "floorplan_remove_room")
    @McpDescription("Remove a room from the Floorplan map by name.")
    suspend fun floorplan_remove_room(
        @McpDescription("Name of the room to remove.")
        name: String,
    ): String {
        val project = currentCoroutineContext().project
        val id = RoomSpec(name, RoomKind.WORKSHOP, emptyList()).id
        var removed = false
        FloorplanPlanService.getInstance(project).update(agentName()) { rooms ->
            rooms.filter { r -> (r.id != id && !r.name.equals(name.trim(), ignoreCase = true)).also { if (!it) removed = true } }
        }
        return if (removed) "Removed room '$name'." else "No room named '$name'."
    }

    @McpTool(name = "floorplan_reset")
    @McpDescription("Discard the designed floorplan and go back to the automatic layout guessed from file names.")
    suspend fun floorplan_reset(): String {
        FloorplanPlanService.getInstance(currentCoroutineContext().project).clear()
        return "Floorplan reset to the automatic layout."
    }

    private suspend fun agentName(): String = try {
        currentCoroutineContext().clientInfo.name
    } catch (_: Throwable) {
        "agent"
    }
}
