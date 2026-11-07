package com.example.frontend.render

import com.example.frontend.render.CozyPalette.c
import com.example.model.ActivityKind
import com.example.model.FurnitureDto
import com.example.model.FurnitureKind
import com.example.model.RoomDto
import com.example.model.SessionDto
import com.example.model.TilePos
import com.example.model.Tiles
import com.example.model.WorldDto
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.RadialGradientPaint
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.abs
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
        fun fit(world: WorldDto, devW: Int, devH: Int, zoomSteps: Int, panX: Int, panY: Int): Viewport =
            fitRect(0, 0, world.width, world.height, devW, devH, zoomSteps, panX, panY)

        /** As [fit], for the tile rectangle (x, y, w, h), e.g. one level of the house. */
        fun fitRect(x: Int, y: Int, w: Int, h: Int, devW: Int, devH: Int, zoomSteps: Int, panX: Int, panY: Int): Viewport {
            val wPx = w * TILE
            val hPx = h * TILE
            val fit = min(devW.toDouble() / max(1, wPx), devH.toDouble() / max(1, hPx))
            val scale = if (fit >= 1.0) {
                max(1, floor(fit).toInt() + zoomSteps).toDouble()
            } else if (zoomSteps <= 0) {
                max(0.1, fit * Math.pow(1.25, zoomSteps.toDouble()))
            } else {
                zoomSteps.toDouble()
            }
            val ox = ((devW - wPx * scale) / 2 - x * TILE * scale).roundToInt() + panX
            val oy = ((devH - hPx * scale) / 2 - y * TILE * scale).roundToInt() + panY
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
 * Pure Java2D drawing of a [WorldDto] and the [Scene]'s characters, in a warm, cozy style: graded
 * sprites, baked room lighting ([CozyRooms]), soft shadows, speech-bubble activity icons and rounded
 * parchment labels. Sprites stay nearest-neighbour; only shadows, bubbles and text are antialiased.
 * No IntelliJ dependencies, so it can be driven headlessly (used to render previews).
 */
class MapRenderer(private val sprites: Sprites) {
    private var staticFor: WorldDto? = null
    private var staticLayer: BufferedImage? = null
    private val vignette: BufferedImage by lazy { makeVignette(256) }

    var labelFont: Font = Font(Font.SANS_SERIF, Font.BOLD, 12)

    fun paint(
        g0: Graphics2D,
        world: WorldDto,
        scene: Scene,
        view: Viewport,
        nowMs: Long,
        highlights: MapHighlights = MapHighlights(),
        /** The level to draw (index into [WorldDto.levels]); null draws the whole grid. */
        level: Int? = null,
    ) {
        val shownLevel = level?.let { Levels.byIndex(world, it) }
        fun onLevel(x: Int, y: Int) = shownLevel == null || Levels.contains(shownLevel, x, y)
        fun onLevel(w: Walker) = onLevel(w.tile.x, w.tile.y)
        val agents = scene.agents.values.filter { onLevel(it) }
        val user = scene.user?.takeIf { onLevel(it) }
        val furniture = world.furniture.filter { onLevel(it.x, it.y) }
        val rooms = world.rooms.filter { onLevel(it.x + it.w / 2, it.y + it.h / 2) }
        val sessions = scene.agents.values.map { it.session }
        val touched = sessions.flatMapTo(HashSet()) { s -> s.touchedFiles.map(Scene::normalise) }
        val failed = failedFiles(sessions)
        val serverFailed = sessions.any { it.activity.kind == ActivityKind.EXECUTE && it.activity.failed }
        val area = g0.clipBounds

        // Warm background behind the house (covers the host's own background).
        area?.let { g0.color = CozyPalette.BACKGROUND; g0.fill(it) }

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

            shownLevel?.let { g.clipRect(it.x * T - T, it.y * T - T, (it.w + 2) * T, (it.h + 2) * T) }
            g.drawImage(staticLayer(world), 0, 0, null)

            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // Footprint trail: soft little prints on the access tiles of furniture this session touched.
            for (a in agents) {
                val c = AgentColors.color(a.slot)
                g.color = Color(c.red, c.green, c.blue, 80)
                for (p in a.session.touchedFiles) {
                    val f = scene.furnitureFor(p) ?: continue
                    g.fill(Ellipse2D.Double(f.access.x * T + 4.0, f.access.y * T + 10.0, 3.0, 4.0))
                    g.fill(Ellipse2D.Double(f.access.x * T + 9.0, f.access.y * T + 7.0, 3.0, 4.0))
                }
            }

            // Warm glow under furniture whose file was touched (rose if its last call failed).
            for (f in furniture) {
                if (f.decor || !f.blocking || f.path.isEmpty() || f.kind == FurnitureKind.DESK) continue
                val col = when (f.path) {
                    in failed -> CozyPalette.ROSE
                    in touched -> CozyPalette.HONEY
                    else -> continue
                }
                val pulse = 0.75 + 0.25 * sin(nowMs / 500.0 + f.x)
                g.color = c(col, (90 * pulse).toInt())
                g.fill(Ellipse2D.Double(f.x * T - 1.0, (f.y + f.h) * T - 7.0, f.w * T + 2.0, 8.0))
            }

            // Standing furniture and characters, painter's order by their bottom row. Rugs and wall-hung pieces are
            // baked into the static layer.
            data class Drawable(val sortY: Double, val draw: () -> Unit)
            val drawables = ArrayList<Drawable>()
            for (f in furniture) {
                if (!f.blocking) continue
                drawables += Drawable((f.y + f.h - 1).toDouble()) {
                    val name = furnitureSprite(f, touched, failed, serverFailed, nowMs)
                    val img = sprites.furniture(name, f.w, f.h)
                    // Taller-than-footprint PNGs stand on the footprint's bottom edge.
                    g.drawImage(img, f.x * T, (f.y + f.h) * T - img.height, null)
                }
            }
            for (a in agents) drawables += Drawable(a.y + 0.01) {
                drawCharacter(g, a, AgentColors.tint(a.slot), agentSprite(scene, a, nowMs), a.slot, working = isWorking(scene, a), nowMs)
            }
            user?.let { u ->
                drawables += Drawable(u.y + 0.005) { drawCharacter(g, u, AgentColors.USER_TINT, userSprite(scene, u, nowMs), 11, false, nowMs) }
            }
            drawables.sortBy { it.sortY }
            drawables.forEach { it.draw() }

            // Hover / selection outlines: soft rounded frames.
            highlights.hoverRoom?.let { r ->
                g.stroke = BasicStroke(1f)
                g.color = c(CozyPalette.CREAM, 70)
                g.draw(RoundRectangle2D.Double(r.x * T + 0.5, r.y * T + 0.5, r.w * T - 1.0, r.h * T - 1.0, 6.0, 6.0))
            }
            val selected = highlights.selectedPath?.takeIf { it.isNotEmpty() }
            furniture.firstOrNull { selected != null && !it.decor && it.path == selected }?.let { softFrame(g, it.x * T, it.y * T, it.w * T, it.h * T, CozyPalette.SELECT, nowMs) }
            rooms.firstOrNull { selected != null && it.path == selected }?.let { r ->
                g.stroke = BasicStroke(1.5f)
                g.color = c(CozyPalette.HONEY, 200)
                g.draw(RoundRectangle2D.Double(r.x * T + 0.75, r.y * T + 0.75, r.w * T - 1.5, r.h * T - 1.5, 8.0, 8.0))
            }
            highlights.hoverFurniture?.let { softFrame(g, it.x * T, it.y * T, it.w * T, it.h * T, CozyPalette.HOVER, nowMs) }

            // Activity icons in little speech bubbles above heads (on top of everything).
            for (a in agents) {
                val icon = iconFor(a, nowMs) ?: continue
                if (a.alpha < 0.5f) continue
                drawBubble(g, sprites[icon], a, beside = scene.arrived(a), nowMs, a.slot, alert = icon == "icon_alert")
            }
            // The user gets a small floating heart while looking at a file.
            user?.let { u ->
                if (u.filePath != null && scene.arrived(u)) {
                    val bob = sin(nowMs / 300.0) * 1.0
                    val tx = AffineTransform(g.transform)
                    g.translate(u.x * T + T - 4.0, headTop(u) + 2.0 + bob)
                    g.drawImage(CozyFallbacks.heart, 0, 0, null)
                    g.transform = tx
                }
            }
        } finally {
            g.dispose()
        }

        // Gentle ambient vignette around the house: a small gradient image stretched smoothly over the world.
        run {
            val lx = shownLevel?.x ?: 0
            val ly = shownLevel?.y ?: 0
            val ww = (shownLevel?.w ?: world.width) * view.tilePx
            val wh = (shownLevel?.h ?: world.height) * view.tilePx
            val mx = ww * 0.12
            val my = wh * 0.12
            val vg = g0.create() as Graphics2D
            vg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            vg.drawImage(
                vignette, (view.originX + lx * view.tilePx - mx).roundToInt(), (view.originY + ly * view.tilePx - my).roundToInt(),
                (ww + 2 * mx).roundToInt(), (wh + 2 * my).roundToInt(), null,
            )
            vg.dispose()
        }

        // Text overlays in device space, so they stay sharp at any scale.
        val tg = g0.create() as Graphics2D
        try {
            tg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            tg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val fontPx = (view.tilePx * 0.5).coerceIn(9.0, 22.0).toFloat()
            val placed = ArrayList<Rectangle>()

            tg.font = labelFont.deriveFont(Font.BOLD, fontPx)
            for (r in rooms) drawRoomLabel(tg, r, view)?.let(placed::add)

            val tagFont = labelFont.deriveFont(Font.PLAIN, (fontPx * 0.8f).coerceAtLeast(9f))
            tg.font = tagFont
            val plates = ArrayList<() -> Unit>()
            val plateRects = ArrayList<Rectangle>()
            /** Places a name tag under its character, nudging it down when it would cover another tag. */
            fun place(w: Walker, text: String): Rectangle {
                val r = nameplateRect(tg, w, text, view)
                var tries = 0
                while (tries++ < 4 && plateRects.any { it.intersects(r) }) r.y += r.height + 1
                plateRects += r
                placed += r
                return r
            }
            user?.takeIf { it.alpha > 0.3f }?.let { u ->
                val r = place(u, "You")
                plates += { drawNameplate(tg, r, "You", AgentColors.USER_TINT, heart = true) }
            }
            for (a in agents) {
                if (a.alpha <= 0.3f) continue
                val text = agentTag(a)
                val r = place(a, text)
                plates += { drawNameplate(tg, r, text, AgentColors.tint(a.slot), heart = false) }
            }

            tg.font = labelFont.deriveFont(Font.PLAIN, (fontPx * 0.72f).coerceAtLeast(9f))
            drawFurnitureLabels(tg, furniture, scene, view, highlights, touched, placed)

            tg.font = tagFont
            plates.forEach { it() }
        } finally {
            tg.dispose()
        }
    }

    // ---- characters ----

    private fun isWorking(scene: Scene, a: Agent): Boolean {
        val kind = a.session.activity.kind
        return scene.arrived(a) && (kind == ActivityKind.EDIT || kind == ActivityKind.DELETE ||
            kind == ActivityKind.MOVE || kind == ActivityKind.EXECUTE)
    }

    private fun agentSprite(scene: Scene, a: Agent, nowMs: Long): String = when {
        a.moving -> sprites.characterFrame(a.facing, Pose.WALK, nowMs, a.slot)
        isWorking(scene, a) -> sprites.characterFrame(Facing.UP, Pose.WORK, nowMs, a.slot)
        scene.arrived(a) -> sprites.characterFrame(Facing.UP, Pose.IDLE, nowMs, a.slot)
        else -> sprites.characterFrame(a.facing, Pose.IDLE, nowMs, a.slot)
    }

    private fun userSprite(scene: Scene, u: UserAvatar, nowMs: Long): String = when {
        u.moving -> sprites.characterFrame(u.facing, Pose.WALK, nowMs, 11)
        scene.arrived(u) -> sprites.characterFrame(Facing.UP, Pose.IDLE, nowMs, 11)
        else -> sprites.characterFrame(u.facing, Pose.IDLE, nowMs, 11)
    }

    /** Height of the character sprites in world pixels (they stand on their tile and overlap the one above). */
    private val charHeight: Int get() = if (Sprites.PREFER_PNG_CHARACTER) T else CozyCharacter.H

    /** World-pixel y of the top of [w]'s head. */
    private fun headTop(w: Walker): Double = w.y * T + T - charHeight

    /**
     * Soft shadow, then the sprite anchored at the feet at its exact (sub-pixel) position, with a slow breathing
     * bob when idle; faded while taking the stairs.
     */
    private fun drawCharacter(g: Graphics2D, w: Walker, tint: Tint, sprite: String, phase: Int, working: Boolean, nowMs: Long) {
        if (w.alpha <= 0.01f) return
        val wx = w.x * T
        val wy = w.y * T
        val lift = when {
            w.moving -> if (Sprites.PREFER_PNG_CHARACTER) abs(sin(nowMs / 140.0 * PI / 2 + phase)) else 0.0
            working -> 0.0
            else -> if (sin(nowMs / 650.0 + phase * 1.7) > 0.5) 1.0 else 0.0 // slow breathing bob
        }
        val oldComposite = g.composite
        if (w.alpha < 1f) g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, w.alpha)
        // Shadow stays on the floor.
        g.color = c(CozyPalette.NIGHT, 85)
        g.fill(Ellipse2D.Double(wx + 3, wy + 13.2, 10.0, 4.0))
        g.color = c(CozyPalette.NIGHT, 45)
        g.fill(Ellipse2D.Double(wx + 1.5, wy + 12.6, 13.0, 5.0))

        val img = sprites.tinted(sprite, tint)
        val old = AffineTransform(g.transform)
        g.translate(wx + (T - img.width) / 2.0, wy + T - img.height + (if (Sprites.PREFER_PNG_CHARACTER) 0 else 1) - lift)
        g.drawImage(img, 0, 0, null)
        g.transform = old
        g.composite = oldComposite
    }

    private fun drawBubble(g: Graphics2D, icon: BufferedImage, a: Walker, beside: Boolean, nowMs: Long, phase: Int, alert: Boolean) {
        val bob = sin(nowMs / 260.0 + phase) * 0.8
        val bw = icon.width + 5.0
        val bh = icon.height + 5.0
        // At furniture, the bubble sits beside the head so the furniture state (screen, server LEDs) stays visible.
        val top = headTop(a)
        val bx = if (beside) a.x * T + T - 2.0 else a.x * T + T / 2.0 - bw / 2
        val by = if (beside) top - bh / 2 + 3 + bob else top - bh - 3 + bob
        val body = RoundRectangle2D.Double(bx, by, bw, bh, 6.0, 6.0)
        val tail = Path2D.Double().apply {
            if (beside) {
                moveTo(bx + 1.5, by + bh - 4); lineTo(bx - 2.5, by + bh + 1.5); lineTo(bx + 4.5, by + bh - 0.5)
            } else {
                val mx = bx + bw / 2
                moveTo(mx - 2.5, by + bh - 0.5); lineTo(mx, by + bh + 3); lineTo(mx + 2.5, by + bh - 0.5)
            }
            closePath()
        }
        // Soft drop shadow.
        g.color = c(CozyPalette.NIGHT, 60)
        g.fill(RoundRectangle2D.Double(bx + 0.8, by + 1.2, bw, bh, 6.0, 6.0))
        val fill = if (alert) c(0xfde9b8) else c(CozyPalette.CREAM)
        g.color = fill
        g.fill(body); g.fill(tail)
        g.stroke = BasicStroke(0.8f)
        g.color = c(if (alert) CozyPalette.WOOD else CozyPalette.INK_SOFT, 220)
        g.draw(body)
        g.color = fill
        // Re-fill just inside the outline where the tail joins, so the tail reads as part of the bubble.
        g.fill(RoundRectangle2D.Double(bx + 0.6, by + 0.6, bw - 1.2, bh - 1.2, 6.0, 6.0))
        g.fill(tail)
        val old = AffineTransform(g.transform)
        g.translate(bx + 2.5, by + 2.5)
        g.drawImage(icon, 0, 0, null)
        g.transform = old
    }

    private fun softFrame(g: Graphics2D, x: Int, y: Int, w: Int, h: Int, color: Color, nowMs: Long) {
        val pulse = 0.7 + 0.3 * sin(nowMs / 400.0)
        g.color = Color(color.red, color.green, color.blue, (50 * pulse).toInt())
        g.fill(RoundRectangle2D.Double(x - 1.0, y - 1.0, w + 2.0, h + 2.0, 5.0, 5.0))
        g.stroke = BasicStroke(1f)
        g.color = Color(color.red, color.green, color.blue, 220)
        g.draw(RoundRectangle2D.Double(x - 0.5, y - 0.5, w + 1.0, h + 1.0, 5.0, 5.0))
    }

    // ---- text overlays ----

    private fun agentTag(a: Agent): String {
        val name = a.session.agentName.ifBlank { a.sessionId.take(8) }
        val s = a.session
        val verb = if (s.pendingPermission != null) "needs you" else when (s.activity.kind) {
            ActivityKind.IDLE -> "idle"
            ActivityKind.THINKING -> "thinking"
            ActivityKind.MESSAGE -> "replying"
            ActivityKind.READ -> "reading"
            ActivityKind.EDIT -> "editing"
            ActivityKind.DELETE -> "deleting"
            ActivityKind.MOVE -> "moving"
            ActivityKind.SEARCH -> "searching"
            ActivityKind.EXECUTE -> "running"
            ActivityKind.FETCH -> "fetching"
            ActivityKind.OTHER -> "working"
            ActivityKind.WAITING_PERMISSION -> "needs you"
        }
        return "$name · $verb"
    }

    private fun nameplateRect(g: Graphics2D, w: Walker, text: String, view: Viewport): Rectangle {
        val fm = g.fontMetrics
        val dot = fm.ascent / 2 + 4
        val tw = fm.stringWidth(text) + 10 + dot
        val th = fm.height + 2
        val cx = view.originX + (w.x + 0.5) * view.tilePx
        val top = view.originY + (w.y + 1.0) * view.tilePx + 1
        return Rectangle((cx - tw / 2.0).roundToInt(), top.roundToInt(), tw, th)
    }

    private fun drawNameplate(g: Graphics2D, r: Rectangle, text: String, tint: Tint, heart: Boolean) {
        val fm = g.fontMetrics
        val arc = r.height.toDouble()
        g.color = c(CozyPalette.NIGHT, 90)
        g.fill(RoundRectangle2D.Double(r.x + 1.0, r.y + 2.0, r.width.toDouble(), r.height.toDouble(), arc, arc))
        g.color = c(CozyPalette.CREAM, 240)
        g.fill(RoundRectangle2D.Double(r.x.toDouble(), r.y.toDouble(), r.width.toDouble(), r.height.toDouble(), arc, arc))
        g.stroke = BasicStroke(1.5f)
        g.color = c(tint.shadow, 230)
        g.draw(RoundRectangle2D.Double(r.x + 0.75, r.y + 0.75, r.width - 1.5, r.height - 1.5, arc, arc))
        // Colour dot (a heart for the user).
        val d = fm.ascent / 2.0
        val dx = r.x + 6.0
        val dy = r.y + (r.height - d) / 2.0
        g.color = c(tint.main)
        if (heart) g.fill(heartShape(dx, dy, d)) else g.fill(Ellipse2D.Double(dx, dy, d, d))
        g.color = CozyPalette.PLAQUE_TEXT
        g.drawString(text, (dx + d + 4).roundToInt(), r.y + 1 + fm.ascent)
    }

    private fun heartShape(x: Double, y: Double, s: Double) = Path2D.Double().apply {
        moveTo(x + s / 2, y + s)
        curveTo(x - s * 0.1, y + s * 0.55, x, y - s * 0.05, x + s / 2, y + s * 0.3)
        curveTo(x + s, y - s * 0.05, x + s * 1.1, y + s * 0.55, x + s / 2, y + s)
        closePath()
    }

    /** Rounded parchment plaque on the room's face wall. Returns the area it covers. */
    private fun drawRoomLabel(g: Graphics2D, r: RoomDto, view: Viewport): Rectangle? {
        if (r.name.isBlank() || r.w < 3) return null
        val fm = g.fontMetrics
        val maxW = ((r.w - 2) * view.tilePx).toInt()
        val kindLabel = r.kind.label
        var label = if (kindLabel.isNotBlank() && !kindLabel.equals(r.name, ignoreCase = true)) "$kindLabel · ${r.name}" else r.name
        if (fm.stringWidth(label) + 14 > maxW) label = r.name
        label = truncate(fm, label, maxW - 14)
        val faceTop = view.originY + (r.y + 1) * view.tilePx
        val cx = view.originX + (r.x + r.w / 2.0) * view.tilePx
        val w = fm.stringWidth(label) + 14
        val h = fm.height + 2
        val x = (cx - w / 2.0).roundToInt()
        val y = (faceTop + (view.tilePx * 0.55 - h) / 2).roundToInt().coerceAtLeast(0)
        val arc = h * 0.6
        g.color = c(CozyPalette.NIGHT, 110)
        g.fill(RoundRectangle2D.Double(x + 1.5, y + 2.5, w.toDouble(), h.toDouble(), arc, arc))
        g.color = CozyPalette.PLAQUE_FILL
        g.fill(RoundRectangle2D.Double(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), arc, arc))
        g.color = c(CozyPalette.CREAM, 200)
        g.stroke = BasicStroke(1f)
        g.draw(RoundRectangle2D.Double(x + 2.0, y + 2.0, w - 4.0, h - 4.0, arc * 0.7, arc * 0.7))
        g.stroke = BasicStroke(1.6f)
        g.color = CozyPalette.PLAQUE_EDGE
        g.draw(RoundRectangle2D.Double(x + 0.8, y + 0.8, w - 1.6, h - 1.6, arc, arc))
        // Two little nails.
        g.color = c(CozyPalette.WOOD_DARK)
        val nail = max(2.0, h / 7.0)
        g.fill(Ellipse2D.Double(x + 4.0, y + h / 2.0 - nail / 2, nail, nail))
        g.fill(Ellipse2D.Double(x + w - 4.0 - nail, y + h / 2.0 - nail / 2, nail, nail))
        // Accent underline in the room kind's colour.
        g.color = c(CozyRooms.style(r.kind).accent, 200)
        g.fillRect(x + 8, y + h - 3, w - 16, 1)
        g.color = CozyPalette.PLAQUE_TEXT
        g.drawString(label, x + 7, y + 1 + fm.ascent)
        return Rectangle(x, y, w, h + 3)
    }

    /**
     * Small parchment captions under furniture. Hovered / selected furniture always gets one; others only when
     * zoomed in far enough, most relevant first, and only where they don't overlap another label.
     */
    private fun drawFurnitureLabels(
        g: Graphics2D, furniture: List<FurnitureDto>, scene: Scene, view: Viewport, highlights: MapHighlights,
        touched: Set<String>, placed: MutableList<Rectangle>,
    ) {
        val hover = highlights.hoverFurniture
        val selected = highlights.selectedPath
        val targets = HashSet<String>()
        scene.agents.values.forEach { a -> a.targetFurniture?.let { targets += it.path } }
        scene.user?.targetFurniture?.let { targets += it.path }
        val zoomedIn = view.tilePx >= 40
        val candidates = furniture
            .filter { !it.decor && it.kind != FurnitureKind.RUG && (it.label.isNotBlank() || it.path.isNotEmpty()) }
            .map { f ->
                val forced = f == hover || (selected != null && f.path == selected)
                val prio = when {
                    f == hover -> 0
                    forced -> 1
                    f.path in targets -> 2
                    f.path in touched -> 3
                    else -> 4
                }
                Triple(f, forced, prio)
            }
            .filter { it.second || zoomedIn }
            .sortedWith(compareBy<Triple<FurnitureDto, Boolean, Int>> { it.third }.thenByDescending { it.first.loc })
        val fm = g.fontMetrics
        for ((f, forced, _) in candidates) {
            val raw = f.label.ifBlank { f.path.substringAfterLast('/') }.ifBlank { if (f.kind == FurnitureKind.SERVER) "Server rack" else "" }
            if (raw.isBlank()) continue
            val maxW = if (forced) (view.tilePx * 8).toInt() else (view.tilePx * 2.6).toInt()
            val text = truncate(fm, raw, maxW)
            val w = fm.stringWidth(text) + 10
            val h = fm.height
            val cx = view.originX + (f.x + f.w / 2.0) * view.tilePx
            val y = (view.originY + (f.y + f.h) * view.tilePx - h * 0.45).roundToInt()
            val rect = Rectangle((cx - w / 2.0).roundToInt(), y, w, h)
            if (!forced && placed.any { it.intersects(rect) }) continue
            placed += rect
            val arc = h * 0.7
            g.color = c(CozyPalette.NIGHT, if (forced) 110 else 70)
            g.fill(RoundRectangle2D.Double(rect.x + 1.0, rect.y + 1.5, w.toDouble(), h.toDouble(), arc, arc))
            g.color = c(CozyPalette.PARCHMENT, if (forced) 245 else 215)
            g.fill(RoundRectangle2D.Double(rect.x.toDouble(), rect.y.toDouble(), w.toDouble(), h.toDouble(), arc, arc))
            g.stroke = BasicStroke(1f)
            g.color = c(if (forced) CozyPalette.HONEY else CozyPalette.WOOD, if (forced) 255 else 170)
            g.draw(RoundRectangle2D.Double(rect.x + 0.5, rect.y + 0.5, w - 1.0, h - 1.0, arc, arc))
            g.color = c(CozyPalette.INK, if (forced) 255 else 225)
            g.drawString(text, rect.x + 5, rect.y + fm.ascent)
        }
    }

    private fun truncate(fm: FontMetrics, text: String, maxW: Int): String {
        if (fm.stringWidth(text) <= maxW) return text
        var t = text
        while (t.length > 1 && fm.stringWidth("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    // ---- state ----

    private fun furnitureSprite(f: FurnitureDto, touched: Set<String>, failed: Set<String>, serverFailed: Boolean, nowMs: Long): String =
        when (f.kind) {
            FurnitureKind.DESK -> when {
                f.decor -> "obj_desk_off"
                f.path in failed -> "obj_desk_error"
                f.path in touched -> "obj_desk"
                else -> "obj_desk_off"
            }
            FurnitureKind.SERVER -> if (serverFailed) "obj_server_error" else sprites.frameName("obj_server_blink", nowMs)
            else -> "obj_" + f.kind.name.lowercase()
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
            ActivityKind.OTHER, ActivityKind.WAITING_PERMISSION -> null
        }
    }

    private fun staticLayer(world: WorldDto): BufferedImage {
        staticLayer?.let { if (staticFor === world) return it }
        val img = CozyRooms.bake(world, sprites)
        staticFor = world
        staticLayer = img
        return img
    }

    private companion object {
        const val T = Viewport.TILE
        const val IDLE_ICON_DELAY_MS = 3000L

        /** A soft warm-dark vignette: warm in the middle, fading to the background colour at the edges. */
        fun makeVignette(size: Int): BufferedImage {
            val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            val ctr = size / 2.0
            g.paint = RadialGradientPaint(
                Point2D.Double(ctr, ctr), (ctr * 1.42).toFloat(),
                floatArrayOf(0f, 0.45f, 0.75f, 1f),
                arrayOf(c(0xffe6b8, 12), c(CozyPalette.NIGHT, 0), c(CozyPalette.NIGHT, 90), c(CozyPalette.NIGHT, 200)),
            )
            g.composite = AlphaComposite.Src
            g.fillRect(0, 0, size, size)
            g.dispose()
            return img
        }
    }
}
