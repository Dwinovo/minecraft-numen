---
name: building_design
description: Building design doctrine for the build/blueprint tools - planning workflow, size reference, single-floor rule, door alignment, composition order with walls, quality checklist. Load BEFORE designing or building any non-trivial structure.
---

# Skill: building_design

Load this before designing anything bigger than a few blocks, and again when a
finished build looks wrong.

## Workflow

1. PLAN first: purpose, footprint, height, one main material + one accent material.
2. Inspect the site (goto / look around): flat enough? big enough? Note the GROUND
   level — every vertical decision below is anchored to it.
3. Build big-to-small in ONE build call where possible: `shapes` for volumes,
   `blocks` for stateful details (stairs facing, doors, torches).
4. After task_finished, LOOK at the result, run the checklist below, patch gaps
   with a small follow-up build call.

## Size reference (width x depth x height)

- hut / shed: 7 x 7 x 6      - house / shop: 12 x 10 x 8
- mansion / temple: 18 x 15 x 12      - castle / cathedral: 30 x 25 x 20

Interior walls at least 3 tall so rooms don't feel cramped.

## THE SINGLE-FLOOR RULE (most common mistake)

A building has EXACTLY ONE floor slab. Pick one of:
- a 1-thick `box` foundation, then `walls` on top of it; or
- a hollow `box` whose bottom face IS the floor (then do NOT add a foundation).

NEVER stack a foundation box under a hollow box — the hollow box's bottom face
adds a second floor, the doorway ends up half-buried, and the door jams against
the raised interior. Use the `walls` shape (vertical perimeter only, no top or
bottom face) for wall rings; reserve hollow `box` for fully sealed shells.

## Door & floor alignment

- door opening = TWO air cells, cut AFTER the walls;
- the LOWER door cell sits at the level a body occupies when standing on the
  interior floor — i.e. directly above the floor slab;
- interior walking level should equal outside ground; if the floor slab raises
  it by one, put a step block outside the door;
- walk the doorway in your head: outside ground -> (step?) -> door lower cell
  -> interior floor. Any solid block in that line means the door is jammed.

## Composition order (matches the bottom-up layered builder)

1. foundation slab (`box`, 1 thick — this IS the interior floor)
2. `walls` perimeter on top of it
3. roof (stacked shrinking solid `box` layers = gable with filled ends, or top
   half of a hollow `sphere` for a dome); roof layer doubles as the ceiling
4. openings: air cells for the door (2 tall) and windows (1-2 above floor)
5. details: stairs facing the right way, door blocks, glass panes, torches

## Quality checklist

- exactly one floor layer; doorway passable per the alignment rule above
- gable triangles under sloped roofs are FILLED; roof sealed, no holes
- windows 1-2 above the floor; panes or glass in the openings
- light the inside (torches) or mobs will spawn
- one main material + one accent (e.g. stone_bricks walls + oak_log corners)
  beats a single-material box

## Tool mapping

- volumes & carving: `build` with `shapes` — box / walls / line / cylinder /
  sphere; hollow variants; block_id minecraft:air carves
- precise stateful blocks: `build` with `blocks` (facing / axis / half /
  properties); later entries overwrite earlier cells, so details go last
- whole structure files: `blueprint` tool (action=list first, then action=build
  at a flat anchor); liquids are always skipped
