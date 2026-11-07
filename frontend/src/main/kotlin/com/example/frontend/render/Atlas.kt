package com.example.frontend.render

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.image.BufferedImage
import java.io.InputStream
import javax.imageio.ImageIO

@Serializable
internal data class AtlasRect(val x: Int, val y: Int, val w: Int, val h: Int)

@Serializable
internal data class AtlasAnim(val frameMs: Int, val frames: List<String>)

@Serializable
internal data class AtlasJson(
    val image: String = "atlas.png",
    val palette: Map<String, String> = emptyMap(),
    val sprites: Map<String, AtlasRect>,
    val animations: Map<String, AtlasAnim> = emptyMap(),
)

/** One tunic colour pair (main + shadow) as 0xRRGGBB. */
data class Tint(val main: Int, val shadow: Int)

/**
 * The compiled sprite atlas (`assets/generated`, copied to `/floorplan/` in the frontend resources).
 * Missing sprites resolve to a magenta placeholder rather than failing.
 */
class Atlas private constructor(
    private val sprites: Map<String, BufferedImage>,
    private val animations: Map<String, AtlasAnim>,
    palette: Map<String, String>,
) {
    private val tunic = parseHex(palette["u"]) ?: 0x3d72d6
    private val tunicShadow = parseHex(palette["B"]) ?: 0x26498f
    private val tinted = HashMap<Pair<String, Tint>, BufferedImage>()
    private val missing: BufferedImage by lazy {
        BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).apply {
            for (y in 0 until 16) for (x in 0 until 16) {
                setRGB(x, y, if ((x / 4 + y / 4) % 2 == 0) 0xFFFF00FF.toInt() else 0xFF000000.toInt())
            }
        }
    }

    /** The tunic colours as authored. */
    val baseTint: Tint get() = Tint(tunic, tunicShadow)

    val isEmpty: Boolean get() = sprites.isEmpty()

    operator fun get(name: String): BufferedImage = sprites[name] ?: missing

    /** The frame of [anim] at [timeMs], or the sprite called [anim] if there is no such animation. */
    fun frame(anim: String, timeMs: Long): BufferedImage = get(frameName(anim, timeMs))

    fun frameName(anim: String, timeMs: Long): String {
        val a = animations[anim] ?: return anim
        if (a.frames.isEmpty()) return anim
        val i = ((timeMs / a.frameMs.coerceAtLeast(1)) % a.frames.size).toInt()
        return a.frames[i]
    }

    /** [name] with the tunic keys (`u`, `B`) swapped to [tint]. Cached. */
    fun tinted(name: String, tint: Tint): BufferedImage {
        if (tint == baseTint) return get(name)
        return tinted.getOrPut(name to tint) {
            val src = get(name)
            val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until src.height) for (x in 0 until src.width) {
                val argb = src.getRGB(x, y)
                val rgb = argb and 0xFFFFFF
                val alpha = argb and 0xFF000000.toInt()
                out.setRGB(
                    x, y,
                    when {
                        alpha == 0 -> argb
                        rgb == tunic -> alpha or tint.main
                        rgb == tunicShadow -> alpha or tint.shadow
                        else -> argb
                    },
                )
            }
            out
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Loads `/floorplan/atlas.json` + its image from the classpath of [anchor]. Never throws. */
        fun loadFromClasspath(anchor: Class<*> = Atlas::class.java, base: String = "/floorplan/"): Atlas = try {
            val meta = anchor.getResourceAsStream(base + "atlas.json")?.use { it.readBytes().decodeToString() }
            val parsed = meta?.let { json.decodeFromString(AtlasJson.serializer(), it) }
            val img = parsed?.let { p -> anchor.getResourceAsStream(base + p.image)?.use { ImageIO.read(it) } }
            if (parsed != null && img != null) fromParts(parsed, img) else empty()
        } catch (e: Exception) {
            loadError = e
            empty()
        } catch (e: LinkageError) {
            loadError = e
            empty()
        }

        /** Why the last [loadFromClasspath] returned an empty atlas, if it did. */
        @Volatile
        var loadError: Throwable? = null
            private set

        fun load(jsonText: String, png: InputStream): Atlas =
            fromParts(json.decodeFromString(AtlasJson.serializer(), jsonText), png.use { ImageIO.read(it) })

        private fun fromParts(meta: AtlasJson, img: BufferedImage): Atlas {
            val sprites = meta.sprites.mapValues { (_, r) ->
                // Each sprite gets its own small ARGB image: fast to draw, safe to tint.
                val sub = BufferedImage(r.w, r.h, BufferedImage.TYPE_INT_ARGB)
                val g = sub.createGraphics()
                g.drawImage(img, 0, 0, r.w, r.h, r.x, r.y, r.x + r.w, r.y + r.h, null)
                g.dispose()
                sub
            }
            return Atlas(sprites, meta.animations, meta.palette)
        }

        private fun empty() = Atlas(emptyMap(), emptyMap(), emptyMap())

        private fun parseHex(s: String?): Int? = s?.removePrefix("#")?.toIntOrNull(16)
    }
}
