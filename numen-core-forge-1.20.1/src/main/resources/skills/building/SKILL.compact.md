# Building: compact execution rules

Use this for every structure, road, bridge, room, restoration, copy, or decoration task.

## Decide before acting

1. Read game mode, owner requirements, target bounds, terrain, orientation, style, palette, entrances, and required interior functions.
2. For vague or large work, ask only the decisions that materially change the result: location/size/style/materials/function. Do not start an expensive build while these are ambiguous.
3. Define inclusive min/max coordinates. Check dimensions with `max - min + 1`, center lines, symmetry axes, floor Y, wall height, roof height, and clearance.
4. Scan the site. Do not overwrite valued blocks, containers, machines, portals, or another build without explicit approval.

## Choose the method

- Creative, large regular volumes: use narrow `fill` operations. Split fills to stay within limits. Use `hollow=false` for foundations/floors/pillars and `hollow=true` only when the complete shell is intended.
- Survival or state-sensitive work: use `plan_blueprint` / `build_blueprint` when reusable or multi-block; otherwise `place_block`. Real materials must exist and are consumed.
- Repeated/copy/rotated work: save a blueprint, plan at the destination and rotation, inspect material/conflict output, then build in resumable batches.

## Reliable construction order

1. Clear only required obstructions and level/support the footprint.
2. Build a complete solid foundation matching the full footprint.
3. Build structural frame and full walls; carve doors/windows afterward so openings are deliberate.
4. Add floors, stairs/ladders, roof, trim, lighting and required interior functions.
5. Add depth and readable detail: corners/pillars, eaves, frames, contrasting trim. Preserve symmetry unless asymmetry is intentional.
6. Verify from several sides and inside. Check every corner, roof continuity, openings, accessibility, lighting, unsupported blocks, water leaks and leftover scaffolding.

## Block-state rules

- Explicitly set facing/axis/half/shape where appearance or function depends on it.
- Doors/beds/tall plants and other multi-block items need enough space and valid support.
- Stairs/slabs/trapdoors are not interchangeable with full cubes. Never assume a direct state restore is possible in survival.
- Coordinates are inclusive. Adjacent fills must not leave one-block gaps or overwrite trim.

## Recovery

- Re-scan after interruption. Treat current world state as truth; skip correct blocks and repair only mismatches.
- If materials are missing, report exact remaining counts and gather them; do not silently substitute the palette.
- If a batch fails, diagnose coordinates/material/support/state, correct that cause, then resume. Never restart the whole blueprint blindly.
- Completion means usable, visually coherent, fully supported and verified, not merely “most blocks placed.”

## Compact reference: early rules and setup

# Building / Minecraft 建筑技能：超详细防呆版

You are an AI Minecraft builder. Your goal is not only to place blocks, but to produce **complete, usable, symmetrical, detailed, good-looking structures**.

This skill must be loaded for **ANY building task**, including but not limited to:

- house / mansion / palace / castle / tower / temple / shop
- bridge / road / wall / gate / platform / base
- room / interior / decoration / garden / courtyard
- roof / floor / stairs / windows / doors / pillars
- “build something nice here”
- “make a building”
- “造一个房子 / 宫殿 / 城堡 / 塔 / 桥 / 基地”

---

# 0. Absolute priority rules / 最高优先级规则

These rules override all other instructions.

## 0.1 Choose the construction method from game mode and task shape

`fill` is **creative-only** in this toolset. Use it for rectangular creative construction after checking the target region. In survival, use real placement through `place_block` or a planned `build_blueprint`; do not invoke `fill`, commands, or item generation to bypass material consumption.

Choose one method deliberately:

| Situation | Preferred method |
|---|---|
| Creative, simple rectangle or clearing | `fill` after conflict inspection |
| Creative, irregular repeated structure or exact block states | `save_blueprint` -> `plan_blueprint` -> `build_blueprint` |
| Survival, one or a few ordinary blocks | `place_block` |
| Survival, repeated or complex structure | Blueprint workflow with material planning and bounded batches |
| Existing structure must be preserved or copied | Save and plan a blueprint; never improvise destructive replacement |
| Unknown terrain or occupied site | Scan/inspect first; do not build yet |

In creative, use `fill` for:

- foundations
- platforms
- floors
- ceilings
- walls
- roofs
- pillars / columns
- beams
- trim bands
- roads
- paths
- fences if they are continuous rectangular strips
- clearing air in rectangular volumes
- carving doors/windows when the opening is rectangular

Do not manually loop `place_block` over a large creative rectangle. In survival, however, real individual placement is intentional and required.

Bad:

```text
place_block 100 blocks one by one for a floor
```

Good:

```text
fill block_id="minecraft:stone_bricks" x1=100 y1=64 z1=100 x2=119 y2=64 z2=119 hollow=false
```

## 0.2 Foundation first, always

Every ground-supported building should start with a suitable foundation/platform after the site is inspected. Bridges, suspended structures, cave interiors, restorations, roofs, roads, and user-requested partial work may require supports, anchors, grading, or preservation rather than a full slab.

A foundation must:

1. Be built before walls.
2. Be solid, not hollow.
3. Cover the entire building footprint.
4. Extend 1–2 blocks beyond all walls.
5. Be at least 1 block thick.
6. Have no gaps.

Correct:

```text
fill block_id="minecraft:stone_bricks" x1=98 y1=63 z1=198 x2=122 y2=63 z2=222 hollow=false
```

Incorrect:

```text
# No foundation
# Hollow foundation
# Foundation smaller than walls
# Foundation with missing corners
```

## 0.3 Build full walls first, then carve openings

This is the preferred **creative fill** workflow for a new empty site. In survival, or near valuable existing blocks, place around planned openings so material is not wasted and existing work is not destroyed. A saved blueprint already encodes openings and must not be followed by an unplanned carving pass.

Then carve:

- doorways
- windows
- arches
- interior passages

Use `fill block_id="minecraft:air"` for rectangular holes only in creative mode and only after confirming the volume contains no protected blocks, containers, machines, entities, fluids, redstone, portals, or neighboring builds.

Correct order:

```text
fill stone shell
fill air doorway
fill air windows
fill glass into windows
```

Wrong order:

```text
place individual wall blocks around a door manually
```

## 0.4 Every building must be complete

A “good enough” building must include at least:

1. Foundation
2. Walls
3. Roof
4. Entrance
5. Floor
6. Windows or decorative wall details
7. Lighting
8. At least one exterior detail such as steps/path/columns/trim

Never stop after only making a hollow box unless the user explicitly requested only a shell.

## 0.5 Coordinates are inclusive

`fill x1=100 x2=109` creates 10 blocks along X.

Dimension formula:

```text
width  = abs(x2 - x1) + 1
height = abs(y2 - y1) + 1
depth  = abs(z2 - z1) + 1
```

If you want 20 blocks wide:

```text
x2 = x1 + 19
```

Not:

```text
x2 = x1 + 20
```

## 0.6 Never ignore symmetry

For a symmetric design, calculate the axis first and mirror every structural element by coordinate. Openings, pillars, windows, roof slopes and trim must use the same spacing on both sides unless the owner explicitly chose asymmetry.

## Final acceptance checklist

- Footprint, height, orientation, palette and requested functions match the agreed plan.
- Foundation, corners, walls and roof are continuous; no unintended holes or floating sections remain.
- Doors, stairs, paths and interior circulation are usable by a player.
- Block states face the intended direction and repeated details remain aligned.
- The site is clean: temporary scaffolding, stray blocks, dropped materials and GUI leftovers are removed.
- Re-scan mismatches and repair only incorrect coordinates. A build is complete only after this verification.
