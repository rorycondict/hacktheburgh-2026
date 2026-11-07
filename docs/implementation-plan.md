# Implementation plan

This plan turns `docs/prd.md` into work for three parallel streams, **shared**, **backend** and **frontend**, all written against the contract below. Where this plan and the PRD disagree, this plan wins. The differences are listed first.

The working product name is **Floorplan**. It is not final, so the display name appears only in the bundle `.properties` files, and package names do not use it.

## Changes from the PRD

| PRD | This plan | Why |
| --- | --- | --- |
| Outdoor overworld: districts, buildings, roofs | **Interior house**: rooms joined by corridors, furniture for files, chibi characters with large heads | The user's art direction. Art is placeholder for now; the user will refine it |
| Separate `core`, `world`, `render`, `transport`, `plugin`, `cli` modules | Keep the template's `shared` / `backend` / `frontend` content modules, plus one new plain-JVM module, `acp-tap` | Split-mode plugin structure already exists; fewer moving parts |
| Ktor WebSocket transport | Plain TCP on 127.0.0.1, newline-delimited JSON, per-run token | No Ktor version clash with the IDE; the tap stays dependency-free |
| Tap parses ACP and publishes `AgentEvent`s | Tap forwards **raw frames**. The backend maps ACP → `AgentEvent` | The tap stays dumb and safe (PRD rule R4); the mapping is unit-testable; the raw log doubles as the replay source |
| jtokkit token counting | `chars / 4` estimate, labelled "estimate" | No extra dependency; swap later if needed |
| CLI (F15) | Out of scope for this pass | P1; the renderer would need to move out of the plugin |

## Task breakdown

P0 = needed for the demo loop. P1 = do it if P0 is done.

| # | Task | PRD ref | Stream | Pri |
| --- | --- | --- | --- | --- |
| 1 | Shared DTOs and the `VisualiserRpcApi` contract (below); remove the chat sample from `shared` | — | shared | P0 |
| 2 | `acp-tap` module: spawn agent, byte-exact stdio passthrough, raw JSONL log, send frames to every registered plugin server, accept `INJECT` lines | F1, T1 | backend | P0 |
| 3 | Fat-jar the tap and ship it inside the plugin sandbox (`<plugin dir>/tap/acp-tap.jar`) | F1 | backend | P0 |
| 4 | Tap server (app service): TCP listener, token, registry file, per-connection ACP mapper, routing by `cwd` to projects | F1, T4 | backend | P0 |
| 5 | ACP → `AgentEvent` mapper plus unit tests from sample ACP lines | T3 | backend | P0 |
| 6 | Session reducer (project service) → `StateFlow<List<SessionDto>>`: activity, usage, cost, plan, permission, touched files, config options | F5, F6, F8, F9, F13, F14 | backend | P0 |
| 7 | `pricing.json` and the cost model (agent-reported cost wins; otherwise estimate), projection range, "same tokens on other models" | F6, F9 | backend | P0 |
| 8 | World builder: index project files (LOC, language, git churn), deterministic house layout, `WorldDto` | F3, F16 | backend | P0 |
| 9 | Room and file summaries: heuristic always; LLM when `OPEN_AI_KEY` is set (Koog if it resolves cleanly, else plain HTTPS); cache per git HEAD in `.overworld/` | F4 | backend | P0 |
| 10 | RPC impl: `world`, `sessions`, `openFile`, `rebuildWorld`, `wrapAgents`, `status` | F7 | backend | P0 |
| 11 | "Wrap my agents": add tap-wrapped copies of each `~/.jetbrains/acp.json` entry (back up first) | UX | backend | P0 |
| 12 | Replay of a raw tap log through the same mapper (demo safety) | F18, R7 | backend | P1 |
| 13 | `setConfigOption` via tap `INJECT` of `session/set_config_option` | F11 | backend | P1 |
| 14 | `ask` about a file or room (LLM with code as context) | F10 | backend | P1 |
| 15 | Sprites as flat PNGs in `assets/` (interior set, names below); the frontend build copies them into resources | T5 | frontend | P0 |
| 16 | Remove the chat sample; frontend project service exposing `world` / `sessions` as `StateFlow` (`durable {}`) | — | frontend | P0 |
| 17 | Java2D map renderer in the tool window: tiles, furniture, room labels, integer nearest-neighbour scaling | F2 | frontend | P0 |
| 18 | Characters: one per session, palette-swapped; BFS pathing on walkable tiles; walk to the file's furniture; activity icons | F2, F8, F14 | frontend | P0 |
| 19 | HUD: agent, model, effort, tokens, context bar, cost so far + projected range, other-model costs | F5, F6, F9 | frontend | P0 |
| 20 | Hover tooltip (path + summary) and click → `openFile` | F7 | frontend | P0 |
| 21 | Permission → "!" icon on the map + IDE balloon notification | F8 | frontend | P0 |
| 22 | Toolbar actions: Wrap my agents, Rebuild map, Replay last session | UX, F18 | frontend | P0/P1 |
| 23 | Quest log panel from `plan` | F13 | frontend | P1 |
| 24 | Model/effort picker from `configOptions` → `setConfigOption` | F11 | frontend | P1 |
| 25 | Ask box for the hovered/selected file | F10 | frontend | P1 |

## Module ownership

Each stream edits **only** its own paths. If you need something from another stream, work around it and report it. Don't edit their files.

| Stream | Owns |
| --- | --- |
| shared | `shared/**` |
| backend | `backend/**`, `acp-tap/**` (new), `settings.gradle.kts`, root `build.gradle.kts` |
| frontend | `frontend/**`, `assets/**` (new) |

Packages: `com.example.model` (DTOs), `com.example.rpc` (API), `com.example.tap` (tap), `com.example.backend.*`, `com.example.frontend.*`. The plugin id stays `com.example.htb`.

## Shared contract (exact)

The shared stream writes these exactly. Backend and frontend code against them.

### `com.example.model` — world

```kotlin
/** Tile legend for WorldDto.tiles. */
object Tiles {
    const val VOID = ' '          // outside the house; not drawn
    const val WALL = '#'          // top of a wall, seen from above
    const val FACE = '='          // front face of a north wall (3/4 view), drawn under a WALL row
    const val DOOR = 'd'          // doorway through a FACE row
    const val CORRIDOR = ':'      // hallway / corridor floor
    const val FLOOR_WOOD = 'w'
    const val FLOOR_TILE = 't'
    const val FLOOR_CARPET = 'c'
    fun isWalkable(c: Char): Boolean = c == DOOR || c == CORRIDOR || c == FLOOR_WOOD || c == FLOOR_TILE || c == FLOOR_CARPET
}

@Serializable data class TilePos(val x: Int, val y: Int)

@Serializable data class WorldDto(
    val width: Int,
    val height: Int,
    val tiles: String,              // row-major, exactly width*height chars from Tiles
    val rooms: List<RoomDto>,
    val furniture: List<FurnitureDto>,
    val spawn: TilePos,             // where idle characters wait (lobby)
    val terminal: TilePos,          // walkable tile in front of the server rack; EXECUTE goes here
    val revision: String,           // git HEAD, or "" if not a git repo
    val generatedAtMillis: Long,
)

@Serializable data class RoomDto(
    val id: String,                 // stable: the room's project-relative dir, or "/" for the lobby
    val name: String,               // display name, e.g. "backend"
    val path: String,               // project-relative dir with '/' separators; "" for the lobby
    val summary: String,
    val x: Int, val y: Int, val w: Int, val h: Int,   // outer bounds in tiles, walls included
    val fileCount: Int,             // all files under the room, not only the shown ones
    val loc: Int,
    val floor: Char,                // one of Tiles.FLOOR_*
)

enum class FurnitureKind { DESK, BOOKSHELF, CABINET, TEST_BENCH, PLANT, SERVER }

@Serializable data class FurnitureDto(
    val path: String,               // project-relative file path ('/' separators); "" for SERVER
    val roomId: String,
    val kind: FurnitureKind,
    val x: Int, val y: Int,         // tile the furniture occupies (not walkable)
    val access: TilePos,            // walkable tile where a character stands to use it
    val loc: Int,
    val language: String,           // e.g. "Kotlin", "Markdown"; "" if unknown
    val churn: Int,                 // commits touching it in the last 30 days
    val summary: String,
)
```

### `com.example.model` — sessions

```kotlin
enum class ActivityKind { IDLE, THINKING, MESSAGE, READ, EDIT, DELETE, MOVE, SEARCH, EXECUTE, FETCH, OTHER, WAITING_PERMISSION }

@Serializable data class ActivityDto(
    val kind: ActivityKind,
    val title: String,              // tool-call title or short text; "" if none
    val path: String?,              // project-relative if inside the project, else absolute; null if none
    val line: Int?,
    val failed: Boolean,
    val toolCallId: String?,
    val sinceMillis: Long,
)

@Serializable data class UsageDto(
    val inputTokens: Long,
    val outputTokens: Long,
    val tokensEstimated: Boolean,
    val contextUsed: Long?,         // from usage_update, if the agent sends it
    val contextSize: Long?,
    val costSoFar: Double,
    val currency: String,           // "USD"
    val costEstimated: Boolean,     // false only when the agent reported cost
    val projectedCostLow: Double?,  // null when there is not enough data
    val projectedCostHigh: Double?,
    val alternativeCosts: Map<String, Double>,   // model id -> cost of the same tokens on that model
)

@Serializable data class PlanEntryDto(val content: String, val priority: String, val status: String) // status: pending | in_progress | completed

@Serializable data class PermissionDto(val toolCallId: String, val title: String, val options: List<String>)

@Serializable data class ToolCallDto(val toolCallId: String, val kind: String, val title: String, val status: String, val path: String?)

@Serializable data class ConfigValueDto(val value: String, val name: String)

@Serializable data class ConfigOptionDto(
    val id: String,
    val name: String,
    val category: String?,          // e.g. "model", "thought_level"/"effort", "mode"
    val currentValue: String,
    val values: List<ConfigValueDto>,
)

@Serializable data class SessionDto(
    val sessionId: String,
    val agentName: String,          // from the tap's --name (the acp.json key)
    val cwd: String,
    val model: String?,
    val effort: String?,
    val mode: String?,
    val turnActive: Boolean,
    val activity: ActivityDto,
    val usage: UsageDto,
    val plan: List<PlanEntryDto>,
    val pendingPermission: PermissionDto?,
    val touchedFiles: List<String>,      // most recent last, max 50
    val recentToolCalls: List<ToolCallDto>, // most recent last, max 20
    val configOptions: List<ConfigOptionDto>,
    val lastEventMillis: Long,
    val replay: Boolean,                 // true if driven by a replayed log
)

@Serializable data class WrapResultDto(val acpJsonPath: String, val wrapped: List<String>, val alreadyWrapped: List<String>, val message: String)

@Serializable data class TapStatusDto(val port: Int, val registryFile: String, val logsDir: String, val connectedTaps: Int)
```

### `com.example.rpc`

```kotlin
@Rpc
interface VisualiserRpcApi : RemoteApi<Unit> {
    companion object {
        suspend fun getInstance(): VisualiserRpcApi = RemoteApiProviderService.resolve(remoteApiDescriptor<VisualiserRpcApi>())
    }
    /** Emits null while the first index is running, then each new world. */
    suspend fun world(projectId: ProjectId): Flow<WorldDto?>
    suspend fun sessions(projectId: ProjectId): Flow<List<SessionDto>>
    suspend fun rebuildWorld(projectId: ProjectId)
    /** path: project-relative or absolute. line: 1-based, optional. */
    suspend fun openFile(projectId: ProjectId, path: String, line: Int?)
    suspend fun wrapAgents(): WrapResultDto
    /** Replays a raw tap log into this project. logFile null = newest log in ~/.overworld/logs. Returns a status message. */
    suspend fun startReplay(projectId: ProjectId, logFile: String?): String
    /** Returns false if the session or option is unknown, or the agent is not behind a tap. */
    suspend fun setConfigOption(projectId: ProjectId, sessionId: String, configId: String, value: String): Boolean
    /** path: project-relative file or room dir. Returns the answer, or a message explaining why there isn't one. */
    suspend fun ask(projectId: ProjectId, path: String, question: String): String
    suspend fun status(): TapStatusDto
}
```

## Interior world layout (backend; frontend draws whatever it gets)

- **Rooms:** a **lobby** (root-level files, the server rack, the spawn point), then one room per top-level directory that has content files. A room for a large directory such as `frontend` may be named after its biggest source root. Skip `.git`, `.idea`, `.gradle`, `.intellijPlatform`, `.kotlin`, `build`, `out`, `node_modules`, `.overworld`, and anything the IDE excludes.
- **Furniture:** each room shows the top N (≤ 12) files by LOC + churn. Kind by file type:
  - `DESK`: source code
  - `BOOKSHELF`: docs (`.md`, `.txt`, `.adoc`, `.rst`)
  - `CABINET`: config/build (`.gradle.kts`, `.kts`, `.json`, `.yaml`, `.yml`, `.toml`, `.xml`, `.properties`)
  - `TEST_BENCH`: tests (path contains `/test/` or the name ends with `Test`/`Spec`)
  - `PLANT`: assets/images and anything else
  - `SERVER`: one per world, in the lobby; this is the "terminal" for EXECUTE
- **Room shape:** top wall row `#`, face row `=`, interior floor rows, bottom wall row `#`, side walls `#`. Furniture sits in rows on interior rows 0, 2, 4…, with aisles on 1, 3, 5…. Interior columns 0 and W-1 stay free, so every aisle connects. `access` = the tile below the furniture.
- **House:** rooms hang above horizontal hallways (`:`), with a door gap in each room's bottom wall. Hallway bands stack downwards and are joined by a vertical corridor on the left. Any room that opens onto a hallway through its north wall gets a `d` in its face row. Every walkable tile must be reachable from `spawn`.
- **Determinism:** the same files give the same layout (sort by path, no randomness).

## Activity → animation (frontend)

| Activity | Character | Icon / effect |
| --- | --- | --- |
| READ | Walks to the file's furniture (`access` tile) | `icon_read` |
| EDIT | Walks to the furniture, plays `hero_work` | `icon_edit`; desk shows `obj_desk` (screen on) |
| DELETE / MOVE | Walks to the furniture | `icon_edit` |
| SEARCH | Stays | `icon_search` |
| EXECUTE | Walks to `world.terminal` | `icon_execute`; `obj_server_error` + `icon_failed` if `failed` |
| FETCH | Stays | `icon_fetch` |
| THINKING / MESSAGE | Stays | `icon_thought` |
| WAITING_PERMISSION | Stops | `icon_alert` + IDE balloon |
| any with `failed` | — | `icon_failed` |
| IDLE (turn ended) | Walks back to `world.spawn` | `icon_idle` after a few seconds |

Target lookup: furniture whose `path` equals `activity.path`. If there isn't one, the room with the longest matching `path` prefix, at its first free interior tile. Otherwise stay put. Unknown absolute paths → stay.

Desks show `obj_desk_off` unless the file is in `touchedFiles` (`obj_desk`) or the agent's last tool call on it failed (`obj_desk_error`).

## Sprite names (one PNG each in `assets/`, e.g. `assets/obj_desk.png`; 16×16 unless noted)

- Tiles: `tile_wall_top`, `tile_wall_face`, `tile_door`, `tile_corridor`, `tile_floor_wood`, `tile_floor_tile`, `tile_floor_carpet`
- Furniture: `obj_desk`, `obj_desk_off`, `obj_desk_error`, `obj_bookshelf`, `obj_cabinet`, `obj_test_bench`, `obj_plant`, `obj_server_0`, `obj_server_1`, `obj_server_error`
- Character (chibi, head ~10 of 16 rows): `hero_{down,up,right,left}_{0,1}`, `hero_{down,up}_2`, `hero_work_0`, `hero_work_1`. The tunic uses exactly `#3d72d6` / `#26498f` so the renderer can swap them per agent.
- Icons (≤ 16 wide): `icon_alert`, `icon_thought`, `icon_read`, `icon_edit`, `icon_search`, `icon_execute`, `icon_fetch`, `icon_failed`, `icon_idle`
- Animations: `hero_walk_{down,up,left,right}`, `hero_work`, `obj_server_blink`

## Tap wire protocol (backend owns both ends)

- **Registry:** each IDE writes `~/.overworld/servers/<uuid>.properties` with `port`, `token` and `pid`, and deletes it on exit. The tap skips entries whose pid is dead.
- **Tap → server:** the first line is `{"type":"hello","token":…,"agent":…,"tapId":…,"pid":…}`. After that, one line per ACP message: `{"type":"frame","dir":"to_agent"|"from_agent","ts":<millis>,"line":<raw JSON-RPC line as a string>}`.
- **Server → tap:** `INJECT <json-rpc line>` writes that line to the agent's stdin. Injected requests use string ids prefixed `floorplan-`, and the tap drops their responses instead of forwarding them to Air.
- **Raw log:** `~/.overworld/logs/<yyyyMMdd-HHmmss>-<agent>.jsonl`, with the same `frame` objects. Replay reads it.
- **Tap rules:** stdout carries protocol bytes only, and is flushed per line. Logs go to stderr. Forward first, copy second. A tap with no listening server still forwards.
