package com.example.chatApp.ui

import javax.swing.*
import java.awt.*
import java.awt.event.*

class DrawingCanvas : JPanel() {
    private val strokes = mutableListOf<MutableList<Point>>()
    private var currentStroke = mutableListOf<Point>()

    init {
        background = Color.WHITE

        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                currentStroke = mutableListOf(e.point)
                strokes.add(currentStroke)
            }
        })

        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                currentStroke.add(e.point)
                repaint()
            }
        })
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)

        val g2 = g.create() as Graphics2D
        g2.color = Color.BLACK
        g2.stroke = BasicStroke(
            3f,
            BasicStroke.CAP_ROUND,
            BasicStroke.JOIN_ROUND
        )

        for (stroke in strokes) {
            for (i in 1 until stroke.size) {
                g2.drawLine(
                    stroke[i - 1].x,
                    stroke[i - 1].y,
                    stroke[i].x,
                    stroke[i].y
                )
            }
        }

        g2.dispose()
    }
}