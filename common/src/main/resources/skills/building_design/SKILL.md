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
3. `roof` over the wall rect — see the roof section below. For a dome use the
   top half of a hollow `sphere` instead.
4. openings: `set` air cells for windows (1-2 above floor); `set_door` cuts and
   fits the whole door in one op
5. **interior fittings** — see the Interiors section. This is not a garnish: on
   an inhabited floor it is 35-50% of the cells, so plan the room purposes and
   the wall lines before you start writing ops, not after.
6. exterior details: `set` stairs facing the right way, glass panes, lanterns;
   `scatter` for flowers and grass around the yard

**Two passes.** She lays everything that stands on its own first, one layer at a
time from the ground up, and then walks the building again to fit the things that
need something to hold onto: torches, signs, ladders, carpets, flowers, rails,
redstone, pressure plates, buttons and hanging lanterns. You do not have to order
those specially — write them wherever they belong in the ops stream and they get
deferred for you. It also means an upper-floor lantern is never placed into thin
air and dropped.

**Liquids are not handled.** Leave `water` and `lava` out of the ops entirely. Dig
and line the basin, the moat, the canal or the fountain so it is ready to hold
water, and let the player pour it — one bucket does the whole pond. Existing water
on the site is never drained either, so pick a dry spot or plan the build around
it.

## Roofs (the part most builds get wrong)

Give `roof` a **slab** block as `block_id` — `stone_brick_slab`,
`deepslate_tile_slab`, `spruce_slab`, `waxed_oxidized_cut_copper_slab`. Slabs are
what a good roof is actually made of, because a slab has three states and the
generator uses all three: a bottom slab is a tread, a double slab is the riser
next to it, and the two alternating make a surface that climbs **half a block per
cell**. Not one full-block step anywhere. Ask for stairs or full blocks and you
get a staircase — recognisably worse, and the bigger the roof the worse it looks.

The generator also gives the roof its **profile**: shallow at the eaves,
steepening toward the ridge, ending about 0.6-0.75 of the half-span tall. You do
not compute any of this. What you choose is the shape, the four material bands,
and how far the eaves reach.

**Shape** — `roof_shape`. The four Chinese ranks, plus the lean-to:

- `xuanshan` (alias `gable`) — two slopes, ridge along the longer axis, the two
  ends closed by a bargeboard. The everyday roof, East and West alike.
- `wudian` (alias `hip`) — four slopes, four diagonal hip ridges, no gable ends.
  The highest rank; reserve it for the grandest hall on a site.
- `xieshan` (alias `half_hip`) — four slopes below, a gable with two decorated
  end panels above. Second in rank and the richest silhouette of the set; the
  natural choice for a main hall that is not the very grandest. Western builders
  know the same shape as a Dutch gable.
- `zuanjian` (alias `pyramid`) — four slopes meeting at a point, for a square
  footprint. Towers, gazebos, pavilions. Stack one per storey for a pagoda.
- `shed` — a single slope one way. Lean-tos, porches, factory wings, and anything
  that was added onto something else.

**Curve** — `roof_curve` is `concave` by default and that default is almost
always right: real tiled roofs are shallow at the eave and steepen toward the
ridge, and it is the single reason an East Asian roof reads as curved rather than
as a stepped pyramid. Ask for `straight` only when you specifically want a hard,
steep, Gothic or Alpine silhouette.

**The four bands.** These are the whole game. The structure is fixed; what makes
one roof Chinese, another Gothic and another Mediterranean is which block goes in
which band:

- `ridge_block` — the ridges, which stand **proud of the tiles**: the crest along
  the top, the four diagonals of a `wudian`/`zuanjian`, the bargeboards of a
  `xuanshan`. Pick something that clearly contrasts with the roof. This one line
  is most of what makes a roof read as designed rather than extruded, and every
  shape wants it.
- `eave_block` — the outermost course only, a drip band running right around the
  edge. One block of width; enormous effect. Copper, dark prismarine, a different
  wood.
- `gable_block` — the end walls: the triangle under a `xuanshan` slope, the
  decorated panel of a `xieshan`. Leave it out and you can see into the attic.
- `soffit_block` — a second skin one block under the tiles, following the same
  slope. This is what the roof looks like **from below** and through an open
  gable. It roughly doubles the cell count, so spend it on roofs people stand
  under — a porch, a temple, a deep-eaved hall — and skip it on a shed nobody
  will look up at.

**Eaves** — `overhang`. 0 reads as unfinished almost everywhere. 1-2 suits most
Western work; East Asian roofs live on their overhang and want 2-4. A deep eave
buys more character than a taller wall, so when the budget is tight, spend it
here.

**Corners** — `corner_lift` 1-3 flicks the four eave corners upward. The upturned
corner is the most recognisable feature of an East Asian roof. Leave it 0 for
Western buildings.

### What roofs are made of

Roof planes want a **weathered, textured** material. The most common mistake is
reaching for something bright and metallic — gold and polished blocks read as
treasure, not as tile, and a large flat plane of them looks worse the bigger it
gets. Verdigris copper, dark prismarine, deepslate tile, grey concrete and dark
wood all read as roofing; save any gold for a finial the size of one block.

Mix the roof palette like any other large surface. The roof is usually the
biggest single plane on the building, which makes it the last place to accept one
flat colour.

### Under the eave

A deep overhang leaves a visible underside, and leaving it blank wastes the most
characterful part of an East Asian building. Two details, both cheap `set` ops:

- **Rafter ends** — a full block poking out under the eave every two cells along
  the eave line. Two is the spacing the roof itself uses, so they line up with
  the slope.
- **Bracket clusters** — a band of upside-down stairs (`half=top`) flanking a
  full block, repeated along the eave. Face the stairs *along* the wall, not
  outward. This is the detail people recognise the style by.

### Choosing, rather than copying

The style reference tells you what the roof should *feel* like — "low and wide",
"steep, for snow", "four-sided", "corners lifted", "the roof is the building".
Turning that into parameters is your call, and two buildings in one style should
not land on the same numbers.

Decision rules that hold across styles:

- Long thin building → `xuanshan` (the ridge wants a direction). Squat or square
  → `wudian` or `zuanjian` read better than a gable on a near-square plan.
- Something added onto something else → `shed`. It is worth reaching for far more
  often than it gets used; one main roof plus a lean-to instantly looks lived-in
  rather than designed.
- Rank matters in Chinese work: **wudian > xieshan > xuanshan**. The roof
  announces the status of what stands under it, so do not put a `wudian` on an
  outhouse and a `xuanshan` on the temple beside it.
- Anything Chinese, Japanese or Korean → keep `concave`, add `corner_lift`, and
  spend on `overhang`. Without those it will read as a Western house wearing
  Asian materials.
- Steeper suits snow, thatch and Gothic; shallower suits sun, tile and anything
  meant to look calm. Let the climate and the material argue for the pitch.

A pagoda is not one roof — it is `zuanjian` repeated once per storey, each a
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

### Writing it in ops

Interior detail is the **last** pass — later ops overwrite earlier cells, so the
shell goes first and the fittings go on top. Almost all of it is `set` with
`properties`, because the state is the whole point:

- vertical panel: `set` a trapdoor with `properties {half: bottom, open: true,
  facing: north}`
- hanging shelf: `set` a trapdoor with `properties {half: top, open: false}`
- lit hearth: `set` a campfire with `properties {signal_fire: false, lit: true}`
- hanging lantern: `set` a lantern with `properties {hanging: true}` under a beam

Carpets, barrels and bookshelves need no properties, so those go in bulk via
`scatter` on a floor plane or a `line` along a wall.

## Mix your materials

Every block_id accepts a weighted mix — `"stone_bricks*8, mossy_stone_bricks*2,
cracked_stone_bricks"` — and each cell picks one, the same way every time.

A large surface in one flat colour is the single most reliable way to make a
build look fake, so **put a mix on every wall, floor and roof that covers real
area**. 10-20% of a weathered or contrasting variant is usually enough; the eye
reads it as texture rather than as a pattern.

## Quality checklist

- exactly one floor layer; doorway passable per the alignment rule above
- large surfaces are mixes, not one flat colour
- roofs have eaves (`overhang`), a ridge that stands proud (`ridge_block`), and
  closed end walls (`gable_block`)
- windows 1-2 above the floor; panes or glass in the openings
- **every inhabited level furnished, not just the ground floor** — if an upper
  room is a bare box, the build is not finished
- furniture along the walls, room centres clear
- each room has one legible purpose, carried by three or four props
- lit well enough that nothing spawns, dim enough to still have shadows
- one main material family + one accent beats a single-material box

## Tool mapping

- everything goes through `build`'s ordered `ops` stream: set / box / walls /
  line / cylinder / sphere / roof / set_door / scatter; hollow variants;
  block_id minecraft:air carves; later ops overwrite earlier cells, so details
  go last
- whole structure files: `blueprint` tool (action=list first, then action=build
  at a flat anchor); liquids are always skipped

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
and wide with lifted corners"; translating that into `roof_shape`,
`roof_curve`, `overhang` and `corner_lift` is yours to do, and doing it
differently on two buildings of the same style is the point, not a mistake.

Load one with `load_skill(building_design, file="references/<style>.md")`.

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

