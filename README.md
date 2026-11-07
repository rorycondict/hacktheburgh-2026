# HackTheBurgh 2026: irn_bru

Tracks:
- JetBrains

## Floorplan (working name)

An IntelliJ IDEA plugin that draws your project as a pixel-art house, with one room per module and furniture for its files. AI agents running in JetBrains Air appear as characters that walk to the files they read and edit. A heads-up display (HUD) shows each agent's model, tokens, context and cost.

- Product: `docs/prd.md`
- What's built and how it's split up: `docs/implementation-plan.md`
- Working in the repo: `CLAUDE.md`

### Try it

```sh
./gradlew runIdeSplitMode      # opens a sandbox IDE with the plugin
```

1. Open the **Floorplan** tool window. The map of the project draws itself.
2. Click **Wrap my agents**, then restart the IDE. Pick a "(Floorplan)" agent in Air and give it a task.
3. With no agent available, click **Replay last session**. It plays the newest log from `~/.overworld/logs/`, or a bundled sample session if there are none yet.

Set `OPEN_AI_KEY` (environment or `.env`) to get AI-written room summaries and the **Ask** box.

### Art

Each sprite is its own PNG in `assets/` (for example `obj_desk.png` or `hero_down_0.png`); edit them directly. A missing or misnamed PNG shows on the map as a magenta checkerboard. To give each agent its own tunic colour, the character PNGs must keep the tunic colours `#3d72d6` and `#26498f`. Animation frame order and timing are in `frontend/.../render/Sprites.kt`.
