# PRD — Pixel Agent Visualiser

Nov 7, 2026 · @Rory

## Summary

We will build **Overworld** (working name): an IntelliJ IDEA plugin, plus a standalone CLI, that turns the codebase into a pixel-art map and the AI agent running in JetBrains Air into a character exploring it. Every read, search, edit, command, plan step, permission prompt and token spent shows up on the map in real time, alongside a HUD for model, cost and context usage.

**Problem.** Agents in Air work fast and out of sight. Developers lose track of which files the agent touched, whether it is stuck, how much context it has left and what the session is costing. When no agent is running, the same map is still a navigable picture of the codebase: hotspots, recent churn, structure.

**How it differs from pixel-agents.** [pixel-agents](https://github.com/pixel-agents-hq/pixel-agents) draws agents as office workers at desks and only knows what the agent is doing. Overworld draws the *codebase itself* as the world, so you see *where* the agent is working, not just *that* it is working. It also adds cost/usage, model control and click-to-ask/navigate.

**Key technical bet.** Air launches custom agents from `~/.jetbrains/acp.json` as `command` + `args`. We register a thin **ACP tap**: a proxy that launches the real agent (Codex, Claude, etc.), forwards every byte unchanged, and copies the stream of ACP events to our plugin. This works for any ACP agent, needs no access to Air internals, and is the same seam our own agent will plug into later.

## Judging criteria mapping

Criterion 1 is weighted highest, so the P0 scope is a working end-to-end loop with a real agent in Air, not a polished mock.

| # | Criterion (priority order) | How Overworld scores | Evidence in the demo |
| --- | --- | --- | --- |
| 1 | Implementation and proof of concept | Live ACP tap on a real Air session; map built from the real open project; real token/cost numbers | Start a task in Air, character moves to the right files as it works |
| 2 | User experience and interface | Lives in a tool window next to Air; zero config after one click ("Wrap my agents"); clicking a building opens the file | Click a building → file opens in the editor; permission prompt → character raises a flag |
| 3 | Innovation and creativity | Codebase as the game world, not an office; goes beyond "prompt around an IDE action" | Side-by-side: same agent run, with and without Overworld |
| 4 | Agentic development process | Team builds it with several Air agents in parallel (one per module) and reviews diffs; Overworld visualises its own construction | Recording of 2–3 agents working on our repo, shown in Overworld |
| 5 | Presentation | 3-minute script with one clear story; design decisions on one slide | Demo script in the last section |

Criterion 4 rewards multi-agent work and comparing agents' results, so we plan agent assignments per subtask and keep a short log of what each agent produced and what we rejected.

## Goals, non-goals and success metrics

**Goals for the hackathon**

1. Show what the current Air agent is doing, live, for the currently open project.
2. Make cost and context usage visible, with a projection for the rest of the task.
3. Give a generated, clickable overview of the codebase that is useful even with no agent running.
4. Let the developer pick a model and effort level with cost in view.
5. Keep one integration seam (the ACP tap + event model) that our own agent can use later.

**Non-goals for the hackathon**

- Replacing Air's chat, diff review or permission UI. We mirror state; Air stays the place you approve and review.
- Supporting every agent's quirks. P0 targets one agent the team actually uses; others work if they speak standard ACP.
- A full layout editor, sound, or multiplayer. These are stretch only.
- Generating sprites at runtime during the demo. Art is generated ahead of time and cached.

**Success metrics for the demo**

- Event-to-animation latency under 300 ms.
- 100% of tool calls in a demo session appear on the map, attributed to the right building when the tool call has a file location.
- Displayed session cost within 10% of the provider's figure (when the agent reports cost) or of our token estimate.
- Zero behaviour change in the agent: Air works identically with or without the tap.
- World map for a 50k-line repo generated in under 60 s, then cached.

## Users and user stories

The primary user is a developer running one or more agents in Air on the project open in IntelliJ IDEA. The secondary user is a developer joining an unfamiliar codebase, with no agent running.

- As a developer running an agent, I can glance at the map and see which files it is reading or editing, so I can catch it wandering off-task.
- As a developer, I am alerted visually when the agent is waiting for my permission, so it does not sit idle.
- As a developer, I can see tokens used, context remaining, cost so far and projected cost to finish, so I can stop or switch model before overspending.
- As a developer, I can see which model and effort level is active, and change them with the cost difference shown.
- As a developer, I can follow the agent's plan as a quest log with steps ticking off.
- As a newcomer, I can see the codebase as named districts with one-line summaries, click a building to open the file, or ask a question about that area.
- As a team, we can run several agents at once and see each as its own character.
- As a terminal user, I can run the same view outside the IDE with one CLI command.

## Functional requirements

P0 must work end to end for the demo; P1 is planned if P0 lands by the midpoint; P2 is stretch or post-hackathon.

| ID | Requirement | Priority | Asked by |
| --- | --- | --- | --- |
| F1 | ACP tap launches the real agent, forwards all traffic unchanged, and publishes parsed events to listeners | P0 | Rory |
| F2 | IntelliJ tool window renders the pixel map and the agent character, animated from live events | P0 | Rory |
| F3 | Map is generated from the open project: districts per top-level module, buildings per significant file | P0 | Rory |
| F4 | Agent-generated codebase overview: one-line summary per district, cached per git commit | P0 | Rory |
| F5 | HUD shows current agent, model and effort level | P0 | Rory, Kevin |
| F6 | HUD shows tokens used, context used/size, session cost so far | P0 | Rory |
| F7 | Click a building to open its file in the editor; hover shows path and summary | P0 | James |
| F8 | Permission requests shown on map and as an IDE balloon notification | P0 | — |
| F9 | Projected cost and tokens to finish the task, from plan progress and per-step averages | P1 | Rory |
| F10 | Ask a question about a district or file from the map; answer from an OpenAI model with that code as context | P1 | James |
| F11 | Switch model and effort from the HUD, with estimated cost per model shown before switching | P1 | Kevin |
| F12 | Model/effort recommendation based on task complexity and budget | P2 | Kevin |
| F13 | Plan shown as a quest log with entries ticking off | P1 | — |
| F14 | Multiple concurrent sessions shown as separate characters | P1 | — |
| F15 | Standalone CLI: `overworld watch` opens the same view outside the IDE; `overworld wrap` edits `acp.json` | P1 | Rory |
| F16 | Codebase-only mode: git churn heatmap, IDE problems shown as damaged buildings | P1 | Rory |
| F17 | Transcript fallback for agents not launched through the tap | P2 | — |
| F18 | Session replay from a recorded event log | P2 | — |

**Notes on Kevin's model switching (F11).** ACP lets an agent expose session configuration options, such as a model picker, which the client changes with `session/set_config_option`. Because the tap sits on the wire, it can send that request itself and then forward the resulting config update to Air so Air's UI stays in sync. If the agent exposes no such option, the HUD falls back to a recommendation the user applies in Air. This needs testing against the chosen agent early (risk R2).

## Visual direction and pixel-art pipeline

The codebase is a top-down 16×16-tile overworld, in the style of a handheld RPG map, with a fixed 32-colour palette. The agent is an adventurer moving between buildings. This keeps it clearly pixel art but distinct from pixel-agents' office interior.

**World from codebase**

- District = top-level module or source root, sized by lines of code, with a name and summary from the overview agent.
- Building = significant file (top N per district by size and churn); roof colour by language.
- Test directories = training yard; build/config files = town hall; docs = library.
- Recent git churn = lit windows; IDE errors = cracked walls; files the agent touched this session = footprint trail.
- Layout is deterministic (seeded from project path) so the map is stable between runs.

**Agent activity to animation** (ACP tool-call kinds)

| Agent signal | Animation |
| --- | --- |
| `read` | Walks to the file's building, opens a book |
| `search` | Climbs the lighthouse, beam sweeps districts |
| `edit` | Hammers at the building; scaffolding appears |
| `delete` / `move` | Demolition dust / pushes a cart between buildings |
| `execute` | Works the forge at the terminal tower; red smoke on failure |
| `fetch` | Sends a carrier pigeon off the map edge |
| Thought chunks / `think` | Sits at a campfire with a thought bubble |
| Permission request | Stops and raises a "!" banner |
| Tool call `failed` | Small storm cloud over the character |
| Turn ends | Walks back to camp, idles |

**HUD.** Model shown as character class and nameplate; effort as walking speed and aura; context used/size as a backpack bar; cost as a gold counter with a projected total; plan as a quest log.

**How the art gets made (recommended: hybrid)**

1. **Sprites as code (primary).** Characters, tiles and small buildings are written as palette-indexed text grids (one character per pixel, e.g. 16×16), compiled to a PNG atlas at build time. An LLM can author these reliably at 16×16–32×32, they diff cleanly in review, and they are easy work to hand to Air agents (criterion 4). Animation frames come from small programmatic offsets (bob, arm swing) plus a few hand-tuned frames. Claude can write this set directly; expect serviceable rather than beautiful results, so budget an hour of human touch-up.
2. **Image model for set pieces (optional).** Larger landmarks and a title card from an OpenAI image model with a transparent background, then snapped to a true pixel grid and quantised to our palette with [proper-pixel-art](https://github.com/KennethJAllen/proper-pixel-art). Run offline; commit the results.
3. **Runtime: recolour and compose only.** Per-agent palette swaps, district colours and the generated layout. No image generation during the demo, so no latency or rate-limit risk.

The pixel-agents character pack is third-party art; we do not reuse it.

## System architecture

Air talks ACP to our tap exactly as it would to the agent; the tap is the only new component on the agent's path, and everything visual hangs off its copy of the event stream.

&#91;embedded content: System architecture · tap, event bus, plugin, world builder, CLI\]

The world builder writes `world.json`, which the plugin loads to draw the map. The plugin can also send commands back through the bus to the tap, which uses them for model and effort switching.

**How the tap is wired in.** The "Wrap my agents" action (or `overworld wrap`) adds a second entry per agent to `~/.jetbrains/acp.json`, keeping the original:

```json
{
  "agent_servers": {
    "Junie (Overworld)": {
      "command": "/path/to/overworld-tap",
      "args": ["--", "junie", "--acp=true"],
      "env": { "OVERWORLD_AGENT_NAME": "Junie" }
    }
  }
}
```

The real agent command and arguments follow `--` unchanged. Air needs a restart after the file changes.

**Tap rules.** Stdout carries only protocol bytes; logs go to stderr. Each JSON-RPC line is forwarded first and parsed second, so a parse error never blocks the agent. Unknown methods and fields pass through untouched. If no plugin is listening, the tap still forwards and simply drops events.

**Linking sessions to the project.** `session/new` carries the working directory; the plugin matches it to the open project's base path, so each IDE window shows only its own agents.

**Standalone mode.** `overworld watch` starts the same bus and renderer in its own window and registers itself, so a tap launched by Air or any other ACP client reports to it. Without the IDE, clicking a building prints the path or opens it with the `idea` launcher.

## Event model and data schema

Every source (ACP tap, transcript watcher, our future agent) is normalised into one versioned `AgentEvent` stream; renderers and the HUD only ever see this model, never raw ACP.

| AgentEvent | Built from (ACP) | Key fields |
| --- | --- | --- |
| `SessionStarted` | `session/new` request + response | sessionId, agentName (from `acp.json` key), cwd, model |
| `TurnStarted` / `TurnEnded` | `session/prompt` request / its response | promptPreview, stopReason |
| `Thought`, `Message` | `agent_thought_chunk`, `agent_message_chunk` | messageId, text (truncated) |
| `ToolStarted` | `tool_call` | toolCallId, kind, title, locations (paths, lines) |
| `ToolUpdated` | `tool_call_update` | toolCallId, status (pending, in\_progress, completed, failed) |
| `PlanUpdated` | `plan` (stable) or `plan_update` (newer schema) | entries: content, priority, status |
| `PermissionRequested` / `Resolved` | `session/request_permission` and its response | toolCallId, options, outcome |
| `UsageUpdated` | `usage_update` | used, size, cost {amount, currency} if sent |
| `TokensEstimated` | our own count of streamed text | inTokens, outTokens, method = estimate |
| `ConfigChanged` | `config_option_update`, `current_mode_update` | model, effort, mode |
| `Custom` | any `sessionUpdate` starting with `_` | raw payload (used by our own agent later) |

**Cost model.** A local `pricing.json` maps model id → input, cached-input and output price per million tokens. Prices change often, so the file is checked against OpenAI's pricing page on the day and is user-editable. When the agent sends `usage_update` with a cost, that figure wins; otherwise we estimate from token counts. `usage_update` is newer and not every agent sends it, so the estimate path is required, not optional. Junie Lite runs are free, so in the demo the HUD shows real token counts, $0 spent, and what the same tokens would have cost on each paid model in pricing.json. That comparison is also the basis for Kevin's model-switch view.

**Projection (F9).** Projected cost = cost so far + (remaining plan entries × mean cost per completed entry). With no plan, use mean cost per turn. Show it as a range, not a single number, and label it as an estimate.

**World model.** `world.json` in `.overworld/` under the project: districts (id, path, name, summary, bounds), buildings (path, LOC, language, churn, tile position), keyed by git HEAD plus a file-tree hash. A VFS listener adds or removes buildings incrementally.

## Tech stack, alternatives and project structure

Everything is Kotlin on the JVM in one Gradle build, so the plugin, CLI and tap share the same event model and renderer.

| Layer | Choice | Why |
| --- | --- | --- |
| Language / build | Kotlin 2.x, JVM 21, Gradle multi-module | Native to IntelliJ; one model shared everywhere |
| Plugin | IntelliJ Platform Gradle Plugin, target IntelliJ IDEA 2026.2 with Air installed | Air ships as a plugin in 2026.2 and is bundled in 2026.3 EAP |
| Renderer (decided) | Java2D on a Swing panel, integer scaling, nearest-neighbour | Works in a tool window and a standalone window with no extra runtime; lowest risk |
| ACP tap | Raw newline-delimited JSON-RPC over stdio with kotlinx.serialization; ACP Kotlin SDK types where helpful | Byte-exact passthrough; tolerant of fields we do not know |
| Local transport | Ktor WebSocket on 127.0.0.1 with a per-run token; servers register in `~/.overworld/servers/` | Several taps can reach several IDE windows; same pattern pixel-agents uses |
| LLM calls | Koog (JetBrains agent framework) with the OpenAI provider | Named by the challenge; easy tool-calling for the overview and Q&A agents |
| Token counting | jtokkit (o200k encoding) | Estimates when the agent sends no usage |
| CLI | Clikt | `watch`, `wrap`, `index`, `replay` subcommands |

**Alternatives worth considering**

- **TypeScript + Canvas/PixiJS renderer in JCEF.** One web renderer for the tool window and for the CLI in a browser; richer animation tooling. Costs a second language and a JCEF bridge. Best if a teammate is strong on web.
- **Compose Multiplatform / Jewel.** Modern Kotlin UI in the tool window and a standalone desktop app. Check that the target IDE version supports Compose in plugins before choosing it; fall back to Java2D if not.
- **Python** for the offline sprite pipeline only (Pillow, proper-pixel-art). Not shipped.
- **Rust or Go** for the tap as a tiny native binary with instant start-up. Not needed at hackathon scale; JVM start-up of about 0.5 s is acceptable.

**Project structure**

```text
overworld/
  settings.gradle.kts
  core/          AgentEvent model, state store, cost model, pricing.json, world model types (pure Kotlin, no IDE deps)
  acp-tap/       ACP proxy: spawn agent, pipe stdio, parse + publish events, optional set_config_option injection
  transport/     WebSocket server/client, server registry, auth token
  world/         codebase indexer (tree, LOC, git churn), layout generator, overview + Q&A agents (Koog/OpenAI)
  render/        sprite atlas loader, tile map, character state machine, HUD, Java2D renderer
  plugin/        tool window, settings, "Wrap my agents" action, file navigation, notifications, VFS listener
  cli/           overworld watch | wrap | index | replay
  assets/
    sprites/     *.pxl palette-grid sources (agent-authored)
    palette.json
    generated/   compiled atlas PNG + JSON (build output, committed for the demo)
  tools/
    sprite-compiler/   .pxl -> PNG atlas (Gradle task)
    image-pipeline/    optional Python: image model -> pixel snap -> palette quantise
  docs/
    agent-log.md       which Air agent did what, and what we rejected (for criterion 4)
```

Module boundaries follow team ownership so parallel agents rarely edit the same files.

## Subtasks, owners and timeline

The first job is to prove the tap works with a real Air session (T1); everything else depends on it, so it gets two people until it does. Owners follow each person's asks and are suggestions; the fourth member owns art and the demo.

| ID | Subtask | Phase | Owner | Depends on | Hand to an Air agent? |
| --- | --- | --- | --- | --- | --- |
| T1 | Spike: tap that spawns the agent, passes stdio through, logs every message to a file; register it in `acp.json` | 1 | Rory + Kevin | — | No, hand-write; it is the riskiest piece |
| T2 | Gradle multi-module skeleton, plugin with empty tool window | 1 | James | — | Yes (scaffold) |
| T3 | `core` event model + ACP → AgentEvent mapper with tests from T1's recorded logs | 1 | Rory | T1 | Yes, review carefully |
| T4 | Transport: WebSocket server in plugin, registry file, tap client | 2 | Rory | T2, T3 | Yes |
| T5 | Palette, `.pxl` format and sprite compiler; first 10 sprites (character 4 directions × 2 frames, 6 tiles) | 1 | Member 4 | — | Yes, two agents in parallel, compare results |
| T6 | Codebase indexer: tree, LOC, language, git churn | 2 | James | T2 | Yes |
| T7 | Layout generator: districts and buildings on a tile grid | 2 | James | T6 | Partly |
| T8 | Renderer: tile map, character state machine, pathing between buildings | 2 | Rory | T5, T7 | Partly |
| T9 | Overview agent (Koog + OpenAI): district names and summaries, cached | 2 | James | T6 | Yes |
| T10 | HUD: model, effort, tokens, context bar, cost; `pricing.json` | 2 | Kevin | T3 | Yes |
| T11 | Click building → open file; hover tooltip | 2 | James | T8 | Yes |
| T12 | Permission flag + IDE notification | 3 | Kevin | T4, T8 | Yes |
| T13 | Cost projection (F9) | 3 | Kevin | T10 | Yes |
| T14 | Model/effort switch via `session/set_config_option` through the tap (F11) | 3 | Kevin | T1, T10 | No, test by hand against the real agent |
| T15 | Ask-about-this-district panel (F10) | 3 | James | T9, T11 | Yes |
| T16 | Quest log, multi-session characters | 3 | Rory | T8 | Yes |
| T17 | CLI: `watch` (standalone window) and `wrap` (edit `acp.json`) | 3 | Rory | T4, T8 | Yes |
| T18 | Demo repo, recorded fallback session (replay), slides, agent log | 4 | Member 4 leads, all help | — | No |

**Gates.** Gate A (end of phase 1, about hour 4 of 18): Air runs an agent through the tap with no visible difference, and we have recorded logs. Gate B (end of phase 2, about hour 10): the character walks to the right building during a real session in the tool window. Gate C (start of phase 4, about hour 15): feature freeze; only fixes, art and demo from here. If Gate A slips past hour 5, switch to the transcript fallback (F17) as the event source and keep the rest of the plan unchanged.

## Extensibility: our own agent working in tandem

Our own Air agent becomes one more ACP agent behind the same tap, so the visualiser needs no changes to show it; it can then send richer signals the tap passes straight through.

- **Same seam.** Built with Koog's ACP support, the agent is registered in `acp.json` as `overworld-tap -- our-agent`. Every standard event already renders.
- **Custom signals.** ACP reserves `sessionUpdate` values starting with `_` for implementation-specific updates and asks proxies to preserve them. Our agent can emit `_overworld_intent` ("heading to the auth module to fix the token bug"), `_overworld_focus` (a district to highlight) or `_overworld_budget`. Other clients ignore them.
- **Tandem patterns.** (a) Orchestrator: our agent splits a task and delegates to Codex/Claude sessions; each session is a character, with the orchestrator as a party leader. (b) Router: our agent picks model and effort per step using the cost model (Kevin's F12) and switches via config options. (c) Reviewer: a second agent follows the first and posts review notes as signs on buildings.
- **Pluggable event sources.** `EventSource` interface with implementations `AcpTapSource`, `TranscriptSource` (fallback), `ReplaySource` (demo safety) and later `NativeAgentSource` (in-process, no tap).
- **Pluggable renderers.** `SceneRenderer` interface with Java2D now; a web renderer or Compose renderer can be added without touching events or world.
- **Versioned schema.** `AgentEvent` carries `schemaVersion`; the transport rejects unknown major versions rather than misrendering.

## Risks, open questions and demo script

The biggest risk is the tap not behaving identically to a direct agent in Air; T1 exists to retire it first.

| ID | Risk | Mitigation |
| --- | --- | --- |
| R1 | Air's built-in agents (not from `acp.json`) bypass the tap | Demo with a wrapped entry, "Junie (Overworld)", running Junie CLI in ACP mode; transcript fallback for others |
| R2 | Chosen agent exposes no model config option, so in-place switching fails | Show the recommendation and let the user switch in Air |
| R3 | Agent sends no `usage_update` | Token estimate with jtokkit + `pricing.json`, labelled as estimate |
| R4 | Tap breaks the stream (stdout pollution, buffering) | Log only to stderr; flush per line; passthrough test with recorded sessions |
| R5 | `acp.json` changes need an IDE restart | Run `wrap` once at the start; document it |
| R6 | Sprite quality is weak | Small 16×16 style; human touch-up hour; image-model set pieces as backup |
| R7 | Live demo fails (network, rate limits) | `ReplaySource` plays a recorded real session |
| R8 | Pricing figures stale | Editable `pricing.json`, checked on the day; prefer agent-reported cost |

**Decisions and open questions**

- [x] Demo agent: Junie (Junie Lite where possible), wrapped as `junie --acp=true` behind the tap.
- [x] Renderer: Java2D in Kotlin.
- [x] Time and team: about 18 hours, 4 people; the fourth member owns art and the demo.
- [ ] Are free Junie Lite runs available through Junie CLI, or only through Air's built-in Junie entry? Test in T1; if only built-in, run Junie CLI with our OpenAI key (bring-your-own-key).
- [ ] Does Junie expose model and effort as ACP session config options? Decides whether F11 switches in place or only recommends.
- [ ] Final product name.

**Demo script (about 3 minutes)**

1. Problem in one line: agents in Air are fast and invisible; you lose track of where they are and what they cost.
2. Open our own repo: the map is already there with districts and summaries. Click a building; the file opens.
3. Start a task in Air with the wrapped Junie agent. The character searches from the lighthouse, walks to the right district, reads, hammers. The quest log ticks off.
4. Agent asks permission; the banner and IDE balloon appear; approve in Air.
5. HUD: cost so far and projected range; switch to a cheaper model and watch the projection drop.
6. Show the agent log: how three Air agents built this, what we kept and what we rejected.
7. Close on extensibility: our own agent drops into the same tap.

## Sources

- [pixel-agents (GitHub)](https://github.com/pixel-agents-hq/pixel-agents): reference project; hooks and transcript detection, server registry pattern.
- [JetBrains Air: browse, install and add custom ACP agents](https://www.jetbrains.com/help/air/air-in-ides-manage-agents.html): `acp.json` location and `command`/`args`/`env` fields.
- [Air in IDEs overview](https://www.jetbrains.com/help/air/air-in-ides-overview.html): plugin availability in 2026.2, bundled in 2026.3 EAP.
- [ACP schema, Rust crate source (v0.13.6)](https://docs.rs/agent-client-protocol-schema/0.13.6/src/agent_client_protocol_schema/v2/client.rs.html): session update variants, `usage_update` fields, `_`-prefixed custom updates.
- [nexos.ai: JetBrains Air integration](https://docs.nexos.ai/gateway-api/integrations/jetbrains-air-integration): worked `acp.json` examples with `env`.
- [proper-pixel-art](https://github.com/KennethJAllen/proper-pixel-art): snapping AI-generated pixel art to a true grid.

* [Junie for ACP clients](https://junie.jetbrains.com/docs/junie-cli-acp.html): running Junie CLI in ACP mode.
* [Air in IDEs EAP announcement](https://blog.jetbrains.com/ai/2026/10/air-in-ides-eap/): free Junie Lite runs need a JetBrains Account.
