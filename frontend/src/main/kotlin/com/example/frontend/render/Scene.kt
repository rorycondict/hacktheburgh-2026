package com.example.frontend.render

import com.example.model.ActivityKind
import com.example.model.FurnitureDto
import com.example.model.SessionDto
import com.example.model.TilePos
import com.example.model.WorldDto
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Facing { DOWN, UP, LEFT, RIGHT }

/** Anything that walks on the map. Positions are in tiles (fractional while walking). */
open class Walker(start: TilePos) {
    var x: Double = start.x.toDouble()
    var y: Double = start.y.toDouble()
    val path = ArrayDeque<TilePos>()
    var facing = Facing.DOWN
    /** Furniture the walker is heading to / using, if any. */
    var targetFurniture: FurnitureDto? = null

    /** Opacity, below 1 while fading out / in on a staircase. */
    var alpha: Float = 1f
        internal set
    internal var hopOut = 0.0
    internal var hopIn = 0.0

    val moving: Boolean get() = path.isNotEmpty()
    val tile: TilePos get() = TilePos(x.roundToInt(), y.roundToInt())
}

/** One character on the map, driven by one [SessionDto]. */
class Agent(val sessionId: String, var session: SessionDto, val slot: Int, start: TilePos) : Walker(start) {
    /** True when the agent's current target is the server terminal. */
    var atTerminal = false
    internal var activityKey: String? = null
    /** Local time when the agent was first seen idle (for the delayed idle icon). */
    var idleSinceLocal: Long = 0
    /** Local time of the agent's last activity change (for following it across levels). */
    var activityChangedLocal: Long = 0
        internal set
}

/** The human user's character: walks to the file open in their editor. */
class UserAvatar(start: TilePos) : Walker(start) {
    /** The normalised project-relative path of the open file, or null. */
    var filePath: String? = null
        internal set
    /** Local time of the last change of [filePath]. */
    var sinceLocal: Long = 0
        internal set
    internal var key: String? = null
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

    /** The user's character; null until there is a world. */
    var user: UserAvatar? = null
        private set
    private var userPath: String? = null
    private var userSince: Long = 0

    fun setWorld(w: WorldDto?) {
        if (w === world) return
        world = w
        grid = w?.let { WalkGrid(it) }
        furnitureByPath.clear()
        w?.furniture?.forEach { if (it.path.isNotEmpty() && !it.decor) furnitureByPath[it.path] = it }
        val g = grid ?: return
        for (a in agents.values) {
            a.path.clear()
            a.targetFurniture = null
            a.atTerminal = false
            a.activityKey = null // recompute targets against the new layout
            if (!g.walkable(a.tile)) place(a, spawnSlot(a.slot))
        }
        val u = user ?: UserAvatar(userHome()).also { user = it }
        u.path.clear()
        u.targetFurniture = null
        u.key = null
        if (!g.walkable(u.tile)) place(u, userHome())
        retargetUser()
    }

    /**
     * Moves the user's character to the file open in the editor: [path] is project-relative with '/'
     * separators (as [FurnitureDto.path]); null means no file is open, so the character waits near the spawn.
     */
    fun setUserPath(path: String?, nowMillis: Long) {
        val p = path?.let(::normalise)?.ifBlank { null }
        if (p != userPath) userSince = nowMillis
        userPath = p
        retargetUser()
    }

    private fun retargetUser() {
        val w = world ?: return
        val g = grid ?: return
        val u = user ?: return
        val p = userPath
        val key = "$p|${w.generatedAtMillis}"
        if (key == u.key) return
        u.key = key
        u.filePath = p
        u.sinceLocal = userSince
        val f = p?.let { furnitureFor(it) }
        u.targetFurniture = f
        val target = when {
            p == null -> userHome()
            f != null -> f.access
            else -> roomTileFor(p)
        } ?: return // unknown file: stay put
        if (!g.walkable(target)) return
        walkTo(u, g, target)
    }

    /** Where the user's character waits with no file open: a spot near the spawn, apart from the agents' first slots. */
    fun userHome(): TilePos {
        val w = world ?: return TilePos(0, 0)
        val near = grid?.nearby(w.spawn, 24).orEmpty()
        return near.lastOrNull() ?: w.spawn
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
            step(a, speedFor(a.session.effort), dt)
        }
        retargetUser()
        user?.let { step(it, USER_SPEED, dt) }
    }

    private fun step(a: Walker, speed: Double, dt: Double) {
        // Arriving on another level: fade in before walking on.
        if (a.hopIn > 0) {
            a.hopIn = (a.hopIn - dt).coerceAtLeast(0.0)
            a.alpha = (1 - a.hopIn / HOP_HALF_S).toFloat()
            if (a.hopIn > 0) return
        }
        var budget = speed * dt
        while (budget > 0 && a.path.isNotEmpty()) {
            val next = a.path.first()
            val dx = next.x - a.x
            val dy = next.y - a.y
            if (abs(dx) + abs(dy) > 1.5) {
                // A staircase portal: fade out here, then appear at the other end (no sliding across the void).
                a.hopOut += dt
                a.alpha = (1 - a.hopOut / HOP_HALF_S).toFloat().coerceIn(0f, 1f)
                if (a.hopOut >= HOP_HALF_S) {
                    a.x = next.x.toDouble()
                    a.y = next.y.toDouble()
                    a.path.removeFirst()
                    a.hopOut = 0.0
                    a.hopIn = HOP_HALF_S
                    a.alpha = 0f
                }
                return
            }
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
        if (a.path.isEmpty()) a.hopOut = 0.0
        if (a.hopOut == 0.0 && a.hopIn == 0.0) a.alpha = 1f
        if (arrived(a)) a.facing = Facing.UP
    }

    /** True when the walker is standing at its furniture / terminal target. */
    fun arrived(a: Walker): Boolean {
        if (a.moving) return false
        val t = arrivalTile(a) ?: return false
        return a.tile == t
    }

    private fun arrivalTile(a: Walker): TilePos? =
        a.targetFurniture?.access ?: if (a is Agent && a.atTerminal) world?.terminal else null

    private fun retarget(a: Agent, nowMs: Long) {
        val s = a.session
        val act = s.activity
        val waiting = s.pendingPermission != null || act.kind == ActivityKind.WAITING_PERMISSION
        val key = "${act.kind}|${act.path}|${act.toolCallId}|$waiting|${world?.generatedAtMillis}"
        if (key == a.activityKey) return
        a.activityKey = key
        a.activityChangedLocal = nowMs
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

    private fun walkTo(a: Walker, g: WalkGrid, target: TilePos) {
        val stepping = a.path.firstOrNull()
        val from = stepping ?: a.tile
        val route = g.path(from, target) ?: return // unreachable: stay
        a.path.clear()
        if (stepping != null) a.path.add(stepping)
        a.path.addAll(route)
    }

    private fun place(a: Walker, p: TilePos) {
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

        /** Half the stairs fade (out, then in), in seconds. */
        const val HOP_HALF_S = 0.25

        /** Tiles per second for the user's character. */
        const val USER_SPEED = 4.0

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
