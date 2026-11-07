package com.example.frontend.render

import com.example.model.TilePos
import com.example.model.WorldDto
import com.example.model.isWalkable

/**
 * Walkability for a world: [com.example.model.Tiles.isWalkable] tiles minus every tile covered by blocking
 * furniture. Staircases are portals: a stairs tile has an extra neighbour, its linked tile on the other level.
 */
class WalkGrid(val world: WorldDto) {
    val width = world.width
    val height = world.height
    private val blocked = BooleanArray(width * height)
    /** Stairs links: tile index to the tile index at the other end. */
    private val portals = HashMap<Int, Int>()

    init {
        for (y in 0 until height) for (x in 0 until width) blocked[y * width + x] = !world.isWalkable(x, y)
        for (f in world.furniture) {
            if (!f.blocking) continue
            for (yy in f.y until f.y + f.h) for (xx in f.x until f.x + f.w) if (inside(xx, yy)) blocked[yy * width + xx] = true
        }
        for (s in world.stairs) {
            if (!inside(s.a.x, s.a.y) || !inside(s.b.x, s.b.y)) continue
            val a = s.a.y * width + s.a.x
            val b = s.b.y * width + s.b.x
            portals[a] = b
            portals[b] = a
        }
    }

    /** The other end of the staircase at [p], if [p] is a stairs tile. */
    fun portal(p: TilePos): TilePos? = portals[p.y * width + p.x]?.let { TilePos(it % width, it / width) }

    fun inside(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height
    fun walkable(x: Int, y: Int): Boolean = inside(x, y) && !blocked[y * width + x]
    fun walkable(p: TilePos): Boolean = walkable(p.x, p.y)

    /** Shortest 4-connected path from [from] to [to], excluding [from]; null if unreachable. */
    fun path(from: TilePos, to: TilePos): List<TilePos>? {
        if (from == to) return emptyList()
        if (!walkable(to) || !inside(from.x, from.y)) return null
        val prev = IntArray(width * height) { UNSEEN }
        val start = from.y * width + from.x
        val goal = to.y * width + to.x
        val queue = ArrayDeque<Int>()
        queue.add(start)
        prev[start] = START
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur == goal) break
            val cx = cur % width
            val cy = cur / width
            for (d in DIRS) {
                val nx = cx + d.first
                val ny = cy + d.second
                if (!walkable(nx, ny)) continue
                val n = ny * width + nx
                if (prev[n] != UNSEEN) continue
                prev[n] = cur
                queue.add(n)
            }
            portals[cur]?.let { n ->
                if (prev[n] == UNSEEN && walkable(n % width, n / width)) { prev[n] = cur; queue.add(n) }
            }
        }
        if (prev[goal] == UNSEEN) return null
        val out = ArrayList<TilePos>()
        var c = goal
        while (c != start) {
            out.add(TilePos(c % width, c / width))
            c = prev[c]
        }
        out.reverse()
        return out
    }

    /** Up to [limit] walkable tiles in BFS order from [from] (including [from] if walkable). */
    fun nearby(from: TilePos, limit: Int): List<TilePos> {
        val seen = HashSet<TilePos>()
        val out = ArrayList<TilePos>()
        val queue = ArrayDeque<TilePos>()
        queue.add(from)
        seen.add(from)
        while (queue.isNotEmpty() && out.size < limit) {
            val p = queue.removeFirst()
            if (walkable(p)) out.add(p)
            for (d in DIRS) {
                val n = TilePos(p.x + d.first, p.y + d.second)
                if (!inside(n.x, n.y) || !seen.add(n)) continue
                if (walkable(n) || !walkable(p)) queue.add(n)
            }
        }
        return out
    }

    private companion object {
        const val UNSEEN = -2
        const val START = -1
        val DIRS = listOf(0 to 1, 1 to 0, 0 to -1, -1 to 0)
    }
}
