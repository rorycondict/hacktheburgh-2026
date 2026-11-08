# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

HackTheBurgh 2026 entry (JetBrains track). The goal, described in `docs/prd.md`, is **Overworld**: an IntelliJ IDEA plugin that draws the open codebase as a pixel-art map and shows a JetBrains Air agent as a character moving around it, with a HUD for model, cost and context usage. Live events come from an **ACP tap**, a stdio proxy registered in `~/.jetbrains/acp.json` that wraps the real agent.

**Read `docs/implementation-plan.md` before `docs/prd.md`.** The plan overrides the PRD and holds the task list, the exact shared contract and module ownership. Its main differences from the PRD:
- The art is an **interior house** (rooms joined by corridors, furniture for files, big-headed characters), not an outdoor overworld.
- The tap talks to the plugin over plain TCP, not Ktor.
- The tap forwards raw ACP frames, and the backend maps them.
- Summaries use plain `java.net.http`, not Koog.

The working product name is "Floorplan". It is not final and appears only in the `product.name` keys of the frontend and backend bundle `.properties` files, plus `<name>` in `plugin.xml`.

## Build and run

Gradle wrapper (Gradle 9.8), Kotlin 2.4, targets IntelliJ IDEA 2026.2.4. The configuration cache and build cache are both on (`gradle.properties`).

```sh
./gradlew build             # compile all modules and assemble the plugin
./gradlew runIdeSplitMode   # launch sandboxed backend + frontend IDEs with the plugin (main dev loop)
./gradlew runIdeBackend     # backend IDE only
./gradlew runIdeFrontend    # frontend (thin client) only
./gradlew buildPlugin       # distributable zip in build/distributions
./gradlew :backend:test :acp-tap:test                 # unit tests (plain JUnit 5 via kotlin("test"))
./gradlew :backend:test --tests 'com.example.backend.acp.*'   # one package/class
./gradlew :acp-tap:jar      # fat jar → acp-tap/build/libs/acp-tap.jar (also copied to <plugin>/tap/ by prepareSandbox)
```

Sprites are flat PNGs in `assets/`, one per sprite, named as the renderer refers to them (`tile_*`, `obj_*`, `hero_*`, `icon_*`). The user draws them by hand, so don't generate or overwrite them. The frontend build copies them to `/floorplan/sprites/`, and `frontend/.../render/Sprites.kt` loads them by name. A missing PNG draws as a magenta placeholder. That file also holds the animation frame lists and the tunic colours (`#3d72d6` / `#26498f`) that get recoloured per agent. To feed the UI without a real agent, use the tool window's **Replay last session** action, which plays the newest log in `~/.overworld/logs/`, or the bundled `backend/src/main/resources/floorplan/sample-session.jsonl` if there is none. Tests use that same file.

On Windows use `gradlew.bat`. The matching IntelliJ run configurations are in `.run/`. Sandbox IDE logs are under `.intellijPlatform/sandbox/*/*/log_runIdeBackend/idea.log` and `.../log_runIdeFrontend/frontend/*/idea.log`.

`.env` (gitignored, template in `.env.example`) holds `OPEN_AI_KEY` for the planned OpenAI/Koog features.

## Architecture: split-mode plugin

The root project is the plugin, with `splitMode = true`, installed into **both** the frontend and the backend IDE. It is made of three content modules, declared in `src/main/resources/META-INF/plugin.xml`:

- **`shared`** (`htb.shared`, `loading="required"`): code used on both sides, i.e. `@Rpc` interfaces, `@Serializable` DTOs and custom serializers. Anything that crosses the process boundary lives here.
- **`backend`** (`htb.backend.xml`): runs in the backend IDE, which holds the project model, files and long-running work. It implements the RPC APIs and registers them through the `platform.rpc.backend.remoteApiProvider` extension (`BackendRpcApiProvider`).
- **`frontend`** (`htb.frontend.xml`): runs in the client IDE. It owns the UI (Swing tool window via `ModularPluginToolWindowFactory`), view models and frontend services.

The root `build.gradle.kts` applies the `rpc`, serialization and `intellij.platform.module` plugins to `shared`, `frontend` and `backend` only.

- **`acp-tap`** is a separate plain-JVM app that depends only on kotlin-stdlib. Air launches it via `~/.jetbrains/acp.json`, it wraps the real agent, and it streams raw frames to every IDE listed in `~/.overworld/servers/`.
- **Wrap My Agents** (`backend/.../wrap/`) writes the wrapped entries. It covers custom `acp.json` agents and the agents Air installed from the ACP registry. `AirAgents` reads Air's `<JetBrains config>/acp-agents/installed.json` and resolves each agent to its unpacked binary, or for npx agents to Air's bundled node runtime plus the package's `bin` script, under `<IDE system dir>/acp-agents/`.
- **Event path:** tap → backend `TapServer` (TCP) → `AcpEventMapper` → `SessionReducer` / `SessionService` (project service, `StateFlow<List<SessionDto>>`) → `VisualiserRpcApi.sessions` → frontend `VisualiserFrontendService` → renderer and HUD.
- **World path:** `ProjectIndexer` (+ `DependencyScanner`) → room plan → `WorldLayout` → `VisualiserRpcApi.world`. The room plan is the agent's, if it designed one through the MCP tools in `backend/.../mcp/FloorplanToolset.kt` (served by the IDE's bundled MCP server, `com.intellij.mcpServer`; persisted by `FloorplanPlanService` in `.overworld/floorplan.json`). Otherwise `HeuristicPlanner` groups files into rooms by role. Rooms have a `RoomKind`, and `RoomThemes` maps each kind to its floor, furniture set and decor. Related rooms are placed next to each other and share a door.
- **User path:** `UserPositionService` (selected editor file) → `VisualiserRpcApi.userPosition` → `Scene.setUserPath` (the "You" character).
- The tap wire protocol is in the plan.

### RPC data flow (pattern to copy for new features)

1. Define an `@Rpc interface X : RemoteApi<Unit>` in `shared`, with a `getInstance()` that calls `RemoteApiProviderService.resolve(remoteApiDescriptor<X>())`. Methods are `suspend` and may return `Flow<…>` for streams. Pass `ProjectId` (not `Project`) to identify the project.
2. Put the payloads in `shared` as `@Serializable` DTOs, with `toDto()`/`fromDto()` mappers (see `dtos.kt` and `LocalDateTimeSerializer` in `serializers.kt`).
3. On the backend, implement the interface (`VisualiserRpcApiImpl`). It resolves `projectId.findProjectOrNull()` and delegates to project `@Service`s that hold `StateFlow` state. Then register it in `BackendRpcApiProvider`.
4. On the frontend, a project `@Service` (`VisualiserFrontendService`) wraps the RPC calls in `durable { … }` so that they survive reconnects, and exposes the results as `StateFlow`s. UI scopes come from `CoroutineScopeHolder.createScope(...)` and are disposed with the tool window.

Paths in session DTOs use `/` separators. They are project-relative when inside the project, else absolute, and must equal `FurnitureDto.path` for the character to walk to that piece of furniture.

Register new extensions in the XML descriptor of the side where they run, not in the root `plugin.xml`.

Package root is `com.example`: `com.example.model` and `com.example.rpc` (shared), `com.example.backend.*`, `com.example.frontend.*` and `com.example.tap`. The plugin id is `com.example.htb`. Both are template defaults and haven't been renamed yet.
