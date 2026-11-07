package com.example.backend.world

import com.example.model.FurnitureKind
import com.example.model.TilePos
import com.example.model.Tiles
import com.example.model.WorldDto
import com.example.model.isWalkable
import com.example.model.tileAt
import kotlin.test.Test
import kotlin.test.assertEquals
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
        out += FileInfo("docs/prd.md", 300, "Markdown", 3)
        out += FileInfo("assets/sprites/hero.pxl", 16, "Pixel art", 0)
        out += FileInfo("assets/sprites/hero.png", 0, "Image", 0)
        // Enough small top-level dirs to force several hallway bands.
        for (d in 0 until 14) for (i in 0..(d % 5)) out += FileInfo("mod$d/src/M$i.kt", 20 + i, "Kotlin", 0)
        return out
    }

    @Test
    fun deterministic() {
        val a = WorldLayout.build(files(), "abc", generatedAtMillis = 1)
        val b = WorldLayout.build(files().shuffled(java.util.Random(42)), "abc", generatedAtMillis = 1)
        assertEquals(a, b)
    }

    @Test
    fun structure() {
        val w = WorldLayout.build(files(), "abc", generatedAtMillis = 1)
        assertEquals(w.width * w.height, w.tiles.length)
        assertTrue(w.tiles.all { it in "# =d:wtc" }, "only legend characters")
        assertEquals(WorldLayout.LOBBY_ID, w.rooms.first().id)
        assertEquals(listOf(WorldLayout.LOBBY_ID, "assets", "backend", "docs", "frontend") + (0 until 14).map { "mod$it" }.sorted(), w.rooms.map { it.id })
        assertEquals(1, w.furniture.count { it.kind == FurnitureKind.SERVER })
        val server = w.furniture.single { it.kind == FurnitureKind.SERVER }
        assertEquals(WorldLayout.LOBBY_ID, server.roomId)
        assertEquals(w.terminal, server.access)
        val backend = w.rooms.single { it.id == "backend" }
        assertEquals(33, backend.fileCount)
        assertTrue(w.furniture.groupBy { it.roomId }.values.all { it.size <= WorldLayout.MAX_FURNITURE })
        assertEquals(12, w.furniture.count { it.roomId == "backend" })
        assertTrue(w.furniture.any { it.roomId == "backend" && it.kind == FurnitureKind.TEST_BENCH })
        assertEquals(FurnitureKind.BOOKSHELF, w.furniture.single { it.path == "docs/prd.md" }.kind)
        assertEquals(FurnitureKind.CABINET, w.furniture.single { it.path == "build.gradle.kts" }.kind)
        assertEquals(FurnitureKind.PLANT, w.furniture.single { it.path == "assets/sprites/hero.png" }.kind)
        assertTrue(w.tiles.contains(Tiles.DOOR), "rooms below a hallway open through their north wall")
        assertTrue(w.rooms.map { it.y }.distinct().size > 2, "several bands")

        for (f in w.furniture) {
            val room = w.rooms.single { it.id == f.roomId }
            assertTrue(f.x > room.x && f.x < room.x + room.w - 1 && f.y > room.y + 1 && f.y < room.y + room.h - 1, "furniture inside its room: $f")
            assertEquals(TilePos(f.x, f.y + 1), f.access)
            assertTrue(w.tileAt(f.x, f.y) == room.floor)
        }
        // Rooms never overlap.
        for (a in w.rooms) for (b in w.rooms) if (a !== b) {
            val overlap = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h
            assertTrue(!overlap, "${a.id} overlaps ${b.id}")
        }
    }

    @Test
    fun everyWalkableTileIsReachableFromSpawn() {
        for (input in listOf(files(), emptyList(), listOf(FileInfo("a/b.kt", 1, "Kotlin", 0)))) {
            val w = WorldLayout.build(input, "", generatedAtMillis = 1)
            val blocked = w.furniture.map { TilePos(it.x, it.y) }.toSet()
            val reached = bfs(w, w.spawn, blocked)
            assertTrue(w.isWalkable(w.spawn.x, w.spawn.y) && w.spawn !in blocked)
            for (y in 0 until w.height) for (x in 0 until w.width) {
                val p = TilePos(x, y)
                if (w.isWalkable(x, y) && p !in blocked) assertTrue(p in reached, "unreachable walkable tile $p (${w.tileAt(x, y)})\n${render(w)}")
            }
            for (f in w.furniture) assertTrue(f.access in reached, "unreachable access for ${f.path}")
            assertTrue(w.terminal in reached)
        }
    }

    @Test
    fun heuristicSummaries() {
        val s = Heuristics.roomSummary("frontend", List(12) { FileInfo("frontend/src/ui/V$it.kt", 10, "Kotlin", 0) })
        assertEquals("12 Kotlin files, mostly UI, 120 lines", s)
    }

    private fun bfs(w: WorldDto, start: TilePos, blocked: Set<TilePos>): Set<TilePos> {
        val seen = hashSetOf(start)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val n = TilePos(p.x + dx, p.y + dy)
                if (w.isWalkable(n.x, n.y) && n !in blocked && seen.add(n)) queue += n
            }
        }
        return seen
    }

    private fun render(w: WorldDto) =w.tiles.chunked(w.width).joinToString("\n")
}
