package com.example.frontend.render

import java.awt.image.BufferedImage

/**
 * The built-in villager character: an original, chibi farm-sim style townsperson, 16x28 pixels (one tile wide,
 * a bit under two tiles tall). Big round head with dot eyes, a highlight and blush, chunky hair locks, a short
 * tunic and little boots, outlined in soft dark brown.
 *
 * The tunic uses exactly [Sprites.BASE_TINT] and the hair uses [HAIR_KEY], so both can be recoloured per agent.
 *
 * Frame names: `villager_<dir>_<pose>` with dir in down/up/left/right and pose in
 * `idle`, `blink`, `walk0..walk3`, `work0`, `work1`.
 */
internal object CozyCharacter {
    const val W = 16
    const val H = 28

    /** Hair key colours (main, shade), swapped per agent like the tunic. */
    val HAIR_KEY = Tint(0x8a5a3b, 0x66402a)

    private const val SKIN = 0xf6d2b0
    private const val SKIN_SHADE = 0xe4b38e
    private const val BLUSH = 0xf0a09a
    private const val EYE = 0x3b2a2a
    private const val SHINE = 0xfff8ec
    private const val COLLAR = 0xf3e2bf
    private const val PANTS = 0x6f5c50
    private const val BOOTS = 0x5a3a2a
    private const val OUTLINE = 0x4a302c

    fun isVillager(name: String) = name.startsWith("villager_")

    fun draw(name: String): BufferedImage {
        val parts = name.removePrefix("villager_").split('_')
        val dir = parts.getOrElse(0) { "down" }
        val pose = parts.getOrElse(1) { "idle" }
        val c = Canvas()
        when (dir) {
            "up" -> drawUp(c, pose)
            "left" -> drawSide(c, pose)
            "right" -> { drawSide(c, pose); c.mirror() }
            else -> drawDown(c, pose)
        }
        c.outline(OUTLINE)
        return c.toImage()
    }

    private fun walkFrame(pose: String): Int = if (pose.startsWith("walk")) pose.last().digitToIntOrNull() ?: 0 else -1

    // ---- front ----

    private fun drawDown(c: Canvas, pose: String) {
        val f = walkFrame(pose)
        val bob = if (f == 1 || f == 3) -1 else 0
        head(c, bob)
        // Hair: cap, chunky fringe locks and side locks.
        c.rows(1 + bob, 5 + bob) { y -> headSpan(y - bob) }.forEach { (y, xs) -> c.fill(xs, y, HAIR_KEY.main) }
        for ((x, len) in listOf(3 to 4, 4 to 3, 5 to 2, 8 to 2, 9 to 3, 12 to 4)) c.rect(x, 6 + bob, 1, len - 1, HAIR_KEY.main)
        c.rect(2, 6 + bob, 1, 4, HAIR_KEY.main); c.rect(13, 6 + bob, 1, 4, HAIR_KEY.main)
        c.rect(4, 2 + bob, 1, 2, HAIR_KEY.shadow); c.rect(8, 2 + bob, 1, 3, HAIR_KEY.shadow); c.rect(11, 3 + bob, 1, 2, HAIR_KEY.shadow)
        c.rect(3, 5 + bob, 10, 1, HAIR_KEY.shadow)
        c.rect(6, 1 + bob, 3, 1, 0xa8754e) // shine on the hair
        // Face
        if (pose == "blink") {
            c.rect(4, 9 + bob, 2, 1, EYE); c.rect(10, 9 + bob, 2, 1, EYE)
        } else {
            c.rect(4, 8 + bob, 2, 2, EYE); c.rect(10, 8 + bob, 2, 2, EYE)
            c.dot(5, 8 + bob, SHINE); c.dot(11, 8 + bob, SHINE)
        }
        c.rect(3, 10 + bob, 2, 1, BLUSH); c.rect(11, 10 + bob, 2, 1, BLUSH)
        c.rect(7, 11 + bob, 2, 1, SKIN_SHADE)
        body(c, bob, f, front = true)
    }

    // ---- back ----

    private fun drawUp(c: Canvas, pose: String) {
        val f = walkFrame(pose)
        val bob = if (f == 1 || f == 3) -1 else 0
        head(c, bob)
        for (y in 1..13) headSpan(y)?.let { c.fill(it, y + bob, HAIR_KEY.main) }
        c.rect(5, 13 + bob, 6, 1, SKIN_SHADE) // nape
        for ((x, y0) in listOf(4 to 4, 7 to 3, 10 to 5)) c.rect(x, y0 + bob, 1, 6, HAIR_KEY.shadow)
        c.rect(3, 12 + bob, 10, 1, HAIR_KEY.shadow)
        c.rect(6, 1 + bob, 4, 1, 0xa8754e)
        body(c, bob, f, front = false)
        if (pose.startsWith("work")) {
            // Arms forward, typing: hands bob alternately.
            val up = pose.endsWith("0")
            c.rect(3, 15 + bob, 1, 3, Sprites.BASE_TINT.main); c.rect(12, 15 + bob, 1, 3, Sprites.BASE_TINT.main)
            c.rect(3, if (up) 13 else 14, 2, 2, SKIN); c.rect(11, if (up) 14 else 13, 2, 2, SKIN)
        }
    }

    // ---- side (facing left; right is mirrored) ----

    private fun drawSide(c: Canvas, pose: String) {
        val f = walkFrame(pose)
        val bob = if (f == 1 || f == 3) -1 else 0
        head(c, bob)
        for (y in 1..5) headSpan(y)?.let { c.fill(it, y + bob, HAIR_KEY.main) }
        c.rect(8, 6 + bob, 6, 5, HAIR_KEY.main) // back of the head
        c.rect(9, 11 + bob, 4, 2, HAIR_KEY.main)
        c.rect(3, 6 + bob, 2, 2, HAIR_KEY.main); c.rect(5, 6 + bob, 1, 1, HAIR_KEY.main) // fringe
        c.rect(9, 3 + bob, 1, 5, HAIR_KEY.shadow); c.rect(12, 4 + bob, 1, 5, HAIR_KEY.shadow)
        c.rect(5, 1 + bob, 3, 1, 0xa8754e)
        // Ear
        c.rect(8, 9 + bob, 1, 2, SKIN_SHADE)
        if (pose == "blink") c.rect(3, 9 + bob, 2, 1, EYE) else { c.rect(3, 8 + bob, 2, 2, EYE); c.dot(4, 8 + bob, SHINE) }
        c.rect(3, 10 + bob, 2, 1, BLUSH)
        // Body
        val tm = Sprites.BASE_TINT.main
        val ts = Sprites.BASE_TINT.shadow
        c.rect(5, 14 + bob, 6, 6, tm)
        c.rect(5, 19 + bob, 6, 1, ts)
        c.rect(10, 14 + bob, 1, 6, ts)
        c.rect(5, 14 + bob, 2, 1, COLLAR)
        // Arm swings with the walk.
        val swing = when (f) { 1 -> -1; 3 -> 1; else -> 0 }
        if (!pose.startsWith("work")) {
            c.rect(7 + swing, 15 + bob, 2, 4, ts)
            c.rect(7 + swing, 19 + bob, 2, 1, SKIN)
        }
        // Legs: stride on frames 1 and 3.
        when (f) {
            1, 3 -> {
                c.rect(4, 20 + bob, 2, 4 - bob, PANTS); c.rect(3, 24, 3, 2, BOOTS)
                c.rect(9, 20 + bob, 2, 4 - bob, PANTS); c.rect(9, 24, 3, 2, BOOTS)
            }
            else -> {
                c.rect(6, 20, 4, 4, PANTS); c.rect(5, 24, 5, 2, BOOTS)
                c.rect(8, 20, 1, 4, 0x5f4d43)
            }
        }
    }

    // ---- shared parts ----

    /** Horizontal span of the round head on [row] (rows 1..13), or null outside it. */
    private fun headSpan(row: Int): IntRange? = when (row) {
        1 -> 5..10
        2 -> 3..12
        in 3..11 -> 2..13
        12 -> 3..12
        13 -> 4..11
        else -> null
    }

    private fun head(c: Canvas, bob: Int) {
        for (y in 1..13) headSpan(y)?.let { c.fill(it, y + bob, SKIN) }
        c.rect(3, 12 + bob, 10, 1, SKIN_SHADE)
    }

    private fun body(c: Canvas, bob: Int, f: Int, front: Boolean) {
        val tm = Sprites.BASE_TINT.main
        val ts = Sprites.BASE_TINT.shadow
        // Tunic
        c.rect(4, 14 + bob, 8, 6, tm)
        c.rect(4, 14 + bob, 1, 6, ts)
        c.rect(4, 19 + bob, 8, 1, ts)
        if (front) { c.rect(6, 14 + bob, 4, 1, COLLAR); c.dot(7, 15 + bob, COLLAR); c.dot(8, 15 + bob, COLLAR) }
        // Arms swing opposite to the legs.
        val l = when (f) { 1 -> 1; 3 -> -1; else -> 0 }
        c.rect(3, 15 + bob + l, 1, 4, tm); c.dot(3, 19 + bob + l, SKIN)
        c.rect(12, 15 + bob - l, 1, 4, ts); c.dot(12, 19 + bob - l, SKIN)
        // Legs and boots; a lifted foot on frames 1 / 3.
        val liftL = if (f == 1) 1 else 0
        val liftR = if (f == 3) 1 else 0
        c.rect(5, 20 + bob, 2, 4 - liftL - bob, PANTS); c.rect(4, 24 - liftL, 3, 2, BOOTS)
        c.rect(9, 20 + bob, 2, 4 - liftR - bob, PANTS); c.rect(9, 24 - liftR, 3, 2, BOOTS)
    }

    private class Canvas {
        val px = IntArray(W * H)

        fun dot(x: Int, y: Int, rgb: Int) { if (x in 0 until W && y in 0 until H) px[y * W + x] = 0xFF000000.toInt() or rgb }
        fun rect(x: Int, y: Int, w: Int, h: Int, rgb: Int) { for (yy in y until y + h) for (xx in x until x + w) dot(xx, yy, rgb) }
        fun fill(xs: IntRange, y: Int, rgb: Int) { for (x in xs) dot(x, y, rgb) }
        fun rows(from: Int, to: Int, span: (Int) -> IntRange?): List<Pair<Int, IntRange>> =
            (from..to).mapNotNull { y -> span(y)?.let { y to it } }

        fun mirror() {
            for (y in 0 until H) for (x in 0 until W / 2) {
                val a = y * W + x
                val b = y * W + (W - 1 - x)
                val t = px[a]; px[a] = px[b]; px[b] = t
            }
        }

        /** Soft outline: every empty pixel next to a filled one. */
        fun outline(rgb: Int) {
            val src = px.copyOf()
            for (y in 0 until H) for (x in 0 until W) {
                if (src[y * W + x] != 0) continue
                val near = (x > 0 && src[y * W + x - 1] != 0) || (x < W - 1 && src[y * W + x + 1] != 0) ||
                    (y > 0 && src[(y - 1) * W + x] != 0) || (y < H - 1 && src[(y + 1) * W + x] != 0)
                if (near) px[y * W + x] = 0xFF000000.toInt() or rgb
            }
        }

        fun toImage(): BufferedImage = BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, W, H, px, 0, W) }
    }
}
