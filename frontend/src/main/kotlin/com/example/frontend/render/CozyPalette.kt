package com.example.frontend.render

import java.awt.Color

/**
 * The warm, muted palette the whole map is graded into: honey woods, cream, sage and dusty rose, with soft
 * dark-brown "ink" instead of black. Sprites keep their hand-drawn shapes; [grade] only shifts their colours.
 */
object CozyPalette {
    // Ink and darks
    const val INK = 0x3b2a2a
    const val INK_SOFT = 0x5a4038
    const val NIGHT = 0x2a201d

    // Woods
    const val WOOD_DARK = 0x7a4f36
    const val WOOD = 0xa8714a
    const val WOOD_LIGHT = 0xd09a66
    const val HONEY = 0xe8b964

    // Lights
    const val CREAM = 0xfbf1dc
    const val PARCHMENT = 0xf3e2bf
    const val PARCHMENT_DARK = 0xd9bf91

    // Accents
    const val SAGE = 0x8fb07a
    const val SAGE_DARK = 0x5f7f52
    const val ROSE = 0xd48f8a
    const val ROSE_DARK = 0xa5625f
    const val CLAY = 0xc9785a
    const val AQUA = 0x9fd3c9
    const val STONE = 0xa89a8a
    const val STONE_DARK = 0x7a6c62
    const val STONE_LIGHT = 0xd6c9b6
    const val DUSK = 0x5a7a9a

    /** Behind the house (fills the whole view). */
    val BACKGROUND = Color(0x2b211e)

    /** Room-label plaque and tooltip colours. */
    val PLAQUE_FILL = Color(PARCHMENT)
    val PLAQUE_EDGE = Color(WOOD_DARK)
    val PLAQUE_TEXT = Color(INK)

    /** Selection highlight (honey) and hover highlight (cream). */
    val SELECT = Color(HONEY)
    val HOVER = Color(CREAM)

    fun c(rgb: Int, alpha: Int = 255): Color = Color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, alpha)

    /**
     * Exact swaps for the colours the authored sprites use (the cool greys and blue-black outline) so the
     * existing art lands in the warm palette. Anything not listed goes through the generic [grade].
     */
    private val SWAPS: Map<Int, Int> = mapOf(
        0x000000 to 0x2e2220,
        0x1b1a26 to INK,
        0x2b2325 to 0x3a2d2b,
        0x33313f to 0x4d3e3a,
        0x55566a to 0x7d6c60,
        0x7c7e94 to 0xa69585,
        0xb3b5c4 to 0xd8cab6,
        0xf4f1e8 to CREAM,
        0xf2c53d to HONEY,
        0x6fdcef to AQUA,
        0xdc4a3f to 0xd5705c,
        0x4fa04c to SAGE,
        0x2d6634 to SAGE_DARK,
        0x2c5c86 to DUSK,
        0x8c2b2b to 0x9a4f48,
        0x5a3820 to 0x6a4630,
    )

    /**
     * Grades one 0xRRGGBB colour: exact swaps first, else a gentle desaturation, a warm shift and darks
     * lifted toward soft brown so no pixel is harsh black.
     */
    fun grade(rgb: Int): Int {
        SWAPS[rgb]?.let { return it }
        var r = (rgb shr 16 and 0xFF) / 255.0
        var g = (rgb shr 8 and 0xFF) / 255.0
        var b = (rgb and 0xFF) / 255.0
        val lum = 0.299 * r + 0.587 * g + 0.114 * b
        val desat = 0.14
        r += (lum - r) * desat
        g += (lum - g) * desat
        b += (lum - b) * desat
        r *= 1.03; g *= 1.0; b *= 0.92
        // Lift darks toward ink brown.
        val k = (1 - lum).let { it * it * it } * 0.55
        r += ((INK shr 16 and 0xFF) / 255.0 - r).coerceAtLeast(0.0) * k
        g += ((INK shr 8 and 0xFF) / 255.0 - g).coerceAtLeast(0.0) * k
        b += ((INK and 0xFF) / 255.0 - b).coerceAtLeast(0.0) * k
        fun ch(v: Double) = (v.coerceIn(0.0, 1.0) * 255).toInt()
        return (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /** A cheap stable hash for per-tile variation. */
    fun hash(x: Int, y: Int, salt: Int = 0): Int {
        var h = x * 374761393 + y * 668265263 + salt * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        return h xor (h ushr 16)
    }
}
