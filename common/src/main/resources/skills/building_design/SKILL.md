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
3. Build big-to-small in ONE build call where possible: a single ordered `ops`
   stream — volumes first, stateful details (`set`, `set_door`) last; later ops
   overwrite earlier cells.
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
3. `roof` op over the wall rect (gable, filled ends, doubles as ceiling); use
   the top half of a hollow `sphere` for a dome
4. openings: `set` air cells for windows (1-2 above floor); `set_door` cuts and
   fits the whole door in one op
5. details: `set` stairs facing the right way, glass panes, torches; `scatter`
   for flowers/grass around the yard

## Quality checklist

- exactly one floor layer; doorway passable per the alignment rule above
- gable triangles under sloped roofs are FILLED; roof sealed, no holes
- windows 1-2 above the floor; panes or glass in the openings
- light the inside (torches) or mobs will spawn
- one main material + one accent (e.g. stone_bricks walls + oak_log corners)
  beats a single-material box

## Tool mapping

- everything goes through `build`'s ordered `ops` stream: set / box / walls /
  line / cylinder / sphere / roof / set_door / scatter; hollow variants;
  block_id minecraft:air carves; later ops overwrite earlier cells, so details
  go last
- whole structure files: `blueprint` tool (action=list first, then action=build
  at a flat anchor); liquids are always skipped

## Style library (load on demand)

Before building in a named style, load its reference:
`load_skill(building_design, file="references/<style>.md")` — then follow its
proportions/palette/recipes over the generic rules above. Available styles:

- medieval_rustic — 中世纪乡村 timber houses
- medieval_castle — 城堡 keep/walls/towers
- japanese — 和风 low wide roofs, dark frame + white infill
- chinese — 中式 axis symmetry, red columns, courtyards
- nordic_viking — 北欧长屋 roof-is-the-building
- desert_adobe — 沙漠土坯 flat roofs, thick cubes
- gothic — 哥特 vertical, pointed arches, buttresses
- steampunk — 蒸汽朋克 copper pipes and towers
- elven_nature — 精灵树屋 grown curves and canopies
- modern — 现代极简 glass planes and cantilevers
- witch_hut — 女巫小屋 crooked stilted hut
- lighthouse_coastal — 海岸灯塔 striped landmark tower

Also `references/decoration.md` — finishing-touch recipes (windows, paths,
gardens, chimneys, interiors); load it before the detail pass of any build.

