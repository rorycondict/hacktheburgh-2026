package com.example.frontend.render

import com.example.frontend.render.CozyFallbacks.Px
import com.example.frontend.render.CozyPalette.AQUA
import com.example.frontend.render.CozyPalette.CLAY
import com.example.frontend.render.CozyPalette.CREAM
import com.example.frontend.render.CozyPalette.DUSK
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

/**
 * Procedural 16x16 stand-ins for the themed furniture kinds (`obj_<kind>`), used until a PNG exists.
 * Returns false for names it does not know.
 */
internal object CozyFurniture {
    private const val GRASS = 0xa6c48f
    private const val MINT = 0xbfe0cf
    private const val IRON = 0x6e625c
    private const val FIRE = 0xf0a65a

    fun draw(p: Px, kind: String): Boolean {
        when (kind) {
            // Living room
            "sofa" -> {
                p.box(1, 4, 14, 6, ROSE); p.box(0, 8, 16, 6, ROSE)
                p.r(2, 9, 5, 3, 0xe6aea4); p.r(9, 9, 5, 3, 0xe6aea4)
                p.r(1, 13, 2, 3, WOOD_DARK); p.r(13, 13, 2, 3, WOOD_DARK)
            }
            "armchair" -> {
                p.box(3, 2, 10, 8, SAGE); p.box(1, 7, 14, 7, SAGE)
                p.r(4, 8, 8, 3, GRASS)
                p.r(2, 13, 2, 3, WOOD_DARK); p.r(12, 13, 2, 3, WOOD_DARK)
            }
            "fireplace" -> {
                p.box(0, 1, 16, 15, STONE_LIGHT); p.r(0, 1, 16, 2, WOOD)
                p.box(3, 6, 10, 10, INK_SOFT)
                p.r(5, 11, 6, 4, CLAY); p.r(6, 9, 4, 4, FIRE); p.r(7, 8, 2, 3, HONEY)
                p.r(4, 14, 8, 1, WOOD_DARK)
            }
            "tv" -> { // wall-mounted
                p.box(1, 2, 14, 10, INK_SOFT); p.r(2, 3, 12, 8, DUSK); p.r(3, 4, 4, 2, AQUA); p.r(9, 8, 4, 2, 0x7a96b4)
                p.r(7, 12, 2, 1, INK_SOFT)
            }
            // Kitchen
            "stove" -> {
                p.box(1, 2, 14, 14, STONE_LIGHT); p.r(2, 3, 12, 4, STONE_DARK)
                p.disc(5, 5, 1, CLAY); p.disc(11, 5, 1, CLAY)
                p.box(3, 8, 10, 6, INK_SOFT); p.r(4, 10, 8, 1, FIRE)
            }
            "counter" -> {
                p.box(0, 4, 16, 12, WOOD); p.r(0, 4, 16, 3, CREAM); p.r(1, 5, 14, 1, PARCHMENT)
                p.r(7, 8, 1, 7, WOOD_DARK); p.r(4, 10, 2, 1, HONEY); p.r(10, 10, 2, 1, HONEY)
            }
            "fridge" -> {
                p.box(2, 0, 12, 16, CREAM); p.r(3, 6, 10, 1, PARCHMENT_DARK)
                p.r(11, 2, 1, 3, STONE); p.r(11, 8, 1, 4, STONE); p.r(4, 2, 2, 2, ROSE); p.r(6, 9, 2, 2, SAGE)
            }
            "dining_table" -> {
                p.box(0, 5, 16, 6, WOOD_LIGHT); p.r(1, 6, 14, 1, 0xe6b87e)
                p.r(2, 10, 2, 6, WOOD_DARK); p.r(12, 10, 2, 6, WOOD_DARK)
                p.box(5, 2, 6, 4, CREAM); p.r(6, 3, 4, 1, ROSE)
            }
            // Workshop
            "workbench" -> {
                p.box(0, 6, 16, 5, WOOD); p.r(1, 7, 14, 1, WOOD_LIGHT)
                p.r(1, 10, 2, 6, WOOD_DARK); p.r(13, 10, 2, 6, WOOD_DARK); p.r(3, 13, 10, 1, WOOD_DARK)
                p.r(3, 4, 5, 2, STONE); p.r(10, 3, 1, 3, WOOD_DARK); p.r(9, 3, 3, 1, IRON)
            }
            "tool_rack" -> { // pegboard on the wall
                p.box(1, 1, 14, 12, WOOD_LIGHT)
                for (yy in 3 until 12 step 3) for (xx in 3 until 14 step 3) p.d(xx, yy, WOOD)
                p.r(3, 3, 1, 7, WOOD_DARK); p.r(2, 3, 3, 2, IRON)
                p.r(7, 3, 1, 8, WOOD_DARK); p.r(6, 9, 3, 2, IRON)
                p.r(11, 3, 2, 6, CLAY); p.r(11, 9, 2, 2, IRON)
            }
            "anvil" -> {
                p.box(1, 5, 14, 4, IRON); p.r(2, 5, 12, 1, STONE)
                p.box(5, 8, 6, 4, IRON); p.box(3, 11, 10, 5, WOOD_DARK)
            }
            // Study
            "writing_desk" -> {
                p.box(0, 6, 16, 5, WOOD); p.r(1, 7, 14, 1, WOOD_LIGHT)
                p.r(1, 10, 2, 6, WOOD_DARK); p.r(13, 10, 2, 6, WOOD_DARK)
                p.box(3, 4, 6, 4, CREAM); p.r(4, 5, 4, 1, STONE)
                p.r(11, 2, 1, 5, INK_SOFT); p.r(10, 6, 3, 1, INK)
            }
            "globe" -> {
                p.disc(8, 6, 5, DUSK); p.r(5, 4, 3, 3, SAGE); p.r(9, 7, 3, 2, SAGE)
                p.r(8, 12, 1, 2, WOOD_DARK); p.box(4, 13, 8, 3, WOOD)
            }
            "filing_cabinet" -> {
                p.box(2, 0, 12, 16, SAGE_DARK)
                for (i in 0 until 3) { p.r(3, 1 + i * 5, 10, 4, SAGE); p.r(6, 2 + i * 5, 4, 1, PARCHMENT) }
            }
            // Gallery
            "easel" -> {
                p.r(4, 2, 1, 14, WOOD_DARK); p.r(11, 2, 1, 14, WOOD_DARK); p.r(7, 1, 2, 15, WOOD)
                p.box(2, 2, 12, 9, CREAM); p.r(4, 5, 3, 3, ROSE); p.r(8, 4, 3, 4, SAGE); p.r(5, 8, 6, 1, HONEY)
            }
            "painting" -> {
                p.box(1, 2, 14, 11, HONEY); p.r(2, 3, 12, 9, 0x9fc4d6)
                p.r(2, 9, 12, 3, SAGE); p.disc(10, 5, 1, CREAM); p.r(4, 7, 3, 2, SAGE_DARK)
            }
            "statue" -> {
                p.disc(8, 3, 2, STONE_LIGHT); p.box(5, 5, 6, 7, STONE_LIGHT); p.r(6, 6, 1, 5, STONE)
                p.box(3, 11, 10, 5, PARCHMENT_DARK)
            }
            // Utility room
            "boiler" -> {
                p.box(3, 1, 10, 15, CLAY); p.r(4, 2, 2, 13, 0xe0a07e)
                p.disc(9, 6, 2, CREAM); p.d(9, 6, INK); p.r(13, 9, 3, 2, IRON)
            }
            "pipes" -> {
                p.r(2, 0, 3, 16, IRON); p.r(3, 0, 1, 16, STONE)
                p.r(2, 7, 14, 3, IRON); p.r(2, 8, 14, 1, STONE)
                p.r(10, 0, 3, 8, IRON); p.disc(11, 4, 2, CLAY)
            }
            "washer" -> {
                p.box(2, 1, 12, 15, CREAM); p.r(3, 2, 10, 2, PARCHMENT_DARK); p.d(11, 2, SAGE)
                p.disc(8, 10, 4, STONE); p.disc(8, 10, 3, 0x9fc4d6); p.r(6, 9, 2, 1, CREAM)
            }
            // Lab
            "chalkboard" -> { // wall-mounted
                p.box(0, 1, 16, 12, WOOD); p.r(1, 2, 14, 10, 0x4f6b5a)
                p.r(3, 4, 5, 1, CREAM); p.r(3, 6, 8, 1, CREAM); p.r(9, 8, 4, 1, CREAM)
                p.r(2, 12, 12, 2, WOOD_DARK); p.r(4, 12, 2, 1, CREAM)
            }
            "microscope" -> {
                p.box(4, 13, 9, 3, STONE_DARK); p.r(10, 3, 2, 11, STONE_DARK)
                p.box(5, 1, 4, 8, MINT); p.r(6, 9, 2, 2, IRON); p.r(4, 10, 6, 1, STONE_LIGHT)
            }
            // Library
            "lectern" -> {
                p.box(2, 2, 12, 5, WOOD_LIGHT); p.box(3, 1, 10, 4, CREAM); p.r(8, 1, 1, 4, PARCHMENT_DARK)
                p.r(7, 6, 2, 8, WOOD_DARK); p.box(4, 13, 8, 3, WOOD)
            }
            "reading_chair" -> {
                p.box(2, 1, 12, 10, 0x5f7f6a); p.box(0, 6, 16, 8, 0x5f7f6a)
                p.r(3, 7, 10, 3, 0x7f9f88); p.r(2, 13, 2, 3, WOOD_DARK); p.r(12, 13, 2, 3, WOOD_DARK)
            }
            // Storage
            "crate" -> {
                p.box(1, 3, 14, 13, WOOD_LIGHT); p.r(2, 4, 12, 1, WOOD)
                for (i in 0 until 10) p.r(3 + i, 13 - i, 2, 1, WOOD)
                p.r(2, 9, 12, 1, WOOD)
            }
            "barrel" -> {
                p.box(3, 1, 10, 15, WOOD); p.r(2, 4, 12, 9, WOOD); p.r(5, 2, 2, 13, WOOD_LIGHT)
                p.r(2, 4, 12, 1, IRON); p.r(2, 12, 12, 1, IRON)
            }
            "shelf" -> {
                p.box(1, 0, 14, 16, WOOD_DARK)
                for (s in 0 until 3) {
                    val y = 1 + s * 5
                    p.r(2, y, 12, 4, WOOD); p.r(2, y + 3, 12, 1, WOOD_LIGHT)
                    p.r(3, y + 1, 3, 2, if (s == 1) CREAM else CLAY); p.r(9, y, 3, 3, if (s == 2) SAGE else PARCHMENT_DARK)
                }
            }
            // Greenhouse
            "flower_bed" -> {
                p.box(0, 8, 16, 8, WOOD); p.r(1, 9, 14, 3, 0x7a5a44)
                p.r(2, 4, 12, 6, SAGE); p.r(3, 3, 3, 3, SAGE_DARK)
                p.disc(4, 5, 1, ROSE); p.disc(8, 4, 1, HONEY); p.disc(12, 6, 1, 0xe6aea4); p.d(6, 8, CREAM)
            }
            "potted_tree" -> {
                p.box(5, 11, 6, 5, CLAY); p.r(7, 8, 2, 4, WOOD_DARK)
                p.disc(8, 5, 5, SAGE_DARK); p.disc(7, 4, 4, SAGE); p.disc(6, 3, 1, GRASS)
            }
            // Decorations
            "rug" -> {
                p.box(0, 2, 16, 12, ROSE_DARK); p.r(1, 3, 14, 10, ROSE)
                p.r(3, 5, 10, 6, 0xe6aea4); p.r(5, 7, 6, 2, HONEY)
            }
            "lamp" -> {
                p.box(4, 1, 8, 6, PARCHMENT); p.r(5, 2, 6, 1, CREAM)
                p.r(7, 7, 2, 7, WOOD_DARK); p.box(4, 13, 8, 3, WOOD)
            }
            "clock" -> { // round wall clock
                p.disc(8, 7, 6, WOOD); p.disc(8, 7, 5, CREAM)
                p.r(8, 3, 1, 5, INK); p.r(8, 7, 3, 1, INK); p.d(8, 2, WOOD_DARK); p.d(13, 7, WOOD_DARK); p.d(3, 7, WOOD_DARK)
                p.r(7, 13, 3, 2, WOOD_DARK); p.disc(8, 14, 1, HONEY)
            }
            // Dining room
            "sideboard" -> {
                p.box(0, 5, 16, 10, WOOD); p.r(0, 5, 16, 2, WOOD_LIGHT)
                p.r(2, 8, 5, 5, WOOD_DARK); p.r(9, 8, 5, 5, WOOD_DARK); p.d(6, 10, HONEY); p.d(9, 10, HONEY)
                p.r(1, 14, 2, 2, WOOD_DARK); p.r(13, 14, 2, 2, WOOD_DARK)
                p.box(3, 2, 4, 4, CREAM); p.r(10, 1, 2, 5, SAGE)
            }
            "china_cabinet" -> {
                p.box(1, 0, 14, 16, WOOD_DARK); p.r(2, 1, 12, 8, 0xcfe3e0)
                p.r(7, 1, 1, 8, WOOD_DARK); p.r(2, 5, 12, 1, WOOD_DARK)
                p.disc(4, 3, 1, CREAM); p.disc(11, 3, 1, 0x8fa6c4); p.r(3, 7, 3, 1, ROSE); p.r(9, 7, 3, 1, CREAM)
                p.r(2, 10, 12, 5, WOOD); p.d(7, 12, HONEY); p.d(8, 12, HONEY)
            }
            "chair" -> {
                p.box(4, 1, 8, 8, WOOD); p.r(5, 2, 6, 1, WOOD_LIGHT); p.r(5, 4, 6, 1, WOOD_LIGHT)
                p.box(3, 8, 10, 4, WOOD_LIGHT); p.r(4, 9, 8, 2, ROSE)
                p.r(4, 12, 1, 4, WOOD_DARK); p.r(11, 12, 1, 4, WOOD_DARK)
            }
            // Bedroom
            "bed" -> drawSized(p, "bed", 16, 16)
            "wardrobe" -> {
                p.box(1, 0, 14, 16, WOOD); p.r(2, 1, 12, 1, WOOD_LIGHT)
                p.r(7, 2, 1, 12, WOOD_DARK); p.r(3, 3, 3, 9, WOOD_LIGHT); p.r(10, 3, 3, 9, WOOD_LIGHT)
                p.d(6, 8, HONEY); p.d(9, 8, HONEY); p.r(2, 14, 12, 1, WOOD_DARK)
            }
            "nightstand" -> {
                p.box(3, 7, 10, 9, WOOD); p.r(4, 10, 8, 1, WOOD_DARK); p.d(8, 12, HONEY)
                p.r(6, 4, 4, 3, PARCHMENT); p.r(7, 6, 2, 1, WOOD_DARK); p.r(5, 3, 6, 1, 0xf6d6a0)
            }
            "dresser" -> {
                p.box(1, 4, 14, 12, WOOD_LIGHT)
                for (i in 0 until 3) { p.r(2, 5 + i * 3 + 1, 12, 2, WOOD); p.r(7, 6 + i * 3, 2, 1, HONEY) }
                p.disc(8, 2, 2, 0xcfe3e0)
            }
            // Bathroom
            "bathtub" -> drawSized(p, "bathtub", 16, 16)
            "toilet" -> {
                p.box(4, 1, 8, 6, CREAM); p.r(5, 2, 6, 1, 0xffffff)
                p.box(3, 6, 10, 7, CREAM); p.r(5, 8, 6, 3, 0xcfe3e0); p.box(5, 12, 6, 4, CREAM)
            }
            "sink" -> {
                p.r(7, 1, 2, 3, STONE); p.r(6, 1, 4, 1, STONE_LIGHT)
                p.box(2, 3, 12, 5, CREAM); p.r(4, 4, 8, 2, 0xa9d4dc)
                p.r(6, 8, 4, 8, CREAM); p.r(7, 8, 1, 8, PARCHMENT_DARK)
            }
            "mirror" -> { // wall-hung
                p.box(4, 1, 8, 12, HONEY); p.r(5, 2, 6, 10, 0xcfe3e0); p.r(6, 3, 1, 4, CREAM); p.r(7, 3, 1, 2, CREAM)
            }
            // Extras
            "piano" -> drawSized(p, "piano", 16, 16)
            "coat_rack" -> {
                p.r(7, 2, 2, 12, WOOD_DARK); p.r(5, 14, 6, 2, WOOD_DARK)
                p.r(4, 2, 8, 1, WOOD_DARK); p.box(3, 3, 4, 7, CLAY); p.box(9, 3, 4, 5, SAGE); p.disc(8, 1, 1, WOOD)
            }
            else -> return false
        }
        return true
    }

    /**
     * Multi-tile pieces drawn for their whole footprint ([w] x [h] pixels). Returns false when there is no
     * dedicated art for [kind] (the caller then centres the 1x1 piece).
     */
    fun drawSized(p: Px, kind: String, w: Int, h: Int): Boolean {
        when (kind) {
            "sofa" -> {
                p.box(0, 3, w, 8, ROSE); p.r(1, 4, w - 2, 1, 0xe6aea4)
                p.box(0, 8, w, 6, ROSE)
                var x = 3
                while (x + 6 <= w - 3) { p.box(x, 8, 7, 4, 0xe6aea4); x += 7 }
                p.box(0, 6, 3, 8, ROSE_DARK); p.box(w - 3, 6, 3, 8, ROSE_DARK)
                p.r(1, 13, 2, 3, WOOD_DARK); p.r(w - 3, 13, 2, 3, WOOD_DARK)
            }
            "counter" -> {
                p.box(0, 3, w, 13, WOOD); p.r(0, 3, w, 3, CREAM); p.r(1, 4, w - 2, 1, PARCHMENT)
                for (x in 0 until w step 8) { p.r(x + 1, 7, 6, 8, WOOD_LIGHT); p.r(x + 6, 10, 1, 2, HONEY) }
                if (w >= 32) { p.box(w / 2 - 5, 3, 10, 3, STONE_LIGHT); p.r(w / 2 - 4, 4, 8, 1, 0xa9d4dc) }
                p.box(3, 0, 5, 4, CREAM); p.r(w - 8, 1, 3, 3, SAGE)
            }
            "workbench" -> {
                p.box(0, 5, w, 6, WOOD); p.r(1, 6, w - 2, 1, WOOD_LIGHT)
                p.r(1, 10, 2, 6, WOOD_DARK); p.r(w - 3, 10, 2, 6, WOOD_DARK); p.r(3, 13, w - 6, 1, WOOD_DARK)
                p.r(4, 3, 6, 2, STONE); p.r(14, 1, 1, 4, WOOD_DARK); p.r(13, 1, 3, 1, IRON)
                if (w >= 32) { p.box(w - 12, 2, 8, 4, CLAY); p.r(w - 10, 3, 4, 1, HONEY) }
            }
            "fireplace" -> {
                p.box(0, 0, w, 16, STONE_LIGHT); p.r(0, 0, w, 3, WOOD); p.r(1, 1, w - 2, 1, WOOD_LIGHT)
                for (yy in 5 until 16 step 4) for (xx in (yy / 4 % 2) * 3 until w step 6) p.r(xx, yy, 1, 3, STONE)
                val ix = w / 2 - 6
                p.box(ix, 5, 12, 11, INK_SOFT)
                p.r(ix + 2, 12, 8, 3, CLAY); p.r(ix + 3, 9, 6, 4, FIRE); p.r(ix + 5, 7, 2, 4, HONEY)
                p.r(ix + 1, 15, 10, 1, WOOD_DARK)
            }
            "piano" -> {
                p.box(0, 0, w, 12, 0x4d3530); p.r(1, 1, w - 2, 1, 0x6a4a40)
                p.box(1, 6, w - 2, 5, CREAM)
                for (x in 2 until w - 2 step 3) p.r(x, 6, 1, 3, INK)
                p.r(2, 12, 2, 4, 0x4d3530); p.r(w - 4, 12, 2, 4, 0x4d3530)
                p.box(w / 2 - 3, 1, 6, 4, PARCHMENT)
            }
            "bathtub" -> {
                p.box(0, 3, w, 12, CREAM); p.r(1, 4, w - 2, 1, 0xffffff)
                p.box(2, 5, w - 4, 7, 0xa9d4dc); p.r(4, 6, 4, 1, 0xd6eef0); p.r(w - 12, 9, 3, 1, 0xd6eef0)
                p.r(w - 5, 1, 2, 4, STONE); p.r(w - 7, 1, 4, 1, STONE_LIGHT)
                p.r(2, 15, 2, 1, HONEY); p.r(w - 4, 15, 2, 1, HONEY)
            }
            "bed" -> {
                p.box(0, 0, w, h, WOOD) // frame
                p.box(1, 0, w - 2, 5, WOOD_LIGHT) // headboard
                p.r(2, 1, w - 4, 1, 0xe6b87e)
                val pw = if (w >= 32) (w - 6) / 2 else w - 4
                p.box(2, 4, pw, 5, CREAM); if (w >= 32) p.box(4 + pw, 4, pw, 5, CREAM)
                // Quilt with a soft patchwork.
                p.box(1, 9, w - 2, h - 10, ROSE)
                for (yy in 11 until h - 2 step 4) for (xx in 3 until w - 3 step 4) if ((xx / 4 + yy / 4) % 2 == 0) p.r(xx, yy, 2, 2, 0xe6aea4)
                p.r(1, 9, w - 2, 1, 0xe6aea4)
            }
            "dining_table" -> {
                val top = if (h >= 32) 6 else 4
                val bottom = if (h >= 32) h - 6 else h - 4
                p.box(0, top, w, bottom - top, WOOD_LIGHT); p.r(1, top + 1, w - 2, 1, 0xe6b87e)
                p.r(2, bottom - 1, 2, h - bottom + 1, WOOD_DARK); p.r(w - 4, bottom - 1, 2, h - bottom + 1, WOOD_DARK)
                // Table runner and place settings.
                p.r(w / 2 - 3, top + 1, 6, bottom - top - 2, CREAM)
                for (x in 4 until w - 6 step 12) { p.disc(x + 2, top + 4, 2, CREAM); p.disc(x + 2, bottom - 4, 2, CREAM) }
                p.disc(w / 2, (top + bottom) / 2, 2, ROSE); p.d(w / 2, (top + bottom) / 2 - 3, SAGE)
            }
            "rug" -> {
                p.box(0, 1, w, h - 2, ROSE_DARK); p.r(1, 2, w - 2, h - 4, ROSE)
                p.r(3, 4, w - 6, h - 8, 0xe6aea4); p.r(5, 6, w - 10, h - 12, ROSE)
                for (x in 2 until w - 2 step 2) { p.d(x, 0, HONEY); p.d(x, h - 1, HONEY) }
                p.disc(w / 2, h / 2, 2, HONEY)
            }
            "flower_bed" -> {
                p.box(0, 7, w, 9, WOOD); p.r(1, 8, w - 2, 3, 0x7a5a44)
                p.r(2, 3, w - 4, 6, SAGE)
                val cols = intArrayOf(ROSE, HONEY, 0xe6aea4, CREAM)
                for (x in 4 until w - 2 step 4) p.disc(x, 4 + (x / 4) % 3, 1, cols[(x / 4) % cols.size])
            }
            "bookshelf", "shelf" -> {
                p.box(0, 0, w, 16, WOOD)
                val colors = intArrayOf(ROSE, SAGE, HONEY, 0x8fa6c4, CLAY, CREAM)
                for (shelf in 0 until 3) {
                    val y = 2 + shelf * 5
                    p.r(1, y + 3, w - 2, 1, WOOD_DARK)
                    for (i in 0 until (w - 4) / 2) p.r(2 + i * 2, y, 1 + (i % 2), 3, colors[(i + shelf * 2) % colors.size])
                }
            }
            else -> return false
        }
        return true
    }
}
