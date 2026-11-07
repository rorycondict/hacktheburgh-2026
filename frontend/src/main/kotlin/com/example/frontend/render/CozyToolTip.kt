package com.example.frontend.render

import java.awt.BasicStroke
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JToolTip

/**
 * A parchment-and-wood tooltip matching the map's room plaques. Use it from a component with
 * `override fun createToolTip(): JToolTip = CozyToolTip(this)`.
 */
class CozyToolTip(owner: JComponent) : JToolTip() {
    init {
        component = owner
        isOpaque = false
        background = CozyPalette.PLAQUE_FILL
        foreground = CozyPalette.PLAQUE_TEXT
        border = BorderFactory.createEmptyBorder(6, 9, 6, 9)
    }

    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width.toDouble()
            val h = height.toDouble()
            // Fill the corners too: heavyweight tooltip windows can't be transparent.
            g.color = CozyPalette.PLAQUE_FILL
            g.fillRect(0, 0, width, height)
            g.stroke = BasicStroke(2f)
            g.color = CozyPalette.PLAQUE_EDGE
            g.draw(RoundRectangle2D.Double(1.0, 1.0, w - 2, h - 2, 8.0, 8.0))
            g.stroke = BasicStroke(1f)
            g.color = CozyPalette.c(CozyPalette.CREAM, 200)
            g.draw(RoundRectangle2D.Double(3.5, 3.5, w - 7, h - 7, 6.0, 6.0))
        } finally {
            g.dispose()
        }
        // Text (HTML) on top, without the UI's own background.
        val ui = ui
        if (ui != null) ui.paint(g0, this)
    }
}
