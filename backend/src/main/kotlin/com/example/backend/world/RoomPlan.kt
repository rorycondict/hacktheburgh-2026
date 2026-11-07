package com.example.backend.world

import com.example.model.FurnitureKind
import com.example.model.FurnitureKind.*
import com.example.model.RoomKind
import com.example.model.Tiles
import kotlinx.serialization.Serializable

/**
 * One room as designed by the agent (through the Floorplan MCP tools) or guessed by [HeuristicPlanner].
 * [files] are project-relative paths, in the order they should be placed (related files together);
 * [labels] maps a path to its themed furniture caption; [connectedTo] names rooms this one is closely
 * related to (calls, depends on, configures), which the layout puts next door.
 */
@Serializable
data class RoomSpec(
    val name: String,
    val kind: RoomKind,
    val files: List<String>,
    val summary: String = "",
    val labels: Map<String, String> = emptyMap(),
    val connectedTo: List<String> = emptyList(),
) {
    /** Stable id derived from the name ("/" for the lobby). */
    val id: String get() = if (kind == RoomKind.LOBBY) WorldLayout.LOBBY_ID else "room:" + slug(name)

    companion object {
        fun slug(name: String): String = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "room" }
    }
}

/** The agent's floorplan, persisted in `<project>/.overworld/floorplan.json`. */
@Serializable
data class FloorplanPlan(val rooms: List<RoomSpec> = emptyList(), val author: String = "", val updatedAtMillis: Long = 0)

/** Where a piece of furniture likes to stand in a room. */
enum class Placement {
    /** In a corner, against two walls (beds, fridges, bathtubs). Placed first. */
    CORNER,
    /** Next to the bed. */
    NEAR_BED,
    /** Free-standing, away from the walls (dining tables, sofas, easels). */
    CENTER,
    /** Against a wall, north wall first (counters, shelves, desks). */
    WALL,
    /** Hangs on the north wall's face (paintings, clocks, mirrors). Not blocking. */
    WALL_HUNG,
    /** Lies on the floor and can be walked over (rugs). Not blocking. */
    FLOOR,
}

/** Footprint and placement of a furniture kind. */
data class Piece(val w: Int, val h: Int, val placement: Placement) {
    val blocking: Boolean get() = placement != Placement.WALL_HUNG && placement != Placement.FLOOR
}

/** Per-kind look: floor, furniture set and decorations, plus where each furniture kind goes. */
object RoomThemes {
    fun floor(kind: RoomKind): Char = when (kind) {
        RoomKind.LIVING_ROOM, RoomKind.LIBRARY, RoomKind.DEPENDENCIES, RoomKind.BEDROOM -> Tiles.FLOOR_CARPET
        RoomKind.KITCHEN, RoomKind.LAB, RoomKind.UTILITY_ROOM, RoomKind.GREENHOUSE, RoomKind.BATHROOM -> Tiles.FLOOR_TILE
        RoomKind.LOBBY, RoomKind.HALLWAY, RoomKind.WORKSHOP, RoomKind.STUDY, RoomKind.GALLERY, RoomKind.STORAGE,
        RoomKind.DINING_ROOM -> Tiles.FLOOR_WOOD
    }

    /** Signature pieces, at most one of each per room, given to the most important files first. */
    fun unique(kind: RoomKind): List<FurnitureKind> = when (kind) {
        RoomKind.LOBBY, RoomKind.HALLWAY -> emptyList()
        RoomKind.LIVING_ROOM -> listOf(FIREPLACE, SOFA, TV, PIANO, ARMCHAIR)
        RoomKind.DINING_ROOM -> listOf(DINING_TABLE, SIDEBOARD, CHINA_CABINET)
        RoomKind.KITCHEN -> listOf(STOVE, FRIDGE, SINK)
        RoomKind.BATHROOM -> listOf(BATHTUB, SINK, TOILET, MIRROR)
        RoomKind.WORKSHOP -> listOf(WORKBENCH, ANVIL, TOOL_RACK)
        RoomKind.GREENHOUSE -> listOf(POTTED_TREE)
        RoomKind.BEDROOM -> listOf(BED, WARDROBE, NIGHTSTAND, DRESSER)
        RoomKind.STUDY -> listOf(WRITING_DESK, GLOBE)
        RoomKind.LIBRARY -> listOf(LECTERN, READING_CHAIR)
        RoomKind.DEPENDENCIES -> emptyList()
        RoomKind.GALLERY -> listOf(EASEL, STATUE)
        RoomKind.LAB -> listOf(CHALKBOARD, MICROSCOPE)
        RoomKind.UTILITY_ROOM -> listOf(BOILER, WASHER, PIPES)
        RoomKind.STORAGE -> emptyList()
    }

    /** Pieces that may repeat, cycled once the signature pieces are used up. */
    fun repeatable(kind: RoomKind): List<FurnitureKind> = when (kind) {
        RoomKind.LOBBY, RoomKind.HALLWAY -> listOf(CABINET, BOOKSHELF, FILING_CABINET)
        RoomKind.LIVING_ROOM -> listOf(BOOKSHELF, ARMCHAIR)
        RoomKind.DINING_ROOM -> listOf(CHAIR, SIDEBOARD)
        RoomKind.KITCHEN -> listOf(COUNTER, SHELF)
        RoomKind.BATHROOM -> listOf(CABINET)
        RoomKind.WORKSHOP -> listOf(DESK, WORKBENCH, CRATE)
        RoomKind.GREENHOUSE -> listOf(FLOWER_BED, PLANT)
        RoomKind.BEDROOM -> listOf(DRESSER, WARDROBE)
        RoomKind.STUDY -> listOf(FILING_CABINET, BOOKSHELF)
        RoomKind.LIBRARY -> listOf(BOOKSHELF)
        RoomKind.DEPENDENCIES -> listOf(BOOKCASE)
        RoomKind.GALLERY -> listOf(PAINTING, EASEL)
        RoomKind.LAB -> listOf(TEST_BENCH)
        RoomKind.UTILITY_ROOM -> listOf(SHELF, CRATE)
        RoomKind.STORAGE -> listOf(SHELF, CRATE, BARREL, CABINET)
    }

    /** Every furniture kind a room of [kind] can contain (for the agent's reference). */
    fun furniture(kind: RoomKind): List<FurnitureKind> = (unique(kind) + repeatable(kind)).distinct()

    fun decor(kind: RoomKind): List<FurnitureKind> = when (kind) {
        RoomKind.LOBBY -> listOf(RUG, COAT_RACK, PLANT, CLOCK)
        RoomKind.HALLWAY -> listOf(RUG, PLANT, PAINTING)
        RoomKind.LIVING_ROOM -> listOf(RUG, LAMP, PAINTING, PLANT)
        RoomKind.DINING_ROOM -> listOf(PAINTING, PLANT)
        RoomKind.KITCHEN -> listOf(CLOCK, PLANT)
        RoomKind.BATHROOM -> listOf(PLANT)
        RoomKind.WORKSHOP -> listOf(CRATE)
        RoomKind.GREENHOUSE -> listOf(PLANT)
        RoomKind.BEDROOM -> listOf(RUG, LAMP, PAINTING)
        RoomKind.STUDY -> listOf(RUG, LAMP, CLOCK)
        RoomKind.LIBRARY -> listOf(RUG, LAMP)
        RoomKind.DEPENDENCIES -> listOf(RUG, LECTERN, READING_CHAIR, LAMP, CLOCK)
        RoomKind.GALLERY -> listOf(PLANT)
        RoomKind.LAB -> listOf(PLANT)
        RoomKind.UTILITY_ROOM -> emptyList()
        RoomKind.STORAGE -> emptyList()
    }

    fun piece(kind: FurnitureKind): Piece = when (kind) {
        BED -> Piece(2, 2, Placement.CORNER)
        BATHTUB -> Piece(2, 1, Placement.CORNER)
        FRIDGE, BOILER, POTTED_TREE, STATUE, PLANT, LAMP, TOILET, WARDROBE, COAT_RACK -> Piece(1, 1, Placement.CORNER)
        DINING_TABLE -> Piece(2, 2, Placement.CENTER)
        SOFA -> Piece(2, 1, Placement.CENTER)
        ARMCHAIR, EASEL, GLOBE, ANVIL, LECTERN, READING_CHAIR, CHAIR -> Piece(1, 1, Placement.CENTER)
        NIGHTSTAND -> Piece(1, 1, Placement.NEAR_BED)
        COUNTER, WORKBENCH, BOOKCASE, FIREPLACE, PIANO, TEST_BENCH, SIDEBOARD, FLOWER_BED -> Piece(2, 1, Placement.WALL)
        CHALKBOARD -> Piece(2, 1, Placement.WALL_HUNG)
        PAINTING, CLOCK, MIRROR, TOOL_RACK, PIPES, TV -> Piece(1, 1, Placement.WALL_HUNG)
        RUG -> Piece(3, 2, Placement.FLOOR)
        else -> Piece(1, 1, Placement.WALL)
    }

    /** "TOOL_RACK" -> "Tool rack". */
    fun displayName(kind: FurnitureKind): String = kind.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** Default caption when the agent gave none: "Stove: SessionFactory". */
    fun defaultLabel(kind: FurnitureKind, path: String): String =
        displayName(kind) + ": " + path.substringAfterLast('/').let { n -> if (n.startsWith('.')) n else n.substringBefore('.') }
}

/**
 * Fallback before (or without) an agent-designed plan: sorts files into rooms by the role their name
 * and path suggest, so the house is themed by aspect of the codebase rather than by directory.
 */
object HeuristicPlanner {
    private val assetExt = setOf("png", "jpg", "jpeg", "gif", "webp", "ico", "svg", "pxl", "ttf", "otf", "woff", "woff2", "wav", "mp3", "ogg")
    private val docExt = setOf("md", "txt", "adoc", "rst", "mdx")
    private val uiExt = setOf("css", "scss", "html", "vue", "svelte", "tsx", "jsx")

    fun plan(files: List<FileInfo>): List<RoomSpec> {
        val byKind = files.sortedBy { it.path }.groupBy { classify(it.path) }
        val rooms = ArrayList<RoomSpec>()
        rooms += RoomSpec(RoomKind.LOBBY.label, RoomKind.LOBBY, byKind[RoomKind.LOBBY].orEmpty().map { it.path })
        for (kind in RoomKind.entries) {
            if (kind == RoomKind.LOBBY || kind == RoomKind.HALLWAY) continue
            val group = byKind[kind] ?: continue
            if (group.size <= WorldLayout.MAX_FURNITURE) {
                rooms += RoomSpec(kind.label, kind, group.map { it.path })
                continue
            }
            // Big groups split by top-level directory: "Workshop · backend", "Workshop · frontend".
            val byTop = group.groupBy { it.path.substringBefore('/') }.toSortedMap()
            if (byTop.size == 1) rooms += RoomSpec(kind.label, kind, group.map { it.path })
            else for ((top, sub) in byTop) rooms += RoomSpec("${kind.label} · $top", kind, sub.map { it.path })
        }
        return rooms
    }

    fun classify(path: String): RoomKind {
        if (!path.contains('/')) return RoomKind.LOBBY
        val ext = FileKinds.extension(path)
        val stem = path.substringAfterLast('/').substringBefore('.')
        val lower = stem.lowercase()
        val segs = path.lowercase().split('/').dropLast(1).toSet()
        fun segAny(vararg s: String) = s.any { it in segs }
        fun stemEnds(vararg s: String) = s.any { stem.endsWith(it) }
        return when {
            FileKinds.isTest(path) -> RoomKind.LAB
            ext in docExt -> RoomKind.LIBRARY
            ext in assetExt || segAny("assets", "sprites", "icons", "images", "img", "fonts") -> RoomKind.GREENHOUSE
            FileKinds.kind(path) == CABINET -> RoomKind.STORAGE
            stemEnds("Factory", "Builder", "Provider", "Creator", "Injector", "Module") && !stemEnds("ApiProvider") -> RoomKind.KITCHEN
            lower in setOf("main", "app", "application", "index", "__main__", "cli", "program", "startup") ||
                stemEnds("Application", "Main", "StartupActivity", "Activity", "Launcher", "Bootstrap", "Cli", "Command") -> RoomKind.LIVING_ROOM
            ext in uiExt || segAny("ui", "view", "views", "render", "components", "widgets", "screens", "toolwindow", "hud") ||
                stemEnds("View", "Panel", "Component", "Renderer", "Widget", "Screen", "Dialog", "Window", "Canvas", "Card", "Icons") -> RoomKind.GALLERY
            stemEnds("Api", "RpcApi", "ApiImpl", "ApiProvider", "Controller", "Endpoint", "Endpoints", "Resource", "Handler", "Routes") ||
                segAny("api", "controllers", "endpoints", "routes") -> RoomKind.DINING_ROOM
            stemEnds("State", "Store", "Cache", "Session", "Sessions", "Repository", "Dao", "Persistence", "Storage", "Reducer") ||
                segAny("state", "store", "session", "cache", "persistence", "db", "storage") -> RoomKind.BEDROOM
            stemEnds("Util", "Utils", "Helper", "Helpers", "Format", "Formatter", "Logger", "Log", "Extensions", "Ext") ||
                lower in setOf("paths", "format", "strings", "logging") || segAny("util", "utils", "helpers", "common") -> RoomKind.BATHROOM
            segAny("model", "models", "dto", "dtos", "entity", "entities", "schema", "types", "domain") ||
                stemEnds("Dto", "Dtos", "Model", "Entity", "Schema", "Types", "Event") || lower == "dtos" -> RoomKind.STUDY
            segAny("rpc", "net", "network", "http", "io", "tap", "transport", "client", "server", "wrap") ||
                stemEnds("Server", "Client", "Rpc", "Socket", "Serializer", "serializers", "Wrapper", "Json", "Adapter", "Protocol", "Fanout") -> RoomKind.UTILITY_ROOM
            FileKinds.kind(path) == DESK -> RoomKind.WORKSHOP
            else -> RoomKind.STORAGE
        }
    }
}
