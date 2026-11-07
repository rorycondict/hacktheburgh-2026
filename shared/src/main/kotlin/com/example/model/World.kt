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

    /** True for tiles a character can stand on. */
    fun isWalkable(c: Char): Boolean = c == DOOR || c == CORRIDOR || c == FLOOR_WOOD || c == FLOOR_TILE || c == FLOOR_CARPET
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
)

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
    /** Stable: the room's project-relative dir, or "lobby". */
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
)

/** Kind of furniture a file is drawn as. */
enum class FurnitureKind { DESK, BOOKSHELF, CABINET, TEST_BENCH, PLANT, SERVER }

/** A file shown as a piece of furniture in a room. */
@Serializable
data class FurnitureDto(
    /** Project-relative file path ('/' separators); "" for SERVER. */
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
)
