package com.example.frontend.render

import com.example.frontend.render.CozyPalette.AQUA
import com.example.frontend.render.CozyPalette.CLAY
import com.example.frontend.render.CozyPalette.CREAM
import com.example.frontend.render.CozyPalette.HONEY
import com.example.frontend.render.CozyPalette.INK
import com.example.frontend.render.CozyPalette.INK_SOFT
import com.example.frontend.render.CozyPalette.PARCHMENT
import com.example.frontend.render.CozyPalette.PARCHMENT_DARK
import com.example.frontend.render.CozyPalette.ROSE
import com.example.frontend.render.CozyPalette.ROSE_DARK
import com.example.frontend.render.CozyPalette.SAGE
import com.example.frontend.render.CozyPalette.SAGE_DARK
import com.example.frontend.render.CozyPalette.STONE
import com.example.frontend.render.CozyPalette.STONE_DARK
import com.example.frontend.render.CozyPalette.STONE_LIGHT
import com.example.frontend.render.CozyPalette.WOOD
import com.example.frontend.render.CozyPalette.WOOD_DARK
import com.example.frontend.render.CozyPalette.WOOD_LIGHT
import java.awt.image.BufferedImage

/**
 * Procedural pixel-art stand-ins, in [CozyPalette], for sprites that have no PNG. They are deliberately
 * simple; the hand-drawn PNG always wins when present. Hero fallbacks use [Sprites.BASE_TINT] for the
 * tunic, so per-agent recolouring still works on them.
 */
internal object CozyFallbacks {
    fun draw(name: String): BufferedImage {
        val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val p = Px(img)
        when {
            name == "tile_wall_top" -> wallTop(p)
            name == "tile_wall_face" -> wallFace(p)
            name == "tile_door" -> door(p)
            name == "tile_corridor" -> planks(p, STONE_LIGHT, STONE, horizontal = true)
            name == "tile_floor_wood" -> planks(p, WOOD_LIGHT, WOOD, horizontal = true)
            name == "tile_floor_tile" -> checker(p, PARCHMENT, PARCHMENT_DARK)
            name == "tile_floor_carpet" -> carpet(p)
            name == "obj_desk" -> desk(p, AQUA)
            name == "obj_desk_off" -> desk(p, INK_SOFT)
            name == "obj_desk_error" -> desk(p, 0xd5705c)
            name == "obj_bookshelf" -> bookshelf(p)
            name == "obj_cabinet" -> cabinet(p)
            name == "obj_test_bench" -> testBench(p)
            name == "obj_plant" -> plant(p)
            name.startsWith("obj_server") -> server(p, when (name) { "obj_server_1" -> SAGE; "obj_server_error" -> 0xd5705c; else -> HONEY })
            name.startsWith("hero_") -> hero(p, name)
            name.startsWith("icon_") -> icon(p, name)
            name.startsWith("obj_") && CozyFurniture.draw(p, name.removePrefix("obj_")) -> Unit
            else -> unknown(p)
        }
        return img
    }

    internal class Px(val img: BufferedImage) {
        /** Offset added to every coordinate (to place 16x16 art inside a bigger canvas). */
        var ox = 0
        var oy = 0
        fun r(x0: Int, y0: Int, w: Int, h: Int, rgb: Int) {
            val x = x0 + ox
            val y = y0 + oy
            for (yy in y until y + h) for (xx in x until x + w) {
                if (xx in 0 until img.width && yy in 0 until img.height) img.setRGB(xx, yy, 0xFF000000.toInt() or rgb)
            }
        }
        fun d(x: Int, y: Int, rgb: Int) = r(x, y, 1, 1, rgb)
        /** Filled ellipse-ish disc of radius [rad] centred on (cx, cy). */
        fun disc(cx: Int, cy: Int, rad: Int, rgb: Int) {
            for (yy in -rad..rad) for (xx in -rad..rad) if (xx * xx + yy * yy <= rad * rad + rad / 2) d(cx + xx, cy + yy, rgb)
        }
        /** Filled rect with a 1px ink outline and rounded (cut) corners. */
        fun box(x: Int, y: Int, w: Int, h: Int, fill: Int, edge: Int = INK) {
            r(x + 1, y, w - 2, h, edge); r(x, y + 1, w, h - 2, edge)
            r(x + 1, y + 1, w - 2, h - 2, fill)
        }
    }

    private fun wallTop(p: Px) {
        p.r(0, 0, 16, 16, 0x5a4038)
        p.r(0, 0, 16, 1, 0x6e5044)
        for (i in 0 until 16 step 4) p.d(i + 1, 8, 0x4d3732)
    }

    private fun wallFace(p: Px) {
        p.r(0, 0, 16, 16, 0xe9cfa8)
        // Soft vertical wallpaper stripes and a wooden skirting board.
        for (x in 1 until 16 step 4) p.r(x, 0, 1, 12, 0xddbf98)
        p.r(0, 0, 16, 1, 0xc9a77f)
        p.r(0, 12, 16, 4, WOOD)
        p.r(0, 12, 16, 1, WOOD_LIGHT)
        p.r(0, 15, 16, 1, WOOD_DARK)
    }

    private fun door(p: Px) {
        p.r(0, 0, 16, 16, WOOD_DARK)
        p.r(2, 1, 12, 15, WOOD)
        p.r(3, 2, 4, 6, WOOD_LIGHT); p.r(9, 2, 4, 6, WOOD_LIGHT)
        p.r(3, 9, 4, 6, WOOD_LIGHT); p.r(9, 9, 4, 6, WOOD_LIGHT)
        p.d(12, 9, HONEY)
    }

    private fun planks(p: Px, light: Int, dark: Int, horizontal: Boolean) {
        p.r(0, 0, 16, 16, light)
        for (row in 0 until 4) {
            val y = row * 4 + 3
            if (horizontal) p.r(0, y, 16, 1, dark)
            val seam = (row * 7 + 3) % 16
            p.r(seam, row * 4, 1, 3, dark)
        }
    }

    private fun checker(p: Px, a: Int, b: Int) {
        for (y in 0 until 16) for (x in 0 until 16) p.d(x, y, if ((x / 8 + y / 8) % 2 == 0) a else b)
        p.r(0, 0, 16, 1, b); p.r(0, 0, 1, 16, b)
    }

    private fun carpet(p: Px) {
        p.r(0, 0, 16, 16, ROSE)
        for (y in 0 until 16) for (x in 0 until 16) if ((x + y) % 6 == 0) p.d(x, y, ROSE_DARK)
        for (y in 0 until 16) for (x in 0 until 16) if ((x - y + 16) % 8 == 0 && (x + y) % 2 == 0) p.d(x, y, 0xe6aea4)
    }

    private fun desk(p: Px, screen: Int) {
        p.box(1, 7, 14, 6, WOOD_LIGHT)
        p.r(2, 12, 2, 4, WOOD_DARK); p.r(12, 12, 2, 4, WOOD_DARK)
        p.box(4, 1, 8, 7, INK_SOFT)
        p.r(5, 2, 6, 4, screen)
        p.r(7, 8, 2, 1, INK)
    }

    private fun bookshelf(p: Px) {
        p.box(1, 0, 14, 16, WOOD)
        val colors = intArrayOf(ROSE, SAGE, HONEY, 0x8fa6c4, CLAY, CREAM)
        for (shelf in 0 until 3) {
            val y = 2 + shelf * 5
            p.r(2, y + 3, 12, 1, WOOD_DARK)
            for (i in 0 until 5) p.r(3 + i * 2, y, 1 + (i % 2), 3, colors[(i + shelf * 2) % colors.size])
        }
    }

    private fun cabinet(p: Px) {
        p.box(2, 1, 12, 15, STONE_LIGHT)
        for (i in 0 until 3) {
            p.r(3, 2 + i * 5, 10, 4, PARCHMENT_DARK)
            p.r(7, 3 + i * 5, 2, 1, WOOD_DARK)
        }
    }

    private fun testBench(p: Px) {
        p.box(0, 8, 16, 5, WOOD_LIGHT)
        p.r(1, 12, 2, 4, WOOD_DARK); p.r(13, 12, 2, 4, WOOD_DARK)
        p.box(3, 3, 4, 6, CREAM); p.r(4, 5, 2, 3, SAGE)
        p.box(9, 2, 4, 7, CREAM); p.r(10, 5, 2, 3, ROSE)
    }

    private fun plant(p: Px) {
        p.box(4, 10, 8, 6, CLAY)
        p.r(5, 10, 6, 1, 0xe0a07e)
        p.box(3, 3, 5, 6, SAGE, SAGE_DARK)
        p.box(8, 2, 5, 7, SAGE, SAGE_DARK)
        p.box(5, 0, 6, 6, 0xa6c48f, SAGE_DARK)
    }

    private fun server(p: Px, led: Int) {
        p.box(2, 0, 12, 16, STONE_DARK)
        for (i in 0 until 4) {
            p.r(3, 2 + i * 3, 10, 2, 0x5f524b)
            p.d(11, 2 + i * 3, if (i % 2 == 0) led else SAGE)
        }
    }

    private fun hero(p: Px, name: String) {
        val main = Sprites.BASE_TINT.main
        val shade = Sprites.BASE_TINT.shadow
        val skin = 0xf3cfaa
        val hair = 0x6a4630
        val up = "_up_" in name
        val side = "_left_" in name || "_right_" in name
        val frame = name.last().digitToIntOrNull() ?: 0
        // Big round head (rows 0..9), small body (rows 10..15).
        p.box(3, 0, 10, 10, if (up) hair else skin)
        if (!up) {
            p.r(4, 1, 8, 3, hair)
            p.r(3, 2, 1, 4, hair); p.r(12, 2, 1, 4, hair)
            if (!side) { p.d(5, 6, INK); p.d(10, 6, INK); p.d(4, 7, 0xeaa594); p.d(11, 7, 0xeaa594) }
            else if ("_left_" in name) p.d(5, 6, INK) else p.d(10, 6, INK)
        }
        p.box(4, 9, 8, 5, main)
        p.r(5, 12, 6, 1, shade)
        val step = if (frame == 1) -1 else if (frame == 2) 1 else 0
        p.r(5 + step.coerceAtMost(0), 14, 2, 2, INK_SOFT)
        p.r(9 + step.coerceAtLeast(0), 14, 2, 2, INK_SOFT)
        if (name.startsWith("hero_work")) p.r(if (frame == 0) 3 else 11, 10, 2, 2, skin)
    }

    private fun icon(p: Px, name: String) {
        when (name) {
            "icon_alert" -> { p.r(7, 2, 2, 8, HONEY); p.r(7, 12, 2, 2, HONEY) }
            "icon_failed" -> for (i in 0 until 10) { p.r(3 + i, 3 + i, 2, 2, 0xd5705c); p.r(12 - i, 3 + i, 2, 2, 0xd5705c) }
            "icon_read" -> { p.box(2, 4, 6, 8, CREAM); p.box(8, 4, 6, 8, CREAM); p.r(4, 6, 3, 1, STONE); p.r(9, 6, 3, 1, STONE) }
            "icon_edit" -> for (i in 0 until 9) p.r(4 + i, 12 - i, 2, 2, if (i < 2) ROSE else HONEY)
            "icon_search" -> { p.box(2, 2, 8, 8, AQUA); p.r(9, 9, 2, 2, WOOD_DARK); p.r(11, 11, 3, 3, WOOD_DARK) }
            "icon_execute" -> { p.box(1, 3, 14, 10, INK_SOFT); p.r(3, 6, 2, 1, SAGE); p.r(5, 7, 2, 1, SAGE); p.r(3, 8, 2, 1, SAGE); p.r(8, 9, 4, 1, SAGE) }
            "icon_fetch" -> { p.box(2, 2, 12, 12, AQUA); p.r(7, 2, 2, 12, SAGE); p.r(2, 7, 12, 2, SAGE) }
            "icon_thought" -> { p.box(2, 3, 12, 8, CREAM); p.r(4, 12, 2, 2, CREAM); p.d(2, 14, CREAM); p.r(5, 6, 1, 1, STONE); p.r(8, 6, 1, 1, STONE); p.r(11, 6, 1, 1, STONE) }
            "icon_idle" -> { p.r(4, 4, 6, 1, CREAM); p.r(8, 5, 1, 1, CREAM); p.r(6, 6, 1, 1, CREAM); p.r(4, 7, 6, 1, CREAM); p.r(10, 9, 4, 1, CREAM); p.d(12, 10, CREAM); p.r(10, 11, 4, 1, CREAM) }
            else -> p.box(4, 4, 8, 8, HONEY)
        }
    }

    private fun unknown(p: Px) {
        for (y in 0 until 16) for (x in 0 until 16) p.d(x, y, if ((x / 4 + y / 4) % 2 == 0) PARCHMENT else PARCHMENT_DARK)
    }

    /** A tiny pixel heart (7x6), used above the user's character. */
    val heart: BufferedImage by lazy {
        val rows = listOf(".XX.XX.", "XHHXXXX", "XHXXXXX", ".XXXXX.", "..XXX..", "...X...")
        BufferedImage(7, 6, BufferedImage.TYPE_INT_ARGB).apply {
            for ((y, row) in rows.withIndex()) for ((x, ch) in row.withIndex()) {
                when (ch) {
                    'X' -> setRGB(x, y, 0xFF000000.toInt() or 0xe07a7a)
                    'H' -> setRGB(x, y, 0xFF000000.toInt() or 0xf6c3b8)
                }
            }
        }
    }
}
