package com.example.frontend.render

import com.example.frontend.render.CozyPalette.CREAM
import com.example.frontend.render.CozyPalette.WOOD
import com.example.frontend.render.CozyPalette.WOOD_LIGHT
import com.example.model.FurnitureDto
import com.example.model.LibraryDto
import java.awt.Rectangle
import java.awt.image.BufferedImage

/**
 * The dependency library's bookcases: a wooden case drawn over the furniture's footprint whose books are its
 * [FurnitureDto.libraries], one spine per library. Books of one group share a colour; dev/test-only libraries
 * get a pale band. [bookAt] maps a point on the case back to the library under it, for per-book hover and click.
 * Coordinates are world pixels relative to the furniture's top-left corner.
 */
internal object Bookcases {
    private const val T = Viewport.TILE
    private const val ROWS = 3
    private const val ROW_H = 4          // book height space per shelf
    private const val ROW_PITCH = 5      // books + plank
    private const val TOP = 1
    private const val LEFT = 2
    private const val SPINE = 2

    private val SPINES = intArrayOf(0xb5534a, 0x5e7f5a, 0x4f6d94, 0xc0883e, 0x7a5a8c, 0x3f7f80, 0x9a4e6e, 0x8a6a3a, 0x58628a, 0xa86b4c)
    private const val BACK = 0x4a3328

    private val cache = object : LinkedHashMap<Pair<List<LibraryDto>, Int>, BufferedImage>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<List<LibraryDto>, Int>, BufferedImage>?) = size > 64
    }

    fun isBookcase(f: FurnitureDto): Boolean = f.libraries.isNotEmpty()

    private fun perRow(w: Int): Int = (w * T - 2 * LEFT) / SPINE

    /** Books actually drawn (the case may list more than fit). */
    fun shown(f: FurnitureDto): Int = minOf(f.libraries.size, perRow(f.w) * ROWS)

    /** Rectangle of book [i] in case-local pixels. */
    private fun rect(f: FurnitureDto, i: Int): Rectangle {
        val per = perRow(f.w)
        val row = i / per
        val col = i % per
        val h = bookHeight(f.libraries[i])
        val top = TOP + row * ROW_PITCH
        return Rectangle(LEFT + col * SPINE, top + (ROW_H - h), SPINE, h)
    }

    /** The book's rectangle in world pixels, for the hover outline. */
    fun bookRect(f: FurnitureDto, i: Int): Rectangle = rect(f, i).apply { translate(f.x * T, f.y * T) }

    /** Index of the book at case-local (lx, ly), or null. A little vertical slack makes the 2px spines easier to hit. */
    fun bookAt(f: FurnitureDto, lx: Double, ly: Double): Int? {
        if (lx < LEFT || ly < TOP) return null
        val per = perRow(f.w)
        val col = ((lx - LEFT) / SPINE).toInt()
        val row = ((ly - TOP) / ROW_PITCH).toInt()
        if (col >= per || row >= ROWS) return null
        val i = row * per + col
        return i.takeIf { it < shown(f) }
    }

    fun image(f: FurnitureDto): BufferedImage = cache.getOrPut(f.libraries to f.w) { draw(f) }

    private fun draw(f: FurnitureDto): BufferedImage {
        val w = f.w * T
        val h = f.h * T
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val p = CozyFallbacks.Px(img)
        p.box(0, 0, w, h, WOOD)
        p.r(1, 1, w - 2, 1, WOOD_LIGHT)
        for (row in 0 until ROWS) {
            val top = TOP + row * ROW_PITCH
            p.r(LEFT, top, w - 2 * LEFT, ROW_H, BACK)
            if (row < ROWS - 1) p.r(1, top + ROW_H, w - 2, 1, WOOD_LIGHT)
        }
        for (i in 0 until shown(f)) {
            val lib = f.libraries[i]
            val r = rect(f, i)
            val c = colour(lib)
            p.r(r.x, r.y, 1, r.height, c)
            p.r(r.x + 1, r.y, 1, r.height, shade(c))
            if (lib.scope in PALE_SCOPES) p.r(r.x, r.y + 1, SPINE, 1, CREAM)
            else if (r.height >= 4) p.d(r.x, r.y + 1, lighten(c))
        }
        return img
    }

    private val PALE_SCOPES = setOf("test", "dev", "build", "compile-only", "annotation", "indirect")

    private fun key(l: LibraryDto) = l.group.ifEmpty { l.name }

    private fun colour(l: LibraryDto): Int = SPINES[Math.floorMod(key(l).hashCode(), SPINES.size)]

    private fun bookHeight(l: LibraryDto): Int = if (Math.floorMod(l.name.hashCode(), 3) == 0) ROW_H - 1 else ROW_H

    private fun shade(c: Int): Int = mix(c, 0x000000, 0.25)
    private fun lighten(c: Int): Int = mix(c, 0xffffff, 0.3)

    private fun mix(a: Int, b: Int, t: Double): Int {
        fun ch(s: Int) = (((a shr s) and 0xff) * (1 - t) + ((b shr s) and 0xff) * t).toInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
