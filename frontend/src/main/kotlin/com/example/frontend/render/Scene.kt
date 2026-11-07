package com.example.frontend.render

import com.example.model.ActivityKind
import com.example.model.FurnitureDto
import com.example.model.SessionDto
import com.example.model.TilePos
import com.example.model.WorldDto
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Facing { DOWN, UP, LEFT, RIGHT }

/** One character on the map, driven by one [SessionDto]. Positions are in tiles (fractional while walking). */
class Agent(val sessionId: String, var session: SessionDto, val slot: Int, start: TilePos) {
    var x: Double = start.x.toDouble()
    var y: Double = start.y.toDouble()
    val path = ArrayDeque<TilePos>()
    var facing = Facing.DOWN
    /** Furniture the agent is heading to / using, if any. */
    var targetFurniture: FurnitureDto? = null
    /** True when the agent's current target is the server terminal. */
    var atTerminal = false
    internal var activityKey: String? = null
    /** Local time when the agent was first seen idle (for the delayed idle icon). */
    var idleSinceLocal: Long = 0

    val moving: Boolean get() = path.isNotEmpty()
    val tile: TilePos get() = TilePos(x.roundToInt(), y.roundToInt())
}

/**
 * Character simulation: one [Agent] per session, BFS pathing to the target that the
 * session's activity implies (see "Activity → animation" in docs/implementation-plan.md).
 */
class Scene(private val colorSlot: (String) -> Int) {
    var world: WorldDto? = null
        private set
    var grid: WalkGrid? = null
        private set
    private val furnitureByPath = HashMap<String, FurnitureDto>()
    val agents = LinkedHashMap<String, Agent>()

    fun setWorld(w: WorldDto?) {
        if (w === world) return
        world = w
        grid = w?.let { WalkGrid(it) }
        furnitureByPath.clear()
        w?.furniture?.forEach { if (it.path.isNotEmpty()) furnitureByPath[it.path] = it }
        val g = grid ?: return
        for (a in agents.values) {
            a.path.clear()
            a.targetFurniture = null
            a.atTerminal = false
            a.activityKey = null // recompute targets against the new layout
            if (!g.walkable(a.tile)) place(a, spawnSlot(a.slot))
        }
    }

    fun setSessions(sessions: List<SessionDto>, nowMs: Long) {
        val ids = sessions.mapTo(HashSet()) { it.sessionId }
        agents.keys.retainAll(ids)
        for (s in sessions) {
            val a = agents.getOrPut(s.sessionId) { Agent(s.sessionId, s, colorSlot(s.sessionId), spawnSlot(colorSlot(s.sessionId))) }
            a.session = s
            retarget(a, nowMs)
        }
    }

    /** Advances walking by [dtMs]. */
    fun update(dtMs: Long, nowMs: Long) {
        val dt = (dtMs.coerceIn(0, 250)) / 1000.0
        for (a in agents.values) {
            retarget(a, nowMs)
            var budget = speedFor(a.session.effort) * dt
            while (budget > 0 && a.path.isNotEmpty()) {
                val next = a.path.first()
                val dx = next.x - a.x
                val dy = next.y - a.y
                if (abs(dx) > 0.001 || abs(dy) > 0.001) {
                    a.facing = when {
                        abs(dx) > abs(dy) -> if (dx > 0) Facing.RIGHT else Facing.LEFT
                        else -> if (dy > 0) Facing.DOWN else Facing.UP
                    }
                }
                val dist = abs(dx) + abs(dy)
                if (dist <= budget) {
                    a.x = next.x.toDouble()
                    a.y = next.y.toDouble()
                    a.path.removeFirst()
                    budget -= dist
                } else {
                    a.x += dx.coerceIn(-budget, budget)
                    a.y += dy.coerceIn(-budget, budget)
                    budget = 0.0
                }
            }
            if (!a.moving && (a.targetFurniture != null || a.atTerminal) && a.tile == arrivalTile(a)) a.facing = Facing.UP
        }
    }

    /** True when the agent is standing at its furniture / terminal target. */
    fun arrived(a: Agent): Boolean = !a.moving && (a.targetFurniture != null || a.atTerminal) && a.tile == arrivalTile(a)

    private fun arrivalTile(a: Agent): TilePos? = a.targetFurniture?.access ?: if (a.atTerminal) world?.terminal else null

    private fun retarget(a: Agent, nowMs: Long) {
        val s = a.session
        val act = s.activity
        val waiting = s.pendingPermission != null || act.kind == ActivityKind.WAITING_PERMISSION
        val key = "${act.kind}|${act.path}|${act.toolCallId}|$waiting|${world?.generatedAtMillis}"
        if (key == a.activityKey) return
        a.activityKey = key
        if (act.kind == ActivityKind.IDLE) a.idleSinceLocal = nowMs
        val g = grid ?: return
        val w = world ?: return

        if (waiting) {
            // Stop: finish the step in progress, then stand still.
            val next = a.path.firstOrNull()
            a.path.clear()
            if (next != null) a.path.add(next)
            return
        }
        a.targetFurniture = null
        a.atTerminal = false
        val target: TilePos? = when (act.kind) {
            ActivityKind.READ, ActivityKind.EDIT, ActivityKind.DELETE, ActivityKind.MOVE -> {
                val f = act.path?.let { furnitureFor(it) }
                a.targetFurniture = f
                f?.access ?: act.path?.let { roomTileFor(it) }
            }
            ActivityKind.EXECUTE -> w.terminal.also { a.atTerminal = true }
            ActivityKind.IDLE -> spawnSlot(a.slot)
            else -> null // SEARCH, FETCH, THINKING, MESSAGE, OTHER: stay
        }
        if (target == null || !g.walkable(target)) return
        walkTo(a, g, target)
    }

    private fun walkTo(a: Agent, g: WalkGrid, target: TilePos) {
        val stepping = a.path.firstOrNull()
        val from = stepping ?: a.tile
        val route = g.path(from, target) ?: return // unreachable: stay
        a.path.clear()
        if (stepping != null) a.path.add(stepping)
        a.path.addAll(route)
    }

    private fun place(a: Agent, p: TilePos) {
        a.x = p.x.toDouble()
        a.y = p.y.toDouble()
    }

    /** The furniture for a file path, if it is shown. */
    fun furnitureFor(path: String): FurnitureDto? = furnitureByPath[normalise(path)]

    /** First free interior tile of the room whose path is the longest prefix of [path]. Absolute paths → null. */
    fun roomTileFor(path: String): TilePos? {
        val w = world ?: return null
        val g = grid ?: return null
        val p = normalise(path)
        if (isAbsolute(p)) return null
        val room = w.rooms
            .filter { r -> r.path.isEmpty() || p == r.path || p.startsWith(r.path + "/") }
            .maxByOrNull { it.path.length } ?: return null
        for (yy in room.y + 2 until room.y + room.h - 1) for (xx in room.x + 1 until room.x + room.w - 1) {
            if (g.walkable(xx, yy)) return TilePos(xx, yy)
        }
        return null
    }

    /** Where an idle agent with colour [slot] waits: the slot-th walkable tile near the spawn. */
    fun spawnSlot(slot: Int): TilePos {
        val w = world ?: return TilePos(0, 0)
        val near = grid?.nearby(w.spawn, 24).orEmpty()
        return if (near.isEmpty()) w.spawn else near[Math.floorMod(slot, near.size)]
    }

    companion object {
        fun normalise(path: String): String = path.replace('\\', '/').removePrefix("./")
        fun isAbsolute(path: String): Boolean =
            path.startsWith("/") || (path.length > 1 && path[1] == ':')

        /** Tiles per second by reasoning effort. */
        fun speedFor(effort: String?): Double {
            val e = effort?.lowercase() ?: return 3.5
            return when {
                "min" in e || "low" in e -> 2.5
                "high" in e || "max" in e -> 5.0
                else -> 3.5
            }
        }
    }
}
