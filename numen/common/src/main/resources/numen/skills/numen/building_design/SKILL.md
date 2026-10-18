---
name: building_design
description: Building design knowledge for Minecraft - building sizes, floors and doorways, which way stairs, doors, trapdoors, ladders, beds, lanterns and logs face, roof geometry and the details that make a roof read as a style, interiors and what furniture is made of, mixing materials, and a library of 40 architectural style references with their materials, proportions and signature moves.
---

# Building design

What separates a build that reads as designed from a box of blocks. The style files under `references/` describe one architectural style each; `references/decoration.md` collects finishing touches.

## Size reference (width x depth x height)

- hut / shed: 7 x 7 x 6      - house / shop: 12 x 10 x 8
- mansion / temple: 18 x 15 x 12      - castle / cathedral: 30 x 25 x 20

Interior walls at least 3 tall so rooms don't feel cramped. Ground is rarely flat: a building on a slope either gets a
foundation built up from the lowest ground to the floor level, or stands on stilts on purpose; otherwise one side hangs
in the air or sinks into the hill.

## Floors and doorways

A building has **exactly one floor slab**, with the walls standing on it. A second solid course under the walls or over
the floor half-buries the doorway, and the door jams against the raised interior.

- A doorway is two air cells tall, cut through the wall.
- The lower door cell sits at the level of a body standing on the interior floor — directly above the floor slab.
- The interior walking level should equal the outside ground; if the floor slab raises it by one, a step block goes
  outside the door.
- Walk the doorway in your head: outside ground → (step?) → lower door cell → interior floor. Any solid block on that
  line jams the door.
- Windows sit 1-2 blocks above the floor, filled with glass or panes.

## Which way a block faces

These are the game's own rules (north is -z, south +z, east +x).

- **stairs** — `facing` is the side the tall back is on, the way you walk UP
  them. A roof slope rises toward the ridge, so its stairs face the ridge: on a
  south slope (the side that drops away to the south) they face north, on a
  north slope south. `half=top` turns them upside down.
- **door** — the closed panel lies against the edge of its cell opposite
  `facing`: in a south wall, `facing=north` sets it flush with the outside,
  `facing=south` with the inside.
- **trapdoor** — open, it stands as a full-height panel against the edge of its
  cell opposite `facing` (`facing=south` stands on the north edge, against a
  north wall); shut, `half=top` is a slab at the top of the cell and
  `half=bottom` one at the bottom.
- **ladder** — hangs on the block on the side opposite `facing`:
  `ladder[facing=north]` needs a solid block just south of it.
- **bed** — `facing` points from the foot to the head; the head goes one cell
  that way.
- **lantern** — `hanging=true` hangs from the block above it and needs one
  there; `hanging=false` stands on the block below.
- **log / pillar** — `axis` is the way it runs: `y` upright, `x` east–west,
  `z` north–south.


## The parts of a building

1. **Foundation / floor** — one solid course, one block thick; it is the interior floor.
2. **Walls** — from floor + 1 up to at least floor + 3.
3. **Roof** — see below; a dome is the top half of a hollow sphere.
4. **Openings** — doorways two cells tall, windows 1-2 above the floor.
5. **Interior fittings** — on an inhabited floor 35-50% of the cells (see Interiors).
6. **Exterior details** — stairs and slabs as trim, glass panes, lanterns, paths, and flowers and grass around the yard.

Torches, signs, ladders, carpets, flowers, rails, buttons, pressure plates and hanging lanterns need a block to hold
onto, so they go up after the blocks around them. Ponds, moats, canals and fountains are dug and lined first; one water
source poured in spreads through a whole shallow basin.

## Roofs (the part most builds get wrong)

There is no roof block: a roof is built course by course, one level at a time, which is why any footprint — L-shaped,
cross-shaped — can carry a fitting roof.

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
  way the roof RISES, toward the ridge (a south slope's stairs face north).
  Steeper, western, and the cheapest roof to write. Under a deep overhang put
  the lowest course as `[half=top]` stairs so the eave reads thin.

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
  The four diagonals fall out of the ring corners; a line of the ridge
  material runs along each of them.
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
one cell per corner buys it. Leave it flat for western buildings.

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

Budget for it: a house whose shell is 3000 cells wants roughly 800-1200 more for
the inside.

### What furniture is actually made of

There is no furniture block in Minecraft, so furniture is ordinary blocks used for
their shape. Measured frequencies from the same building, in order:

- **Trapdoors — 397 of 941 furnishing cells, across seven different woods.** By a
  wide margin the most useful detail block in the game, because it is the only
  thin one you can put in any orientation. All three shapes earn their keep:
  - `open=true` → a **thin vertical panel** against one edge of the cell (the
    edge opposite `facing`; `half` does not change it): a screen, a shutter, a
    cupboard front, railing infill, a partition that does not eat the room.
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

The state is the whole point of most interior detail blocks:

- vertical panel: `oak_trapdoor[half=bottom,open=true,facing=north]` (stands on the cell's south edge)
- hanging shelf: `oak_trapdoor[half=top,open=false]`
- lit hearth: `campfire[signal_fire=false,lit=true]`
- hanging lantern: `lantern[hanging=true]` under a beam

A row of barrels along a wall, a floor of carpet with the odd pot: placed where each piece belongs, not sprinkled at
random.

## Mix your materials

A large surface in one flat colour is the single most reliable way to make a build look fake, so **mix every wall,
floor and roof that covers real area**: 10-20% of a weathered or contrasting variant is usually enough; the eye reads it
as texture rather than as a pattern. Spread the variant unevenly across the surface rather than in visible stripes or
patches.

## What a finished build has

- exactly one floor course; every doorway passable
- large surfaces mixed, not one flat colour
- roofs that overhang the walls, a ridge standing proud of the tiles, closed gable ends, and no gap between neighbouring
  courses
- windows 1-2 above the floor, with panes or glass
- **every inhabited level furnished**, not just the ground floor
- furniture along the walls, room centres clear
- each room with one legible purpose, carried by three or four props
- lit well enough that nothing spawns, dim enough to still have shadows
- one main material family plus one accent, rather than a single-material box

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
template rather than as a vocabulary.

A style file deliberately never names tool parameters. It says the roof is "low
and wide with lifted corners"; translating that into courses, materials, an
overhang and a corner lift is yours to do, and doing it differently on two
buildings of the same style is the point, not a mistake.

Each style is the file `references/<name>.md` of this skill, e.g. `references/baroque.md`.

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
on top of any other one**: a ruined, abandoned, ancient or reclaimed version of a base style.

`references/decoration.md` collects finishing touches (windows, paths, gardens,
chimneys, interiors) that work in any style.

