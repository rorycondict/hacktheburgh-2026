package com.example.backend.world

import com.example.model.FurnitureKind
import com.example.model.RoomKind
import com.example.model.TilePos
import com.example.model.Tiles
import com.example.model.WorldDto
import com.example.model.isWalkable
import com.example.model.tileAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorldLayoutTest {
    private fun files(): List<FileInfo> {
        val out = ArrayList<FileInfo>()
        out += FileInfo("README.md", 40, "Markdown", 2)
        out += FileInfo("build.gradle.kts", 60, "Kotlin Script", 5)
        out += FileInfo("settings.gradle.kts", 30, "Kotlin Script", 1)
        for (i in 0 until 30) out += FileInfo("backend/src/main/kotlin/F$i.kt", 10 + i * 7, "Kotlin", i % 4)
        for (i in 0 until 3) out += FileInfo("backend/src/test/kotlin/F${i}Test.kt", 500, "Kotlin", 0)
        for (i in 0 until 5) out += FileInfo("frontend/src/main/kotlin/ui/View$i.kt", 100, "Kotlin", 1)
        out += FileInfo("backend/src/main/kotlin/SessionFactory.kt", 80, "Kotlin", 0)
        out += FileInfo("backend/src/main/kotlin/Main.kt", 30, "Kotlin", 0)
        out += FileInfo("backend/src/main/kotlin/SessionStore.kt", 90, "Kotlin", 0)
        out += FileInfo("backend/src/main/kotlin/PathUtil.kt", 20, "Kotlin", 0)
        out += FileInfo("shared/src/main/kotlin/rpc/VisualiserRpcApi.kt", 50, "Kotlin", 0)
        out += FileInfo("docs/prd.md", 300, "Markdown", 3)
        out += FileInfo("assets/sprites/hero.pxl", 16, "Pixel art", 0)
        out += FileInfo("assets/sprites/hero.png", 0, "Image", 0)
        for (d in 0 until 6) for (i in 0..(d % 5)) out += FileInfo("mod$d/src/M$i.kt", 20 + i, "Kotlin", 0)
        return out
    }

    @Test
    fun deterministic() {
        val a = WorldLayout.build(files(), "abc", generatedAtMillis = 1)
        val b = WorldLayout.build(files().shuffled(java.util.Random(42)), "abc", generatedAtMillis = 1)
        assertEquals(a, b)
    }

    @Test
    fun houseStructure() {
        val w = WorldLayout.build(files(), "abc", generatedAtMillis = 1)
        println(render(w))
        assertEquals(w.width * w.height, w.tiles.length)
        assertTrue(w.tiles.all { it in "# =dswtc" }, "only legend characters, no corridor tiles")
        assertEquals(WorldLayout.LOBBY_ID, w.rooms.first().id)
        assertEquals(setOf(1, 0, -1), w.levels.map { it.index }.toSet(), "upstairs, ground floor and basement")
        assertEquals(2, w.stairs.size)
        val kinds = w.rooms.map { it.kind }.toSet()
        assertTrue(kinds.containsAll(setOf(RoomKind.LIVING_ROOM, RoomKind.KITCHEN, RoomKind.DINING_ROOM, RoomKind.BEDROOM, RoomKind.BATHROOM, RoomKind.LAB)), "$kinds")
        for (r in w.rooms) assertEquals(r.kind.level.takeIf { r.kind != RoomKind.HALLWAY } ?: r.level, r.level, "${r.name} on its home floor")
        val server = w.furniture.single { it.kind == FurnitureKind.SERVER }
        assertEquals(WorldLayout.LOBBY_ID, server.roomId)
        assertEquals(w.terminal, server.access)
        assertEquals(RoomKind.LOBBY, w.rooms.single { w.spawn.x in it.x until it.x + it.w && w.spawn.y in it.y until it.y + it.h }.kind, "start in the entrance hall")

        // Kitchen and dining room share a door.
        val kitchen = w.rooms.single { it.kind == RoomKind.KITCHEN }
        val dining = w.rooms.single { it.kind == RoomKind.DINING_ROOM }
        val (l, r) = if (kitchen.x < dining.x) kitchen to dining else dining to kitchen
        assertEquals(l.x + l.w - 1, r.x, "kitchen and dining room are neighbours")
        assertTrue((l.y until l.y + l.h).any { w.tileAt(r.x, it) == Tiles.DOOR }, "door between kitchen and dining room")

        for (f in w.furniture) {
            val room = w.rooms.single { it.id == f.roomId }
            assertTrue(f.x > room.x && f.x + f.w - 1 < room.x + room.w - 1, "inside its room horizontally: $f")
            if (f.blocking) {
                assertTrue(f.y > room.y + 1 && f.y + f.h - 1 < room.y + room.h - 1, "on the floor: $f")
                for (yy in f.y until f.y + f.h) for (xx in f.x until f.x + f.w) assertEquals(room.floor, w.tileAt(xx, yy))
            }
        }
        // Blocking furniture never overlaps.
        val covered = HashSet<TilePos>()
        for (f in w.furniture.filter { it.blocking }) for (yy in f.y until f.y + f.h) for (xx in f.x until f.x + f.w) assertTrue(covered.add(TilePos(xx, yy)), "overlap at $xx,$yy")
        // The bed is in a corner.
        val bed = w.furniture.single { it.kind == FurnitureKind.BED }
        val br = w.rooms.single { it.id == bed.roomId }
        val left = bed.x == br.x + 1; val right = bed.x + bed.w == br.x + br.w - 1
        val top = bed.y == br.y + 2; val bottom = bed.y + bed.h == br.y + br.h - 1
        assertTrue((left || right) && (top || bottom), "bed in a corner: $bed in $br")
        // Wall-hung pieces sit on the face row.
        for (f in w.furniture.filter { !it.blocking && it.kind != FurnitureKind.RUG }) assertEquals(Tiles.FACE, w.tileAt(f.x, f.y), "wall-hung $f")
        // Rooms never overlap except along shared walls.
        for (a in w.rooms) for (b in w.rooms) if (a !== b) {
            val overlap = a.x + 1 < b.x + b.w - 1 && b.x + 1 < a.x + a.w - 1 && a.y + 1 < b.y + b.h - 1 && b.y + 1 < a.y + a.h - 1
            assertTrue(!overlap, "${a.name} overlaps ${b.name}")
        }
    }

    private fun curated(): WorldDto {
        val rooms = listOf(
            RoomSpec("Front door", RoomKind.LOBBY, listOf("README.md", "build.gradle.kts")),
            RoomSpec("Docs", RoomKind.LIBRARY, listOf("docs/prd.md"), labels = mapOf("docs/prd.md" to "The big plan")),
            RoomSpec("Engine", RoomKind.WORKSHOP, (0 until 5).map { "backend/src/main/kotlin/F$it.kt" } + "nope/missing.kt", connectedTo = listOf("Engine 2")),
            RoomSpec("Engine 2", RoomKind.WORKSHOP, (5 until 9).map { "backend/src/main/kotlin/F$it.kt" }),
            RoomSpec("Tests", RoomKind.LAB, (0 until 3).map { "backend/src/test/kotlin/F${it}Test.kt" }),
        )
        return WorldLayout.build(rooms, files(), "abc", Summaries(), 1, emptyMap(), curated = true)
    }

    @Test
    fun agentPlan() {
        val w = curated()
        println(render(w))
        assertEquals(setOf("Front door", "Docs", "Engine", "Engine 2", "Tests", "Landing", "Cellar hall"), w.rooms.map { it.name }.toSet())
        assertTrue(w.furniture.none { it.path.startsWith("mod") || it.path.startsWith("assets/") }, "only chosen files")
        assertEquals("The big plan", w.furniture.single { it.path == "docs/prd.md" }.label)
        val a = w.rooms.single { it.name == "Engine" }
        val b = w.rooms.single { it.name == "Engine 2" }
        assertTrue(b.id in a.connectedTo)
        assertEquals(a.x + a.w - 1, b.x, "related rooms are next door")
        assertTrue((a.y until a.y + a.h).any { w.tileAt(b.x, it) == Tiles.DOOR }, "connecting door")
    }

    @Test
    fun dependenciesDecideNeighbours() {
        val rooms = listOf(
            RoomSpec("A", RoomKind.WORKSHOP, listOf("mod0/src/M0.kt")),
            RoomSpec("B", RoomKind.WORKSHOP, listOf("mod1/src/M0.kt")),
            RoomSpec("C", RoomKind.WORKSHOP, listOf("mod2/src/M0.kt")),
        )
        val deps = mapOf("mod0/src/M0.kt" to setOf("mod2/src/M0.kt"))
        val w = WorldLayout.build(rooms, files(), "", Summaries(), 1, deps, curated = true)
        assertEquals(listOf("A", "C", "B"), w.rooms.filter { it.kind == RoomKind.WORKSHOP }.sortedWith(compareBy({ it.y }, { it.x })).map { it.name })
    }

    @Test
    fun everyWalkableTileIsReachableFromSpawn() {
        val inputs = listOf(files(), emptyList(), listOf(FileInfo("a/b.kt", 1, "Kotlin", 0)), listOf(FileInfo("a/bTest.kt", 1, "Kotlin", 0)))
            .map { WorldLayout.build(it, "", generatedAtMillis = 1) } + curated()
        for (w in inputs) {
            val blocked = w.furniture.filter { it.blocking }.flatMap { f -> (f.y until f.y + f.h).flatMap { y -> (f.x until f.x + f.w).map { TilePos(it, y) } } }.toSet()
            val reached = bfs(w, w.spawn, blocked)
            assertTrue(w.isWalkable(w.spawn.x, w.spawn.y) && w.spawn !in blocked)
            for (y in 0 until w.height) for (x in 0 until w.width) {
                val p = TilePos(x, y)
                if (w.isWalkable(x, y) && p !in blocked) assertTrue(p in reached, "unreachable walkable tile $p (${w.tileAt(x, y)})\n${render(w)}")
            }
            for (f in w.furniture) assertTrue(f.access in reached, "unreachable access for $f\n${render(w)}")
            assertTrue(w.terminal in reached)
        }
    }

    @Test
    fun crowdedRoomsStillFit() {
        val many = (0 until 40).map { FileInfo("big/src/Thing${it}Factory.kt", 100 + it, "Kotlin", 0) }
        val w = WorldLayout.build(listOf(RoomSpec("Kitchen", RoomKind.KITCHEN, many.map { it.path })), many, "", Summaries(), 1, emptyMap(), curated = true)
        assertEquals(WorldLayout.MAX_FURNITURE, w.furniture.count { !it.decor && it.path.isNotEmpty() && it.roomId != WorldLayout.LOBBY_ID })
        assertNotNull(w.furniture.singleOrNull { it.kind == FurnitureKind.STOVE })
    }

    @Test
    fun heuristicSummaries() {
        val s = Heuristics.roomSummary("frontend", List(12) { FileInfo("frontend/src/ui/V$it.kt", 10, "Kotlin", 0) })
        assertEquals("12 Kotlin files, mostly UI, 120 lines", s)
    }

    private fun bfs(w: WorldDto, start: TilePos, blocked: Set<TilePos>): Set<TilePos> {
        val portals = HashMap<TilePos, TilePos>()
        for (s in w.stairs) { portals[s.a] = s.b; portals[s.b] = s.a }
        val seen = hashSetOf(start)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            val next = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).map { (dx, dy) -> TilePos(p.x + dx, p.y + dy) } + listOfNotNull(portals[p])
            for (n in next) if (w.isWalkable(n.x, n.y) && n !in blocked && seen.add(n)) queue += n
        }
        return seen
    }

    /** ASCII picture with furniture overlaid (first letter of the kind, lowercase for non-blocking). */
    private fun render(w: WorldDto): String {
        val rows = w.tiles.chunked(w.width).map { it.toCharArray() }
        for (f in w.furniture) for (yy in f.y until f.y + f.h) for (xx in f.x until f.x + f.w) {
            val c = f.kind.name.first()
            rows[yy][xx] = if (f.blocking) c else c.lowercaseChar()
        }
        rows[w.spawn.y][w.spawn.x] = '@'
        return rows.joinToString("\n") { String(it) }
    }
}
