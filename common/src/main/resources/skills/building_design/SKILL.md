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
5. details: `set` stairs facing the right way, glass panes, torches; `scatter`
   for flowers/grass around the yard

## Roofs (the part most builds get wrong)

Always give `roof` a **stairs** block as `block_id` — it lays real sloped courses
and works out the facing for you. A roof made of full blocks is a stepped pile.

**Shape** — `roof_shape`:

- `gable` — two slopes, ridge along the longer axis. Western gable; Chinese
  *xuanshan* (eaves overhanging the gable wall) or *yingshan* (flush).
- `hip` — four slopes, no gable ends. Western hip; Chinese *wudian*, the highest
  rank, reserved for the grandest hall on a site.
- `half_hip` — four slopes below, a gable with two gable-ends above. Chinese
  *xieshan*, Western Dutch gable. Second in rank and the richest silhouette of
  the set; the natural choice for a main hall that is not the very grandest.
- `pyramid` — hip on a square footprint, converging to a point. Towers, gazebos,
  Chinese *zanjian*. Stack one per storey for a pagoda.
- `shed` — a single slope one way. Lean-tos, porches, factory wings, and
  anything that was added onto something else.
- `saltbox` — asymmetric gable, ridge off-centre (`ridge_offset`), one short
  steep slope and one long shallow one. Reads as a house that grew a rear
  extension, which is exactly where the shape came from.


**Curve** — `roof_curve`:

- `straight` — constant pitch. Correct for essentially all Western work.
- `concave` — shallow at the eaves, steepening toward the ridge. **This is the
  single reason East Asian roofs read as curved instead of as a stepped
  pyramid.** Use it for every Chinese, Japanese and Korean building. It follows
  the historic rule: start the eave course at half pitch and steepen each course
  until the ridge is near 1:1, which also makes the roof sit lower than a
  straight one over the same span.

**Corners** — `corner_lift` 1-3 flicks the four eave corners upward. The
upturned corner is the most recognisable feature of an East Asian roof. Leave it
0 for Western buildings.

**Eaves** — `overhang`. 0 reads as unfinished almost everywhere. 1-2 suits most
Western work; East Asian roofs live on their overhang and want more. A deep eave
buys more character than a taller wall, so when the budget is tight spend it here.

**Closing up** — `gable_block` fills the triangular ends of a gable (leave it out
and you can see straight into the attic); `ridge_block` caps the peak. On `hip`
and `half_hip`, `ridge_block` also draws the four sloping corner ridges, so those
shapes always want one.

### Choosing, rather than copying

The style reference tells you what the roof should *feel* like — "low and wide",
"steep, for snow", "four-sided", "corners lifted", "the roof is the building".
Turning that into parameters is your call, and two buildings in one style should
not land on the same numbers.

Decision rules that hold across styles:

- Long thin building → `gable` (the ridge wants a direction). Squat or square →
  `hip` or `pyramid` read better than a gable on a near-square plan.
- Something added onto something else → `shed` or `saltbox`. These two are worth
  reaching for far more often than they get used; a compound of one main roof
  plus a lean-to instantly looks lived-in rather than designed.
- Anything Chinese, Japanese or Korean → `concave`, always, plus some
  `corner_lift`. Without those two it will read as a Western house with Asian
  materials.
- Rank matters in Chinese work: **wudian > xieshan > xuanshan > yingshan**. The
  roof announces the status of what stands under it, so do not put `hip` on an
  outhouse and `gable` on the temple beside it.
- Steeper suits snow, thatch and Gothic; shallower suits sun, tile and anything
  meant to look calm. Let the climate and the material argue for the pitch.

A pagoda is not one roof — it is `pyramid` repeated once per storey, each a
little smaller. Multi-winged buildings likewise get one roof per wing at
different heights, not a single roof stretched over everything.

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
- roofs have eaves (`overhang`), closed gable ends, and a capped ridge
- windows 1-2 above the floor; panes or glass in the openings
- light the inside (torches) or mobs will spawn
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

