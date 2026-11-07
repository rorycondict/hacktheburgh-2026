package com.example.frontend.render

import com.example.model.FurnitureDto
import com.example.model.RoomDto
import com.example.model.SessionDto
import com.example.model.WorldDto
import com.example.model.covers
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.util.ui.JBUI
import javax.swing.JButton
import javax.swing.JToolTip
import com.intellij.openapi.util.text.StringUtil
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Rectangle
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.AffineTransform
import javax.swing.JComponent
import javax.swing.Timer
import javax.swing.ToolTipManager
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Swing host for [MapRenderer]: integer-scaled map, ctrl+wheel zoom, drag to pan, double-click or the
 * floating "centre" button to reset,
 * hover tooltips, click on furniture/rooms. Shows one level (floor) of the house at a time, cross-fading
 * between them; a small floor selector appears when there is more than one level. Animates at ~30 fps only
 * while showing.
 * All methods must be called on the EDT.
 */
class MapComponent(sprites: Sprites, colorSlot: (String) -> Int) : JComponent(), Disposable {
    val scene = Scene(colorSlot)
    private val renderer = MapRenderer(sprites)

    var onFurnitureClicked: (FurnitureDto) -> Unit = {}
    var onRoomClicked: (RoomDto) -> Unit = {}
    var emptyText: String = ""
        set(value) { field = value; repaint() }
    var selectedPath: String? = null
        set(value) { field = value; repaint() }

    private var zoomSteps = 0
    private var panX = 0
    private var panY = 0
    private var devScale = 1.0
    private var lastView: Viewport? = null
    private var hoverFurniture: FurnitureDto? = null
    private var hoverRoom: RoomDto? = null
    private var lastTick = System.currentTimeMillis()
    private var disposed = false

    /** Which level is shown (auto-follow or a manual pick) and the cross-fade between levels. */
    private val follower = LevelFollower()
    private var shownLevel: Int? = null
    private var fadeFrom: Int? = null
    private var fadeStart = 0L
    /** Floor selector pills, in component coordinates, with their level index. */
    private var pills: List<Pair<Rectangle, Int>> = emptyList()

    private val timer = Timer(33) { tick() }

    /** Floating button in the top-right corner: back to the centred, unzoomed view. */
    private val centerButton = JButton(AllIcons.General.Locate).apply {
        toolTipText = "Reset to the centre of the map"
        isFocusable = false
        isRolloverEnabled = true
        putClientProperty("JButton.buttonType", "square")
        margin = JBUI.insets(2)
        addActionListener { resetView() }
    }

    init {
        isOpaque = true
        layout = null
        add(centerButton)
        ToolTipManager.sharedInstance().registerComponent(this)
        addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) updateTimer()
        }
        val mouse = object : MouseAdapter() {
            private var pressAt: Point? = null
            private var panAtPress = 0 to 0
            private var dragged = false

            override fun mousePressed(e: MouseEvent) {
                val now = System.currentTimeMillis()
                follower.touch(now)
                pills.firstOrNull { it.first.contains(e.point) }?.let { (_, level) ->
                    follower.pick(level, scene, now)
                    pressAt = null
                    dragged = true // swallow the click
                    repaint()
                    return
                }
                pressAt = e.point
                panAtPress = panX to panY
                dragged = false
            }

            override fun mouseDragged(e: MouseEvent) {
                val p = pressAt ?: return
                val dx = e.x - p.x
                val dy = e.y - p.y
                if (!dragged && abs(dx) + abs(dy) < 4) return
                dragged = true
                cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                panX = panAtPress.first + (dx * devScale).roundToInt()
                panY = panAtPress.second + (dy * devScale).roundToInt()
                repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                pressAt = null
                cursor = Cursor.getDefaultCursor()
            }

            override fun mouseClicked(e: MouseEvent) {
                if (dragged || e.button != MouseEvent.BUTTON1) return
                if (e.clickCount == 2 && hitFurniture(e) == null) {
                    resetView()
                    return
                }
                hitFurniture(e)?.let { onFurnitureClicked(it); return }
                hitRoom(e)?.let { onRoomClicked(it) }
            }

            override fun mouseMoved(e: MouseEvent) {
                follower.touch(System.currentTimeMillis())
                val f = hitFurniture(e)
                val r = if (f == null) hitRoom(e) else null
                if (f != hoverFurniture || r != hoverRoom) {
                    hoverFurniture = f
                    hoverRoom = r
                    cursor = if (f != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
                    repaint()
                }
            }

            override fun mouseExited(e: MouseEvent) {
                hoverFurniture = null
                hoverRoom = null
                repaint()
            }

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                if (!e.isControlDown && !e.isMetaDown) {
                    parent?.dispatchEvent(javax.swing.SwingUtilities.convertMouseEvent(this@MapComponent, e, parent))
                    return
                }
                follower.touch(System.currentTimeMillis())
                zoomSteps = (zoomSteps - e.wheelRotation.coerceIn(-1, 1)).coerceIn(-8, 8)
                repaint()
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
    }

    /** Centres the map, resets zoom and pan, and goes back to following the agents between levels. */
    fun resetView() {
        zoomSteps = 0; panX = 0; panY = 0
        follower.reset()
        repaint()
    }

    override fun doLayout() {
        val d = centerButton.preferredSize
        val gap = JBUI.scale(8)
        centerButton.setBounds(width - d.width - gap, gap, d.width, d.height)
    }

    fun setWorld(world: WorldDto?) {
        scene.setWorld(world)
        hoverFurniture = null
        hoverRoom = null
        repaint()
    }

    /** Where the user is in the code (the file selected in their editor), drawn as their own character. */
    fun setUserPath(path: String?) {
        scene.setUserPath(path, System.currentTimeMillis())
        repaint()
    }

    fun setSessions(sessions: List<SessionDto>) {
        scene.setSessions(sessions, System.currentTimeMillis())
        repaint()
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        scene.update(now - lastTick, now)
        lastTick = now
        repaint()
    }

    private fun updateTimer() {
        if (!disposed && isShowing) {
            lastTick = System.currentTimeMillis()
            if (!timer.isRunning) timer.start()
        } else {
            timer.stop()
        }
    }

    override fun dispose() {
        disposed = true
        timer.stop()
        ToolTipManager.sharedInstance().unregisterComponent(this)
    }

    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        try {
            g.color = BACKGROUND
            g.fillRect(0, 0, width, height)
            // Work in device pixels so integer scaling stays crisp on HiDPI.
            val t = g.transform
            devScale = t.scaleX.takeIf { it > 0 } ?: 1.0
            g.transform = AffineTransform.getTranslateInstance(t.translateX, t.translateY)
            val devW = (width * devScale).roundToInt()
            val devH = (height * devScale).roundToInt()
            val world = scene.world
            if (world == null || world.width == 0 || world.height == 0) {
                lastView = null
                drawCentered(g, emptyText, devW, devH)
                return
            }
            val now = System.currentTimeMillis()
            val levels = Levels.of(world)
            val target = follower.shown(scene, now).takeIf { Levels.byIndex(world, it) != null } ?: levels.first().index
            val current = shownLevel
            if (current == null || Levels.byIndex(world, current) == null) {
                shownLevel = target
                fadeFrom = null
            } else if (target != current) {
                fadeFrom = current
                fadeStart = now
                shownLevel = target
            }
            val level = shownLevel ?: target
            val view = viewFor(world, level, devW, devH)
            lastView = view
            renderer.labelFont = font ?: renderer.labelFont
            val highlights = MapHighlights(hoverFurniture, hoverRoom, selectedPath)
            val from = fadeFrom
            val fadeT = ((now - fadeStart) / FADE_MS.toDouble()).coerceIn(0.0, 1.0)
            if (from != null && fadeT < 1.0 && devW > 0 && devH > 0) {
                // Cross-fade: draw both levels offscreen and blend.
                val a = BufferedImage(devW, devH, BufferedImage.TYPE_INT_ARGB)
                val b = BufferedImage(devW, devH, BufferedImage.TYPE_INT_ARGB)
                for ((img, lv) in listOf(a to from, b to level)) {
                    val ig = img.createGraphics()
                    ig.setClip(0, 0, devW, devH)
                    renderer.paint(ig, world, scene, viewFor(world, lv, devW, devH), now, highlights, lv)
                    ig.dispose()
                }
                g.drawImage(a, 0, 0, null)
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fadeT.toFloat())
                g.drawImage(b, 0, 0, null)
                g.composite = AlphaComposite.SrcOver
            } else {
                fadeFrom = null
                renderer.paint(g, world, scene, view, now, highlights, level)
            }
            drawLevelPicker(g0 as Graphics2D, world, level)
        } finally {
            g.dispose()
        }
    }

    private fun viewFor(world: WorldDto, level: Int, devW: Int, devH: Int): Viewport {
        val l = Levels.byIndex(world, level) ?: return Viewport.fit(world, devW, devH, zoomSteps, panX, panY)
        return Viewport.fitRect(l.x, l.y, l.w, l.h, devW, devH, zoomSteps, panX, panY)
    }

    /** Stacked pill buttons, top level first, with a dot per character on each level. Component coordinates. */
    private fun drawLevelPicker(g0: Graphics2D, world: WorldDto, shown: Int) {
        val levels = Levels.of(world).sortedByDescending { it.index }
        if (levels.size < 2) { pills = emptyList(); return }
        val g = g0.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = (font ?: g.font).deriveFont(Font.BOLD, JBUI.scaleFontSize(11f).toFloat())
            val fm = g.fontMetrics
            val gap = JBUI.scale(8)
            val dotD = JBUI.scale(6)
            val padX = JBUI.scale(9)
            val h = fm.height + JBUI.scale(6)
            val dotsFor = levels.associate { l ->
                val cols = ArrayList<Color>()
                scene.user?.let { if (Levels.contains(l, it.tile.x, it.tile.y)) cols += AgentColors.userColor }
                scene.agents.values.forEach { if (Levels.contains(l, it.tile.x, it.tile.y)) cols += AgentColors.color(it.slot) }
                l.index to cols
            }
            val w = levels.maxOf { l -> fm.stringWidth(Levels.displayName(l)) + (dotsFor[l.index]!!.size.coerceAtMost(6)) * (dotD + JBUI.scale(2)) } + padX * 2 + JBUI.scale(4)
            var y = centerButton.y + centerButton.height + gap
            val x = width - gap - w
            val out = ArrayList<Pair<Rectangle, Int>>()
            for (l in levels) {
                val r = Rectangle(x, y, w, h)
                out += r to l.index
                val active = l.index == shown
                val arc = h.toDouble()
                g.color = CozyPalette.c(CozyPalette.NIGHT, 110)
                g.fill(RoundRectangle2D.Double(r.x + 1.0, r.y + 2.0, r.width.toDouble(), r.height.toDouble(), arc, arc))
                g.color = if (active) CozyPalette.c(CozyPalette.HONEY) else CozyPalette.c(CozyPalette.PARCHMENT, 235)
                g.fill(RoundRectangle2D.Double(r.x.toDouble(), r.y.toDouble(), r.width.toDouble(), r.height.toDouble(), arc, arc))
                g.stroke = BasicStroke(JBUI.scale(1).toFloat() * 1.5f)
                g.color = CozyPalette.c(if (active) CozyPalette.WOOD_DARK else CozyPalette.WOOD, 230)
                g.draw(RoundRectangle2D.Double(r.x + 0.75, r.y + 0.75, r.width - 1.5, r.height - 1.5, arc, arc))
                g.color = CozyPalette.PLAQUE_TEXT
                g.drawString(Levels.displayName(l), r.x + padX, r.y + (h - fm.height) / 2 + fm.ascent)
                var dx = r.x + r.width - padX - dotD
                for (c in dotsFor[l.index]!!.take(6)) {
                    g.color = c
                    g.fill(Ellipse2D.Double(dx.toDouble(), r.y + (h - dotD) / 2.0, dotD.toDouble(), dotD.toDouble()))
                    g.color = CozyPalette.c(CozyPalette.INK_SOFT, 200)
                    g.stroke = BasicStroke(1f)
                    g.draw(Ellipse2D.Double(dx.toDouble(), r.y + (h - dotD) / 2.0, dotD.toDouble(), dotD.toDouble()))
                    dx -= dotD + JBUI.scale(2)
                }
                y += h + JBUI.scale(4)
            }
            pills = out
        } finally {
            g.dispose()
        }
    }

    private fun drawCentered(g: Graphics2D, text: String, w: Int, h: Int) {
        if (text.isEmpty()) return
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = (font ?: g.font).deriveFont((13 * devScale).toFloat())
        g.color = CozyPalette.c(CozyPalette.PARCHMENT_DARK)
        val fm = g.fontMetrics
        g.drawString(text, (w - fm.stringWidth(text)) / 2, h / 2)
    }

    /** The tile under the mouse, only if it lies on the shown level. */
    private fun tileUnder(e: MouseEvent): com.example.model.TilePos? {
        val p = lastView?.tileAt(e.x * devScale, e.y * devScale) ?: return null
        val world = scene.world ?: return null
        val l = shownLevel?.let { Levels.byIndex(world, it) } ?: return p
        return p.takeIf { Levels.contains(l, it.x, it.y) }
    }

    private fun hitFurniture(e: MouseEvent): FurnitureDto? {
        val p = tileUnder(e) ?: return null
        return scene.world?.furniture?.firstOrNull { it.covers(p.x, p.y) && !it.decor }
    }

    private fun hitRoom(e: MouseEvent): RoomDto? {
        val p = tileUnder(e) ?: return null
        return scene.world?.rooms
            ?.filter { p.x >= it.x && p.x < it.x + it.w && p.y >= it.y && p.y < it.y + it.h }
            ?.minByOrNull { it.w * it.h }
    }

    override fun createToolTip(): JToolTip = CozyToolTip(this)

    override fun getToolTipText(e: MouseEvent): String? {
        pills.firstOrNull { it.first.contains(e.point) }?.let { (_, level) ->
            val l = scene.world?.let { Levels.byIndex(it, level) } ?: return null
            return html("<b>${esc(Levels.displayName(l))}</b>", "Click to show this floor")
        }
        val p = tileUnder(e) ?: return null
        scene.user?.takeIf { it.tile == p }?.let { u ->
            return html("<b>You</b>", u.filePath?.let { "Looking at ${esc(it)}" } ?: "No file open")
        }
        scene.agents.values.firstOrNull { it.tile == p }?.let { a ->
            val s = a.session
            val act = s.activity
            return html(
                "<b>${esc(s.agentName)}</b>" + (s.model?.let { " &middot; ${esc(it)}" } ?: ""),
                esc(act.kind.name.lowercase().replace('_', ' ')) + (act.title.takeIf { it.isNotBlank() }?.let { ": " + esc(it) } ?: ""),
                act.path?.let { esc(it) },
            )
        }
        hitFurniture(e)?.let { f ->
            if (f.path.isEmpty()) return html("<b>Server rack</b>", "Agents come here to run commands.")
            val facts = listOfNotNull(f.language.ifBlank { null }, "${f.loc} LOC", "churn ${f.churn} (30 days)").joinToString(" &middot; ") { esc(it) }
            return html(
                "<b>${esc(f.label.ifBlank { f.path })}</b>",
                if (f.label.isNotBlank()) "<span style='color:gray'>${esc(f.path)}</span>" else null,
                facts, f.summary.ifBlank { null }?.let { esc(it) }, "<i>Click to open</i>",
            )
        }
        hitRoom(e)?.let { r ->
            return html(
                "<b>${esc(r.name)}</b> &middot; ${esc(r.kind.label)}" + (r.path.takeIf { it.isNotEmpty() }?.let { " <span style='color:gray'>${esc(it)}/</span>" } ?: ""),
                "<i>${esc(r.kind.meaning)}</i>",
                "${r.fileCount} files &middot; ${r.loc} LOC",
                r.summary.ifBlank { null }?.let { esc(it) },
            )
        }
        return null
    }

    private fun html(vararg lines: String?): String {
        val present = lines.filterNotNull()
        val style = if (present.any { it.length > 80 }) " style='width:320px'" else ""
        return "<html><body$style>" + present.joinToString("<br>") + "</body></html>"
    }

    private fun esc(s: String) = StringUtil.escapeXmlEntities(s)

    private companion object {
        val BACKGROUND = CozyPalette.BACKGROUND
        const val FADE_MS = 400L
    }
}
