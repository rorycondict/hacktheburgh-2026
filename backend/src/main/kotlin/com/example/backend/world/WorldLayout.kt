package com.example.backend.world

import com.example.model.FurnitureDto
import com.example.model.FurnitureKind
import com.example.model.RoomDto
import com.example.model.TilePos
import com.example.model.Tiles
import com.example.model.WorldDto
import kotlin.math.max

/** Room and file summaries to put into the world; missing entries fall back to heuristics. */
data class Summaries(val rooms: Map<String, String> = emptyMap(), val files: Map<String, String> = emptyMap())

/**
 * Deterministic interior layout: a lobby plus one room per top-level directory, hung above and
 * below horizontal hallways that are joined by a vertical corridor on the left. Pure function of
 * its input (files are sorted by path; no randomness).
 */
object WorldLayout {
    /** "/" (the project root) can never collide with a directory room, whose id is a top-level dir name. */
    const val LOBBY_ID = "/"
    const val MAX_FURNITURE = 12
    private const val MAX_ROW_WIDTH = 64
    private const val ROOM_X0 = 3          // x=0 border wall, x=1..2 vertical corridor
    private const val HALL_H = 2

    private class RoomPlan(
        val id: String,
        val name: String,
        val path: String,
        val allFiles: List<FileInfo>,
        val shown: List<FileInfo>,
        val server: Boolean,
        val floor: Char,
    ) {
        val slots = shown.size + (if (server) 1 else 0)
        val perRow = when {
            slots <= 4 -> max(1, slots)
            else -> minOf(6, (slots + 1) / 2)
        }
        val rows = max(1, (slots + perRow - 1) / perRow)
        val innerW = max(7, 2 * perRow + 1)
        val innerH = max(4, 2 * rows)
        val w = innerW + 2
        val h = innerH + 3
        /** Interior column of the door: even, so it never sits on a furniture column. */
        val doorCol = (innerW / 2).let { if (it % 2 == 0) it else it - 1 }
        var x = 0
        var y = 0
        var opensNorth = false
    }

    fun build(
        files: List<FileInfo>,
        revision: String,
        summaries: Summaries = Summaries(),
        generatedAtMillis: Long = System.currentTimeMillis(),
    ): WorldDto {
        val sorted = files.sortedBy { it.path }
        val rootFiles = sorted.filter { !it.path.contains('/') }
        val byDir = sorted.filter { it.path.contains('/') }.groupBy { it.path.substringBefore('/') }.toSortedMap()

        val plans = ArrayList<RoomPlan>()
        plans += RoomPlan(LOBBY_ID, "lobby", "", rootFiles, pick(rootFiles, MAX_FURNITURE - 1), server = true, floor = Tiles.FLOOR_WOOD)
        var i = 0
        for ((dir, dirFiles) in byDir) {
            i++
            plans += RoomPlan(dir, dir, dir, dirFiles, pick(dirFiles, MAX_FURNITURE), server = false, floor = floorFor(dir, i))
        }

        // Assign rooms to rows: band b has an upper row (doors in the bottom wall) and a lower row
        // (doors in the north wall), both opening onto the band's hallway.
        data class Band(val upper: MutableList<RoomPlan> = ArrayList(), val lower: MutableList<RoomPlan> = ArrayList())
        val bands = ArrayList<Band>()
        var band = Band().also { bands += it }
        var upperFull = false
        fun width(list: List<RoomPlan>) = list.sumOf { it.w }
        for (p in plans) {
            if (!upperFull) {
                if (band.upper.isEmpty() || width(band.upper) + p.w <= MAX_ROW_WIDTH) { band.upper += p; continue }
                upperFull = true
            }
            if (band.lower.isEmpty() || width(band.lower) + p.w <= MAX_ROW_WIDTH) { band.lower += p; continue }
            band = Band().also { bands += it }
            band.upper += p
            upperFull = false
        }

        // Vertical placement.
        var cursorY = 1
        val hallways = ArrayList<Triple<Int, Int, Int>>() // y, x0, x1
        for (b in bands) {
            val upperH = b.upper.maxOfOrNull { it.h } ?: 0
            val hallY = cursorY + upperH
            var x = ROOM_X0
            for (p in b.upper) { p.x = x; p.y = hallY - p.h; x += p.w }
            val upperRight = x - 1
            x = ROOM_X0
            for (p in b.lower) { p.x = x; p.y = hallY + HALL_H; p.opensNorth = true; x += p.w }
            val lowerRight = x - 1
            hallways += Triple(hallY, 1, max(upperRight, lowerRight))
            val lowerH = b.lower.maxOfOrNull { it.h } ?: 0
            cursorY = hallY + HALL_H + lowerH
        }

        val width = (hallways.maxOf { it.third }) + 2
        val height = cursorY + 1
        val grid = Array(height) { CharArray(width) { Tiles.VOID } }

        // Hallways and the vertical corridor joining them.
        for ((y, x0, x1) in hallways) for (yy in y until y + HALL_H) for (xx in x0..x1) grid[yy][xx] = Tiles.CORRIDOR
        if (hallways.size > 1) {
            for (yy in hallways.first().first until hallways.last().first + HALL_H) for (xx in 1..2) grid[yy][xx] = Tiles.CORRIDOR
        }
        // Walls around corridors (any void neighbour of a corridor tile).
        for (y in 0 until height) for (x in 0 until width) {
            if (grid[y][x] != Tiles.CORRIDOR) continue
            for (dy in -1..1) for (dx in -1..1) {
                val ny = y + dy; val nx = x + dx
                if (ny in 0 until height && nx in 0 until width && grid[ny][nx] == Tiles.VOID) grid[ny][nx] = Tiles.WALL
            }
        }

        val rooms = ArrayList<RoomDto>()
        val furniture = ArrayList<FurnitureDto>()
        var spawn = TilePos(0, 0)
        var terminal = TilePos(0, 0)
        for (p in plans) {
            drawRoom(grid, p)
            val doorX = p.x + 1 + p.doorCol
            if (p.id == LOBBY_ID) spawn = TilePos(doorX, p.y + 2 + p.innerH - 1)
            rooms += RoomDto(
                id = p.id,
                name = p.name,
                path = p.path,
                summary = summaries.rooms[p.id] ?: Heuristics.roomSummary(p.path, p.allFiles),
                x = p.x, y = p.y, w = p.w, h = p.h,
                fileCount = p.allFiles.size,
                loc = p.allFiles.sumOf { it.loc },
                floor = p.floor,
            )
            var slot = 0
            fun slotPos(k: Int): Pair<Int, Int> {
                val row = k / p.perRow
                val col = 1 + 2 * (k % p.perRow)
                return (p.x + 1 + col) to (p.y + 2 + 2 * row)
            }
            if (p.server) {
                val (fx, fy) = slotPos(slot++)
                terminal = TilePos(fx, fy + 1)
                furniture += FurnitureDto("", p.id, FurnitureKind.SERVER, fx, fy, terminal, 0, "", 0, "Server rack: commands run here")
            }
            for (f in p.shown) {
                val (fx, fy) = slotPos(slot++)
                furniture += FurnitureDto(
                    path = f.path,
                    roomId = p.id,
                    kind = FileKinds.kind(f.path),
                    x = fx, y = fy,
                    access = TilePos(fx, fy + 1),
                    loc = f.loc,
                    language = f.language,
                    churn = f.churn,
                    summary = summaries.files[f.path] ?: Heuristics.fileSummary(f),
                )
            }
        }

        return WorldDto(
            width = width,
            height = height,
            tiles = buildString(width * height) { for (row in grid) append(row) },
            rooms = rooms,
            furniture = furniture,
            spawn = spawn,
            terminal = terminal,
            revision = revision,
            generatedAtMillis = generatedAtMillis,
        )
    }

    /** Top files by LOC + churn, shown sorted by path. */
    private fun pick(files: List<FileInfo>, n: Int): List<FileInfo> =
        files.sortedWith(compareByDescending<FileInfo> { it.loc + it.churn * 20 }.thenBy { it.path })
            .take(n)
            .sortedBy { it.path }

    private fun floorFor(dir: String, index: Int): Char {
        val d = dir.lowercase()
        return when {
            "test" in d -> Tiles.FLOOR_TILE
            "doc" in d -> Tiles.FLOOR_CARPET
            else -> listOf(Tiles.FLOOR_TILE, Tiles.FLOOR_CARPET, Tiles.FLOOR_WOOD)[index % 3]
        }
    }

    private fun drawRoom(grid: Array<CharArray>, p: RoomPlan) {
        val x0 = p.x; val y0 = p.y; val x1 = p.x + p.w - 1; val y1 = p.y + p.h - 1
        for (y in y0..y1) for (x in x0..x1) {
            grid[y][x] = when {
                y == y0 || y == y1 || x == x0 || x == x1 -> Tiles.WALL
                y == y0 + 1 -> Tiles.FACE
                else -> p.floor
            }
        }
        val doorX = x0 + 1 + p.doorCol
        if (p.opensNorth) {
            grid[y0][doorX] = Tiles.CORRIDOR
            grid[y0 + 1][doorX] = Tiles.DOOR
        } else {
            grid[y1][doorX] = p.floor
        }
    }
}
