package com.example.frontend.render

import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/** One tunic colour pair (main + shadow) as 0xRRGGBB. */
data class Tint(val main: Int, val shadow: Int)

/**
 * Sprites loaded one PNG per name from `/floorplan/sprites/<name>.png`. The build copies every PNG in
 * `assets/` there unchanged. Images load on first use and are cached. A missing sprite draws as a magenta
 * checkerboard instead of failing, so an artist can add or rename PNGs without breaking the map.
 */
class Sprites(private val anchor: Class<*> = Sprites::class.java, private val base: String = "/floorplan/sprites/") {
    private val images = ConcurrentHashMap<String, BufferedImage>()
    private val tinted = ConcurrentHashMap<Pair<String, Tint>, BufferedImage>()
    private val missingNames = ConcurrentHashMap.newKeySet<String>()

    /** Sprite names that had no PNG (they draw as the placeholder). */
    val missing: Set<String> get() = missingNames

    operator fun get(name: String): BufferedImage = images.getOrPut(name) { load(name) ?: placeholder.also { missingNames += name } }

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

    /** Copies into a plain ARGB image: fast to draw and safe to read pixels from for tinting. */
    private fun toArgb(src: BufferedImage): BufferedImage {
        if (src.type == BufferedImage.TYPE_INT_ARGB) return src
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().apply { drawImage(src, 0, 0, null); dispose() }
        return out
    }

    private data class Anim(val frameMs: Int, val frames: List<String>)

    companion object {
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

        private val placeholder: BufferedImage by lazy {
            BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).apply {
                for (y in 0 until 16) for (x in 0 until 16) {
                    setRGB(x, y, if ((x / 4 + y / 4) % 2 == 0) 0xFFFF00FF.toInt() else 0xFF000000.toInt())
                }
            }
        }
    }
}
