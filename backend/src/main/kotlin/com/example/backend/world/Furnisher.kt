package com.example.backend.world

import com.example.model.FurnitureKind
import com.example.model.LibraryDto

/**
 * Arranges furniture in one room the way a person would: beds and fridges in corners, counters and
 * shelves against the walls, tables and sofas in the middle, the nightstand by the bed, paintings and
 * clocks on the north wall, a rug on the floor. Every placement keeps the room's free floor connected
 * and leaves each piece a free tile to stand at. Coordinates are interior: (0, 0) is the floor tile just
 * below the north wall's face; wall-hung pieces get y = -1 (the face row).
 */
internal object Furnisher {
    /** [libraries]: for shelves of the dependency library, the libraries this piece stands for ([file] is null). */
    class Item(val kind: FurnitureKind, val file: FileInfo?, val label: String, val decor: Boolean, val libraries: List<LibraryDto> = emptyList())

    class Placed(val item: Item, val x: Int, val y: Int, val piece: Piece)

    private val SERVER_PIECE = Piece(1, 1, Placement.WALL)

    fun pieceOf(item: Item, hallway: Boolean, w: Int): Piece {
        if (item.kind == FurnitureKind.SERVER) return SERVER_PIECE
        val p = RoomThemes.piece(item.kind)
        if (!hallway) return p
        // In a 3-row corridor nothing stands in the middle row; the rug becomes a long runner.
        return when (p.placement) {
            Placement.FLOOR -> Piece(minOf(8, maxOf(2, w - 6)), 1, Placement.FLOOR)
            Placement.CENTER, Placement.NEAR_BED -> Piece(1, 1, Placement.WALL)
            Placement.CORNER -> Piece(1, 1, Placement.CORNER)
            else -> if (p.h > 1) Piece(p.w, 1, p.placement) else p
        }
    }

    /**
     * @param reserved interior tiles that must stay free (door thresholds, stairs, spawn).
     * @param doorFaceCols columns of the face row that are doorways (no wall-hung pieces there).
     * @return the placements, or null if a non-decor item didn't fit (decorations that don't fit are dropped).
     */
    fun place(w: Int, h: Int, items: List<Item>, reserved: Set<Pair<Int, Int>>, doorFaceCols: Set<Int>, hallway: Boolean = false): List<Placed>? {
        val blocked = Array(h) { BooleanArray(w) }
        val placed = ArrayList<Placed>()
        val order = items.withIndex().sortedWith(compareBy({ pieceOf(it.value, hallway, w).placement.ordinal }, { it.index })).map { it.value }

        fun inRoom(x: Int, y: Int) = x in 0 until w && y in 0 until h
        fun free(x: Int, y: Int) = inRoom(x, y) && !blocked[y][x]

        fun fitsBlocking(x: Int, y: Int, p: Piece): Boolean {
            for (yy in y until y + p.h) for (xx in x until x + p.w) {
                if (!inRoom(xx, yy) || blocked[yy][xx] || (xx to yy) in reserved) return false
            }
            return true
        }

        fun connectedAfter(x: Int, y: Int, p: Piece): Boolean {
            for (yy in y until y + p.h) for (xx in x until x + p.w) blocked[yy][xx] = true
            try {
                val seed = reserved.firstOrNull { free(it.first, it.second) }
                    ?: (0 until h).asSequence().flatMap { yy -> (0 until w).asSequence().map { xx -> xx to yy } }.firstOrNull { free(it.first, it.second) }
                    ?: return false
                val seen = HashSet<Pair<Int, Int>>()
                val queue = ArrayDeque(listOf(seed))
                seen += seed
                while (queue.isNotEmpty()) {
                    val (cx, cy) = queue.removeFirst()
                    for ((dx, dy) in DIRS) {
                        val n = (cx + dx) to (cy + dy)
                        if (free(n.first, n.second) && seen.add(n)) queue += n
                    }
                }
                var freeCount = 0
                for (yy in 0 until h) for (xx in 0 until w) if (!blocked[yy][xx]) freeCount++
                if (seen.size != freeCount) return false
                // Every blocking piece (including this one) keeps a free tile next to it.
                val all = placed.filter { it.piece.blocking }.map { Triple(it.x, it.y, it.piece) } + Triple(x, y, p)
                return all.all { (px, py, pp) -> neighbours(px, py, pp).any { (nx, ny) -> free(nx, ny) } }
            } finally {
                for (yy in y until y + p.h) for (xx in x until x + p.w) blocked[yy][xx] = false
            }
        }

        fun cornerCandidates(p: Piece) = listOf(0 to 0, (w - p.w) to 0, 0 to (h - p.h), (w - p.w) to (h - p.h)).distinct()

        fun wallCandidates(p: Piece): List<Pair<Int, Int>> {
            val out = LinkedHashSet<Pair<Int, Int>>()
            for (x in 0..w - p.w) out += x to 0                     // north wall
            for (y in 1..h - p.h) { out += 0 to y; out += (w - p.w) to y }   // west and east walls
            for (x in 0..w - p.w) out += x to (h - p.h)             // south wall
            return out.toList()
        }

        fun centerCandidates(p: Piece): List<Pair<Int, Int>> {
            val cx = (w - p.w) / 2.0
            val cy = (h - p.h) / 2.0
            val out = ArrayList<Pair<Int, Int>>()
            for (y in 1..h - p.h - 1) for (x in 1..w - p.w - 1) out += x to y
            return out.sortedWith(compareBy({ (x, y) -> (x - cx) * (x - cx) + (y - cy) * (y - cy) }, { it.second }, { it.first }))
        }

        fun nearBedCandidates(p: Piece): List<Pair<Int, Int>> {
            val bed = placed.firstOrNull { it.item.kind == FurnitureKind.BED } ?: return emptyList()
            return neighbours(bed.x, bed.y, bed.piece).filter { inRoom(it.first, it.second) }
                .sortedBy { (x, y) -> if (x == 0 || y == 0 || x == w - p.w || y == h - p.h) 0 else 1 }
        }

        /** Gap (in tiles, capped) between a footprint and the nearest blocking piece already placed. */
        fun spread(x: Int, y: Int, p: Piece): Int = placed.filter { it.piece.blocking }.minOfOrNull { o ->
            val gx = maxOf(0, maxOf(o.x - (x + p.w), x - (o.x + o.piece.w)))
            val gy = maxOf(0, maxOf(o.y - (y + p.h), y - (o.y + o.piece.h)))
            maxOf(gx, gy)
        }?.coerceAtMost(3) ?: 3

        fun wallBonus(x: Int, y: Int, p: Piece): Int = when {
            y == 0 -> 3
            x == 0 || x == w - p.w -> 2
            else -> 0
        }

        fun anyCandidates(p: Piece): List<Pair<Int, Int>> =
            (0..h - p.h).flatMap { y -> (0..w - p.w).map { x -> x to y } }

        for (item in order) {
            val p = pieceOf(item, hallway, w)
            if (p.w > w || p.h > h) { if (!item.decor) return null else continue }
            when (p.placement) {
                Placement.WALL_HUNG -> {
                    val taken = placed.filter { it.piece.placement == Placement.WALL_HUNG }
                    val spots = (0..w - p.w).filter { x ->
                        (x until x + p.w).none { it in doorFaceCols } &&
                            taken.none { t -> x < t.x + t.piece.w && t.x < x + p.w } &&
                            (x until x + p.w).any { free(it, 0) }
                    }
                    val best = spots.maxWithOrNull(compareBy<Int> { x -> taken.minOfOrNull { kotlin.math.abs(it.x - x) } ?: (w - kotlin.math.abs(2 * x + p.w - w)) }
                        .thenBy { x -> -kotlin.math.abs(2 * x + p.w - w) })
                    if (best != null) placed += Placed(item, best, -1, p) else if (!item.decor) return null
                }
                Placement.FLOOR -> {
                    if (placed.any { it.piece.placement == Placement.FLOOR }) continue
                    val cx = (w - p.w) / 2
                    val cy = if (hallway) 1 else (h - p.h) / 2
                    placed += Placed(item, cx, cy, p)
                }
                else -> {
                    val primary = when (p.placement) {
                        Placement.CORNER -> cornerCandidates(p)
                        Placement.CENTER -> if (hallway) emptyList() else centerCandidates(p)
                        Placement.NEAR_BED -> nearBedCandidates(p)
                        else -> emptyList()
                    }
                    // Wall pieces spread around the room (north wall preferred) instead of lining up in a row.
                    val northFirst = item.kind in NORTH_FIRST
                    val walls = wallCandidates(p).sortedByDescending { (x, y) ->
                        if (northFirst) wallBonus(x, y, p) * 10 + spread(x, y, p) else spread(x, y, p) * 4 + wallBonus(x, y, p)
                    }
                    val all = (primary + walls + anyCandidates(p)).distinct()
                    val spot = all.firstOrNull { (x, y) -> fitsBlocking(x, y, p) && connectedAfter(x, y, p) }
                    if (spot == null) { if (!item.decor) return null else continue }
                    for (yy in spot.second until spot.second + p.h) for (xx in spot.first until spot.first + p.w) blocked[yy][xx] = true
                    placed += Placed(item, spot.first, spot.second, p)
                }
            }
        }
        // Wall-hung and floor pieces come after every blocking piece (see the ordering), so their standing tiles stay free.
        return placed
    }

    /** A free interior tile next to the piece where a character stands to use it. */
    fun access(pl: Placed, w: Int, h: Int, isBlocked: (Int, Int) -> Boolean): Pair<Int, Int> {
        val p = pl.piece
        val cands = when (p.placement) {
            Placement.WALL_HUNG -> (pl.x until pl.x + p.w).map { it to 0 } + neighbours(pl.x, 0, Piece(p.w, 1, p.placement))
            Placement.FLOOR -> listOf((pl.x + p.w / 2) to (pl.y + p.h / 2)) + (pl.y until pl.y + p.h).flatMap { y -> (pl.x until pl.x + p.w).map { it to y } }
            else -> neighbours(pl.x, pl.y, p)
        }
        return cands.firstOrNull { (x, y) -> x in 0 until w && y in 0 until h && !isBlocked(x, y) } ?: (pl.x to (pl.y + p.h).coerceAtMost(h - 1))
    }

    /** Tiles orthogonally next to a footprint: below first (characters face up at furniture), then sides, then above. */
    private fun neighbours(x: Int, y: Int, p: Piece): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (xx in x until x + p.w) out += xx to (y + p.h)
        for (yy in y until y + p.h) { out += (x - 1) to yy; out += (x + p.w) to yy }
        for (xx in x until x + p.w) out += xx to (y - 1)
        return out
    }

    private val DIRS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    /** Pieces that belong on the north (back) wall, facing into the room. */
    private val NORTH_FIRST = setOf(
        FurnitureKind.FIREPLACE, FurnitureKind.STOVE, FurnitureKind.PIANO, FurnitureKind.SIDEBOARD, FurnitureKind.COUNTER,
        FurnitureKind.WRITING_DESK, FurnitureKind.WORKBENCH, FurnitureKind.CHINA_CABINET, FurnitureKind.SERVER, FurnitureKind.SINK,
    )
}
