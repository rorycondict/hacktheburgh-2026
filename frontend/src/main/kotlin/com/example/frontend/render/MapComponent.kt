package com.example.frontend.render

import com.example.model.FurnitureDto
import com.example.model.RoomDto
import com.example.model.SessionDto
import com.example.model.WorldDto
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.text.StringUtil
import java.awt.Color
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
 * Swing host for [MapRenderer]: integer-scaled map, ctrl+wheel zoom, drag to pan, double-click to reset,
 * hover tooltips, click on furniture/rooms. Animates at ~30 fps only while showing.
 * All methods must be called on the EDT.
 */
class MapComponent(atlas: Atlas, colorSlot: (String) -> Int) : JComponent(), Disposable {
    val scene = Scene(colorSlot)
    private val renderer = MapRenderer(atlas)

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

    private val timer = Timer(33) { tick() }

    init {
        isOpaque = true
        ToolTipManager.sharedInstance().registerComponent(this)
        addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) updateTimer()
        }
        val mouse = object : MouseAdapter() {
            private var pressAt: Point? = null
            private var panAtPress = 0 to 0
            private var dragged = false

            override fun mousePressed(e: MouseEvent) {
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
                    zoomSteps = 0; panX = 0; panY = 0
                    repaint()
                    return
                }
                hitFurniture(e)?.let { onFurnitureClicked(it); return }
                hitRoom(e)?.let { onRoomClicked(it) }
            }

            override fun mouseMoved(e: MouseEvent) {
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
                zoomSteps = (zoomSteps - e.wheelRotation.coerceIn(-1, 1)).coerceIn(-8, 8)
                repaint()
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
    }

    fun setWorld(world: WorldDto?) {
        scene.setWorld(world)
        hoverFurniture = null
        hoverRoom = null
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
            val view = Viewport.fit(world, devW, devH, zoomSteps, panX, panY)
            lastView = view
            renderer.labelFont = font ?: renderer.labelFont
            renderer.paint(g, world, scene, view, System.currentTimeMillis(), MapHighlights(hoverFurniture, hoverRoom, selectedPath))
        } finally {
            g.dispose()
        }
    }

    private fun drawCentered(g: Graphics2D, text: String, w: Int, h: Int) {
        if (text.isEmpty()) return
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = (font ?: g.font).deriveFont((13 * devScale).toFloat())
        g.color = Color(0xb3b5c4)
        val fm = g.fontMetrics
        g.drawString(text, (w - fm.stringWidth(text)) / 2, h / 2)
    }

    private fun tileUnder(e: MouseEvent) = lastView?.tileAt(e.x * devScale, e.y * devScale)

    private fun hitFurniture(e: MouseEvent): FurnitureDto? {
        val p = tileUnder(e) ?: return null
        return scene.world?.furniture?.firstOrNull { it.x == p.x && it.y == p.y }
    }

    private fun hitRoom(e: MouseEvent): RoomDto? {
        val p = tileUnder(e) ?: return null
        return scene.world?.rooms
            ?.filter { p.x >= it.x && p.x < it.x + it.w && p.y >= it.y && p.y < it.y + it.h }
            ?.minByOrNull { it.w * it.h }
    }

    override fun getToolTipText(e: MouseEvent): String? {
        val p = tileUnder(e) ?: return null
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
            return html("<b>${esc(f.path)}</b>", facts, f.summary.ifBlank { null }?.let { esc(it) }, "<i>Click to open</i>")
        }
        hitRoom(e)?.let { r ->
            return html(
                "<b>${esc(r.name)}</b>" + (r.path.takeIf { it.isNotEmpty() }?.let { " <span style='color:gray'>${esc(it)}/</span>" } ?: ""),
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
        val BACKGROUND = Color(0x121119)
    }
}
