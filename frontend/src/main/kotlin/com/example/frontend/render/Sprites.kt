package com.example.frontend.render

import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * One tunic colour pair (main + shadow) as 0xRRGGBB, plus an optional hair pair for the built-in villager
 * (-1 = keep the authored hair).
 */
data class Tint(val main: Int, val shadow: Int, val hair: Int = -1, val hairShadow: Int = -1)

/** What a character is doing, for picking its animation frame. */
enum class Pose { IDLE, WALK, WORK }

/**
 * Sprites loaded one PNG per name from `/floorplan/sprites/<name>.png`. The build copies every PNG in
 * `assets/` there unchanged. Images load on first use and are cached. On load each pixel is graded into the
 * warm [CozyPalette] (the PNGs on disk are never touched); the tunic key colours ([BASE_TINT]) are kept
 * exact so per-agent recolouring still works. A missing sprite draws as a procedural cozy stand-in from
 * [CozyFallbacks] instead of failing, so an artist can add or rename PNGs without breaking the map.
 */
class Sprites(private val anchor: Class<*> = Sprites::class.java, private val base: String = "/floorplan/sprites/") {
    private val images = ConcurrentHashMap<String, BufferedImage>()
    private val tinted = ConcurrentHashMap<Pair<String, Tint>, BufferedImage>()
    private val missingNames = ConcurrentHashMap.newKeySet<String>()

    /** Sprite names that had no PNG (they draw as the placeholder). */
    val missing: Set<String> get() = missingNames

    operator fun get(name: String): BufferedImage = images.getOrPut(name) {
        if (CozyCharacter.isVillager(name)) CozyCharacter.draw(name)
        else load(name)?.let(::grade) ?: CozyFallbacks.draw(name).also { missingNames += name }
    }

    /** True if [name] came from a PNG (not a procedural stand-in). */
    fun hasPng(name: String): Boolean { get(name); return name !in missingNames && !CozyCharacter.isVillager(name) }

    /**
     * Furniture sprite [name] for a footprint of [w] x [h] tiles. A PNG is used when it is 1x1 furniture or the
     * PNG already has the footprint's size; otherwise a procedural piece designed for that size.
     */
    fun furniture(name: String, w: Int, h: Int): BufferedImage {
        if (w <= 1 && h <= 1) return get(name)
        val png = get(name)
        if (hasPng(name) && png.width == w * 16 && png.height == h * 16) return png
        return images.getOrPut("$name@${w}x$h") { CozyFallbacks.drawSized(name, w, h) }
    }

    /** Tile sprite [name] as a PNG, or null when tiles are drawn procedurally ([PREFER_PNG_TILES] off or no PNG). */
    fun tilePng(name: String): BufferedImage? = if (PREFER_PNG_TILES && hasPng(name)) get(name) else null

    /**
     * The sprite name for a character facing [facing] in [pose] at [timeMs]. Uses the built-in villager unless
     * [PREFER_PNG_CHARACTER] is on (then the `hero_*` PNGs). [phase] desynchronises characters.
     */
    fun characterFrame(facing: Facing, pose: Pose, timeMs: Long, phase: Int = 0): String {
        val dir = facing.name.lowercase()
        val t = timeMs + phase * 97L
        if (PREFER_PNG_CHARACTER) return when (pose) {
            Pose.WALK -> frameName("hero_walk_$dir", t)
            Pose.WORK -> frameName("hero_work", t)
            Pose.IDLE -> "hero_${dir}_0"
        }
        return when (pose) {
            Pose.WALK -> "villager_${dir}_walk${(t / 130) % 4}"
            Pose.WORK -> "villager_up_work${(t / 240) % 2}"
            Pose.IDLE -> if ((t + phase * 731L) % 3700 < 140) "villager_${dir}_blink" else "villager_${dir}_idle"
        }
    }

    /** The frame of [anim] at [timeMs], or the sprite called [anim] if there is no such animation. */
    fun frame(anim: String, timeMs: Long): BufferedImage = get(frameName(anim, timeMs))

    fun frameName(anim: String, timeMs: Long): String {
        val a = ANIMATIONS[anim] ?: return anim
        val i = ((timeMs / a.frameMs.coerceAtLeast(1)) % a.frames.size).toInt()
        return a.frames[i]
    }

    /** [name] with the tunic colours ([BASE_TINT]) swapped to [tint]. Cached. */
    fun tinted(name: String, tint: Tint): BufferedImage {
        if (tint == BASE_TINT) return get(name)
        return tinted.getOrPut(name to tint) {
            val src = get(name)
            val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until src.height) for (x in 0 until src.width) {
                val argb = src.getRGB(x, y)
                val alpha = argb and 0xFF000000.toInt()
                out.setRGB(
                    x, y,
                    when {
                        alpha == 0 -> argb
                        argb and 0xFFFFFF == BASE_TINT.main -> alpha or tint.main
                        argb and 0xFFFFFF == BASE_TINT.shadow -> alpha or tint.shadow
                        tint.hair >= 0 && argb and 0xFFFFFF == CozyCharacter.HAIR_KEY.main -> alpha or tint.hair
                        tint.hairShadow >= 0 && argb and 0xFFFFFF == CozyCharacter.HAIR_KEY.shadow -> alpha or tint.hairShadow
                        else -> argb
                    },
                )
            }
            out
        }
    }

    private fun load(name: String): BufferedImage? = try {
        anchor.getResourceAsStream("$base$name.png")?.use { ImageIO.read(it) }?.let(::toArgb)
    } catch (e: Exception) {
        null
    }

    /** Warm colour grade (see [CozyPalette.grade]), leaving the tunic key colours exact. */
    private fun grade(src: BufferedImage): BufferedImage {
        val cache = HashMap<Int, Int>()
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val argb = src.getRGB(x, y)
            val alpha = argb and 0xFF000000.toInt()
            if (alpha == 0) continue
            val rgb = argb and 0xFFFFFF
            if (rgb == BASE_TINT.main || rgb == BASE_TINT.shadow) continue
            src.setRGB(x, y, alpha or cache.getOrPut(rgb) { CozyPalette.grade(rgb) })
        }
        return src
    }

    /** Copies into a plain ARGB image: fast to draw and safe to read pixels from for tinting. */
    private fun toArgb(src: BufferedImage): BufferedImage {
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().apply { drawImage(src, 0, 0, null); dispose() }
        return out
    }

    private data class Anim(val frameMs: Int, val frames: List<String>)

    companion object {
        /**
         * Draw characters from the `hero_*` PNGs (true) or as the built-in villager from [CozyCharacter] (false,
         * the default). The HUD's `hero_down_0` head icon always uses the PNG.
         */
        const val PREFER_PNG_CHARACTER = false

        /**
         * Draw walls and floors from the `tile_*` PNGs (true) or procedurally with 4-direction wall
         * autotiling and per-room-kind floors (false, the default). Furniture PNGs are used either way.
         */
        const val PREFER_PNG_TILES = false

        /**
         * The character's tunic colours as drawn in the `hero_*` PNGs. Pixels of exactly these colours
         * are recoloured per agent, so keep them if you redraw the character.
         */
        val BASE_TINT = Tint(main = 0x3d72d6, shadow = 0x26498f)

        /** Frame sequences. Each frame is a sprite name, i.e. a PNG in `assets/`. */
        private val ANIMATIONS = mapOf(
            "hero_walk_down" to Anim(140, listOf("hero_down_1", "hero_down_0", "hero_down_2", "hero_down_0")),
            "hero_walk_up" to Anim(140, listOf("hero_up_1", "hero_up_0", "hero_up_2", "hero_up_0")),
            "hero_walk_left" to Anim(160, listOf("hero_left_1", "hero_left_0")),
            "hero_walk_right" to Anim(160, listOf("hero_right_1", "hero_right_0")),
            "hero_work" to Anim(260, listOf("hero_work_0", "hero_work_1")),
            "obj_server_blink" to Anim(450, listOf("obj_server_0", "obj_server_1")),
        )
    }
}
