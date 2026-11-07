---
name: building_design
description: Building design knowledge for Minecraft - how a building fits its site and biome, sizes and room heights, floors and doorways, which way stairs, doors, trapdoors, ladders, beds, lanterns and logs face, roof shapes and pitches and the bands that make a roof read as a style, wall depth and block palettes, interiors with measured furnishing data, what furniture is made of, light levels and mob spawning, and a library of 40 architectural style references with their materials, proportions and signature moves.
---

# Building design

What separates a build that reads as designed from a box of blocks. Builders, and the judges of the Generative
Design in Minecraft competition (GDMC), look at four things: whether a building **fits its site**, whether it
**works** for a body walking through it, whether it **tells** who built it and why, and whether it **looks**
composed. The sections below are the knowledge behind each. The style files under `references/` describe one
architectural style each; `references/decoration.md` collects finishing touches.

## Site and terrain

- **Ground is rarely flat.** On a slope a building either stands on a foundation reaching down to the lowest ground or
  on deliberate stilts; otherwise one side hangs in the air or sinks into the hill. A stone base high enough that no
  dirt shows under the walls anchors a building to the ground.
- **A fitting building changes the terrain little.** It follows the contours — steps down a hillside as several floor
  levels, sits on posts over water or marsh, digs into a slope — rather than levelling the hill flat around itself.
- **The biome sets the native palette.** Vanilla villages show it: plains build in oak and cobblestone, desert in
  sandstone and terracotta, savanna in acacia and terracotta, taiga in spruce and mossy cobblestone, snowy plains in
  spruce, stone bricks and packed ice. Native materials make a building look grown in place; imported ones read as a
  deliberate statement.
- **Climate argues for form.** Steep roofs belong to snow, heavy rain and thatch (real thatch needs a pitch of 45° or
  more to shed water); flat roofs and thick walls with small openings to dry heat; raised floors to floods and swamps.
- **A body moves through it.** A player is 1.8 tall and needs 2 clear cells of headroom; walks up slabs and stairs
  without jumping but needs a jump for a full block; cannot jump a fence or wall (1.5 tall). Every part of a building
  or settlement that is meant to be reached has a walkable way to it — a path, a stair, a bridge over the water.

## Size and height

- hut / shed: 7 x 7 x 6      - house / shop: 12 x 10 x 8
- mansion / temple: 18 x 15 x 12      - castle / cathedral: 30 x 25 x 20

A room 2 cells tall is the least a player fits in and reads cramped; 3–4 is a normal room, and a hall or a great room
runs far taller. Varying ceiling height between rooms is part of what makes a plan read. Corridors are 2 wide in a
small house, 3–4 in a large one.

## Floors and doorways

A ground floor is **one floor slab**, with the walls standing on it. A second solid course under the walls or over the
floor half-buries the doorway, and the door jams against the raised interior.

- A doorway is two air cells tall, cut through the wall; its lower cell sits directly above the floor slab, at the
  level of a body standing on the interior floor.
- Where the floor slab lifts the interior one above the outside ground, a step block stands outside the door. Any solid block on the line outside ground → step → door → interior floor jams the doorway.
- Windows sit 1–2 above the floor, filled with glass or panes.

## Which way a block faces

These are the game's own rules (north is -z, south +z, east +x).

- **stairs** — `facing` is the side the tall back is on, the way you walk UP them. A roof slope rises toward the
  ridge, so its stairs face the ridge: on a south slope (the side that drops away to the south) they face north.
  `half=top` turns them upside down.
- **slab** — `type=bottom` fills the lower half of the cell, `type=top` the upper half, `type=double` the whole cell.
- **door** — the closed panel lies against the edge of its cell opposite `facing`: in a south wall, `facing=north`
  sets it flush with the outside, `facing=south` with the inside.
- **trapdoor** — open, it is a full-height panel against the edge of its cell opposite `facing`, whatever its `half`
  (`facing=south` stands on the north edge); shut, `half=top` is a thin plate at the top of the cell and
  `half=bottom` one at the bottom.
- **ladder** — hangs on the block on the side opposite `facing`: `ladder[facing=north]` needs a solid block just
  south of it.
- **bed** — `facing` points from the foot to the head; the head is the next cell that way.
- **lantern** — `hanging=true` hangs from the block above it and needs one there; `hanging=false` stands on the
  block below.
- **log / pillar** — `axis` is the way it runs: `y` upright, `x` east–west, `z` north–south.

## Roofs

There is no roof block: a roof is a stack of courses, one level at a time, which is why any footprint — L-shaped,
cross-shaped — can carry a fitting roof.

### Pitch, and the rule that keeps a roof closed

Three pitches come straight out of the blocks:

- **slabs, half a block per cell** — a bottom slab, then a double slab, then a bottom slab one level up. The
  shallowest pitch (1 up per 2 across); what a tiled roof is made of, and what East Asian roofs need.
- **stairs, one block per cell** — one course of stairs per level (1 in 1, 45°).
- **full blocks, two up per cell** — the steep 2-in-1 pitch (about 63°), for spires and steep northern roofs.

**Neighbouring courses must touch** — the top of one meets the underside of the next. Two bottom slabs a whole block
apart never meet: a visible slit, and a row of slabs hanging in the air. Where a slab plane and a stair plane meet, the
half-block levels of one and the whole-block levels of the other have to line up, or the join opens exactly that gap.
An eave course of upside-down stairs reads thinner than a plain one.

On a stair roof an odd span ends in a single ridge cell that takes a slab or full block; an even span ends in two
stair courses meeting back to back.

### Height

A roof's height is usually 0.5–0.75 of the half-span; below that it reads as a lid, above it as a tower (each style
file gives its own range). On an inhabited building the roof is one to two storeys tall. A stair roof is fixed at
1.0, which suits a narrow building and looks absurd on a wide hall. A single simple roof starts to look strange past
about 15 x 15, and a gable reads best under about 12 wide; larger plans carry several joined roof sections.

East Asian roofs are **concave**: shallow at the eave, steeper near the ridge. On hand-built halls the surface rises
half a block per cell for the first two thirds and a full block per cell near the ridge, about 0.6 of a block per
cell overall — a 13-cell half-span ends about 8 blocks up.

### Shapes

- **Gable** — two slopes meeting at a ridge; the triangular gable ends are walls.
- **Hip** — four slopes; each course is a ring inset one cell on all four sides from the course below, and the
  diagonals fall out of the ring corners. On a square plan it closes to a point: a **pyramid**.
- **Half-hip** — hipped for the lower part, gabled above, with a small gable end at the top (or the reverse).
- **Shed / lean-to** — one slope, from a low edge to a high one.
- **Saltbox** — a gable with one slope much longer than the other.
- **Gambrel** — a steep lower slope and a shallow upper one on each side (the barn roof); **mansard** — the same on
  all four sides, with a near-flat top. **Bell-cast** — the reverse: a shallow lower kick under a steep upper slope.
- **Helm** — a square tower roof with a gable on each of its four faces.
- **Saw-tooth** — a row of asymmetric ridges, the steep face of each glazed.
- **Flat** — level, finished by a parapet or a lip; without an edge it reads unfinished.
- **Dome** — a hemisphere shell, not a roof of courses at all.

Where two wings meet (L, T, cross) their roofs join in a valley: a diagonal line of cells that belongs to both slopes.
Multi-winged buildings carry one roof per wing at different heights rather than one roof stretched over everything;
a pagoda is a pyramid roof repeated once per storey, each a little smaller.

### The four bands (this is what makes a style)

The structure is the same everywhere. What makes one roof Chinese, another Gothic and another Mediterranean is which
block goes in which band:

- **Ridge** — stands PROUD of the tiles: a contrasting solid block along the crest, one cell higher than the top
  course, plus the four diagonals of a hip roof and the bargeboards of a gable. This single line is most of what makes
  a roof read as designed rather than extruded.
- **Eave** — the outermost course only: top slabs or upside-down stairs in a contrasting material. One cell of width,
  enormous effect.
- **Gable ends** — the triangle under a gable slope, in the wall material or a contrasting panel; left open, the attic
  shows.
- **Soffit** — a second skin one cell under the tiles following the same slope: what the roof looks like from below
  and through an open gable. It roughly doubles the roof's cells, and pays off on a porch, a temple, a deep-eaved
  hall rather than a shed nobody looks up at.

### Eaves and corners

A roof overhangs the walls by 1–4 cells. No overhang reads unfinished in almost every style (flush eaves are a
deliberate modern move); 1–2 suits most western work; East Asian roofs live on their overhang at 2–4. A deep eave buys
more character than a taller wall.

An East Asian roof lifts its four eave corners 1–3 cells in the ridge material — the upturned corner is the most
recognisable feature of the style. Western roofs keep the corners flat.

Under a deep eave the underside is visible:

- **Rafter ends** — a full block poking out under the eave every two cells, the spacing the slab slope itself uses.
- **Bracket clusters** — upside-down stairs flanking a full block, repeated along the eave, facing ALONG the wall
  rather than outward. This is the detail East Asian work is recognised by.

### Materials and choice

Roof planes want a **weathered, textured** material. Gold and polished blocks read as treasure, not as tile, and a
large flat plane of them looks worse the bigger it gets; verdigris copper, dark prismarine, deepslate tiles, grey
concrete and dark wood all read as roofing, and gold belongs on a one-block finial. The roof is usually the biggest
single plane on a building, so it is the last place to take one flat colour; weathering lightens what faces the sun
(ridges) and darkens where water runs off (the eave line).

What holds across styles:

- Long thin building → a gable (the ridge wants a direction). Squat or square → hip or pyramid.
- Something added onto something else → a shed roof. One main roof plus a lean-to reads lived-in rather than designed.
- Rank in Chinese work: hip > half-hip > gable. The roof announces the status of what stands under it.
- Chinese, Japanese and Korean roofs are slabs, a concave pitch, lifted corners and a deep overhang; without those a
  building reads as a western house wearing Asian materials.
- Steeper suits snow, thatch and Gothic; shallower suits sun, tile and anything meant to look calm.

## Walls: depth and palette

**Depth.** A flat wall is the second most common sign of an unconsidered build, after a flat colour. Depth comes from
structure: posts and pillars standing 1 proud of the infill, windows and doors set back 1 so the opening casts a
shadow, sills and lintels of stairs or slabs, an upper floor oversailing the lower, buttresses, the roof's eave. Depth
with no structural reason behind it reads arbitrary; a long run of one block with nothing breaking it reads as a
texture swatch.

**Palette.** A building's palette is usually 3–5 blocks — a main material, a secondary, an accent — sharing one
temperature (warm or cool) and varying in texture. Blocks in a palette rank first by lightness, then by hue, then by
texture. Roof, walls and ceilings belong to one palette; an acacia ceiling in an oak-and-spruce house breaks it.

**Mixing a surface.** One flat colour over a large surface is the most reliable way to make a build look fake. 10–20%
of a weathered or neighbouring variant spread unevenly reads as texture rather than pattern. In a gradient each step
is next to its neighbour in lightness — skipping steps reads as splatter. Weather darkens and roughens the places it
acts on: the base where rain splashes, the corners, around openings, under the eave drip line; the lightest blocks sit
where the eye should land. Horizontal stripes laid across a wall regardless of its features flatten it.

## Interiors (this is where builds are actually lost)

Measured off a hand-built 40 x 45 compound: **on its inhabited levels 35–50% of the placed cells are furnishing** —
four in ten are a trapdoor, a barrel, a shelf, a carpet or a lantern — and across the whole build furnishing is 16% of
5859 cells. An interior of a bed, a crafting table and two torches is a storage shed with a bed in it.

- **98% of furniture touches a wall** (390 of 399 pieces). For a body two blocks tall a block in the middle of a room
  is an obstacle; room centres are circulation.
- **Every level is furnished** — the reference has furnishing on 17 of its 23 layers. A bare upper floor betrays a
  good exterior the moment someone climbs the stairs.
- **The frame continues indoors** — 30–42 timber cells per storey. A style that shows its frame outside shows it in
  the rooms too.
- **Light is sparse and comes from above** — 130 light sources across the compound. Hostile mobs spawn only where
  block light is 0; a lantern emits 15 and light falls by 1 per cell of walking distance (x + y + z), so a few hung
  lanterns keep a whole floor safe and still leave shadows. A torch stuck on a wall at head height is the look of an
  unfinished build.

### What furniture is made of

There is no furniture block, so furniture is ordinary blocks used for their shape. In order of measured frequency:

- **Trapdoors — 397 of 941 furnishing cells, in seven woods**: the only thin block that takes any orientation. Open,
  a vertical panel: screen, shutter, cupboard front, railing infill, a partition that does not eat the room. Shut at
  the top of the cell, a shelf under a beam or a ceiling panel; shut at the bottom, a low ledge, a step, a hearth lip.
  Mixed wood types read as separate pieces of furniture rather than one repeated fitting.
- **Utility blocks in quantity**: `barrel` (68), `composter` (41), `chest` (32), `chiseled_bookshelf` (24),
  `bookshelf` (20), `loom` (17), `lectern` (12), `smoker`, `cauldron`, `cartography_table`. A wall of barrels reads
  as a storeroom; three barrels read as three barrels.
- **`campfire` (78)** — the hearth, smoke included; `campfire[signal_fire=false]` is a domestic one, `soul_campfire`
  an eerie one.
- **Carpets** in muted colours zone a floor; **wall signs and banners** are the cheapest "someone lives here" marker;
  **`lantern`, `candle`, `soul_lantern`** are hung rather than scattered; **`scaffolding`, `ladder`** read as built
  circulation rather than a hole in the floor.

A bed, a crafting table and a furnace are utilities, not furnishing — none of them is among the reference building's
twenty most-used blocks.

### Height bands

- **floor** — carpet, campfire, low trapdoor ledges, the odd `decorated_pot`
- **1–2 above the floor** — the furniture band: barrels, chests, bookshelves, loom, lectern, cauldron; most cells
- **2–3 above the floor** — the wall band: wall signs, wall banners, trapdoor shelves, a `flower_pot` on a ledge
- **ceiling** — exposed beams, hanging lanterns, trapdoor panels between the beams

### A room is a function, and the props say which

Three or four props make a room's purpose legible; more turns it into a shop display. Two rooms with the same props
are one room built twice.

- kitchen — `smoker` + `cauldron` + a run of barrels + a campfire, a `composter` in the corner
- study — `lectern`, a wall of `bookshelf` / `chiseled_bookshelf`, candles on a trapdoor shelf
- storeroom — barrels and chests in a grid two or three high, `hay_block` sacks
- workshop — `loom`, `stonecutter`, `grindstone`, `smithing_table`, barrels, a lantern overhead
- bedroom — bed against the wall, a chest at its foot, a carpet, one shelf, one hanging lantern
- shrine or hearth room — campfire on a stone plinth, banners, paired lanterns, `decorated_pot`

## Style references — how to read them

A style file is a **vocabulary, not a template**: the character of a style and the reasoning behind it.

- **Materials** are semantic slots (frame / infill / roof / floor / accent / light) with several interchangeable
  candidates each and a note on why they read that way; order within a slot carries no preference.
- **Proportions** are ratios and ranges, never fixed dimensions.
- **Signature moves** state the intent first and one possible execution second; the intent is what matters.
- **Variants** are distinct buildings within one style; two can be blended.
- **Avoid** is the sharpest section: negative constraints carry more style information than positive ones, and they
  keep a style recognisable while everything else varies.

Two buildings in the same style differ in footprint, height, massing and exact blocks; twins mean the reference was
read as a template.

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

`ruins_overgrown` 废墟 is not a style of its own — it is a **treatment applied on top of any other one**: a ruined,
abandoned, ancient or reclaimed version of a base style.

`references/decoration.md` collects finishing touches (ceilings, lighting, windows, paths, gardens, chimneys, water)
that work in any style.
