# Sprites

Pixel art for the map, written as text. Each `.pxl` file is a set of palette-indexed grids, one character per pixel. The art here is **placeholder**: refine it freely, but keep the sprite names, since the renderer looks sprites up by name.

```
assets/
  palette.json          32 colours: {"K": {"name": "ink", "hex": "#1b1a26"}, ...}
  sprites/*.pxl         sprite sources (tiles, furniture, hero, icons)
  generated/            compiler output (committed; the build only copies it)
    atlas.png           every sprite packed into one image
    atlas.json          {"image", "palette": {key: hex}, "sprites": {name: {x,y,w,h}}, "animations": {name: {frameMs, frames}}}
    preview.png         every sprite at 4x with its name: look at this after editing
    sprites/<name>.png  each sprite on its own
```

## Regenerate

From the repo root (needs only a JDK):

```sh
java tools/sprite-compiler/SpriteCompiler.java assets
```

Commit `assets/generated/` afterwards. The frontend build copies `atlas.png` and `atlas.json` into its resources at `/floorplan/`, and it never runs the compiler.

## `.pxl` format

```
# comment
@sprite name            rows follow, one palette key per pixel; a blank line ends the sprite
....KKKK....            '.' is always transparent; every row must be the same width

@mirror name from src                 horizontal flip of src
@recolor name from src a=b c=d        copy of src with keys swapped (applied all at once)
@anim name frameMs frame1 frame2 ...  animation over existing sprites
```

A source sprite must be defined before you `@mirror` or `@recolor` it. Files are read in name order.

## Rules

- **Tiles are 16x16.** The map is 3/4 top-down: `tile_wall_top` is the dark top of a wall, `tile_wall_face` is the front of a north wall (plaster above a wood wainscot), and `tile_door` is an opening through a face row.
- **Furniture is 16x16 on a transparent background.** It occupies one tile, and characters stand on the tile below it.
- **The character is a chibi.** The head is rows 0 to 9 (10 of 16), and the body is rows 10 to 15.
- **`u` (tunic) and `B` (tunic shadow) are reserved for the character's tunic.** The renderer swaps those two colours per agent, so don't use them in any other sprite.
- **Icons are at most 16 wide** and are drawn above the character's head.

## Names

| Group | Sprites | Used for |
| --- | --- | --- |
| Tiles | `tile_wall_top` `#`, `tile_wall_face` `=`, `tile_door` `d`, `tile_corridor` `:`, `tile_floor_wood` `w`, `tile_floor_tile` `t`, `tile_floor_carpet` `c` | One per `WorldDto.tiles` char (`Tiles`) |
| Desk | `obj_desk` (screen on), `obj_desk_off`, `obj_desk_error` | `DESK` (source). Off unless the file is in `touchedFiles`; error if the last tool call on it failed |
| Furniture | `obj_bookshelf`, `obj_cabinet`, `obj_test_bench`, `obj_plant` | `BOOKSHELF` docs, `CABINET` config/build, `TEST_BENCH` tests, `PLANT` everything else |
| Server | `obj_server_0`, `obj_server_1`, `obj_server_error` | `SERVER`, the terminal for EXECUTE. It blinks, and shows the error sprite while an EXECUTE has failed |
| Hero | `hero_{down,up,left,right}_{0,1}`, `hero_{down,up}_2`, `hero_work_{0,1}` | Agents. `_0` is standing; `left` is mirrored from `right` |
| Icons | `icon_alert`, `icon_thought`, `icon_read`, `icon_edit`, `icon_search`, `icon_execute`, `icon_fetch`, `icon_failed`, `icon_idle` | Activity above the head |
| Animations | `hero_walk_{down,up,left,right}`, `hero_work`, `obj_server_blink` | Walking, working at a desk, server LEDs |
