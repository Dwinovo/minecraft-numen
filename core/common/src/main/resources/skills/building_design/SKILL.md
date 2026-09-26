---
name: building_design
description: Building design doctrine for the build commands - designs written step by step and built with build at, blueprint files, planning workflow, size reference, single-floor rule, door alignment, composition order with walls, quality checklist. Load BEFORE designing or building any non-trivial structure.
---

# Skill: building_design

Load this before designing anything bigger than a few blocks, and again when a
finished build looks wrong.

## Workflow

1. PLAN first: purpose, footprint, height, one main material + one accent material.
2. Inspect the site (goto / look around): flat enough? big enough? Note the GROUND
   level — every vertical decision below is anchored to it.
   **Uneven ground is YOUR problem to solve, not the builder's**: the builder puts
   blocks exactly where told, so on a slope one side of the footprint will hang in
   the air (or bury into the hill). Scan the footprint first; if the surface varies,
   either move the site, or give the design a foundation step — a `layer` of the
   wall material repeated from the lowest ground up to your chosen floor level
   (costs materials in survival like any build). Stilt houses are a valid choice
   too — just make it a choice, not an accident.
3. Write the building as a DESIGN, big to small: `build new` it, then add the
   steps one line at a time with `--into` — `layer` grids first, single `set`
   details last; later steps overwrite earlier cells. Coordinates in a design are
   relative to its origin (0,0,0), so you can think in the building's own terms.
   `build show` lists the steps with what each costs.
4. `build at` the design on the site: it prices the whole design first and builds
   it as one background job.
5. After task_finished, LOOK at the result and run the checklist below. To fix
   something, change the design (`build step`, `build insert`, `build drop`, or
   one more step with `--into`) and `build at` the same spot again — it only adds
   what is missing, changes what differs and takes away blocks of yours the
   design no longer has.

## Size reference (width x depth x height)

- hut / shed: 7 x 7 x 6      - house / shop: 12 x 10 x 8
- mansion / temple: 18 x 15 x 12      - castle / cathedral: 30 x 25 x 20

Interior walls at least 3 tall so rooms don't feel cramped.

## THE SINGLE-FLOOR RULE (most common mistake)

A building has EXACTLY ONE floor slab: one solid `layer` at the floor level, and
the wall ring stacked on top of it.

```
floor   x1,y1,z1 rows all '#'          <- this IS the interior floor
walls   x1,y1+1,z1  y2=y1+3  rows '#' on the perimeter, '.' inside
```

NEVER put a second solid grid under the walls or over the floor — the doorway
ends up half-buried and the door jams against the raised interior. The wall grid
has '.' everywhere inside: `.` means "leave this cell alone", which is what you
want for a room.

## Door & floor alignment

- door opening = TWO air cells, cut AFTER the walls;
- the LOWER door cell sits at the level a body occupies when standing on the
  interior floor — i.e. directly above the floor slab;
- interior walking level should equal outside ground; if the floor slab raises
  it by one, put a step block outside the door;
- walk the doorway in your head: outside ground -> (step?) -> door lower cell
  -> interior floor. Any solid block in that line means the door is jammed.

## The primitives

Seven primitives, all geometry, no style. What you build with them is yours.
Each is one line: run it on its own and it is built at once, at world
coordinates; add `--into` and it becomes the next step of a design.

- `layer` — a character grid with a `legend`, stamped at one level, or repeated
  up to the level `up_to` names. The first row sits at the given z and runs +x,
  so the grid reads like a map: north at the top, east to the right. `' '` and
  `'.'` leave a cell alone. One grid is a floor, a wall ring, an L-shaped
  footprint, a course of roof tiles, a window pattern, scattered flowers.
  **This is the primitive you will use for almost everything.**
- `set` — one cell, exactly the block state you write, when a grid would be
  overkill. `place` — one block put down the way a player would, facing the way
  you look: a chest, a furnace, a crafting table.
- `line` — two points, diagonals included: beams, posts, ridges, hip lines.
- `cylinder` / `sphere` — round geometry, `hollow` for a shell. A dome is the
  top half of a hollow `sphere`.
- `copy` — take a region and stamp it elsewhere, with `rotation` and `mirror`.
  Build one wing, mirror it. Build one window bay, repeat it down the wall.
  Cheaper than writing it twice, and the two halves actually match. In a design
  it copies the design's own earlier steps.

A small house, written as a design:
```
build new cottage
build layer 0 0 0 ####### ####### ####### ####### ####### --block cobblestone --into cottage
build layer 0 1 0 ####### #.....# #.....# #.....# ####### --block "oak_planks*8, spruce_planks*2" --up_to 3 --into cottage
build layer 3 1 0 # --block air --up_to 2 --into cottage
build set oak_door[facing=south] 3 1 4 --into cottage
build show cottage
build at cottage 120 64 -35
```

Block states ride along with the block name, exactly as in `/setblock`:
`oak_stairs[facing=north,half=top]`, `oak_slab[type=double]`, `oak_log[axis=x]`,
`trapdoor[open=true,facing=north]`. A door, bed or tall flower is placed from its
LOWER half alone — the other half appears with it.

`mask` decides what happens where something already stands, per step: `carve` (default)
builds through anything and an `air` cell digs that cell out; `overwrite` leaves
air cells alone; `solid` only overwrites with full blocks; `keep` only builds
into air and grass. Adding to a building someone else made? `keep`.

## Composition order (matches the bottom-up layered builder)

1. foundation: one solid `layer`, 1 thick — this IS the interior floor
2. wall ring: one `layer` repeated from floor+1 to floor+3
3. roof — see the roof section below. For a dome use the top half of a hollow
   `sphere` instead.
4. openings: a `layer` of `air` for the doorway (two cells tall) and windows
   1-2 above the floor; the door itself is one `set` of its lower half
5. **interior fittings** — see the Interiors section. This is not a garnish: on
   an inhabited floor it is 35-50% of the cells, so plan the room purposes and
   the wall lines before you start writing steps, not after.
6. exterior details: stairs facing the right way, glass panes, lanterns, and a
   sparse `layer` of flowers and grass around the yard

**Two passes.** She lays everything that stands on its own first, one layer at a
time from the ground up, and then walks the building again to fit the things that
need something to hold onto: torches, signs, ladders, carpets, flowers, rails,
redstone, pressure plates, buttons and hanging lanterns. You do not have to order
those specially — write them wherever they belong in the design and they get
deferred for you. It also means an upper-floor lantern is never placed into thin
air and dropped.

**Liquids are not handled.** Leave `water` and `lava` out of the design entirely. Dig
and line the basin, the moat, the canal or the fountain so it is ready to hold
water, and let the player pour it — one bucket does the whole pond. Existing water
on the site is never drained either, so pick a dry spot or plan the build around
it.

## Roofs (the part most builds get wrong)

There is no roof primitive. You draw a roof course by course with `layer`, one grid per
level, and that is the point: any shape you can draw, you can build — including
the L-shaped and cross-shaped roofs no generator would have given you.

### The one rule that keeps a roof watertight

A cell can hold a slab in three states, and that is how a roof climbs:

- `oak_slab` — the lower half of the cell
- `oak_slab[type=top]` — the upper half
- `oak_slab[type=double]` — the whole cell

**Neighbouring courses must touch.** A course may climb at most half a block over
the course beside it; climb a whole block between two bottom slabs and the two
never meet — you get a visible slit and a row of slabs hanging in the air. Two
ways to climb that always meet:

- **Slabs, half a block per cell**: alternate `[type=double]` and a plain bottom
  slab as you go up. This is what a tiled roof is actually made of, it is the
  shallowest pitch, and it is what East Asian roofs need.
- **Stairs, one block per cell**: one course of stairs per level, `facing` the
  way the roof RISES (a south slope's stairs face south). Steeper, western, and
  the cheapest roof to write. Under a deep overhang put the lowest course as
  `[half=top]` stairs so the eave reads thin.

Never mix the two on one plane — the join is exactly where the gap appears.

### Pitch and height

Roof height ≈ 0.5-0.75 × half-span. Below that it reads as a lid, above it as a
tower. With stairs (one block per cell) you get 1.0 automatically, which is why
a stair roof suits a narrow building and looks absurd on a wide hall.

East Asian roofs are **concave**: shallow at the eave, steeper near the ridge.
Measured off hand-built halls, the surface rises one half-block per cell for the
first two thirds and two half-blocks per cell near the ridge, averaging 1.2 —
i.e. a 13-cell half-span ends about 8 blocks up. Write that as slab courses that
start `double`/`bottom` alternating and switch to one course per level near the
top.

### The shapes, as grids

A 12-wide, 10-deep building, ridge along x, eave level `y`. Course k sits at
`y+k` and is drawn twice — one row from the north edge, one from the south:

```
k=0   ############        <- both eave rows
      ............
      ............
      ############
k=1   ............
      ############
      ############
      ............
```

- **Gable (two slopes)** — as above: two rows per course marching inward,
  the gable ends filled in as a triangle with the wall material.
- **Hip (four slopes)** — each course is a RING inset by k on all four sides.
  The four diagonals fall out of the ring corners; run a `line` of the ridge
  material along each of them.
- **Pyramid** — a hip roof on a square footprint; the rings shrink to a point.
- **Half-hip** — rings for the lower third, then switch to two rows and finish
  as a gable, with a decorated panel filling the small end wall.
- **Shed** — one row per course, marching from the low edge to the high one.
- **Dome** — the top half of a hollow `sphere`, not a roof at all.

For a cross-shaped or L-shaped building, draw the courses of both wings in the
same grid and keep the inner corner filled — the valley is a diagonal of cells
that belong to both slopes. One grid per level; the wings cannot fight each other
because they are the same drawing.

### The four bands (this is what makes a style)

The structure above is the same everywhere. What makes one roof Chinese, another
Gothic and another Mediterranean is which block goes in which band:

- **Ridge** — stands PROUD of the tiles: a `line` of a contrasting solid block
  along the crest, one cell higher than the top course, plus the four diagonals
  of a hip roof and the bargeboards of a gable. This single line is most of what
  makes a roof read as designed rather than extruded, and every shape wants it.
- **Eave** — the outermost course only: `[type=top]` slabs, or inverted stairs,
  in a contrasting material. One cell of width, enormous effect.
- **Gable ends** — the triangle under a gable slope. Fill it with the wall
  material or a contrasting panel; leave it out and you can see into the attic.
- **Soffit** — a second skin one cell under the tiles, following the same slope.
  This is what the roof looks like from below and through an open gable. It
  roughly doubles the cell count: spend it on a porch, a temple, a deep-eaved
  hall; skip it on a shed nobody looks up at.

### Eaves and corners

Extend the roof grid 1-4 cells beyond the walls. 0 reads as unfinished almost
everywhere; 1-2 suits most western work; East Asian roofs live on their overhang
and want 2-4. A deep eave buys more character than a taller wall.

Lift the four eave corners 1-3 cells with the ridge material for an East Asian
roof — the upturned corner is the most recognisable feature of the style, and
one `set` per corner buys it. Leave it flat for western buildings.

### Under the eave

A deep overhang leaves a visible underside, and leaving it blank wastes the most
characterful part of an East Asian building. Two cheap details:

- **Rafter ends** — a full block poking out under the eave every two cells along
  the eave line. Two is the spacing the slope itself uses, so they line up.
- **Bracket clusters** — a band of `[half=top]` stairs flanking a full block,
  repeated along the eave, facing ALONG the wall rather than outward. This is the
  detail people recognise the style by.

### What roofs are made of

Roof planes want a **weathered, textured** material. The most common mistake is
reaching for something bright and metallic — gold and polished blocks read as
treasure, not as tile, and a large flat plane of them looks worse the bigger it
gets. Verdigris copper, dark prismarine, deepslate tile, grey concrete and dark
wood all read as roofing; save any gold for a finial the size of one block.

Mix the roof palette like any other large surface. The roof is usually the
biggest single plane on the building, which makes it the last place to accept one
flat colour.

### Choosing, rather than copying

The style reference tells you what the roof should *feel* like — "low and wide",
"steep, for snow", "four-sided", "corners lifted", "the roof is the building".
Turning that into courses is your call, and two buildings in one style should not
land on the same numbers.

Decision rules that hold across styles:

- Long thin building → a gable (the ridge wants a direction). Squat or square →
  hip or pyramid read better than a gable on a near-square plan.
- Something added onto something else → a shed roof. It is worth reaching for far
  more often than it gets used; one main roof plus a lean-to instantly looks
  lived-in rather than designed.
- Rank matters in Chinese work: hip > half-hip > gable. The roof announces the
  status of what stands under it, so do not put the grandest roof on an outhouse
  and a plain gable on the temple beside it.
- Anything Chinese, Japanese or Korean → slabs, a concave pitch, lifted corners,
  and a deep overhang. Without those it will read as a western house wearing
  Asian materials.
- Steeper suits snow, thatch and Gothic; shallower suits sun, tile and anything
  meant to look calm. Let the climate and the material argue for the pitch.

A pagoda is not one roof — it is a pyramid roof repeated once per storey, each a
little smaller. Multi-winged buildings likewise get one roof per wing at
different heights, not a single roof stretched over everything.

## Interiors (this is where builds are actually lost)

**On an inhabited level, 35-50% of the blocks you place are furnishing.** That is
measured off a hand-built compound: on its living floors, four in ten placed cells
are a trapdoor, a barrel, a shelf, a carpet or a lantern; across the whole build,
furnishing is 16% of 5859 cells. If your interior is a bed, a crafting table and
two torches, you are not slightly under-furnished — you are two orders of
magnitude short, and the room will read as a storage shed with a bed in it.

Budget for it. A house whose shell is 3000 cells wants roughly 800-1200 more for
the inside, and the 16384-cell limit has room for that.

### What furniture is actually made of

There is no furniture block in Minecraft, so furniture is ordinary blocks used for
their shape. Measured frequencies from the same building, in order:

- **Trapdoors — 397 of 941 furnishing cells, across seven different woods.** By a
  wide margin the most useful detail block in the game, because it is the only
  thin one you can put in any orientation. All four states earn their keep:
  - `open=true` → a **thin vertical panel** filling part of a cell: a screen, a
    shutter, a cupboard front, railing infill, a partition that does not eat the
    room.
  - `open=false, half=top` → a **shelf hanging under a beam**, or a ceiling panel.
  - `open=false, half=bottom` → a **low ledge at floor level**: a step, a hearth
    lip, the edge of a platform.
  - Mixing wood types (spruce / oak / dark_oak / jungle / bamboo / acacia) reads
    as different pieces of furniture rather than one repeated fitting.
- **Utility blocks used as furniture, in quantity**: `barrel` (68), `composter`
  (41), `chest` (32), `chiseled_bookshelf` (24), `bookshelf` (20), `loom` (17),
  `lectern` (12), `smoker`, `cauldron`, `cartography_table`. The key word is
  quantity — a wall of barrels reads as a storeroom; three barrels reads as three
  barrels.
- **`campfire` (78)** — the hearth, and its smoke is free atmosphere. Set
  `signal_fire=false` for a domestic one; `soul_campfire` for anything eerie.
- **Carpets in muted colours** — the cheapest way to zone a floor and say "this
  part of the room is for sitting".
- **Wall signs and wall banners** — the cheapest "someone lives here" marker.
- **`lantern`, `candle`, `soul_lantern`** — sparingly, and hung, not scattered.
- **`scaffolding`, `ladder`** — open frameworks and vertical circulation that
  read as built rather than as a hole in the floor.

**A bed, a crafting table and a furnace is a survival starter base, not a home.**
None of those three appear in the reference building's twenty most-used blocks.
Place them if the player will use them, but never mistake them for furnishing.

### Rules that measured out

- **98% of furniture touches a wall** (390 of 399 pieces; nine free-standing).
  Furniture in the middle of a room reads as an obstacle, because in a game where
  the player is two blocks tall, it is one. Leave the centre clear and line the
  walls.
- **Furnish every level.** The reference has furnishing on 17 of its 23 layers.
  An upper floor left as a bare box is the most common way a good exterior is
  betrayed the moment someone climbs the stairs.
- **The frame continues indoors** — 30-42 timber cells per storey. Posts and
  beams do not stop at the outside face; if the style shows its frame, show it in
  the rooms too.
- **Light is sparse and comes from above.** 130 light sources across a 40x45
  compound. Enough that nothing spawns, few enough that the room has shadows in
  it. Hang lanterns from beams; a torch stuck on a wall at head height is the
  look of an unfinished build.

### Zone by height

The measured distribution sorts itself into bands, and using them keeps a room
from being furniture-along-the-floor-and-nothing-else:

- **floor** — carpet, campfire, low ledges (`half=bottom` trapdoors), the odd
  `decorated_pot`
- **1-2 above the floor** — the furniture band: barrels, chests, bookshelves,
  loom, lectern, cauldron. This is where the eye goes and where most cells go.
- **2-3 above the floor** — the wall band: wall signs, wall banners, shelves
  (`half=top` trapdoors), a `flower_pot` on a ledge
- **ceiling** — exposed beams, hanging lanterns, `half=top` trapdoor panels
  between the beams

### A room is a function, and the props say which

An unlabelled furnished room is still a shed. Give each room one legible purpose
and let three or four props carry it:

- kitchen — `smoker` or `furnace` + `cauldron` + barrels + a campfire
- study — `lectern` + `bookshelf`/`chiseled_bookshelf` wall + candles
- storeroom — barrels and chests in a grid, `composter`, sacks read as `hay_block`
- workshop — `loom`, `stonecutter`, `grindstone`, `smithing_table`, barrels
- bedroom — bed, a chest at its foot, a lantern, a carpet, one shelf
- shrine or hearth room — campfire on a stone plinth, banners, paired lanterns

Two rooms with the same props are one room built twice. Vary the purpose before
you vary the blocks.

### Writing it in steps

Interior detail is the **last** pass — later steps overwrite earlier cells, so the
shell goes first and the fittings go on top. Almost all of it is one cell with a
state, because the state is the whole point:

- vertical panel: `oak_trapdoor[half=bottom,open=true,facing=north]`
- hanging shelf: `oak_trapdoor[half=top,open=false]`
- lit hearth: `campfire[signal_fire=false,lit=true]`
- hanging lantern: `lantern[hanging=true]` under a beam

A row of barrels along a wall is a `line`; a floor of carpet and the odd pot is
one `layer` grid — draw where each piece goes instead of sprinkling at random.

## Mix your materials

Every block accepts a weighted mix — `"stone_bricks*8, mossy_stone_bricks*2,
cracked_stone_bricks"`, quoted because it has spaces — and each cell picks one,
the same way every time.

A large surface in one flat colour is the single most reliable way to make a
build look fake, so **put a mix on every wall, floor and roof that covers real
area**. 10-20% of a weathered or contrasting variant is usually enough; the eye
reads it as texture rather than as a pattern.

## Quality checklist

- exactly one floor layer; doorway passable per the alignment rule above
- large surfaces are mixes, not one flat colour
- roofs overhang the walls, have a ridge that stands proud of the tiles, closed
  gable ends, and no gap between neighbouring courses
- windows 1-2 above the floor; panes or glass in the openings
- **every inhabited level furnished, not just the ground floor** — if an upper
  room is a bare box, the build is not finished
- furniture along the walls, room centres clear
- each room has one legible purpose, carried by three or four props
- lit well enough that nothing spawns, dim enough to still have shadows
- one main material family + one accent beats a single-material box

## Command mapping

- a design is a named list of primitive steps: `build new` starts one, a
  primitive with `--into` appends a step, `build show` lists the steps and what
  they cost, `build step` / `build insert` / `build drop` change them, `build at`
  builds it on a spot and — run again on the same spot — changes the building to
  match; block states ride in the block name; `air` clears; `mask` decides what
  may be overwritten; later steps overwrite earlier cells, so details go last
- a primitive without `--into` is built at once, at world coordinates: a single
  `build place crafting_table 120 64 -35` is the quick way to put one block down
- whole structure files: `build designs` lists them with the designs, `build show`
  prices one, `build at` builds it; liquids are always skipped
- `build built` lists what has been built and where

## Style references — how to read them

A style file is a **vocabulary, not a template**. It gives you the character of
a style and the reasoning behind it; composing the actual building stays yours.

- **Materials** are semantic slots (frame / infill / roof / floor / accent /
  light) with SEVERAL candidates each and a note on why they read that way.
  Choose per site, per biome, and per what she actually carries. Never default
  to the first candidate just because it is first.
- **Proportions** are ratios and ranges, never fixed dimensions. A style says
  "roof rise about 0.4–0.7 of the half-span"; it never says "13x9x3".
- **Signature moves** state the INTENT first and one possible execution second.
  Hit the intent however the site allows — the listed method is an example.
- **Variants** exist so two buildings in one style are not twins. Pick one, or
  blend two.
- **Avoid** is the sharpest section. Negative constraints carry more style
  information than positive ones, and they are what keeps a style recognisable
  while everything else varies.

**Two buildings in the same style SHOULD differ** in footprint, height, massing
and exact blocks. If yours come out as twins, you are reading the reference as a
template — go back and re-roll the proportions and the material picks.

A style file deliberately never names tool parameters. It says the roof is "low
and wide with lifted corners"; translating that into courses, materials, an
overhang and a corner lift is yours to do, and doing it differently on two
buildings of the same style is the point, not a mistake.

Load one with `skill load building_design --file references/baroque.md` (any style file name below).

### East Asia
`japanese_minka` 和风民居 · `japanese_shrine` 神社 · `japanese_castle` 天守 ·
`chinese_classical` 中式官式 · `korean_hanok` 韩屋

### South & Southeast Asia
`southeast_stilt` 高脚屋 · `indian_temple` 印度石庙

### Historic Europe
`medieval_rustic` 中世纪村舍 · `medieval_castle` 城堡 · `tudor` 都铎半木 ·
`gothic` 哥特 · `baroque` 巴洛克 · `nordic_viking` 维京长屋 ·
`mediterranean` 地中海 · `alpine_chalet` 阿尔卑斯木屋

### Ancient
`greek_classical` 古希腊 · `roman_imperial` 古罗马 · `egyptian` 古埃及 ·
`mesoamerican` 中美洲金字塔

### Middle East & Desert
`islamic` 伊斯兰 · `desert_adobe` 沙漠土坯

### Modern
`modern_minimalist` 现代极简 · `modern_skyscraper` 摩天楼 · `brutalist` 粗野主义 ·
`art_deco` 装饰艺术 · `industrial` 工业厂房 · `scandinavian_modern` 北欧现代

### Vernacular
`farmhouse` 农舍谷仓 · `log_cabin` 原木小屋 · `lighthouse_coastal` 海岸灯塔 ·
`wild_west` 西部小镇 · `victorian` 维多利亚

### Fantasy
`elven_nature` 精灵 · `dwarven_hall` 矮人 · `witch_hut` 女巫 ·
`steampunk` 蒸汽朋克 · `cyberpunk` 赛博朋克 · `fantasy_floating` 浮空 ·
`underwater` 水下

`ruins_overgrown` 废墟 is not a style of its own — it is a **treatment you apply
on top of any other one**. Load it together with the base style whenever the
player asks for something ruined, abandoned, ancient or reclaimed.

Also `references/decoration.md` — finishing-touch recipes (windows, paths,
gardens, chimneys, interiors); load it before the detail pass of any build.

