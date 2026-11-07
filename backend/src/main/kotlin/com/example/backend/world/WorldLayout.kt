package com.example.backend.world

import com.example.model.FurnitureDto
import com.example.model.FurnitureKind
import com.example.model.LevelDto
import com.example.model.RoomDto
import com.example.model.RoomKind
import com.example.model.StairsDto
import com.example.model.TilePos
import com.example.model.Tiles
import com.example.model.WorldDto
import kotlin.math.max

/** Room and file summaries to put into the world; missing entries fall back to heuristics. */
data class Summaries(val rooms: Map<String, String> = emptyMap(), val files: Map<String, String> = emptyMap())

/**
 * Deterministic house layout. Takes [RoomSpec]s (designed by the agent through the MCP tools, or guessed
 * by [HeuristicPlanner]) and builds a house of up to three floors (ground, upstairs, basement; each room
 * kind has a home floor). Every floor is a hallway spine (on the ground floor, the entrance hall) with
 * rooms above and below it. Rooms share walls and open onto the hallway; domestic pairs (kitchen and
 * dining room...) and rooms whose code is related also get a door between them. Stairs at the end of
 * the hallway join the floors. Furniture is arranged per room by [Furnisher]. Pure function of its input.
 */
object WorldLayout {
    /** "/" (the project root) can never collide with another room id, which all start with "room:" or "hall:". */
    const val LOBBY_ID = "/"
    const val MAX_FURNITURE = 12
    private const val HALL_H = 3              // hallway floor rows
    private const val LEVEL_GAP = 3           // void rows between levels in the grid
    private const val MAX_ROOM_W = 16

    private val LEVEL_NAMES = mapOf(1 to "Upstairs", 0 to "Ground floor", -1 to "Basement")

    /** Rooms that naturally share a door. */
    private val DOMESTIC_PAIRS = setOf(
        setOf(RoomKind.KITCHEN, RoomKind.DINING_ROOM),
        setOf(RoomKind.LIVING_ROOM, RoomKind.DINING_ROOM),
        setOf(RoomKind.STUDY, RoomKind.LIBRARY),
        setOf(RoomKind.BEDROOM, RoomKind.STUDY),
        setOf(RoomKind.LAB, RoomKind.UTILITY_ROOM),
    )

    private class RoomPlan(
        val id: String,
        val spec: RoomSpec,
        val allFiles: List<FileInfo>,
        val items: List<Furnisher.Item>,
        val path: String,
        val level: Int,
    ) {
        val kind get() = spec.kind
        val hallway get() = kind == RoomKind.LOBBY || kind == RoomKind.HALLWAY
        var innerW = 5
        var innerH = 5
        var x = 0
        var y = 0
        /** True for rooms below the hallway (door through their north wall). */
        var below = false
        var placed: List<Furnisher.Placed> = emptyList()
        val w get() = innerW + 2
        val h get() = innerH + 3
        /** Interior column of the hallway door. */
        val doorCol get() = innerW / 2
        /** Interior row of side doors to neighbours. */
        val sideRow get() = innerH / 2
    }

    /** Automatic layout: rooms by role ([HeuristicPlanner]). */
    fun build(
        files: List<FileInfo>,
        revision: String,
        summaries: Summaries = Summaries(),
        generatedAtMillis: Long = System.currentTimeMillis(),
        deps: Map<String, Set<String>> = emptyMap(),
    ): WorldDto = build(HeuristicPlanner.plan(files), files, revision, summaries, generatedAtMillis, deps, curated = false)

    /**
     * @param curated true when [rooms] came from the agent: file order and labels are kept as given.
     * @param deps file path -> paths it refers to (see [DependencyScanner]).
     */
    fun build(
        rooms: List<RoomSpec>,
        files: List<FileInfo>,
        revision: String,
        summaries: Summaries,
        generatedAtMillis: Long,
        deps: Map<String, Set<String>>,
        curated: Boolean,
    ): WorldDto {
        val byPath = files.associateBy { it.path }
        // The entrance hall always exists (spawn + server rack); agent LOBBY rooms merge into it.
        val lobbySpecs = rooms.filter { it.kind == RoomKind.LOBBY }
        val lobby = RoomSpec(
            name = lobbySpecs.firstOrNull()?.name ?: RoomKind.LOBBY.label,
            kind = RoomKind.LOBBY,
            files = lobbySpecs.flatMap { it.files },
            summary = lobbySpecs.firstNotNullOfOrNull { it.summary.ifBlank { null } } ?: "",
            labels = lobbySpecs.fold(emptyMap()) { acc, s -> acc + s.labels },
            connectedTo = lobbySpecs.flatMap { it.connectedTo },
        )
        val others = rooms.filter { it.kind != RoomKind.LOBBY }
            .map { if (it.kind == RoomKind.HALLWAY) it.copy(kind = RoomKind.WORKSHOP) else it }
            .let { list -> if (curated) list else list.sortedBy { it.id } }
            .distinctBy { it.id }

        val claimed = HashSet<String>()
        fun plan(spec: RoomSpec, id: String = spec.id, level: Int = spec.kind.level): RoomPlan {
            val all = spec.files.map { PathNorm.of(it) }.filter { claimed.add(it) }.mapNotNull { byPath[it] }
            val max = if (spec.kind == RoomKind.LOBBY) MAX_FURNITURE - 1 else MAX_FURNITURE
            val shown = if (curated) all.take(max) else pick(all, max)
            val items = ArrayList<Furnisher.Item>()
            if (spec.kind == RoomKind.LOBBY) items += Furnisher.Item(FurnitureKind.SERVER, null, "Server rack", decor = false)
            // Most important files get the room's signature pieces (bed, stove, fireplace...), the rest repeatable ones.
            val unique = RoomThemes.unique(spec.kind)
            val repeat = RoomThemes.repeatable(spec.kind)
            val ranked = shown.sortedWith(compareByDescending<FileInfo> { it.loc + it.churn * 20 }.thenBy { it.path })
            val kindOf = HashMap<String, FurnitureKind>()
            for ((i, f) in ranked.withIndex()) {
                kindOf[f.path] = when {
                    spec.kind == RoomKind.LOBBY || spec.kind == RoomKind.HALLWAY -> FileKinds.kind(f.path).let { if (it == FurnitureKind.DESK || it == FurnitureKind.TEST_BENCH || it == FurnitureKind.PLANT) FurnitureKind.CABINET else it }
                    i < unique.size -> unique[i]
                    else -> repeat[(i - unique.size) % repeat.size]
                }
            }
            for (f in shown) {
                val kind = kindOf.getValue(f.path)
                val label = spec.labels[f.path]?.trim()?.ifEmpty { null } ?: RoomThemes.defaultLabel(kind, f.path)
                items += Furnisher.Item(kind, f, label.take(48), decor = false)
            }
            val used = kindOf.values.toSet()
            for (d in RoomThemes.decor(spec.kind)) if (d !in used || d == FurnitureKind.PLANT) items += Furnisher.Item(d, null, "", decor = true)
            val path = if (spec.kind == RoomKind.LOBBY) "" else commonDir(all.map { it.path })
            return RoomPlan(id, spec, all, items, path, level)
        }
        val lobbyPlan = plan(lobby)
        val otherPlans = others.map { plan(it) }.filter { it.allFiles.isNotEmpty() || curated }
        val links = relations(listOf(lobbyPlan) + otherPlans, deps)

        // Floors: ground always; upstairs / basement only if they have rooms.
        val byLevel = otherPlans.groupBy { it.level }
        val levelIndexes = (listOf(0) + byLevel.keys).distinct().sortedDescending()   // 1, 0, -1: top of the grid first
        val halls = levelIndexes.associateWith { lv ->
            if (lv == 0) lobbyPlan
            else plan(RoomSpec(if (lv > 0) "Landing" else "Cellar hall", RoomKind.HALLWAY, emptyList()), id = "hall:$lv", level = lv)
        }

        class Level(val index: Int, val hall: RoomPlan, val top: List<RoomPlan>, val bottom: List<RoomPlan>) {
            var y = 0
            var width = 0
            var height = 0
        }
        val levels = levelIndexes.map { lv ->
            val ordered = orderRooms(byLevel[lv].orEmpty(), links)
            val (top, bottom) = splitRows(ordered, links)
            Level(lv, halls.getValue(lv), top, bottom)
        }

        // Size rooms row by row (rooms in a row share their height so walls line up), then the hallway.
        for (lv in levels) {
            lv.bottom.forEach { it.below = true }
            for (row in listOf(lv.top, lv.bottom)) sizeRow(row)
            val rowW = { row: List<RoomPlan> -> if (row.isEmpty()) 0 else row.sumOf { it.w } - (row.size - 1) }
            val hallMin = hallMinWidth(lv.hall)
            lv.width = maxOf(rowW(lv.top), rowW(lv.bottom), hallMin + 2, 14)
            // Stretch the last room of each row so the floor is a clean rectangle.
            for (row in listOf(lv.top, lv.bottom)) if (row.isNotEmpty()) row.last().innerW += lv.width - rowW(row)
            lv.hall.innerW = lv.width - 2
            lv.hall.innerH = HALL_H
        }

        // Vertical placement: levels stacked in the grid with void rows between.
        var cursorY = 0
        for (lv in levels) {
            lv.y = cursorY
            val topH = lv.top.maxOfOrNull { it.h } ?: 0
            val hallY = if (lv.top.isEmpty()) cursorY else cursorY + topH - 1
            var x = 0
            for (p in lv.top) { p.x = x; p.y = cursorY; x += p.w - 1 }
            lv.hall.x = 0; lv.hall.y = hallY
            val bottomY = hallY + lv.hall.h - 1
            x = 0
            for (p in lv.bottom) { p.x = x; p.y = bottomY; x += p.w - 1 }
            val bottomH = lv.bottom.maxOfOrNull { it.h } ?: 0
            lv.height = (if (lv.bottom.isEmpty()) hallY + lv.hall.h else bottomY + bottomH) - cursorY
            cursorY += lv.height + LEVEL_GAP
        }
        val width = levels.maxOf { it.width }
        val height = cursorY - LEVEL_GAP
        val grid = Array(height) { CharArray(width) { Tiles.VOID } }

        // Draw: hallways first, then rooms (shared walls are simply drawn twice).
        for (lv in levels) { drawRoom(grid, lv.hall); (lv.top + lv.bottom).forEach { drawRoom(grid, it) } }

        // Doors to the hallway, and side doors between related neighbours.
        val hallReserved = HashMap<String, MutableSet<Pair<Int, Int>>>()
        val hallFaceDoors = HashMap<String, MutableSet<Int>>()
        for (lv in levels) {
            val hall = lv.hall
            val res = hallReserved.getOrPut(hall.id) { HashSet() }
            val faceDoors = hallFaceDoors.getOrPut(hall.id) { HashSet() }
            for (p in lv.top) {
                val dx = p.x + 1 + p.doorCol
                grid[hall.y][dx] = Tiles.DOOR          // shared wall (room's south wall = hallway's north wall)
                grid[hall.y + 1][dx] = Tiles.DOOR      // hallway's north face
                res += (dx - hall.x - 1) to 0
                faceDoors += dx - hall.x - 1
            }
            for (p in lv.bottom) {
                val dx = p.x + 1 + p.doorCol
                grid[p.y][dx] = Tiles.DOOR             // shared wall (hallway's south wall = room's north wall)
                grid[p.y + 1][dx] = Tiles.DOOR         // room's north face
                res += (dx - hall.x - 1) to (HALL_H - 1)
            }
            for (row in listOf(lv.top, lv.bottom)) for ((a, b) in row.zipWithNext()) {
                val related = (links[a.id]?.get(b.id) ?: 0) > 0 || setOf(a.kind, b.kind) in DOMESTIC_PAIRS
                if (!related) continue
                grid[a.y + 2 + a.sideRow][a.x + a.w - 1] = Tiles.DOOR
            }
            res += 0 to 1                                    // spawn / landing spot at the west end
        }

        // Stairs at the east end of each hallway: up in the top row, down in the bottom row.
        val stairs = ArrayList<StairsDto>()
        fun stairTile(lv: Level, up: Boolean): TilePos {
            val hall = lv.hall
            val ix = hall.innerW - 1
            val iy = if (up) 0 else HALL_H - 1
            hallReserved.getValue(hall.id) += ix to iy
            val t = TilePos(hall.x + 1 + ix, hall.y + 2 + iy)
            grid[t.y][t.x] = Tiles.STAIRS
            return t
        }
        val byIndex = levels.associateBy { it.index }
        for (lv in levels) {
            val above = byIndex[lv.index + 1] ?: continue
            stairs += StairsDto(stairTile(lv, up = true), lv.index, stairTile(above, up = false), above.index)
        }

        // Furniture.
        for (lv in levels) {
            val hall = lv.hall
            hall.placed = Furnisher.place(hall.innerW, HALL_H, hall.items, hallReserved.getValue(hall.id), hallFaceDoors.getValue(hall.id), hallway = true)
                ?: placeLeniently(hall, hallReserved.getValue(hall.id), hallFaceDoors.getValue(hall.id))
            for (p in lv.top + lv.bottom) {
                p.placed = Furnisher.place(p.innerW, p.innerH, p.items, reservedFor(p), if (p.below) setOf(p.doorCol) else emptySet())
                    ?: placeLeniently(p, reservedFor(p), if (p.below) setOf(p.doorCol) else emptySet())
            }
        }

        val roomDtos = ArrayList<RoomDto>()
        val furniture = ArrayList<FurnitureDto>()
        var terminal: TilePos? = null
        val ground = byIndex.getValue(0)
        val spawn = TilePos(ground.hall.x + 1, ground.hall.y + 3)
        // Ground floor first, so the entrance hall is the first room.
        for (lv in levels.sortedBy { if (it.index == 0) 0 else 1 }) for (p in listOf(lv.hall) + lv.top + lv.bottom) {
            roomDtos += RoomDto(
                id = p.id,
                name = p.spec.name,
                path = p.path,
                summary = p.spec.summary.ifBlank { null } ?: summaries.rooms[p.id] ?: when (p.kind) {
                    RoomKind.HALLWAY -> "Stairs and the corridor joining the rooms ${if (p.level > 0) "upstairs" else "in the basement"}"
                    else -> p.kind.label + ": " + Heuristics.roomSummary(p.path.ifEmpty { if (p.id == LOBBY_ID) "" else "." }, p.allFiles)
                },
                x = p.x, y = p.y, w = p.w, h = p.h,
                fileCount = p.allFiles.size,
                loc = p.allFiles.sumOf { it.loc },
                floor = RoomThemes.floor(p.kind),
                kind = p.kind,
                level = p.level,
                connectedTo = links[p.id].orEmpty().filterValues { it > 0 }.keys.sorted(),
            )
            val blockedSet = HashSet<Pair<Int, Int>>()
            for (pl in p.placed) if (pl.piece.blocking) for (yy in pl.y until pl.y + pl.piece.h) for (xx in pl.x until pl.x + pl.piece.w) blockedSet += xx to yy
            val innerH = if (p.hallway) HALL_H else p.innerH
            for (pl in p.placed) {
                val (ax, ay) = Furnisher.access(pl, p.innerW, innerH) { x, y -> (x to y) in blockedSet }
                val ox = p.x + 1
                val oy = p.y + 2
                val access = TilePos(ox + ax, oy + ay)
                val fx = ox + pl.x
                val fy = oy + pl.y      // y = -1 → the face row
                val f = pl.item.file
                val pc = pl.piece
                furniture += when {
                    pl.item.kind == FurnitureKind.SERVER -> {
                        terminal = access
                        FurnitureDto("", p.id, FurnitureKind.SERVER, fx, fy, access, 0, "", 0, "Server rack: commands run here", pl.item.label, w = pc.w, h = pc.h, blocking = pc.blocking)
                    }
                    f == null -> FurnitureDto("", p.id, pl.item.kind, fx, fy, access, 0, "", 0, "", "", decor = true, w = pc.w, h = pc.h, blocking = pc.blocking)
                    else -> FurnitureDto(
                        path = f.path,
                        roomId = p.id,
                        kind = pl.item.kind,
                        x = fx, y = fy,
                        access = access,
                        loc = f.loc,
                        language = f.language,
                        churn = f.churn,
                        summary = summaries.files[f.path] ?: Heuristics.fileSummary(f),
                        label = pl.item.label,
                        w = pc.w, h = pc.h, blocking = pc.blocking,
                    )
                }
            }
        }

        return WorldDto(
            width = width,
            height = height,
            tiles = buildString(width * height) { for (row in grid) append(row) },
            rooms = roomDtos,
            furniture = furniture,
            spawn = spawn,
            terminal = terminal ?: spawn,
            revision = revision,
            generatedAtMillis = generatedAtMillis,
            levels = levels.map { LevelDto(it.index, LEVEL_NAMES.getValue(it.index), 0, it.y, it.width, it.height) },
            stairs = stairs,
        )
    }

    /** Door thresholds of a room (hallway door + both possible side doors) stay free. */
    private fun reservedFor(p: RoomPlan): Set<Pair<Int, Int>> {
        val w = p.innerW; val h = p.innerH; val dc = p.doorCol; val sr = p.sideRow
        val door = if (p.below) listOf(dc to 0, dc to 1) else listOf(dc to h - 1, dc to h - 2)
        return (door + listOf(0 to sr, 1 to sr, (w - 1) to sr, (w - 2) to sr)).toSet()
    }

    /** Sizes the rooms of one row: a shared interior height, and for each room the narrowest width its furniture fits. */
    private fun sizeRow(row: List<RoomPlan>) {
        if (row.isEmpty()) return
        for (h in listOf(5, 6, 7, 8)) {
            var ok = true
            for (p in row) {
                p.innerH = h
                val fit = (5..MAX_ROOM_W).firstOrNull { w ->
                    p.innerW = w
                    Furnisher.place(w, h, p.items, reservedFor(p), if (p.below) setOf(p.doorCol) else emptySet()) != null
                }
                if (fit == null) { ok = false; p.innerW = MAX_ROOM_W; continue }
                // A little breathing room beyond the tightest fit, so tables and sofas can stand free.
                p.innerW = (fit + 2..minOf(MAX_ROOM_W, fit + 2)).firstOrNull { w ->
                    p.innerW = w
                    Furnisher.place(w, h, p.items, reservedFor(p), if (p.below) setOf(p.doorCol) else emptySet()) != null
                } ?: fit
            }
            if (ok) return
        }
    }

    /** Hallway width needed for its furniture (plus room for doors and stairs). */
    private fun hallMinWidth(hall: RoomPlan): Int {
        val blocking = hall.items.sumOf { if (Furnisher.pieceOf(it, true, 20).blocking) Furnisher.pieceOf(it, true, 20).w else 0 }
        return max(10, blocking + 6)
    }

    /** When a room is too crowded, drop decorations, then the least important files, until the rest fits. */
    private fun placeLeniently(p: RoomPlan, reserved: Set<Pair<Int, Int>>, faceDoors: Set<Int>): List<Furnisher.Placed> {
        val h = if (p.hallway) HALL_H else p.innerH
        var items = p.items.filter { !it.decor }
        while (items.isNotEmpty()) {
            Furnisher.place(p.innerW, h, items, reserved, faceDoors, p.hallway)?.let { return it }
            items = items.dropLast(1)
        }
        return emptyList()
    }

    /** Rooms of a floor in house order (by kind: living room, dining room, kitchen...), related rooms next to each other. */
    private fun orderRooms(rooms: List<RoomPlan>, links: Map<String, Map<String, Int>>): List<RoomPlan> {
        val out = ArrayList<RoomPlan>()
        for ((_, group) in rooms.groupBy { it.kind }.toSortedMap(compareBy { it.ordinal })) {
            val left = group.toMutableList()
            while (left.isNotEmpty()) {
                val prev = out.lastOrNull()?.id
                val next = left.withIndex().maxWith(
                    compareBy<IndexedValue<RoomPlan>> { prev?.let { p -> links[p]?.get(it.value.id) } ?: 0 }
                        .thenBy { iv -> out.sumOf { links[it.id]?.get(iv.value.id) ?: 0 } }
                        .thenByDescending { it.index },
                ).value
                left.remove(next)
                out += next
            }
        }
        return out
    }

    /** First rooms above the hallway, the rest below: balances the rows' widths without splitting related neighbours. */
    private fun splitRows(rooms: List<RoomPlan>, links: Map<String, Map<String, Int>>): Pair<List<RoomPlan>, List<RoomPlan>> {
        if (rooms.size <= 1) return rooms to emptyList()
        val est = rooms.map { 6 + it.items.size }
        val cut = (1..rooms.size).minBy { c ->
            val top = est.take(c).sum()
            val bottom = est.drop(c).sum()
            val related = c < rooms.size && rooms[c - 1].let { a -> rooms[c].let { b ->
                (links[a.id]?.get(b.id) ?: 0) > 0 || setOf(a.kind, b.kind) in DOMESTIC_PAIRS
            } }
            kotlin.math.abs(top - bottom) + (if (related) 30 else 0)
        }
        return rooms.take(cut) to rooms.drop(cut)
    }

    /** Room id -> (room id -> link weight): agent-declared links count 5, each cross-room file reference 1. */
    private fun relations(plans: List<RoomPlan>, deps: Map<String, Set<String>>): Map<String, Map<String, Int>> {
        val roomOf = HashMap<String, String>()
        for (p in plans) for (f in p.allFiles) roomOf[f.path] = p.id
        val byName = plans.associateBy { it.spec.name.trim().lowercase() }
        val byId = plans.associateBy { it.id }
        val out = HashMap<String, HashMap<String, Int>>()
        fun add(a: String, b: String, w: Int) {
            if (a == b) return
            out.getOrPut(a) { HashMap() }.merge(b, w, Int::plus)
            out.getOrPut(b) { HashMap() }.merge(a, w, Int::plus)
        }
        for (p in plans) for (n in p.spec.connectedTo) {
            val other = byName[n.trim().lowercase()] ?: byId[n] ?: byId["room:" + RoomSpec.slug(n)] ?: continue
            add(p.id, other.id, 5)
        }
        for ((from, tos) in deps) {
            val a = roomOf[from] ?: continue
            for (to in tos) add(a, roomOf[to] ?: continue, 1)
        }
        return out
    }

    /** Longest common directory of [paths] ("" if none). */
    private fun commonDir(paths: List<String>): String {
        if (paths.isEmpty()) return ""
        var prefix = paths.first().substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }
        for (p in paths.drop(1)) {
            val dirs = p.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }
            prefix = prefix.zip(dirs).takeWhile { (a, b) -> a == b }.map { it.first }
        }
        return prefix.joinToString("/")
    }

    /** Top files by LOC + churn, shown sorted by path. */
    private fun pick(files: List<FileInfo>, n: Int): List<FileInfo> =
        files.sortedWith(compareByDescending<FileInfo> { it.loc + it.churn * 20 }.thenBy { it.path })
            .take(n)
            .sortedBy { it.path }

    private fun drawRoom(grid: Array<CharArray>, p: RoomPlan) {
        val floor = RoomThemes.floor(p.kind)
        val x0 = p.x; val y0 = p.y; val x1 = p.x + p.w - 1; val y1 = p.y + p.h - 1
        for (y in y0..y1) for (x in x0..x1) {
            val border = y == y0 || y == y1 || x == x0 || x == x1
            grid[y][x] = when {
                border -> Tiles.WALL
                y == y0 + 1 -> Tiles.FACE
                else -> floor
            }
        }
    }
}

/** Path normalisation for agent-supplied paths: '/' separators, no leading "./" or "/". */
object PathNorm {
    fun of(p: String): String = p.trim().replace('\\', '/').removePrefix("./").trimStart('/')
}
