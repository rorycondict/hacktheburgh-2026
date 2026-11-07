package com.example.frontend.render

import com.example.model.ActivityKind
import com.example.model.FurnitureDto
import com.example.model.FurnitureKind
import com.example.model.RoomDto
import com.example.model.SessionDto
import com.example.model.TilePos
import com.example.model.Tiles
import com.example.model.WorldDto
import com.example.model.tileAt
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Where the world is drawn inside the component, in device pixels. */
data class Viewport(val originX: Int, val originY: Int, val scale: Double) {
    val tilePx: Double get() = TILE * scale

    fun tileAt(devX: Double, devY: Double): TilePos =
        TilePos(floor((devX - originX) / tilePx).toInt(), floor((devY - originY) / tilePx).toInt())

    companion object {
        const val TILE = 16

        /**
         * Integer nearest-neighbour scale that fits the world (fractional only when the world is
         * bigger than the view at 1x), plus [zoomSteps] integer steps and a pan offset.
         */
        fun fit(world: WorldDto, devW: Int, devH: Int, zoomSteps: Int, panX: Int, panY: Int): Viewport {
            val wPx = world.width * TILE
            val hPx = world.height * TILE
            val fit = min(devW.toDouble() / max(1, wPx), devH.toDouble() / max(1, hPx))
            val scale = if (fit >= 1.0) {
                max(1, floor(fit).toInt() + zoomSteps).toDouble()
            } else if (zoomSteps <= 0) {
                max(0.1, fit * Math.pow(1.25, zoomSteps.toDouble()))
            } else {
                zoomSteps.toDouble()
            }
            val ox = ((devW - wPx * scale) / 2).roundToInt() + panX
            val oy = ((devH - hPx * scale) / 2).roundToInt() + panY
            return Viewport(ox, oy, scale)
        }
    }
}

/** What the map should highlight, from the HUD/mouse. */
data class MapHighlights(
    val hoverFurniture: FurnitureDto? = null,
    val hoverRoom: RoomDto? = null,
    val selectedPath: String? = null,
)

/**
 * Pure Java2D drawing of a [WorldDto] and the [Scene]'s agents. No IntelliJ dependencies, so it can be
 * driven headlessly (used to render previews).
 */
class MapRenderer(private val atlas: Atlas) {
    private var staticFor: WorldDto? = null
    private var staticLayer: BufferedImage? = null

    var labelFont: Font = Font(Font.SANS_SERIF, Font.BOLD, 12)

    fun paint(
        g0: Graphics2D,
        world: WorldDto,
        scene: Scene,
        view: Viewport,
        nowMs: Long,
        highlights: MapHighlights = MapHighlights(),
    ) {
        val sessions = scene.agents.values.map { it.session }
        val touched = sessions.flatMapTo(HashSet()) { s -> s.touchedFiles.map(Scene::normalise) }
        val failed = failedFiles(sessions)
        val serverFailed = sessions.any { it.activity.kind == ActivityKind.EXECUTE && it.activity.failed }

        val g = g0.create() as Graphics2D
        try {
            val integer = view.scale == floor(view.scale)
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                if (integer) RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR else RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
            g.translate(view.originX, view.originY)
            g.scale(view.scale, view.scale)

            g.drawImage(staticLayer(world), 0, 0, null)

            // Footprint trail: soft marks on the access tiles of furniture this session touched.
            for (a in scene.agents.values) {
                val c = AgentColors.color(a.slot)
                g.color = Color(c.red, c.green, c.blue, 70)
                for (p in a.session.touchedFiles) {
                    val f = scene.furnitureFor(p) ?: continue
                    g.fillRect(f.access.x * T + 3, f.access.y * T + 11, 4, 3)
                    g.fillRect(f.access.x * T + 9, f.access.y * T + 8, 4, 3)
                }
            }

            // Furniture and agents, painter's order by row.
            data class Drawable(val sortY: Double, val draw: () -> Unit)
            val drawables = ArrayList<Drawable>()
            for (f in world.furniture) drawables += Drawable(f.y.toDouble()) {
                val name = furnitureSprite(f, touched, failed, serverFailed, nowMs)
                g.drawImage(atlas[name], f.x * T, f.y * T, null)
            }
            for (a in scene.agents.values) drawables += Drawable(a.y + 0.01) { drawAgent(g, scene, a, nowMs) }
            drawables.sortBy { it.sortY }
            drawables.forEach { it.draw() }

            // Hover / selection outlines.
            g.stroke = BasicStroke(1f)
            highlights.hoverRoom?.let { r ->
                g.color = Color(255, 255, 255, 60)
                g.drawRect(r.x * T, r.y * T, r.w * T - 1, r.h * T - 1)
            }
            world.furniture.firstOrNull { highlights.selectedPath != null && it.path == highlights.selectedPath }?.let {
                g.color = Color(0xf2c53d)
                g.drawRect(it.x * T, it.y * T, T - 1, T - 1)
            }
            world.rooms.firstOrNull { highlights.selectedPath != null && it.path == highlights.selectedPath && it.path.isNotEmpty() }?.let {
                g.color = Color(0xf2c53d)
                g.drawRect(it.x * T, it.y * T, it.w * T - 1, it.h * T - 1)
            }
            highlights.hoverFurniture?.let {
                g.color = Color.WHITE
                g.drawRect(it.x * T, it.y * T, T - 1, T - 1)
            }

            // Icons above heads (pixel art, world space, on top of everything).
            for (a in scene.agents.values) {
                val icon = iconFor(a, nowMs) ?: continue
                val img = atlas[icon]
                val bob = (sin(nowMs / 220.0 + a.slot) * 1.2).roundToInt()
                // At furniture, the icon sits beside the head so the furniture state (screen, server LEDs) stays visible.
                val beside = scene.arrived(a)
                val ix = if (beside) (a.x * T + T - 3).roundToInt() else (a.x * T + T / 2.0 - img.width / 2.0).roundToInt()
                val iy = if (beside) (a.y * T - img.height / 2.0 + bob).roundToInt() else (a.y * T - img.height - 1 + bob).roundToInt()
                g.drawImage(img, ix, iy, null)
            }
        } finally {
            g.dispose()
        }

        // Text overlays in device space, so they stay sharp at any scale.
        val tg = g0.create() as Graphics2D
        try {
            tg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val fontPx = (view.tilePx * 0.5).coerceIn(9.0, 22.0).toFloat()
            tg.font = labelFont.deriveFont(fontPx)
            for (r in world.rooms) drawRoomLabel(tg, r, view)
            tg.font = labelFont.deriveFont(Font.PLAIN, (fontPx * 0.85f).coerceAtLeast(9f))
            for (a in scene.agents.values) drawNameplate(tg, a, view)
        } finally {
            tg.dispose()
        }
    }

    private fun drawAgent(g: Graphics2D, scene: Scene, a: Agent, nowMs: Long) {
        val px = (a.x * T).roundToInt()
        val py = (a.y * T).roundToInt()
        // Shadow
        val oldComposite = g.composite
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f)
        g.color = Color.BLACK
        g.fillOval(px + 3, py + 13, 10, 4)
        g.composite = oldComposite

        val kind = a.session.activity.kind
        val name = when {
            a.moving -> atlas.frameName("hero_walk_" + a.facing.name.lowercase(), nowMs + a.slot * 70L)
            scene.arrived(a) && (kind == ActivityKind.EDIT || kind == ActivityKind.DELETE ||
                kind == ActivityKind.MOVE || kind == ActivityKind.EXECUTE) -> atlas.frameName("hero_work", nowMs + a.slot * 90L)
            scene.arrived(a) -> "hero_up_0"
            else -> "hero_" + a.facing.name.lowercase() + "_0"
        }
        g.drawImage(atlas.tinted(name, AgentColors.tint(a.slot)), px, py, null)
    }

    private fun drawNameplate(g: Graphics2D, a: Agent, view: Viewport) {
        val text = a.session.agentName.ifBlank { a.sessionId.take(8) }
        val fm = g.fontMetrics
        val cx = view.originX + (a.x + 0.5) * view.tilePx
        val top = view.originY + (a.y + 1.0) * view.tilePx + 1
        val w = fm.stringWidth(text) + 6
        val h = fm.height
        val x = (cx - w / 2.0).roundToInt()
        val y = top.roundToInt()
        g.color = Color(20, 20, 30, 190)
        g.fillRoundRect(x, y, w, h, 6, 6)
        g.color = AgentColors.color(a.slot).brighter()
        g.drawString(text, x + 3, y + fm.ascent)
    }

    private fun drawRoomLabel(g: Graphics2D, r: RoomDto, view: Viewport) {
        val text = r.name
        if (text.isBlank() || r.w < 3) return
        val fm = g.fontMetrics
        val maxW = ((r.w - 2) * view.tilePx).toInt()
        var label = text
        while (label.length > 1 && fm.stringWidth(label) > maxW) label = label.dropLast(2) + "…"
        val faceTop = view.originY + (r.y + 1) * view.tilePx
        val cx = view.originX + (r.x + r.w / 2.0) * view.tilePx
        val w = fm.stringWidth(label) + 8
        val h = fm.height
        val x = (cx - w / 2.0).roundToInt()
        val y = (faceTop + (view.tilePx * 0.35 - h) / 2).roundToInt().coerceAtLeast(0)
        g.color = Color(43, 41, 56, 220)
        g.fillRect(x, y, w, h)
        g.color = Color(0xf2c53d)
        g.drawRect(x, y, w - 1, h - 1)
        g.color = Color(0xf4f1e8)
        g.drawString(label, x + 4, y + fm.ascent)
    }

    private fun furnitureSprite(f: FurnitureDto, touched: Set<String>, failed: Set<String>, serverFailed: Boolean, nowMs: Long): String =
        when (f.kind) {
            FurnitureKind.DESK -> when {
                f.path in failed -> "obj_desk_error"
                f.path in touched -> "obj_desk"
                else -> "obj_desk_off"
            }
            FurnitureKind.BOOKSHELF -> "obj_bookshelf"
            FurnitureKind.CABINET -> "obj_cabinet"
            FurnitureKind.TEST_BENCH -> "obj_test_bench"
            FurnitureKind.PLANT -> "obj_plant"
            FurnitureKind.SERVER -> if (serverFailed) "obj_server_error" else atlas.frameName("obj_server_blink", nowMs)
        }

    /** Files whose most recent tool call (per session) failed, or that the current failed activity targets. */
    private fun failedFiles(sessions: List<SessionDto>): Set<String> {
        val out = HashSet<String>()
        for (s in sessions) {
            val last = HashMap<String, String>()
            for (c in s.recentToolCalls) c.path?.let { last[Scene.normalise(it)] = c.status }
            last.filterValues { it == "failed" }.keys.forEach(out::add)
            if (s.activity.failed) s.activity.path?.let { out += Scene.normalise(it) }
        }
        return out
    }

    private fun iconFor(a: Agent, nowMs: Long): String? {
        val s = a.session
        val act = s.activity
        if (s.pendingPermission != null || act.kind == ActivityKind.WAITING_PERMISSION) return "icon_alert"
        if (act.failed) return "icon_failed"
        return when (act.kind) {
            ActivityKind.READ -> "icon_read"
            ActivityKind.EDIT, ActivityKind.DELETE, ActivityKind.MOVE -> "icon_edit"
            ActivityKind.SEARCH -> "icon_search"
            ActivityKind.EXECUTE -> "icon_execute"
            ActivityKind.FETCH -> "icon_fetch"
            ActivityKind.THINKING, ActivityKind.MESSAGE -> "icon_thought"
            ActivityKind.IDLE -> if (!a.moving && nowMs - a.idleSinceLocal > IDLE_ICON_DELAY_MS) "icon_idle" else null
            else -> null
        }
    }

    private fun staticLayer(world: WorldDto): BufferedImage {
        staticLayer?.let { if (staticFor === world) return it }
        val img = BufferedImage(max(1, world.width * T), max(1, world.height * T), BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        for (y in 0 until world.height) for (x in 0 until world.width) {
            val name = tileSprite(world.tileAt(x, y)) ?: continue
            g.drawImage(atlas[name], x * T, y * T, null)
        }
        g.dispose()
        staticFor = world
        staticLayer = img
        return img
    }

    private fun tileSprite(c: Char): String? = when (c) {
        Tiles.WALL -> "tile_wall_top"
        Tiles.FACE -> "tile_wall_face"
        Tiles.DOOR -> "tile_door"
        Tiles.CORRIDOR -> "tile_corridor"
        Tiles.FLOOR_WOOD -> "tile_floor_wood"
        Tiles.FLOOR_TILE -> "tile_floor_tile"
        Tiles.FLOOR_CARPET -> "tile_floor_carpet"
        else -> null
    }

    private companion object {
        const val T = Viewport.TILE
        const val IDLE_ICON_DELAY_MS = 3000L
    }
}
