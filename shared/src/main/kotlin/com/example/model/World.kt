package com.example.model

import kotlinx.serialization.Serializable

/** Tile legend for [WorldDto.tiles]. */
object Tiles {
    /** Outside the house; not drawn. */
    const val VOID = ' '

    /** Top of a wall, seen from above. */
    const val WALL = '#'

    /** Front face of a north wall (3/4 view), drawn under a [WALL] row. */
    const val FACE = '='

    /** Doorway through a [FACE] row. */
    const val DOOR = 'd'

    /** Hallway / corridor floor. */
    const val CORRIDOR = ':'

    /** Wooden room floor. */
    const val FLOOR_WOOD = 'w'

    /** Tiled room floor. */
    const val FLOOR_TILE = 't'

    /** Carpeted room floor. */
    const val FLOOR_CARPET = 'c'

    /** Staircase to another level; see [WorldDto.stairs] for where it leads. Walkable. */
    const val STAIRS = 's'

    /** True for tiles a character can stand on. */
    fun isWalkable(c: Char): Boolean = c == DOOR || c == CORRIDOR || c == FLOOR_WOOD || c == FLOOR_TILE || c == FLOOR_CARPET || c == STAIRS
}

/** A tile coordinate in the world grid (x = column, y = row). */
@Serializable
data class TilePos(val x: Int, val y: Int)

/** The whole interior map of a project: tile grid, rooms and furniture. */
@Serializable
data class WorldDto(
    val width: Int,
    val height: Int,
    /** Row-major, exactly width*height chars from [Tiles]. */
    val tiles: String,
    val rooms: List<RoomDto>,
    val furniture: List<FurnitureDto>,
    /** Where idle characters wait (lobby). */
    val spawn: TilePos,
    /** Walkable tile in front of the server rack; EXECUTE goes here. */
    val terminal: TilePos,
    /** Git HEAD, or "" if not a git repo. */
    val revision: String,
    val generatedAtMillis: Long,
    /**
     * The house's floors. All levels live in the one [tiles] grid, stacked vertically with void rows
     * between them; each level's bounds say which part of the grid it is. The frontend shows one level
     * at a time. Empty = a single level covering the whole grid.
     */
    val levels: List<LevelDto> = emptyList(),
    /** Staircases joining levels: stepping onto one end continues from the other. */
    val stairs: List<StairsDto> = emptyList(),
)

/** One floor of the house: index 0 = ground floor, 1 = upstairs, -1 = basement. Bounds in tiles. */
@Serializable
data class LevelDto(val index: Int, val name: String, val x: Int, val y: Int, val w: Int, val h: Int)

/** A two-way staircase: [a] on level [aLevel], [b] on level [bLevel]. Both are [Tiles.STAIRS] tiles. */
@Serializable
data class StairsDto(val a: TilePos, val aLevel: Int, val b: TilePos, val bLevel: Int)

/** True if (x, y) lies inside the grid. */
fun WorldDto.inBounds(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

/** The tile at (x, y), or [Tiles.VOID] if out of bounds. */
fun WorldDto.tileAt(x: Int, y: Int): Char =
    if (inBounds(x, y)) tiles.getOrElse(y * width + x) { Tiles.VOID } else Tiles.VOID

/** True if the tile at (x, y) is in bounds and walkable per [Tiles.isWalkable]. Ignores furniture. */
fun WorldDto.isWalkable(x: Int, y: Int): Boolean = Tiles.isWalkable(tileAt(x, y))

/** A room (directory) in the house. */
@Serializable
data class RoomDto(
    /** Stable: the room's project-relative dir, or "/" for the lobby (project root). */
    val id: String,
    /** Display name, e.g. "backend". */
    val name: String,
    /** Project-relative dir with '/' separators; "" for the lobby. */
    val path: String,
    val summary: String,
    /** Outer bounds in tiles, walls included. */
    val x: Int, val y: Int, val w: Int, val h: Int,
    /** All files under the room, not only the shown ones. */
    val fileCount: Int,
    val loc: Int,
    /** One of Tiles.FLOOR_*. */
    val floor: Char,
    /** What part of the codebase the room stands for; decides its furniture set and look. */
    val kind: RoomKind = RoomKind.WORKSHOP,
    /** Index of the [LevelDto] the room is on. */
    val level: Int = 0,
    /** Ids of closely related rooms (code dependencies or agent-declared links); neighbours among them share a door. */
    val connectedTo: List<String> = emptyList(),
)

/**
 * Room themes. Each kind stands for one aspect of a codebase and gets its own furniture set, so rooms
 * of the same kind always look alike. The agent picks kinds through the Floorplan MCP tools; without
 * an agent plan the backend guesses them from file names.
 */
enum class RoomKind(val label: String, val meaning: String, val level: Int) {
    LOBBY("Entrance hall", "project root: build scripts, top-level config; the server rack where commands run", 0),
    LIVING_ROOM("Living room", "entry points: main functions, app/plugin bootstrap, CLI commands, startup wiring", 0),
    DINING_ROOM("Dining room", "public API and service interfaces: where results are served (RPC interfaces, controllers, endpoints)", 0),
    KITCHEN("Kitchen", "factories, builders, providers, dependency injection: code that creates other objects", 0),
    BATHROOM("Bathroom", "small utilities and helpers: cleanup, formatting, logging, string/path helpers", 0),
    WORKSHOP("Workshop", "core business logic, services, engines, algorithms", 0),
    GREENHOUSE("Greenhouse", "assets: images, sprites, icons, fonts, sounds", 0),
    BEDROOM("Bedroom", "state and persistence: sessions, caches, stores, state that rests between calls", 1),
    STUDY("Study", "data models, DTOs, types, schemas, interfaces and contracts", 1),
    LIBRARY("Library", "documentation, READMEs, guides, design docs", 1),
    GALLERY("Art studio", "UI: views, components, rendering, styling, tool windows", 1),
    LAB("Laboratory", "tests, fixtures, test data, benchmarks", -1),
    UTILITY_ROOM("Utility room", "plumbing: networking, RPC transport, IO, protocols, adapters", -1),
    STORAGE("Storage room", "configuration, resources, build logic, scripts, generated or vendored files", -1),
    /** Generated: the corridor joining the rooms of an upper or lower level. Not for the agent. */
    HALLWAY("Hallway", "the corridor joining the rooms on this floor", 0),
}

/**
 * Kind of furniture a file (or a decoration) is drawn as. Sprite name: `obj_` + lowercase name,
 * e.g. `obj_stove`. Which kinds appear in a room depends on its [RoomKind].
 */
enum class FurnitureKind {
    DESK, BOOKSHELF, CABINET, TEST_BENCH, PLANT, SERVER,
    // Living room
    SOFA, ARMCHAIR, FIREPLACE, TV,
    // Kitchen
    STOVE, COUNTER, FRIDGE, DINING_TABLE,
    // Workshop
    WORKBENCH, TOOL_RACK, ANVIL,
    // Study
    WRITING_DESK, GLOBE, FILING_CABINET,
    // Gallery
    EASEL, PAINTING, STATUE,
    // Utility room
    BOILER, PIPES, WASHER,
    // Lab
    CHALKBOARD, MICROSCOPE,
    // Library
    LECTERN, READING_CHAIR,
    // Storage
    CRATE, BARREL, SHELF,
    // Greenhouse
    FLOWER_BED, POTTED_TREE,
    // Dining room
    SIDEBOARD, CHINA_CABINET, CHAIR,
    // Bedroom
    BED, WARDROBE, NIGHTSTAND, DRESSER,
    // Bathroom
    BATHTUB, TOILET, SINK, MIRROR,
    // Living room / hallway extras
    PIANO, COAT_RACK,
    // Decoration anywhere
    RUG, LAMP, CLOCK,
}

/** A file shown as a piece of furniture in a room, or (with [decor]) a decoration that stands for no file. */
@Serializable
data class FurnitureDto(
    /** Project-relative file path ('/' separators); "" for SERVER and decorations. */
    val path: String,
    val roomId: String,
    val kind: FurnitureKind,
    /** Tile the furniture occupies (not walkable). */
    val x: Int, val y: Int,
    /** Walkable tile where a character stands to use it. */
    val access: TilePos,
    val loc: Int,
    /** E.g. "Kotlin", "Markdown"; "" if unknown. */
    val language: String,
    /** Commits touching it in the last 30 days. */
    val churn: Int,
    val summary: String,
    /** Short themed caption drawn under the furniture, e.g. "Stove: SessionFactory". "" = no caption. */
    val label: String = "",
    /** True for decorations: no file, not clickable, not a walk target. */
    val decor: Boolean = false,
    /** Footprint in tiles from (x, y) to the right and down; e.g. a bed is 2x2. */
    val w: Int = 1,
    val h: Int = 1,
    /**
     * False for things you can walk over or that hang on a wall (rugs, paintings, clocks, mirrors). Wall-hung
     * pieces sit on the [Tiles.FACE] row of the room's north wall.
     */
    val blocking: Boolean = true,
)

/** True if the furniture's footprint covers (x, y). */
fun FurnitureDto.covers(tx: Int, ty: Int): Boolean = tx >= x && tx < x + w && ty >= y && ty < y + h
