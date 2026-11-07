package com.example.frontend.render

import com.example.frontend.render.CozyPalette.c
import com.example.frontend.render.CozyPalette.hash
import com.example.model.FurnitureKind
import com.example.model.RoomDto
import com.example.model.RoomKind
import com.example.model.Tiles
import com.example.model.WorldDto
import com.example.model.tileAt
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics2D
import java.awt.MultipleGradientPaint
import java.awt.RadialGradientPaint
import java.awt.Rectangle
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import kotlin.math.max

/** Floor material of a room kind. */
internal enum class FloorMat { PLANKS, WORN_PLANKS, DIM_PLANKS, PARQUET, CHECKER, SMALL_TILES, BIG_TILES, SLATE, CARPET, GRASS }

/** Wallpaper pattern on a north wall face. */
internal enum class WallPattern { STRIPES, DOTS, DIAMONDS, PLAIN, TILES, BRICK, LEAVES }

/** Extra treatment over a room's floor. */
internal enum class Overlay { NONE, RUNNER, BIG_RUG }

/** How one [RoomKind] looks. Colours are 0xRRGGBB. */
internal data class RoomStyle(
    val floor: FloorMat, val floorA: Int, val floorB: Int,
    val paper: Int, val paperAlt: Int, val pattern: WallPattern, val wainscot: Int,
    val accent: Int, val light: Int, val lightAlpha: Int = 46, val overlay: Overlay = Overlay.NONE,
)

/**
 * Bakes the static part of the map at 1 world pixel per pixel: per-kind floors, 4-direction autotiled walls
 * (tall wallpapered north faces with a cap, thin side and south wall tops, joined corners and T-junctions,
 * thicker darker outer walls), door frames, stairs, ambient occlusion, warm room light, furniture shadows,
 * rugs and wall-hung pieces. Scaled up with nearest-neighbour this reads as soft pixel art.
 */
internal object CozyRooms {
    private const val T = Viewport.TILE

    fun style(kind: RoomKind): RoomStyle = when (kind) {
        RoomKind.LOBBY -> RoomStyle(FloorMat.PLANKS, 0xc8935f, 0xb07c4e, 0xe9cfa8, 0xddbf98, WallPattern.STRIPES, CozyPalette.WOOD, CozyPalette.WOOD, 0xffd9a0)
        RoomKind.HALLWAY -> RoomStyle(FloorMat.PLANKS, 0xbf8a58, 0xa87548, 0xe0c8a8, 0xcfb090, WallPattern.STRIPES, CozyPalette.WOOD, CozyPalette.WOOD_DARK, 0xffd9a0, 40)
        RoomKind.LIVING_ROOM -> RoomStyle(FloorMat.PLANKS, 0xc8935f, 0xb48050, 0xe8c9a6, 0xd9ae92, WallPattern.DIAMONDS, CozyPalette.WOOD, CozyPalette.ROSE, 0xffc98a, 56, Overlay.BIG_RUG)
        RoomKind.DINING_ROOM -> RoomStyle(FloorMat.PARQUET, 0xc48c58, 0xa87446, 0xc98f86, 0xb57a72, WallPattern.STRIPES, CozyPalette.WOOD_DARK, CozyPalette.ROSE_DARK, 0xffd9a0, 54)
        RoomKind.KITCHEN -> RoomStyle(FloorMat.CHECKER, 0xf3e6cc, 0xc9b48e, 0xf3e2bf, 0xa9c49a, WallPattern.TILES, CozyPalette.SAGE, CozyPalette.SAGE, 0xfff0c8)
        RoomKind.BATHROOM -> RoomStyle(FloorMat.SMALL_TILES, 0xeef4f2, 0x9cc4d4, 0xdfeef0, 0x9cc4d4, WallPattern.TILES, 0xf8fbfa, 0x8fb8c8, 0xf4fbff, 40)
        RoomKind.WORKSHOP -> RoomStyle(FloorMat.WORN_PLANKS, 0xb0835a, 0x946a46, 0xd2b08a, 0xb89470, WallPattern.BRICK, CozyPalette.WOOD_DARK, CozyPalette.CLAY, 0xffcf94)
        RoomKind.GREENHOUSE -> RoomStyle(FloorMat.GRASS, 0x9cbf86, 0x7fa56c, 0xdcebcc, CozyPalette.SAGE, WallPattern.LEAVES, 0x9a7a5a, CozyPalette.SAGE, 0xf6ffd8, 50)
        RoomKind.BEDROOM -> RoomStyle(FloorMat.CARPET, 0xd6b0bc, 0xc49ca8, 0xeed6de, 0xdcb8c6, WallPattern.DOTS, 0xf3e2bf, 0xcf8fa6, 0xffd8b0, 50)
        RoomKind.STUDY -> RoomStyle(FloorMat.PLANKS, 0xa8744a, 0x8f6240, 0xbcc6d0, 0xa2afbe, WallPattern.STRIPES, CozyPalette.WOOD_DARK, CozyPalette.DUSK, 0xffe2b0)
        RoomKind.LIBRARY -> RoomStyle(FloorMat.CARPET, 0x52795c, 0x45694f, 0x7a9a7c, 0x67876a, WallPattern.DIAMONDS, CozyPalette.WOOD_DARK, CozyPalette.SAGE_DARK, 0xffd28a, 54)
        RoomKind.DEPENDENCIES -> RoomStyle(FloorMat.CARPET, 0x7a4a3e, 0x683e33, 0xc9b28a, 0xb59c74, WallPattern.STRIPES, CozyPalette.WOOD_DARK, CozyPalette.CLAY, 0xffd28a, 54)
        RoomKind.GALLERY -> RoomStyle(FloorMat.PARQUET, 0xdcc3a0, 0xc4a882, 0xf4ece0, 0xe2d6c6, WallPattern.PLAIN, 0xd8c8b0, 0xa996cc, 0xfff4dc)
        RoomKind.LAB -> RoomStyle(FloorMat.BIG_TILES, 0xcfe8dc, 0xa9cfbe, 0xdcf0e6, 0xb4dccb, WallPattern.TILES, 0xb4dccb, 0x72b5ab, 0xeafff4)
        RoomKind.UTILITY_ROOM -> RoomStyle(FloorMat.SLATE, 0x8e9294, 0x777b7e, 0xaaa8a0, 0x8f8d86, WallPattern.BRICK, CozyPalette.STONE_DARK, CozyPalette.STONE_DARK, 0xe6eee4, 34)
        RoomKind.STORAGE -> RoomStyle(FloorMat.DIM_PLANKS, 0x8a6a50, 0x735640, 0x9c8270, 0x82685a, WallPattern.BRICK, CozyPalette.WOOD_DARK, CozyPalette.WOOD_DARK, 0xe8c890, 28)
    }

    private fun isFloor(ch: Char) = ch == Tiles.FLOOR_WOOD || ch == Tiles.FLOOR_TILE || ch == Tiles.FLOOR_CARPET || ch == Tiles.CORRIDOR
    private fun isGround(ch: Char) = isFloor(ch) || ch == Tiles.DOOR || ch == Tiles.STAIRS

    /** Per-bake lookups. */
    private class Ctx(val world: WorldDto) {
        val w = world.width
        val h = world.height
        /** Room index for each tile inside a room's walls (face row included), else -1. */
        val roomAt = IntArray(w * h) { -1 }
        /** +1 for stairs going up, -1 for down. */
        val stairsDir = HashMap<Int, Int>()

        init {
            world.rooms.forEachIndexed { i, r ->
                for (y in r.y + 1 until r.y + r.h - 1) for (x in r.x + 1 until r.x + r.w - 1) {
                    if (x in 0 until w && y in 0 until h) roomAt[y * w + x] = i
                }
            }
            for (s in world.stairs) {
                if (world.inside(s.a.x, s.a.y)) stairsDir[s.a.y * w + s.a.x] = if (s.bLevel > s.aLevel) 1 else -1
                if (world.inside(s.b.x, s.b.y)) stairsDir[s.b.y * w + s.b.x] = if (s.aLevel > s.bLevel) 1 else -1
            }
        }

        fun t(x: Int, y: Int) = world.tileAt(x, y)
        fun room(x: Int, y: Int): RoomDto? = if (x in 0 until w && y in 0 until h) roomAt[y * w + x].takeIf { it >= 0 }?.let { world.rooms[it] } else null

        /** Face-row tile: '=' or a doorway cut through the face row. */
        fun isFace(x: Int, y: Int): Boolean {
            val ch = t(x, y)
            if (ch == Tiles.FACE) return true
            if (ch != Tiles.DOOR) return false
            val r = room(x, y) ?: return false
            return y == r.y + 1
        }

        fun isWall(x: Int, y: Int) = t(x, y) == Tiles.WALL

        /** The room whose surface (floor or face) should show at a ground / face tile. */
        fun surfaceRoom(x: Int, y: Int): RoomDto? = room(x, y)
            ?: listOf(0 to 1, 0 to -1, -1 to 0, 1 to 0).firstNotNullOfOrNull { (dx, dy) -> room(x + dx, y + dy)?.takeIf { isGround(t(x + dx, y + dy)) } }

        fun outer(x: Int, y: Int): Boolean {
            for (dy in -1..1) for (dx in -1..1) if (t(x + dx, y + dy) == Tiles.VOID) return true
            return false
        }
    }

    private fun WorldDto.inside(x: Int, y: Int) = x in 0 until width && y in 0 until height

    fun bake(world: WorldDto, sprites: Sprites): BufferedImage {
        val w = max(1, world.width * T)
        val h = max(1, world.height * T)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        val ctx = Ctx(world)

        // 1. Soft drop shadow of the house onto the background.
        val mask = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        mask.createGraphics().apply {
            color = c(CozyPalette.NIGHT)
            for (y in 0 until world.height) for (x in 0 until world.width) if (world.tileAt(x, y) != Tiles.VOID) fillRect(x * T, y * T, T, T)
            dispose()
        }
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.32f)
        g.drawImage(mask, 3, 5, null)
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f)
        g.drawImage(mask, 1, 2, null)
        g.composite = AlphaComposite.SrcOver

        // 2. Tiles: floors, faces, doors, stairs, then walls (which borrow their neighbours' surfaces).
        for (y in 0 until world.height) for (x in 0 until world.width) {
            val ch = world.tileAt(x, y)
            val png = pngFor(sprites, ch)
            if (png != null) { g.drawImage(png, x * T, y * T, null); continue }
            when {
                ch == Tiles.VOID -> Unit
                ch == Tiles.WALL -> Unit // after the room overlays
                ch == Tiles.FACE -> paintFace(g, x * T, y * T, x, styleAt(ctx, x, y))
                ch == Tiles.DOOR -> paintDoor(g, ctx, x, y)
                ch == Tiles.STAIRS -> paintStairs(g, ctx, x, y)
                else -> paintFloor(g, x * T, y * T, x, y, styleAt(ctx, x, y))
            }
        }

        // 3. Room overlays: runners and big rugs.
        for (r in world.rooms) overlay(g, ctx, r, style(r.kind))

        // 4. Flat floor pieces (rugs) under everything else.
        for (f in world.furniture) if (!f.blocking && f.kind == FurnitureKind.RUG) {
            g.drawImage(sprites.furniture("obj_rug", f.w, f.h), f.x * T, f.y * T, null)
        }

        // 5. Warm light pooled in each room.
        for (r in world.rooms) light(g, r, style(r.kind))

        // 6. Ambient occlusion below north faces and beside side walls.
        for (y in 0 until world.height) for (x in 0 until world.width) {
            if (!isGround(world.tileAt(x, y)) || ctx.isFace(x, y)) continue
            if (ctx.isFace(x, y - 1) || ctx.isWall(x, y - 1)) {
                for (i in 0 until 5) { g.color = c(CozyPalette.NIGHT, 72 - i * 14); g.fillRect(x * T, y * T + i, T, 1) }
            }
            if (ctx.isWall(x - 1, y)) {
                g.color = c(CozyPalette.NIGHT, 44); g.fillRect(x * T, y * T, 2, T)
                g.color = c(CozyPalette.NIGHT, 20); g.fillRect(x * T + 2, y * T, 2, T)
            }
            if (ctx.isWall(x + 1, y)) { g.color = c(CozyPalette.NIGHT, 30); g.fillRect(x * T + T - 2, y * T, 2, T) }
        }

        // 7. Walls on top, autotiled from their neighbours.
        if (sprites.tilePng("tile_wall_top") == null) {
            for (y in 0 until world.height) for (x in 0 until world.width) if (world.tileAt(x, y) == Tiles.WALL) paintWall(g, ctx, x, y)
        }

        // 8. Wall-hung pieces on the faces, and soft shadows under standing furniture.
        for (f in world.furniture) {
            if (f.kind == FurnitureKind.RUG) continue
            if (!f.blocking) {
                g.drawImage(sprites.furniture("obj_" + f.kind.name.lowercase(), f.w, f.h), f.x * T, f.y * T, null)
                g.color = c(CozyPalette.NIGHT, 50)
                g.fillRect(f.x * T + 2, (f.y + f.h) * T - 2, f.w * T - 4, 1)
            } else {
                val fw = f.w * T
                val by = (f.y + f.h) * T
                g.color = c(CozyPalette.NIGHT, 62)
                g.fillOval(f.x * T + 1, by - 5, fw - 2, 5)
                g.color = c(CozyPalette.NIGHT, 40)
                g.fillOval(f.x * T + 3, by - 4, fw - 6, 3)
            }
        }
        g.dispose()
        return img
    }

    private fun pngFor(sprites: Sprites, ch: Char): BufferedImage? {
        if (!Sprites.PREFER_PNG_TILES) return null
        val name = when (ch) {
            Tiles.WALL -> "tile_wall_top"
            Tiles.FACE -> "tile_wall_face"
            Tiles.DOOR -> "tile_door"
            Tiles.CORRIDOR -> "tile_corridor"
            Tiles.FLOOR_WOOD -> "tile_floor_wood"
            Tiles.FLOOR_TILE -> "tile_floor_tile"
            Tiles.FLOOR_CARPET -> "tile_floor_carpet"
            else -> return null
        }
        return sprites.tilePng(name)
    }

    private fun styleAt(ctx: Ctx, x: Int, y: Int): RoomStyle =
        style(ctx.surfaceRoom(x, y)?.kind ?: if (ctx.t(x, y) == Tiles.CORRIDOR) RoomKind.HALLWAY else RoomKind.HALLWAY)

    // ---- floors ----

    fun paintFloor(g: Graphics2D, px: Int, py: Int, tx: Int, ty: Int, s: RoomStyle) {
        val hv = hash(tx, ty, 3)
        when (s.floor) {
            FloorMat.PLANKS, FloorMat.WORN_PLANKS, FloorMat.DIM_PLANKS -> {
                for (row in 0 until 4) {
                    val y = py + row * 4
                    val seam = Math.floorMod(hash(tx, ty * 4 + row, 11), 12) + 2
                    val shade = Math.floorMod(hash(tx, ty * 4 + row, 5), 3)
                    val base = if (shade == 0) s.floorB else s.floorA
                    g.color = c(base); g.fillRect(px, y, T, 4)
                    g.color = c(CozyPalette.NIGHT, 26); g.fillRect(px, y + 3, T, 1) // groove
                    g.color = c(CozyPalette.NIGHT, 40); g.fillRect(px + seam, y, 1, 3)
                    g.color = c(CozyPalette.CREAM, 22); g.fillRect(px, y, T, 1)
                }
                if (Math.floorMod(hv, 7) == 0) { g.color = c(CozyPalette.NIGHT, 50); g.fillRect(px + Math.floorMod(hv shr 3, 12) + 2, py + 5, 2, 1) }
                if (s.floor == FloorMat.WORN_PLANKS) {
                    g.color = c(CozyPalette.CREAM, 34); g.fillRect(px + Math.floorMod(hv shr 6, 9), py + 9, 5, 1)
                }
                if (s.floor == FloorMat.DIM_PLANKS) { g.color = c(CozyPalette.NIGHT, 40); g.fillRect(px, py, T, T) }
            }
            FloorMat.PARQUET -> {
                for (by in 0 until 2) for (bx in 0 until 2) {
                    val x0 = px + bx * 8
                    val y0 = py + by * 8
                    g.color = c(s.floorA); g.fillRect(x0, y0, 8, 8)
                    g.color = c(s.floorB)
                    if ((bx + by + tx + ty) % 2 == 0) { g.fillRect(x0, y0 + 3, 8, 1); g.fillRect(x0, y0 + 7, 8, 1) }
                    else { g.fillRect(x0 + 3, y0, 1, 8); g.fillRect(x0 + 7, y0, 1, 8) }
                }
                g.color = c(CozyPalette.CREAM, 26); g.fillRect(px + 1, py + 1, 3, 1)
            }
            FloorMat.CHECKER -> {
                for (by in 0 until 2) for (bx in 0 until 2) {
                    g.color = c(if ((bx + by) % 2 == 0) s.floorA else s.floorB)
                    g.fillRect(px + bx * 8, py + by * 8, 8, 8)
                }
                g.color = c(CozyPalette.CREAM, 40); g.fillRect(px + 1, py + 1, 2, 1); g.fillRect(px + 9, py + 9, 2, 1)
            }
            FloorMat.SMALL_TILES -> {
                g.color = c(s.floorA); g.fillRect(px, py, T, T)
                g.color = c(0xc9dcdc)
                for (i in 0 until T step 4) { g.fillRect(px + i, py, 1, T); g.fillRect(px, py + i, T, 1) }
                g.color = c(s.floorB)
                for (yy in 0 until 4) for (xx in 0 until 4) if ((xx + yy + tx + ty) % 4 == 0) g.fillRect(px + xx * 4 + 1, py + yy * 4 + 1, 3, 3)
            }
            FloorMat.BIG_TILES, FloorMat.SLATE -> {
                for (by in 0 until 2) for (bx in 0 until 2) {
                    val v = Math.floorMod(hash(tx * 2 + bx, ty * 2 + by, 9), 3)
                    g.color = c(if (v == 0) s.floorB else s.floorA)
                    g.fillRect(px + bx * 8, py + by * 8, 8, 8)
                }
                g.color = c(CozyPalette.NIGHT, if (s.floor == FloorMat.SLATE) 60 else 36)
                g.fillRect(px, py + 7, T, 1); g.fillRect(px + 7, py, 1, T)
                g.fillRect(px, py + 15, T, 1); g.fillRect(px + 15, py, 1, T)
                g.color = c(CozyPalette.CREAM, 40); g.fillRect(px + 1, py + 1, 2, 1); g.fillRect(px + 9, py + 9, 2, 1)
            }
            FloorMat.CARPET -> {
                g.color = c(s.floorA); g.fillRect(px, py, T, T)
                g.color = c(s.floorB)
                for (i in 0 until 6) g.fillRect(px + Math.floorMod(hv shr (i * 3), 15), py + Math.floorMod(hv shr (i * 3 + 1), 15), 1, 1)
                g.color = c(CozyPalette.CREAM, 18)
                for (i in 0 until 3) g.fillRect(px + Math.floorMod(hv shr (i * 5 + 2), 15), py + Math.floorMod(hv shr (i * 5 + 4), 15), 1, 1)
            }
            FloorMat.GRASS -> {
                g.color = c(s.floorA); g.fillRect(px, py, T, T)
                g.color = c(s.floorB)
                for (i in 0 until 5) {
                    val gx = px + Math.floorMod(hv shr (i * 4), 14) + 1
                    val gy = py + Math.floorMod(hv shr (i * 4 + 2), 13) + 2
                    g.fillRect(gx, gy, 1, 2); g.fillRect(gx + 1, gy - 1, 1, 2)
                }
                if (Math.floorMod(hv, 5) == 0) { g.color = c(0xd8cdb8); g.fillOval(px + 4, py + 5, 8, 6); g.color = c(0xbfb39c); g.fillRect(px + 5, py + 10, 6, 1) }
                if (Math.floorMod(hv, 11) == 1) { g.color = c(0xf6d6e0); g.fillRect(px + 10, py + 3, 2, 2) }
            }
        }
    }

    // ---- wall faces ----

    fun paintFace(g: Graphics2D, px: Int, py: Int, tx: Int, s: RoomStyle) {
        g.color = c(s.paper); g.fillRect(px, py, T, 11)
        g.color = c(s.paperAlt)
        when (s.pattern) {
            WallPattern.STRIPES -> for (i in 0 until T step 4) g.fillRect(px + i + 1, py, 1, 11)
            WallPattern.DOTS -> for (yy in 2 until 11 step 4) for (xx in ((yy / 4) % 2) * 2 until T step 4) g.fillRect(px + xx + 1, py + yy, 1, 1)
            WallPattern.DIAMONDS -> for (yy in 0 until 11) {
                val d = Math.floorMod(yy - 5, 8)
                g.fillRect(px + d, py + yy, 1, 1); g.fillRect(px + 15 - d, py + yy, 1, 1)
            }
            WallPattern.PLAIN -> { g.color = c(s.paperAlt, 90); g.fillRect(px, py + 2, T, 1) }
            WallPattern.TILES -> {
                g.fillRect(px, py + 5, T, 6)
                g.color = c(CozyPalette.CREAM, 120)
                g.fillRect(px, py + 7, T, 1); g.fillRect(px + 4 + (tx % 2) * 4, py + 5, 1, 6); g.fillRect(px + 12 - (tx % 2) * 4, py + 5, 1, 6)
            }
            WallPattern.BRICK -> for (row in 0 until 3) {
                val y = py + 1 + row * 3
                g.fillRect(px, y + 2, T, 1)
                val off = if (row % 2 == 0) 0 else 4
                g.fillRect(px + off, y, 1, 2); g.fillRect(px + off + 8, y, 1, 2)
            }
            WallPattern.LEAVES -> for (i in 0 until 3) {
                val lx = px + Math.floorMod(hash(tx, i, 13), 13) + 1
                val ly = py + 2 + i * 3
                g.fillRect(lx, ly, 2, 1); g.fillRect(lx + 1, ly + 1, 1, 1)
            }
        }
        // Soft shadow under the cap.
        g.color = c(CozyPalette.NIGHT, 60); g.fillRect(px, py, T, 1)
        g.color = c(CozyPalette.NIGHT, 26); g.fillRect(px, py + 1, T, 1)
        // Chair rail, wainscot, baseboard.
        g.color = c(CozyPalette.CREAM, 140); g.fillRect(px, py + 10, T, 1)
        g.color = c(s.wainscot); g.fillRect(px, py + 11, T, 4)
        g.color = c(CozyPalette.NIGHT, 40); g.fillRect(px + 7, py + 12, 1, 2); g.fillRect(px + 15, py + 12, 1, 2)
        g.color = c(CozyPalette.CREAM, 50); g.fillRect(px, py + 11, T, 1)
        g.color = c(CozyPalette.INK_SOFT); g.fillRect(px, py + 15, T, 1)
    }

    // ---- walls ----

    private const val CAP = 0x8a6a58
    private const val CAP_LIGHT = 0xa8866e
    private const val CAP_DARK = 0x5e463c
    private const val OUT_CAP = 0x5e463c
    private const val OUT_LIGHT = 0x76584a
    private const val OUT_DARK = 0x3e2c28

    /** Paints what a neighbouring tile shows (floor, face or nothing) into part of the wall tile at (x, y). */
    private fun paintSurfaceOf(g: Graphics2D, ctx: Ctx, sx: Int, sy: Int, x: Int, y: Int, clip: Rectangle) {
        val ch = ctx.t(sx, sy)
        if (ch == Tiles.VOID || ch == Tiles.WALL) return
        val old = g.clip
        g.clipRect(clip.x, clip.y, clip.width, clip.height)
        val s = styleAt(ctx, sx, sy)
        if (ctx.isFace(sx, sy)) paintFace(g, x * T, y * T, x, s)
        else {
            paintFloor(g, x * T, y * T, x, y, s)
            // Matches the darker room edges (light vignette + occlusion) next to the wall.
            g.color = c(CozyPalette.NIGHT, 60); g.fillRect(clip.x, clip.y, clip.width, clip.height)
        }
        g.clip = old
    }

    private fun paintWall(g: Graphics2D, ctx: Ctx, x: Int, y: Int) {
        val outer = ctx.outer(x, y)
        val t = if (outer) 8 else 6
        val up = ctx.isWall(x, y - 1)
        val down = ctx.isWall(x, y + 1)
        val left = ctx.isWall(x - 1, y)
        val right = ctx.isWall(x + 1, y)
        val faceBelow = ctx.isFace(x, y + 1) || ctx.isFace(x - 1, y + 1) || ctx.isFace(x + 1, y + 1)
        val groundAbove = isGround(ctx.t(x, y - 1))
        val horizontal = left || right
        // Where the horizontal band sits: on top of a north face, as the thin top edge of a south wall, or centred.
        val hy = when {
            !horizontal -> 8 - t / 2
            faceBelow -> T - t
            groundAbove && !isGround(ctx.t(x, y + 1)) -> 0
            else -> 8 - t / 2
        }
        val splitY = if (horizontal) (if (hy == 0) t else hy) else 8
        val px = x * T
        val py = y * T

        // Underlay: each quadrant borrows the nearest non-wall neighbour's surface.
        for (qy in listOf(-1, 1)) for (qx in listOf(-1, 1)) {
            val rect = Rectangle(px + if (qx < 0) 0 else 8, py + if (qy < 0) 0 else splitY, 8, if (qy < 0) splitY else T - splitY)
            if (rect.height <= 0) continue
            val cands = listOf(x + qx to y, x to y + qy, x + qx to y + qy)
            val src = cands.firstOrNull { (cx, cy) -> val ch = ctx.t(cx, cy); ch != Tiles.WALL && ch != Tiles.VOID }
            if (src != null) paintSurfaceOf(g, ctx, src.first, src.second, x, y, rect)
        }

        val cap = if (outer) OUT_CAP else CAP
        val light = if (outer) OUT_LIGHT else CAP_LIGHT
        val dark = if (outer) OUT_DARK else CAP_DARK
        val vx = px + 8 - t / 2
        fun band(rx: Int, ry: Int, rw: Int, rh: Int) {
            if (rw <= 0 || rh <= 0) return
            g.color = c(cap); g.fillRect(rx, ry, rw, rh)
        }
        // Horizontal arms.
        if (horizontal) {
            val x0 = if (left) px else vx
            val x1 = if (right) px + T else vx + t
            band(x0, py + hy, x1 - x0, t)
        }
        // Vertical arms.
        if (up || down || !horizontal) {
            val y0 = if (up) py else if (horizontal) py + hy else py + 8 - t / 2
            val y1 = if (down) py + T else if (horizontal) py + hy + t else py + 8 + t / 2
            band(vx, y0, t, y1 - y0)
        }
        // Edges: light top/left rim, dark bottom/right rim, only where the cap meets a non-wall surface.
        val img = Rectangle(px, py, T, T)
        for (yy in img.y until img.y + T) for (xx in img.x until img.x + T) {
            if (!inBand(xx - px, yy - py, horizontal, left, right, up, down, hy, t)) continue
            val n = inBand(xx - px, yy - py - 1, horizontal, left, right, up, down, hy, t) || (yy - py - 1 < 0 && up)
            val s = inBand(xx - px, yy - py + 1, horizontal, left, right, up, down, hy, t) || (yy - py + 1 >= T && down)
            val w = inBand(xx - px - 1, yy - py, horizontal, left, right, up, down, hy, t) || (xx - px - 1 < 0 && left)
            val e = inBand(xx - px + 1, yy - py, horizontal, left, right, up, down, hy, t) || (xx - px + 1 >= T && right)
            when {
                !s -> { g.color = c(dark); g.fillRect(xx, yy, 1, 1) }
                !n || !w -> { g.color = c(light); g.fillRect(xx, yy, 1, 1) }
                !e -> { g.color = c(dark, 160); g.fillRect(xx, yy, 1, 1) }
            }
        }
        // A south wall shows a little of its outer face below the top edge.
        if (horizontal && hy == 0 && !isGround(ctx.t(x, y + 1))) {
            g.color = c(dark); g.fillRect(px, py + t, T, 3)
            g.color = c(CozyPalette.NIGHT, 90); g.fillRect(px, py + t + 3, T, 1)
        }
        // Subtle texture on the cap.
        if (Math.floorMod(hash(x, y, 21), 3) == 0) {
            g.color = c(light, 120)
            val hx = vx + 1
            g.fillRect(hx, py + if (horizontal) hy + t / 2 else 4, 2, 1)
        }
    }

    private fun inBand(lx: Int, ly: Int, horizontal: Boolean, left: Boolean, right: Boolean, up: Boolean, down: Boolean, hy: Int, t: Int): Boolean {
        if (lx !in 0 until T || ly !in 0 until T) return false
        val vx = 8 - t / 2
        if (horizontal) {
            val x0 = if (left) 0 else vx
            val x1 = if (right) T else vx + t
            if (ly in hy until hy + t && lx in x0 until x1) return true
        }
        if (up || down || !horizontal) {
            val y0 = if (up) 0 else if (horizontal) hy else 8 - t / 2
            val y1 = if (down) T else if (horizontal) hy + t else 8 + t / 2
            if (lx in vx until vx + t && ly in y0 until y1) return true
        }
        return false
    }

    // ---- doors ----

    private fun paintDoor(g: Graphics2D, ctx: Ctx, x: Int, y: Int) {
        val px = x * T
        val py = y * T
        val wallL = ctx.isWall(x - 1, y) || ctx.isFace(x - 1, y) && ctx.t(x - 1, y) == Tiles.FACE
        val wallR = ctx.isWall(x + 1, y) || ctx.isFace(x + 1, y) && ctx.t(x + 1, y) == Tiles.FACE
        val wallU = ctx.isWall(x, y - 1)
        val wallD = ctx.isWall(x, y + 1)
        val eastWest = wallU && wallD && !wallL && !wallR
        if (eastWest) {
            // Doorway through a side wall: floors of both rooms, a threshold, and the wall's ends framed.
            paintSurfaceOf(g, ctx, x - 1, y, x, y, Rectangle(px, py, 8, T))
            paintSurfaceOf(g, ctx, x + 1, y, x, y, Rectangle(px + 8, py, 8, T))
            g.color = c(CozyPalette.WOOD_LIGHT); g.fillRect(px + 5, py, 6, T)
            g.color = c(CozyPalette.WOOD); g.fillRect(px + 5, py + 3, 6, 1); g.fillRect(px + 5, py + 8, 6, 1); g.fillRect(px + 5, py + 13, 6, 1)
            g.color = c(CozyPalette.WOOD_DARK); g.fillRect(px + 4, py, 1, T); g.fillRect(px + 11, py, 1, T)
            g.color = c(CozyPalette.WOOD_DARK); g.fillRect(px + 4, py, 8, 2); g.fillRect(px + 4, py + T - 2, 8, 2)
            return
        }
        val s = styleAt(ctx, x, y)
        paintFloor(g, px, py, x, y, s)
        val inFace = ctx.isFace(x, y)
        if (inFace) {
            // Opening in a north face: darker inside, wooden jambs on the wall sides, a threshold at the bottom.
            g.color = c(CozyPalette.NIGHT, 70); g.fillRect(px, py, T, 5)
            g.color = c(CozyPalette.NIGHT, 36); g.fillRect(px, py + 5, T, 3)
            g.color = c(CozyPalette.WOOD_LIGHT); g.fillRect(px, py + T - 2, T, 2)
            g.color = c(CozyPalette.WOOD_DARK); g.fillRect(px, py + T - 1, T, 1)
        }
        if (wallL) { g.color = c(CozyPalette.WOOD); g.fillRect(px, py, 3, T); g.color = c(CozyPalette.WOOD_DARK); g.fillRect(px + 2, py, 1, T) }
        if (wallR) { g.color = c(CozyPalette.WOOD); g.fillRect(px + T - 3, py, 3, T); g.color = c(CozyPalette.WOOD_DARK); g.fillRect(px + T - 3, py, 1, T) }
        if (!inFace && ctx.isFace(x, y + 1)) {
            // The wall-row half of a north doorway: a lintel shadow so the wall reads as cut through.
            g.color = c(CozyPalette.NIGHT, 40); g.fillRect(px, py + T - 3, T, 3)
        }
    }

    // ---- stairs ----

    private fun paintStairs(g: Graphics2D, ctx: Ctx, x: Int, y: Int) {
        val px = x * T
        val py = y * T
        val s = styleAt(ctx, x, y)
        paintFloor(g, px, py, x, y, s)
        val up = (ctx.stairsDir[y * ctx.w + x] ?: 1) > 0
        if (up) {
            // Steps rising toward the top: lighter as they climb, with side rails.
            for (i in 0 until 4) {
                val sy = py + i * 4
                val shade = listOf(0xe0b07a, 0xcf9c68, 0xbb8858, 0xa8774a)[i]
                g.color = c(shade); g.fillRect(px + 2, sy, 12, 4)
                g.color = c(CozyPalette.CREAM, 90); g.fillRect(px + 2, sy, 12, 1)
                g.color = c(CozyPalette.WOOD_DARK); g.fillRect(px + 2, sy + 3, 12, 1)
            }
            g.color = c(CozyPalette.WOOD_DARK); g.fillRect(px + 1, py, 2, T); g.fillRect(px + 13, py, 2, T)
            g.color = c(CozyPalette.WOOD_LIGHT); g.fillRect(px + 1, py, 1, T)
            // Little chevron up.
            g.color = c(CozyPalette.CREAM, 200); g.fillRect(px + 7, py + 5, 2, 1); g.fillRect(px + 6, py + 6, 1, 1); g.fillRect(px + 9, py + 6, 1, 1)
        } else {
            // Steps going down into shadow.
            g.color = c(CozyPalette.NIGHT); g.fillRect(px + 2, py, 12, T)
            for (i in 0 until 4) {
                val sy = py + i * 4
                val a = 255 - i * 55
                g.color = c(0xb8875a, a); g.fillRect(px + 2, sy, 12, 3)
                g.color = c(CozyPalette.CREAM, max(0, 70 - i * 20)); g.fillRect(px + 2, sy, 12, 1)
            }
            g.color = c(CozyPalette.WOOD_DARK); g.fillRect(px + 1, py, 2, T); g.fillRect(px + 13, py, 2, T)
            g.color = c(CozyPalette.CREAM, 200); g.fillRect(px + 7, py + 6, 2, 1); g.fillRect(px + 6, py + 5, 1, 1); g.fillRect(px + 9, py + 5, 1, 1)
        }
    }

    // ---- per-room overlays and light ----

    private fun interior(r: RoomDto) = Rectangle((r.x + 1) * T, (r.y + 2) * T, max(0, r.w - 2) * T, max(0, r.h - 3) * T)

    private fun overlay(g: Graphics2D, ctx: Ctx, r: RoomDto, s: RoomStyle) {
        val ir = interior(r)
        if (ir.isEmpty) return
        when (s.overlay) {
            Overlay.NONE -> Unit
            Overlay.RUNNER -> {
                // A long runner down the middle of the hall, along its longer side.
                val horizontal = ir.width >= ir.height
                val rect = if (horizontal) {
                    val rh = if (ir.height >= 3 * T) 12 else 10
                    Rectangle(ir.x + 6, ir.y + (ir.height - rh) / 2, ir.width - 12, rh)
                } else {
                    val rw = if (ir.width >= 3 * T) 12 else 10
                    Rectangle(ir.x + (ir.width - rw) / 2, ir.y + 6, rw, ir.height - 12)
                }
                if (rect.width <= 4 || rect.height <= 4) return
                g.color = c(0x8f4a44); g.fillRect(rect.x, rect.y, rect.width, rect.height)
                g.color = c(0xb8645a); g.fillRect(rect.x + 1, rect.y + 1, rect.width - 2, rect.height - 2)
                g.color = c(CozyPalette.HONEY, 170)
                g.drawRect(rect.x + 2, rect.y + 2, rect.width - 5, rect.height - 5)
                g.color = c(0xe6aea4, 120)
                if (horizontal) { var x = rect.x + 6; while (x < rect.x + rect.width - 6) { g.fillRect(x, rect.y + rect.height / 2, 2, 1); x += 6 } }
                else { var y = rect.y + 6; while (y < rect.y + rect.height - 6) { g.fillRect(rect.x + rect.width / 2, y, 1, 2); y += 6 } }
            }
            Overlay.BIG_RUG -> {
                if (r.w < 5 || r.h < 6) return
                val rx = (r.x + 2) * T + 4
                val ry = (r.y + 3) * T + 4
                val rw = (r.w - 4) * T - 8
                val rh = (r.h - 5) * T - 8
                if (rw <= 8 || rh <= 8) return
                g.color = c(CozyPalette.ROSE_DARK, 170); g.fillRoundRect(rx, ry, rw, rh, 6, 6)
                g.color = c(CozyPalette.ROSE, 190); g.fillRoundRect(rx + 2, ry + 2, rw - 4, rh - 4, 4, 4)
                g.color = c(0xe6aea4, 140); g.drawRect(rx + 5, ry + 5, rw - 11, rh - 11)
                g.color = c(CozyPalette.HONEY, 100)
                var fx = rx + 2
                while (fx < rx + rw - 2) { g.fillRect(fx, ry - 1, 1, 1); g.fillRect(fx, ry + rh, 1, 1); fx += 2 }
            }
        }
    }

    private fun light(g: Graphics2D, r: RoomDto, s: RoomStyle) {
        val interior = interior(r)
        if (interior.isEmpty) return
        val oldClip = g.clip
        g.clip = interior
        val cx = interior.centerX
        val cy = interior.centerY
        val m = max(interior.width, interior.height).toDouble()
        val rad = (m * 0.62).toFloat()
        val tx = AffineTransform().apply {
            translate(cx, cy); scale((interior.width / m).coerceAtLeast(0.2), (interior.height / m).coerceAtLeast(0.2)); translate(-cx, -cy)
        }
        g.paint = RadialGradientPaint(
            Point2D.Double(cx, cy), rad, Point2D.Double(cx, cy),
            floatArrayOf(0f, 0.55f, 1f),
            arrayOf(c(s.light, s.lightAlpha), c(s.light, s.lightAlpha / 3), c(CozyPalette.NIGHT, 50)),
            MultipleGradientPaint.CycleMethod.NO_CYCLE, MultipleGradientPaint.ColorSpaceType.SRGB, tx,
        )
        g.fill(interior)
        g.clip = oldClip
        g.paint = Color.BLACK
    }
}
