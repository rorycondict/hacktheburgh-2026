# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

HackTheBurgh 2026 entry (JetBrains track). The goal, described in `docs/prd.md`, is **Overworld**: an IntelliJ IDEA plugin that draws the open codebase as a pixel-art map and shows a JetBrains Air agent as a character moving around it, with a HUD for model, cost and context usage. Live events come from an **ACP tap**, a stdio proxy registered in `~/.jetbrains/acp.json` that wraps the real agent.

**Current state:** the repo is still JetBrains' modular split-mode plugin template. Its sample chat tool window ("ModularPlugin", which gives fake AI replies) is a placeholder. None of the Overworld modules from the PRD (`core`, `acp-tap`, `transport`, `world`, `render`, `cli`) exist yet. Read `docs/prd.md` before building features: it defines the `AgentEvent` model, the tap rules, the animation mapping, and the decided stack (Java2D renderer, kotlinx.serialization, Ktor WebSocket transport, Koog with OpenAI, jtokkit, Clikt).

## Build and run

Gradle wrapper (Gradle 9.8), Kotlin 2.4, targets IntelliJ IDEA 2026.2.4. The configuration cache and build cache are both on (`gradle.properties`).

```sh
./gradlew build             # compile all modules and assemble the plugin
./gradlew runIdeSplitMode   # launch sandboxed backend + frontend IDEs with the plugin (main dev loop)
./gradlew runIdeBackend     # backend IDE only
./gradlew runIdeFrontend    # frontend (thin client) only
./gradlew buildPlugin       # distributable zip in build/distributions
./gradlew test              # platform test framework is configured, but no tests exist yet
./gradlew test --tests 'com.example.SomeTest'   # single test class
```

On Windows use `gradlew.bat`. The matching IntelliJ run configurations are in `.run/`. Sandbox IDE logs are under `.intellijPlatform/sandbox/*/*/log_runIdeBackend/idea.log` and `.../log_runIdeFrontend/frontend/*/idea.log`.

`.env` (gitignored, template in `.env.example`) holds `OPEN_AI_KEY` for the planned OpenAI/Koog features.

## Architecture: split-mode plugin

The root project is the plugin, with `splitMode = true`, installed into **both** the frontend and the backend IDE. It is made of three content modules, declared in `src/main/resources/META-INF/plugin.xml`:

- **`shared`** (`htb.shared`, `loading="required"`): code used on both sides, i.e. `@Rpc` interfaces, `@Serializable` DTOs and custom serializers. Anything that crosses the process boundary lives here.
- **`backend`** (`htb.backend.xml`): runs in the backend IDE, which holds the project model, files and long-running work. It implements the RPC APIs and registers them through the `platform.rpc.backend.remoteApiProvider` extension (`BackendRpcApiProvider`).
- **`frontend`** (`htb.frontend.xml`): runs in the client IDE. It owns the UI (Swing tool window via `ModularPluginToolWindowFactory`), view models and frontend services.

The root `build.gradle.kts` applies the `rpc`, Kotlin JVM, serialization and `intellij.platform.module` plugins to every subproject. Each module's `build.gradle.kts` only lists its bundled platform modules and `project(":shared")`.

### RPC data flow (pattern to copy for new features)

1. Define an `@Rpc interface X : RemoteApi<Unit>` in `shared`, with a `getInstance()` that calls `RemoteApiProviderService.resolve(remoteApiDescriptor<X>())`. Methods are `suspend` and may return `Flow<…>` for streams. Pass `ProjectId` (not `Project`) to identify the project.
2. Put the payloads in `shared` as `@Serializable` DTOs, with `toDto()`/`fromDto()` mappers (see `dtos.kt` and `LocalDateTimeSerializer` in `serializers.kt`).
3. On the backend, implement the interface (`BackendChatRepositoryRpcApi`). It resolves `projectId.findProjectOrNull()`, returns an empty result if that is null, and delegates to a project-level `@Service` that holds state in a `MutableStateFlow` (`BackendChatRepositoryModel`). Then register it in `BackendRpcApiProvider`.
4. On the frontend, a project `@Service` (`FrontendChatRepositoryModel`) wraps the RPC calls in `durable { … }` so that they survive reconnects, and exposes the result as a `StateFlow` through `stateIn`. View models get coroutine scopes from `CoroutineScopeHolder.createScope(...)` and are registered with `Disposer` against the tool window.

Register new extensions in the XML descriptor of the side where they run, not in the root `plugin.xml`.

Package root is `com.example` and the plugin id is `com.example.htb`. Both are template defaults and haven't been renamed yet.
