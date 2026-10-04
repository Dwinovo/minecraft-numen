---
name: stronghold_finding
description: Craft eyes of ender, find the stronghold with the locate structure command (no eye-throwing needed), reach the portal room, and fill the 12 frames via numen.scan.block + numen.use.block.
---

# Skill: stronghold_finding

Phase 5 of the dragon route. With rods and pearls in hand you craft eyes, walk straight to the stronghold — **`numen.locate.structure("minecraft:stronghold")` replaces the whole throw-and-triangulate ritual** — and activate the End portal.

## Done when

- You're standing at an **activated End portal** (all 12 frames filled, purple portal surface visible)
- Dragon-fight gear still intact (`numen.status.self` check against `dragon_combat`'s packlist)

## Step 1 — craft the eyes

Both are 2×2/shapeless recipes — `numen.inv.make` makes them in your own grid, no crafting table needed (the `containers` skill shows how to lay a grid by hand).

1. `blaze_powder` — each blaze rod grinds into 2 powder.
2. `ender_eye` (×12) — 1 blaze powder + 1 ender pearl each.

12 is the worst case; frames generate pre-filled at 10% each (~1–2 typically), so spares may remain. **Never throw eyes to navigate** — `numen.locate.structure("minecraft:stronghold")` is free and exact; eyes are only for the frames.

## Step 2 — go there

1. `numen.locate.structure("minecraft:stronghold")` → coordinates, direction, distance (often 1000–2500 blocks; the journey is the long part).
2. `numen.move.to` the column it gave (its `pos`, e.g. `numen.move.to({x = 1200, z = -340})`) to cross the surface, then descend where you stand with `numen.move.to({y = 30}, {costs = {dig = true, place = true}})` — navigation digs down on its own. Strongholds sit around Y 6–50.
3. Hit stone bricks → you're inside. `numen.scan.blocks("end_portal_frame")` to find the portal room; no match → explore the corridors with `numen.move.explore` (it heads out a hop at a time in a direction and calls the until_ function you give after each hop — one that rescans for end_portal_frame). (Stronghold corridors are stone_bricks / mossy_stone_bricks / cracked_stone_bricks.)

## Step 3 — secure the portal room

The room has a lava pool under the frame and a **silverfish spawner** on the stairs:

1. `numen.scan.blocks("spawner")` and hand the spawner's cluster to `numen.work.mine` immediately — unlike the blaze spawner, this one is pure liability.
2. If silverfish are already out, scan them, then `numen.fight.attack` each runtime id (or `numen.fight.clear()`); don't let them burrow into the brickwork.
3. Cover the lava pool edges where you'll stand with cobblestone: `numen.build.place` a row of it drawn with `numen.shape.line`, or `numen.build.place({{name = "cobblestone", pos = {x = 120, y = 64, z = -35}}})` for single cells.

## Step 4 — fill the frames

1. The 12 `end_portal_frame` blocks ring a 3×3 opening. `numen.scan.blocks("end_portal_frame")` returns them as one group that lists all 12 positions.
2. `numen.scan.block` each frame — the `has_eye` property tells you which are pre-filled.
3. `numen.use.block({x = 120, y = 64, z = -35}, {item = "minecraft:ender_eye"})` on each empty frame, one line per frame. **Eyes cannot be taken back out.**
4. The 12th eye activates the portal; the opening fills with the starfield surface.

## Before dropping in

- Load the `dragon_combat` skill **now**, not after jumping — the fight needs a plan.
- Verify the dragon packlist and **tell your owner the portal is active and where it is.** They may want to set their respawn nearby and come watch — entering the End is one-way until the dragon dies.

## What to load next

`dragon_combat`. This is the final phase.
