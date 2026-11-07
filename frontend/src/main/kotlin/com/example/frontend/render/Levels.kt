package com.example.frontend.render

import com.example.model.FurnitureDto
import com.example.model.LevelDto
import com.example.model.TilePos
import com.example.model.WorldDto

/** Level lookups for a [WorldDto] (an empty [WorldDto.levels] means one level covering the whole grid). */
object Levels {
    fun of(world: WorldDto): List<LevelDto> =
        world.levels.ifEmpty { listOf(LevelDto(0, "Ground floor", 0, 0, world.width, world.height)) }

    fun contains(l: LevelDto, x: Int, y: Int): Boolean = x >= l.x && x < l.x + l.w && y >= l.y && y < l.y + l.h

    /** The level whose bounds contain (x, y), or null. */
    fun at(world: WorldDto, x: Int, y: Int): LevelDto? = of(world).firstOrNull { contains(it, x, y) }

    fun at(world: WorldDto, p: TilePos): LevelDto? = at(world, p.x, p.y)

    fun indexAt(world: WorldDto, p: TilePos): Int = at(world, p)?.index ?: 0

    fun byIndex(world: WorldDto, index: Int): LevelDto? = of(world).firstOrNull { it.index == index }

    fun ofFurniture(world: WorldDto, f: FurnitureDto): Int = at(world, f.x, f.y)?.index ?: 0

    /** A friendly default name when the backend gives none. */
    fun displayName(l: LevelDto): String = l.name.ifBlank {
        when {
            l.index == 0 -> "Ground floor"
            l.index == 1 -> "Upstairs"
            l.index == -1 -> "Basement"
            l.index > 1 -> "Floor ${l.index}"
            else -> "Basement ${-l.index}"
        }
    }
}

/**
 * Which level the map shows. Auto-follow shows the level of the agent that most recently changed activity
 * (or the user's level when no agent is active). A manual pick sticks until some character changes level
 * or there has been no input for [MANUAL_TIMEOUT_MS].
 */
class LevelFollower {
    var manual: Int? = null
        private set
    private var manualSignature: String? = null
    private var lastInputMs = 0L

    /** The level the map should show now. */
    fun shown(scene: Scene, nowMs: Long): Int {
        val world = scene.world ?: return 0
        val signature = levelSignature(scene, world)
        manual?.let { m ->
            if (signature != manualSignature || nowMs - lastInputMs > MANUAL_TIMEOUT_MS || Levels.byIndex(world, m) == null) {
                manual = null
            } else {
                return m
            }
        }
        return autoLevel(scene, world)
    }

    fun pick(level: Int, scene: Scene, nowMs: Long) {
        val world = scene.world ?: return
        manual = level
        manualSignature = levelSignature(scene, world)
        lastInputMs = nowMs
    }

    /** Any user input on the map: keeps a manual pick alive. */
    fun touch(nowMs: Long) { lastInputMs = nowMs }

    fun reset() { manual = null }

    private fun levelSignature(scene: Scene, world: WorldDto): String =
        scene.agents.values.joinToString(",") { "${it.sessionId}:${Levels.indexAt(world, it.tile)}" }

    private fun autoLevel(scene: Scene, world: WorldDto): Int {
        val active = scene.agents.values
            .filter { it.session.turnActive || it.session.activity.kind != com.example.model.ActivityKind.IDLE }
            .maxByOrNull { it.activityChangedLocal }
        active?.let { return Levels.indexAt(world, it.tile) }
        scene.user?.let { return Levels.indexAt(world, it.tile) }
        return Levels.of(world).minByOrNull { kotlin.math.abs(it.index) }?.index ?: 0
    }

    companion object {
        const val MANUAL_TIMEOUT_MS = 15_000L
    }
}
