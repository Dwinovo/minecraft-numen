---
name: building
description: Reliable Minecraft architecture using creative fill, real survival placement, reusable blueprints, rotation, material planning, resumable batches, terrain checks, block-state handling, verification, and recovery. Load for any building, road, bridge, base, room, restoration, copy, or decoration task.
---

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

For houses, palaces, castles, temples, gates, towers, and formal structures:

- Choose a centerline.
- Put the main door on the centerline.
- Place windows symmetrically.
- Mirror left and right wings.
- Put towers at matching corners.
- Use odd widths when possible so there is a clear center block.

Example:

```text
Building x=100..112 has width 13.
CenterX = 106.
A 3-wide doorway should be x=105..107.
```

---

# 1. Core tools

## 1.1 Tool table

| Tool | Use it for |
|---|---|
| `get_self_status` | Get exact player position before choosing build coordinates. |
| `get_world_info` | Confirm dimension, game rules, height limits, and broader environment when relevant. |
| `scan_blocks` | Find terrain materials, hazards, fluids, or nearby structures over a bounded area. |
| `inspect_block` | Check ground or existing blocks before modifying important areas. |
| `inspect_block_storage` | Detect valuable container/machine contents before any nearby destructive work. |
| `creative_give` | Give materials if survival-like inventory matters. In creative mode, materials are effectively unlimited. |
| `fill` | Any rectangular volume, large or small. Use constantly. |
| `place_block` | Single blocks, stairs, doors, torches, lanterns, furniture, irregular decorations. |
| `break_block` | Single-block correction only. Prefer `fill air` for rectangular removal. |
| `save_blueprint` | Capture loaded non-air block states in an owner-scoped reusable blueprint; excludes block-entity NBT. |
| `plan_blueprint` | Preview rotation, target positions, conflicts, state fixes, and materials without changing the world. |
| `build_blueprint` | Build a confirmed blueprint in resumable batches with real survival placement or bounded creative writes. |
| `pause_tasks` / `resume_tasks` | Freeze and continue queued long-running construction without losing order or timeout budget. |

## 1.2 `fill` syntax

```text
fill block_id="minecraft:stone_bricks" x1=100 y1=64 z1=200 x2=110 y2=70 z2=210 hollow=true
```

Parameters:

- `block_id`: block type
- `x1 y1 z1`: first corner
- `x2 y2 z2`: opposite corner
- `hollow=true`: only fills the outer shell/faces
- `hollow=false`: fills the entire solid volume

## 1.3 When to use `hollow=true`

Use `hollow=true` for complete room shells:

```text
fill block_id="minecraft:stone_bricks" x1=100 y1=64 z1=200 x2=112 y2=70 z2=212 hollow=true
```

This creates:

- floor
- ceiling
- north wall
- south wall
- west wall
- east wall
- empty interior

## 1.4 When to use `hollow=false`

Use `hollow=false` for:

- foundation
- solid platform
- floor replacement
- ceiling replacement
- pillar
- beam
- trim
- stairs made of solid rectangular block layers
- clearing with air
- glass windows after carving holes

Examples:

```text
# Solid foundation
fill block_id="minecraft:stone_bricks" x1=98 y1=63 z1=198 x2=122 y2=63 z2=222 hollow=false

# Solid vertical pillar
fill block_id="minecraft:quartz_pillar" x1=104 y1=64 z1=204 x2=104 y2=72 z2=204 hollow=false

# Clear a doorway
fill block_id="minecraft:air" x1=105 y1=64 z1=200 x2=107 y2=67 z2=200 hollow=false
```

## 1.5 Fill size limit

A `fill` call must not exceed 20000 blocks.

Calculate:

```text
volume = width * height * depth
```

Example:

```text
x=0..99   => width 100
y=64..73 => height 10
z=0..29   => depth 30

volume = 100 * 10 * 30 = 30000
```

Too large. Split it:

```text
fill block_id="minecraft:stone_bricks" x1=0  y1=64 z1=0 x2=49 y2=73 z2=29 hollow=false
fill block_id="minecraft:stone_bricks" x1=50 y1=64 z1=0 x2=99 y2=73 z2=29 hollow=false
```

Even if using `hollow=true`, still avoid huge boxes and split if the bounding volume is over 20000.

---

# 2. Coordinate system and planning

## 2.1 Minecraft coordinate directions

Usually:

```text
+X = east
-X = west
+Y = up
-Y = down
+Z = south
-Z = north
```

Use this convention unless the user gives a different orientation.

## 2.2 Default orientation

If the user does not specify direction:

- The building front is the south side.
- The front wall is at `zMax`.
- The main entrance opens toward positive Z.

So for a building:

```text
x = 100..112
z = 200..210
```

The front/south wall is:

```text
z = 210
```

The back/north wall is:

```text
z = 200
```

## 2.3 Always get current position first

Before building, call:

```text
get_self_status
```

Then choose build coordinates away from the player so the building does not trap or suffocate the player.

Safe default:

```text
Build at least 5 blocks away from the player.
If unsure, place the building in front or to the side of the player, not centered on the player.
```

## 2.4 Choosing floor and foundation height

Use this convention:

```text
foundationY = ground level
floorY      = foundationY + 1
```

Example:

```text
foundationY = 63
floorY      = 64
```

Then:

- Foundation blocks are at `y=63`.
- Room floor blocks are at `y=64`.
- Player walks on top of `y=64` floor blocks.

If unsure after `get_self_status`:

```text
If player is standing on ground:
foundationY = floor(playerY) - 1
floorY = foundationY + 1
```

## 2.5 Define all important variables before building

For any structure, define:

```text
wallX1
wallX2
wallZ1
wallZ2
foundationY
floorY
ceilingY
roofY
centerX
centerZ
```

Example for a 13x11 house:

```text
wallX1 = 100
wallX2 = 112
wallZ1 = 200
wallZ2 = 210

foundationY = 63
floorY = 64
ceilingY = 69
roofY = 70

centerX = 106
centerZ = 205
```

Check dimensions:

```text
width = 112 - 100 + 1 = 13
depth = 210 - 200 + 1 = 11
height = 69 - 64 + 1 = 6
```

Interior empty walking space will be:

```text
x = 101..111
y = 65..68
z = 201..209
```

## 2.6 Prefer odd dimensions

Odd dimensions make centered doors and roofs easier.

Good default widths:

```text
7, 9, 11, 13, 15, 17, 19, 21, 25, 31, 41
```

For a normal house:

```text
width  = 13
depth  = 11
height = 6
```

For a mansion:

```text
width  = 25–35
depth  = 19–29
height = 8–12
```

For a palace:

```text
width  = 35–55
depth  = 25–45
height = 10–18
```

For towers:

```text
5x5, 7x7, or 9x9 footprint
height = 12–25
```

---

# 3. Standard building workflow

For every building task, follow this order, selecting a mode-appropriate execution branch.

## Step 1: Understand user request

Determine:

```text
structure type: house / palace / castle / tower / bridge / etc.
style: medieval / modern / wooden / stone / fantasy / etc.
size: small / medium / large / huge
location: here / nearby / coordinates specified
orientation: north/south/east/west if specified
```

If the user gives no style:

```text
Default style = stone-brick + dark-oak fantasy medieval.
```

If the user gives no size:

```text
Default size = medium, complete, detailed, not tiny.
```

## Step 2: Get position

```text
get_self_status
```

Choose coordinates.

Do not build directly inside the player’s body.

## Step 3: Choose material palette

A good palette has at least:

```text
main wall block
accent/trim block
floor block
roof block
glass block
light block
optional column block
```

Default palette:

```text
main wall: minecraft:stone_bricks
accent: minecraft:smooth_stone or minecraft:quartz_pillar
floor: minecraft:polished_andesite
roof: minecraft:dark_oak_planks
glass: minecraft:glass
light: minecraft:glowstone
steps: minecraft:stone_brick_stairs
```

## Step 4: Prepare materials if needed

If inventory matters:

```text
creative_give item_id="minecraft:stone_bricks" count=64
creative_give item_id="minecraft:stone_bricks" count=64
creative_give item_id="minecraft:polished_andesite" count=64
creative_give item_id="minecraft:dark_oak_planks" count=64
creative_give item_id="minecraft:glass" count=64
creative_give item_id="minecraft:glowstone" count=64
creative_give item_id="minecraft:quartz_pillar" count=64
creative_give item_id="minecraft:stone_brick_stairs" count=64
```

In creative mode, do not waste time giving stacks unless an interaction specifically requires inventory. In survival, do not use `creative_give`; plan the exact bill of materials and obtain them legitimately.

## Step 5: Inspect the site and choose execution branch

Before changing the world:

1. Inspect representative corners, center, ground level, and vertical clearance.
2. Look for fluids, falling blocks, vegetation, caves, containers, machines, redstone, portals, beds, signs, and another player's build.
3. Check that the whole target lies inside world height and loaded chunks.
4. Choose creative `fill`, survival placement, or the blueprint workflow.
5. For any destructive clearing, state the exact bounding box and verify it contains nothing protected.

## Step 6: Foundation

Use solid `fill`.

Foundation must be larger than walls.

Formula:

```text
foundationX1 = minWallX - margin
foundationX2 = maxWallX + margin
foundationZ1 = minWallZ - margin
foundationZ2 = maxWallZ + margin
foundationY  = floorY - 1
```

Recommended margin:

```text
small house: 1–2 blocks
mansion: 2 blocks
palace/castle: 2–4 blocks
```

## Step 7: Main shells / rooms

Use `fill hollow=true`.

Each room should be a complete box.

Example:

```text
fill block_id="minecraft:stone_bricks" x1=100 y1=64 z1=200 x2=112 y2=69 z2=210 hollow=true
```

For multi-room buildings:

- Build central hall.
- Build left wing.
- Build right wing.
- Build towers or porch.
- Then carve passages.

## Step 8: Carve openings

Use `fill air`.

Carve:

- main entrance
- side entrances
- windows
- interior passages
- archways

Then fill windows with glass.

## Step 9: Replace floors

The hollow shell already includes a floor, but replace the interior floor with nicer material.

Interior floor coordinates are:

```text
x = wallX1 + 1 .. wallX2 - 1
z = wallZ1 + 1 .. wallZ2 - 1
y = floorY
```

Example:

```text
fill block_id="minecraft:polished_andesite" x1=101 y1=64 z1=201 x2=111 y2=64 z2=209 hollow=false
```

## Step 10: Add structural details

Add at least some of:

- corner pillars
- top trim band
- bottom trim band
- columns
- window frames
- porch
- steps
- balcony
- roof overhang
- battlements for castles
- path to entrance

Use `fill` for rectangular trim and columns.

## Step 11: Roof

Every building needs a roof.

Acceptable roofs:

- flat roof with overhang
- stepped/gabled roof
- pyramid roof
- battlement roof
- dome approximation
- tower cap

Never leave a building open unless explicitly requested.

## Step 12: Lighting

Add lights inside.

Good defaults:

```text
glowstone
sea_lantern
lanterns if supported
torches if supported
```

For rectangular ceiling lights, use `fill` even for a single-block rectangular position if convenient.

Example:

```text
fill block_id="minecraft:glowstone" x1=104 y1=69 z1=204 x2=104 y2=69 z2=204 hollow=false
```

## Step 13: Exterior polish

Add:

- front steps
- path
- small platform/porch
- fence/railing
- garden or trees if requested
- lamps near entrance

## Step 14: Verify before final response

Check:

1. Foundation exists.
2. Walls are complete.
3. Entrance is open.
4. Windows are carved and filled with glass.
5. Floor exists.
6. Roof exists.
7. Interior has light.
8. No obvious gaps.
9. Symmetry looks correct.
10. Large flat walls have trim/windows/columns.

Fix problems before saying the build is complete.

---

# 4. Foundations in detail

## 4.1 Simple house foundation

House walls:

```text
x=100..112
z=200..210
floorY=64
```

Foundation with 2-block margin:

```text
fill block_id="minecraft:stone_bricks" x1=98 y1=63 z1=198 x2=114 y2=63 z2=212 hollow=false
```

Check:

```text
foundation width = 114 - 98 + 1 = 17
foundation depth = 212 - 198 + 1 = 15

wall width = 112 - 100 + 1 = 13
wall depth = 210 - 200 + 1 = 11

foundation extends 2 blocks on every side.
```

## 4.2 Thick foundation

For large palace/castle, use 2 or more layers.

Example 2-layer foundation:

```text
fill block_id="minecraft:stone_bricks" x1=98 y1=62 z1=198 x2=122 y2=63 z2=222 hollow=false
```

This creates:

```text
height = 63 - 62 + 1 = 2
```

## 4.3 Leveling uneven terrain

If terrain is uneven, build a solid platform over it.

Do not try to manually fill missing spots.

Use:

```text
fill block_id="minecraft:stone_bricks" x1=98 y1=63 z1=198 x2=122 y2=63 z2=222 hollow=false
```

If you need to clear air above the building site:

```text
fill block_id="minecraft:air" x1=98 y1=64 z1=198 x2=122 y2=85 z2=222 hollow=false
```

Only clear if safe and appropriate. Do not destroy user’s existing structures unless asked.

---

# 5. Walls and room shells

## 5.1 Basic shell

```text
fill block_id="minecraft:stone_bricks" x1=100 y1=64 z1=200 x2=112 y2=69 z2=210 hollow=true
```

Creates a 13x6x11 shell.

Interior air:

```text
x=101..111
y=65..68
z=201..209
```

## 5.2 Normal height guide

| Building | floorY | ceilingY | Exterior shell height | Interior air height |
|---|---:|---:|---:|---:|
| Tiny hut | 64 | 68 | 5 | 3 |
| Normal house | 64 | 69 | 6 | 4 |
| Mansion room | 64 | 71 | 8 | 6 |
| Palace hall | 64 | 75 | 12 | 10 |
| Tower | 64 | 80+ | 17+ | 15+ |

## 5.3 Multi-room rule

A large building should not be one giant plain box.

Use multiple boxes:

```text
central hall
left wing
right wing
front porch
rear room
corner towers
```

Good palace massing:

```text
          rear towers
      [tower] [main hall] [tower]

      [wing]  [main hall] [wing]

          [front porch]
```

## 5.4 Adjacent rooms

If two rooms touch, build both as hollow boxes, then carve a doorway through the shared wall.

Example:

Main hall:

```text
fill block_id="minecraft:stone_bricks" x1=100 y1=64 z1=200 x2=112 y2=72 z2=212 hollow=true
```

Left wing:

```text
fill block_id="minecraft:stone_bricks" x1=88 y1=64 z1=202 x2=99 y2=70 z2=210 hollow=true
```

Connection between them:

```text
fill block_id="minecraft:air" x1=99 y1=64 z1=205 x2=100 y2=67 z2=207 hollow=false
```

This removes both adjacent wall layers.

## 5.5 Interior partition walls

Interior walls are rectangular vertical panels.

Use solid `fill`.

Example partition wall:

```text
fill block_id="minecraft:stone_bricks" x1=106 y1=65 z1=201 x2=106 y2=68 z2=209 hollow=false
```

Carve interior door:

```text
fill block_id="minecraft:air" x1=106 y1=65 z1=205 x2=106 y2=67 z2=206 hollow=false
```

Do not build partitions with individual `place_block`.

---

# 6. Doors, windows, and openings

## 6.1 Door sizes

| Door type | Width | Height | Use |
|---|---:|---:|---|
| Small door | 1 | 3 | Hut/small house |
| Normal door | 2 | 3 | House |
| Grand door | 3 | 4 | Mansion/palace |
| Palace gate | 5 | 5 | Palace/castle |
| Castle gate | 5–7 | 6–9 | Large castle |

Door carving includes the wall floor level.

Example 3-wide, 4-tall door:

```text
fill block_id="minecraft:air" x1=105 y1=64 z1=210 x2=107 y2=67 z2=210 hollow=false
```

## 6.2 Centered doorway formula

For a wall from:

```text
x=100..112
```

Width:

```text
112 - 100 + 1 = 13
```

Center:

```text
centerX = 106
```

For 3-wide doorway:

```text
doorX1 = 105
doorX2 = 107
```

Use:

```text
fill block_id="minecraft:air" x1=105 y1=64 z1=210 x2=107 y2=67 z2=210 hollow=false
```

## 6.3 Front/south wall opening

For front wall at `z=wallZ2`:

```text
fill block_id="minecraft:air" x1=doorX1 y1=floorY z1=wallZ2 x2=doorX2 y2=floorY+3 z2=wallZ2 hollow=false
```

## 6.4 Back/north wall opening

For back wall at `z=wallZ1`:

```text
fill block_id="minecraft:air" x1=doorX1 y1=floorY z1=wallZ1 x2=doorX2 y2=floorY+3 z2=wallZ1 hollow=false
```

## 6.5 West/east wall opening

West wall at `x=wallX1`:

```text
fill block_id="minecraft:air" x1=wallX1 y1=floorY z1=doorZ1 x2=wallX1 y2=floorY+3 z2=doorZ2 hollow=false
```

East wall at `x=wallX2`:

```text
fill block_id="minecraft:air" x1=wallX2 y1=floorY z1=doorZ1 x2=wallX2 y2=floorY+3 z2=doorZ2 hollow=false
```

## 6.6 Window sizes

| Window type | Width | Height | Good Y range |
|---|---:|---:|---|
| Small window | 1 | 2 | floorY+2 to floorY+3 |
| Normal window | 2 | 2 | floorY+2 to floorY+3 |
| Tall window | 2 | 3 | floorY+2 to floorY+4 |
| Palace window | 3 | 4 | floorY+3 to floorY+6 |

Normal house window:

```text
fill block_id="minecraft:air" x1=102 y1=66 z1=210 x2=103 y2=67 z2=210 hollow=false
fill block_id="minecraft:glass" x1=102 y1=66 z1=210 x2=103 y2=67 z2=210 hollow=false
```

## 6.7 Window symmetry rule

Never put random windows.

For front wall width 13:

```text
x=100..112
center=106
door=105..107
```

Good symmetric windows:

```text
left window:  x=102..103
right window: x=109..110
```

They are both 3 blocks from the nearest corner and mirror around the center.

## 6.8 Do not cut windows too close to corners

Leave at least:

```text
1 block minimum from corner for small structures
2 blocks preferred
3+ blocks for palaces/castles
```

Bad:

```text
window touches x=100 corner pillar
```

Good:

```text
window starts at x=102 or x=103
```

## 6.9 Glass after carving

Always:

```text
fill air window
fill glass same coordinates
```

Do not place glass before carving.

---

# 7. Floors, carpets, ceilings

## 7.1 Interior floor replacement

After shell:

```text
fill block_id="minecraft:polished_andesite" x1=101 y1=64 z1=201 x2=111 y2=64 z2=209 hollow=false
```

This replaces only the interior floor, not the walls.

## 7.2 Floor border

Good floors look better with borders.

Interior area:

```text
x=101..111
z=201..209
```

Border strips:

```text
fill block_id="minecraft:smooth_stone" x1=101 y1=64 z1=201 x2=111 y2=64 z2=201 hollow=false
fill block_id="minecraft:smooth_stone" x1=101 y1=64 z1=209 x2=111 y2=64 z2=209 hollow=false
fill block_id="minecraft:smooth_stone" x1=101 y1=64 z1=201 x2=101 y2=64 z2=209 hollow=false
fill block_id="minecraft:smooth_stone" x1=111 y1=64 z1=201 x2=111 y2=64 z2=209 hollow=false
```

Center fill:

```text
fill block_id="minecraft:polished_andesite" x1=102 y1=64 z1=202 x2=110 y2=64 z2=208 hollow=false
```

## 7.3 Carpet/rug

A rug is a rectangular area. Use `fill`.

Example red carpet from entrance to center:

```text
fill block_id="minecraft:red_carpet" x1=105 y1=65 z1=202 x2=107 y2=65 z2=209 hollow=false
```

Carpet sits one block above floor.

## 7.4 Ceiling lights

For ceiling at `y=69`, lights can replace ceiling blocks:

```text
fill block_id="minecraft:glowstone" x1=104 y1=69 z1=204 x2=104 y2=69 z2=204 hollow=false
fill block_id="minecraft:glowstone" x1=108 y1=69 z1=204 x2=108 y2=69 z2=204 hollow=false
fill block_id="minecraft:glowstone" x1=104 y1=69 z1=207 x2=104 y2=69 z2=207 hollow=false
fill block_id="minecraft:glowstone" x1=108 y1=69 z1=207 x2=108 y2=69 z2=207 hollow=false
```

For large halls, place lights every 5–7 blocks.

---

# 8. Roofs

Every building needs a roof unless explicitly requested otherwise.

## 8.1 Flat roof with overhang

Simple and reliable.

Walls:

```text
x=100..112
z=200..210
ceilingY=69
```

Roof at `y=70`, overhang 1:

```text
fill block_id="minecraft:dark_oak_planks" x1=99 y1=70 z1=199 x2=113 y2=70 z2=211 hollow=false
```

## 8.2 Flat roof with parapet

Good for modern houses, castles, palaces.

Roof deck:

```text
fill block_id="minecraft:dark_oak_planks" x1=99 y1=70 z1=199 x2=113 y2=70 z2=211 hollow=false
```

Parapet edges:

```text
fill block_id="minecraft:stone_bricks" x1=99 y1=71 z1=199 x2=113 y2=71 z2=199 hollow=false
fill block_id="minecraft:stone_bricks" x1=99 y1=71 z1=211 x2=113 y2=71 z2=211 hollow=false
fill block_id="minecraft:stone_bricks" x1=99 y1=71 z1=199 x2=99 y2=71 z2=211 hollow=false
fill block_id="minecraft:stone_bricks" x1=113 y1=71 z1=199 x2=113 y2=71 z2=211 hollow=false
```

## 8.3 Stepped gable roof using `fill`

This is reliable and looks better than a flat roof.

Example for house:

```text
walls x=100..112
walls z=200..210
roof overhang x=99..113
roof starts y=70
front/back overhang z=199..211
```

Roof runs along X, slopes along Z.

Layer 0:

```text
fill block_id="minecraft:dark_oak_planks" x1=99 y1=70 z1=199 x2=113 y2=70 z2=199 hollow=false
fill block_id="minecraft:dark_oak_planks" x1=99 y1=70 z1=211 x2=113 y2=70 z2=211 hollow=false
```

Layer 1:

```text
fill block_id="minecraft:dark_oak_planks" x1=99 y1=71 z1=200 x2=113 y2=71 z2=200 hollow=false
fill block_id="minecraft:dark_oak_planks" x1=99 y1=71 z1=210 x2=113 y2=71 z2=210 hollow=false
```

Layer 2:

```text
fill block_id="minecraft:dark_oak_planks" x1=99 y1=72 z1=201 x2=113 y2=72 z2=201 hollow=false
fill block_id="minecraft:dark_oak_planks" x1=99 y1=72 z1=209 x2=113 y2=72 z2=209 hollow=false
```

Layer 3:

```text
fill block_id="minecraft:dark_oak_planks" x1=99 y1=73 z1=202 x2=113 y2=73 z2=202 hollow=false
fill block_id="minecraft:dark_oak_planks" x1=99 y1=73 z1=208 x2=113 y2=73 z2=208 hollow=false
```

Layer 4:

```text
fill block_id="minecraft:dark_oak_planks" x1=99 y1=74 z1=203 x2=113 y2=74 z2=203 hollow=false
fill block_id="minecraft:dark_oak_planks" x1=99 y1=74 z1=207 x2=113 y2=74 z2=207 hollow=false
```

Layer 5:

```text
fill block_id="minecraft:dark_oak_planks" x1=99 y1=75 z1=204 x2=113 y2=75 z2=204 hollow=false
fill block_id="minecraft:dark_oak_planks" x1=99 y1=75 z1=206 x2=113 y2=75 z2=206 hollow=false
```

Ridge:

```text
fill block_id="minecraft:dark_oak_planks" x1=99 y1=76 z1=205 x2=113 y2=76 z2=205 hollow=false
```

## 8.4 Pyramid/tower roof

For a 7x7 tower:

```text
tower x=100..106
tower z=200..206
topY=78
```

Pyramid roof:

```text
fill block_id="minecraft:dark_oak_planks" x1=99 y1=79 z1=199 x2=107 y2=79 z2=207 hollow=false
fill block_id="minecraft:dark_oak_planks" x1=100 y1=80 z1=200 x2=106 y2=80 z2=206 hollow=false
fill block_id="minecraft:dark_oak_planks" x1=101 y1=81 z1=201 x2=105 y2=81 z2=205 hollow=false
fill block_id="minecraft:dark_oak_planks" x1=102 y1=82 z1=202 x2=104 y2=82 z2=204 hollow=false
fill block_id="minecraft:dark_oak_planks" x1=103 y1=83 z1=203 x2=103 y2=83 z2=203 hollow=false
```

## 8.5 Castle battlements

For castle walls/towers, add battlements.

Continuous parapet:

```text
fill block_id="minecraft:stone_bricks" x1=100 y1=80 z1=200 x2=112 y2=80 z2=200 hollow=false
```

Crenel blocks can be individual or small fills. Since they are single blocks, `place_block` is acceptable, but small `fill` is also fine.

Example alternating merlons:

```text
fill block_id="minecraft:stone_bricks" x1=100 y1=81 z1=200 x2=100 y2=82 z2=200 hollow=false
fill block_id="minecraft:stone_bricks" x1=102 y1=81 z1=200 x2=102 y2=82 z2=200 hollow=false
fill block_id="minecraft:stone_bricks" x1=104 y1=81 z1=200 x2=104 y2=82 z2=200 hollow=false
fill block_id="minecraft:stone_bricks" x1=106 y1=81 z1=200 x2=106 y2=82 z2=200 hollow=false
fill block_id="minecraft:stone_bricks" x1=108 y1=81 z1=200 x2=108 y2=82 z2=200 hollow=false
fill block_id="minecraft:stone_bricks" x1=110 y1=81 z1=200 x2=110 y2=82 z2=200 hollow=false
fill block_id="minecraft:stone_bricks" x1=112 y1=81 z1=200 x2=112 y2=82 z2=200 hollow=false
```

---

# 9. Structural decoration rules

Good buildings need depth. Do not make giant flat walls.

## 9.1 Corner pillars

Add pillars at corners after shell.

Example:

```text
fill block_id="minecraft:quartz_pillar" x1=100 y1=64 z1=200 x2=100 y2=70 z2=200 hollow=false
fill block_id="minecraft:quartz_pillar" x1=112 y1=64 z1=200 x2=112 y2=70 z2=200 hollow=false
fill block_id="minecraft:quartz_pillar" x1=100 y1=64 z1=210 x2=100 y2=70 z2=210 hollow=false
fill block_id="minecraft:quartz_pillar" x1=112 y1=64 z1=210 x2=112 y2=70 z2=210 hollow=false
```

## 9.2 Top trim band

Add a horizontal trim band near the roof.

North wall:

```text
fill block_id="minecraft:smooth_stone" x1=100 y1=69 z1=200 x2=112 y2=69 z2=200 hollow=false
```

South wall:

```text
fill block_id="minecraft:smooth_stone" x1=100 y1=69 z1=210 x2=112 y2=69 z2=210 hollow=false
```

West wall:

```text
fill block_id="minecraft:smooth_stone" x1=100 y1=69 z1=200 x2=100 y2=69 z2=210 hollow=false
```

East wall:

```text
fill block_id="minecraft:smooth_stone" x1=112 y1=69 z1=200 x2=112 y2=69 z2=210 hollow=false
```

## 9.3 Bottom trim band

At floor level or one above floor:

```text
fill block_id="minecraft:smooth_stone" x1=100 y1=65 z1=200 x2=112 y2=65 z2=200 hollow=false
fill block_id="minecraft:smooth_stone" x1=100 y1=65 z1=210 x2=112 y2=65 z2=210 hollow=false
fill block_id="minecraft:smooth_stone" x1=100 y1=65 z1=200 x2=100 y2=65 z2=210 hollow=false
fill block_id="minecraft:smooth_stone" x1=112 y1=65 z1=200 x2=112 y2=65 z2=210 hollow=false
```

Do not cover windows or doors. If trim conflicts with windows, place trim before carving, then carve windows again.

## 9.4 Exterior columns

For palace entrances, use columns.

Example two front columns:

```text
fill block_id="minecraft:quartz_pillar" x1=103 y1=64 z1=212 x2=103 y2=70 z2=212 hollow=false
fill block_id="minecraft:quartz_pillar" x1=109 y1=64 z1=212 x2=109 y2=70 z2=212 hollow=false
```

Add porch roof:

```text
fill block_id="minecraft:stone_bricks" x1=102 y1=71 z1=210 x2=110 y2=71 z2=213 hollow=false
```

## 9.5 Steps

Use `place_block` for stair blocks because stairs have orientation.

Example front steps on south side:

```text
place_block block_id="minecraft:stone_brick_stairs" x=105 y=63 z=211 facing="south"
place_block block_id="minecraft:stone_brick_stairs" x=106 y=63 z=211 facing="south"
place_block block_id="minecraft:stone_brick_stairs" x=107 y=63 z=211 facing="south"
```

For a grand staircase, add wider rows:

```text
place_block block_id="minecraft:stone_brick_stairs" x=104 y=63 z=211 facing="south"
place_block block_id="minecraft:stone_brick_stairs" x=105 y=63 z=211 facing="south"
place_block block_id="minecraft:stone_brick_stairs" x=106 y=63 z=211 facing="south"
place_block block_id="minecraft:stone_brick_stairs" x=107 y=63 z=211 facing="south"
place_block block_id="minecraft:stone_brick_stairs" x=108 y=63 z=211 facing="south"

place_block block_id="minecraft:stone_brick_stairs" x=103 y=62 z=212 facing="south"
place_block block_id="minecraft:stone_brick_stairs" x=104 y=62 z=212 facing="south"
place_block block_id="minecraft:stone_brick_stairs" x=105 y=62 z=212 facing="south"
place_block block_id="minecraft:stone_brick_stairs" x=106 y=62 z=212 facing="south"
place_block block_id="minecraft:stone_brick_stairs" x=107 y=62 z=212 facing="south"
place_block block_id="minecraft:stone_brick_stairs" x=108 y=62 z=212 facing="south"
place_block block_id="minecraft:stone_brick_stairs" x=109 y=62 z=212 facing="south"
```

---

# 10. Style palettes

## 10.1 Medieval stone house

```text
main: minecraft:stone_bricks
accent: minecraft:cobblestone or minecraft:smooth_stone
roof: minecraft:dark_oak_planks
floor: minecraft:polished_andesite
glass: minecraft:glass
light: minecraft:glowstone
columns: minecraft:oak_log or minecraft:stripped_oak_log
```

## 10.2 Palace / temple

```text
main: minecraft:quartz_block or minecraft:stone_bricks
accent: minecraft:quartz_pillar
roof: minecraft:dark_oak_planks or minecraft:gold_block accents
floor: minecraft:polished_andesite
glass: minecraft:glass
light: minecraft:sea_lantern or minecraft:glowstone
```

## 10.3 Modern house

```text
main: minecraft:white_concrete
accent: minecraft:gray_concrete
roof: minecraft:smooth_quartz or minecraft:dark_oak_planks
floor: minecraft:quartz_block
glass: minecraft:glass
light: minecraft:sea_lantern
```

## 10.4 Desert building

```text
main: minecraft:sandstone
accent: minecraft:smooth_sandstone
roof: minecraft:cut_sandstone
floor: minecraft:smooth_sandstone
glass: minecraft:glass
light: minecraft:glowstone
```

## 10.5 Castle

```text
main: minecraft:stone_bricks
accent: minecraft:cracked_stone_bricks or minecraft:cobblestone
floor: minecraft:polished_andesite
roof: minecraft:dark_oak_planks
glass: minecraft:glass
light: minecraft:glowstone
```

---

# 11. Building type requirements

## 11.1 House

Must include:

- foundation
- one main room
- door
- at least 4 windows
- roof with overhang
- floor
- lights
- front steps
- path or porch

Recommended dimensions:

```text
small: 9x9
normal: 13x11
large: 17x15
```

## 11.2 Mansion

Must include:

- central hall
- at least two side rooms/wings
- symmetrical front facade
- grand doorway
- many windows
- roof over each module
- columns or trim
- interior lighting
- front path/stairs

## 11.3 Palace

Must include:

- large central hall
- symmetrical wings
- grand entrance
- columns
- high ceiling
- tall windows
- roof/parapet
- decorative floor
- lights
- front stairs
- optional towers/garden/courtyard

## 11.4 Castle

Must include:

- outer wall or main keep
- towers
- battlements
- gate
- stone foundation
- windows/slits
- roof or crenellated top
- courtyard/path if large

## 11.5 Tower

Must include:

- solid foundation
- hollow tower shell
- interior floor or platform
- windows/slits
- roof or battlements
- entrance
- lighting

## 11.6 Bridge

Must include:

- deck/platform
- supports/piers
- railings
- entry ramps/stairs
- lighting if large

Use `fill` for deck, rails, and supports.

---

# 12. Large structures

For large structures, do not create one huge plain box.

Use modular massing.

## 12.1 Good palace layout

Example modules:

```text
central hall: 19x17
left wing:    11x13
right wing:   11x13
front porch:  11x6
corner towers: 7x7
```

Build as multiple hollow boxes.

## 12.2 Castle layout

Example modules:

```text
curtain wall rectangle
four corner towers
central keep
gatehouse
courtyard
walkway on wall
battlements
```

## 12.3 Avoid massive blank walls

If a wall is longer than 10 blocks, add at least one of:

- windows every 4–6 blocks
- pillars every 5–7 blocks
- trim bands
- buttresses
- balconies
- material variation

---

# 13. Verification checklist

Before saying “done”, verify mentally or with `inspect_block` if needed.

## 13.1 Foundation checklist

- Is there a solid foundation?
- Does it extend beyond all walls?
- Is it at least one layer thick?
- Are there no missing corners?

## 13.2 Wall checklist

- Are all exterior walls complete?
- Are walls built with `fill hollow=true` or rectangular fills?
- Are there no random gaps except intentional openings?
- Are shared room passages carved?

## 13.3 Opening checklist

- Main entrance exists.
- Doorway is tall enough.
- Windows are symmetric.
- Windows have glass.
- Openings are not too close to corners.

## 13.4 Roof checklist

- Roof covers entire building.
- Roof overhang exists where appropriate.
- No room is open to sky.
- Roof does not block interior movement.
- Roof style matches building.

## 13.5 Interior checklist

- Floor is decorative.
- Lights exist.
- Interior is not completely dark.
- Player can enter and walk around.

## 13.6 Aesthetic checklist

- Not just a plain cube.
- Has trim/columns/windows/roof depth.
- Symmetrical if formal.
- Uses at least 3 materials.
- Entrance is obvious.

---

# 14. Common mistakes and fixes

## Mistake: Forgot foundation

Fix:

```text
fill block_id="minecraft:stone_bricks" x1=foundationX1 y1=foundationY z1=foundationZ1 x2=foundationX2 y2=foundationY z2=foundationZ2 hollow=false
```

If building already exists, add foundation under/around it carefully.

## Mistake: Door blocked by wall

Fix by carving larger air volume:

```text
fill block_id="minecraft:air" x1=doorX1 y1=floorY z1=frontZ x2=doorX2 y2=floorY+3 z2=frontZ hollow=false
```

## Mistake: Window missing glass

Fix:

```text
fill block_id="minecraft:glass" x1=windowX1 y1=windowY1 z1=wallZ x2=windowX2 y2=windowY2 z2=wallZ hollow=false
```

## Mistake: Roof too small

Fix with overhang:

```text
fill block_id="minecraft:dark_oak_planks" x1=wallX1-1 y1=roofY z1=wallZ1-1 x2=wallX2+1 y2=roofY z2=wallZ2+1 hollow=false
```

## Mistake: Building looks like a box

Fix by adding:

- corner pillars
- top trim
- bottom trim
- windows
- porch
- roof overhang
- steps
- path

---

# 15. Complete default house template

Use this when the user asks for a nice small/medium house and gives no coordinates.

Adjust coordinates based on player position if needed.

Assume:

```text
foundationY = 63
floorY = 64
walls x=100..112
walls z=200..210
front = south = z=210
centerX = 106
```

## 15.1 Foundation

```text
fill block_id="minecraft:stone_bricks" x1=98 y1=63 z1=198 x2=114 y2=63 z2=212 hollow=false
```

## 15.2 Main shell

```text
fill block_id="minecraft:stone_bricks" x1=100 y1=64 z1=200 x2=112 y2=69 z2=210 hollow=true
```

## 15.3 Main doorway

```text
fill block_id="minecraft:air" x1=105 y1=64 z1=210 x2=107 y2=67 z2=210 hollow=false
```

## 15.4 Front windows

```text
fill block_id="minecraft:air" x1=102 y1=66 z1=210 x2=103 y2=67 z2=210 hollow=false
fill block_id="minecraft:air" x1=109 y1=66 z1=210 x2=110 y2=67 z2=210 hollow=false

fill block_id="minecraft:glass" x1=102 y1=66 z1=210 x2=103 y2=67 z2=210 hollow=false
fill block_id="minecraft:glass" x1=109 y1=66 z1=210 x2=110 y2=67 z2=210 hollow=false
```

## 15.5 Back windows

```text
fill block_id="minecraft:air" x1=102 y1=66 z1=200 x2=103 y2=67 z2=200 hollow=false
fill block_id="minecraft:air" x1=109 y1=66 z1=200 x2=110 y2=67 z2=200 hollow=false

fill block_id="minecraft:glass" x1=102 y1=66 z1=200 x2=103 y2=67 z2=200 hollow=false
fill block_id="minecraft:glass" x1=109 y1=66 z1=200 x2=110 y2=67 z2=200 hollow=false
```

## 15.6 Side windows

West side:

```text
fill block_id="minecraft:air" x1=100 y1=66 z1=203 x2=100 y2=67 z2=204 hollow=false
fill block_id="minecraft:air" x1=100 y1=66 z1=206 x2=100 y2=67 z2=207 hollow=false

fill block_id="minecraft:glass" x1=100 y1=66 z1=203 x2=100 y2=67 z2=204 hollow=false
fill block_id="minecraft:glass" x1=100 y1=66 z1=206 x2=100 y2=67 z2=207 hollow=false
```

East side:

```text
fill block_id="minecraft:air" x1=112 y1=66 z1=203 x2=112 y2=67 z2=204 hollow=false
fill block_id="minecraft:air" x1=112 y1=66 z1=206 x2=112 y2=67 z2=207 hollow=false

fill block_id="minecraft:glass" x1=112 y1=66 z1=203 x2=112 y2=67 z2=204 hollow=false
fill block_id="minecraft:glass" x1=112 y1=66 z1=206 x2=112 y2=67 z2=207 hollow=false
```

## 15.7 Interior floor

```text
fill block_id="minecraft:polished_andesite" x1=101 y1=64 z1=201 x2=111 y2=64 z2=209 hollow=false
```

## 15.8 Corner pillars

```text
fill block_id="minecraft:quartz_pillar" x1=100 y1=64 z1=200 x2=100 y2=70 z2=200 hollow=false
fill block_id="minecraft:quartz_pillar" x1=112 y1=64 z1=200 x2=112 y2=70 z2=200 hollow=false
fill block_id="minecraft:quartz_pillar" x1=100 y1=64 z1=210 x2=100 y2=70 z2=210 hollow=false
fill block_id="minecraft:quartz_pillar" x1=112 y1=64 z1=210 x2=112 y2=70 z2=210 hollow=false
```

## 15.9 Top trim

```text
fill block_id="minecraft:smooth_stone" x1=100 y1=69 z1=200 x2=112 y2=69 z2=200 hollow=false
fill block_id="minecraft:smooth_stone" x1=100 y1=69 z1=210 x2=112 y2=69 z2=210 hollow=false
fill block_id="minecraft:smooth_stone" x1=100 y1=69 z1=200 x2=100 y2=69 z2=210 hollow=false
fill block_id="minecraft:smooth_stone" x1=112 y1=69 z1=200 x2=112 y2=69 z2=210 hollow=false
```

## 15.10 Gable roof

```text
fill block_id="minecraft:dark_oak_planks" x1=99 y1=70 z1=199 x2=113 y2=70 z2=199 hollow=false
fill block_id="minecraft:dark_oak_planks" x1=99 y1=70 z1=211 x2=113 y2=70 z2=211 hollow=false

fill block_id="minecraft:dark_oak_planks" x1=99 y1=71 z1=200 x2=113 y2=71 z2=200 hollow=false
fill block_id="minecraft:dark_oak_planks" x1=99 y1=71 z1=210 x2=113 y2=71 z2=210 hollow=false

fill block_id="minecraft:dark_oak_planks" x1=99 y1=72 z1=201 x2=113 y2=72 z2=201 hollow=false
fill block_id="minecraft:dark_oak_planks" x1=99 y1=72 z1=209 x2=113 y2=72 z2=209 hollow=false

fill block_id="minecraft:dark_oak_planks" x1=99 y1=73 z1=202 x2=113 y2=73 z2=202 hollow=false
fill block_id="minecraft:dark_oak_planks" x1=99 y1=73 z1=208 x2=113 y2=73 z2=208 hollow=false

fill block_id="minecraft:dark_oak_planks" x1=99 y1=74 z1=203 x2=113 y2=74 z2=203 hollow=false
fill block_id="minecraft:dark_oak_planks" x1=99 y1=74 z1=207 x2=113 y2=74 z2=207 hollow=false

fill block_id="minecraft:dark_oak_planks" x1=99 y1=75 z1=204 x2=113 y2=75 z2=204 hollow=false
fill block_id="minecraft:dark_oak_planks" x1=99 y1=75 z1=206 x2=113 y2=75 z2=206 hollow=false

fill block_id="minecraft:dark_oak_planks" x1=99 y1=76 z1=205 x2=113 y2=76 z2=205 hollow=false
```

## 15.11 Ceiling lights

```text
fill block_id="minecraft:glowstone" x1=104 y1=69 z1=204 x2=104 y2=69 z2=204 hollow=false
fill block_id="minecraft:glowstone" x1=108 y1=69 z1=204 x2=108 y2=69 z2=204 hollow=false
fill block_id="minecraft:glowstone" x1=104 y1=69 z1=207 x2=104 y2=69 z2=207 hollow=false
fill block_id="minecraft:glowstone" x1=108 y1=69 z1=207 x2=108 y2=69 z2=207 hollow=false
```

## 15.12 Front steps

```text
place_block block_id="minecraft:stone_brick_stairs" x=105 y=63 z=211 facing="south"
place_block block_id="minecraft:stone_brick_stairs" x=106 y=63 z=211 facing="south"
place_block block_id="minecraft:stone_brick_stairs" x=107 y=63 z=211 facing="south"
```

## 15.13 Path

```text
fill block_id="minecraft:gravel" x1=105 y1=63 z1=212 x2=107 y2=63 z2=220 hollow=false
```

This house has:

- foundation
- walls
- door
- windows
- glass
- floor
- corner pillars
- trim
- roof
- lighting
- steps
- path

So it is complete.

---

# 16. Complete palace skeleton template

Use this for a medium-large palace if no coordinates are specified. Adjust around player location.

Assume:

```text
foundationY = 63
floorY = 64
front = south
centerX = 219
```

Main modules:

```text
Main hall:  x=210..228, z=304..320, y=64..75
West wing:  x=199..209, z=306..318, y=64..72
East wing:  x=229..239, z=306..318, y=64..72
Front porch:x=214..224, z=321..326, y=64..70
```

## 16.1 Foundation

```text
fill block_id="minecraft:stone_bricks" x1=197 y1=63 z1=297 x2=241 y2=63 z2=329 hollow=false
```

## 16.2 Main hall

```text
fill block_id="minecraft:stone_bricks" x1=210 y1=64 z1=304 x2=228 y2=75 z2=320 hollow=true
```

## 16.3 Wings

```text
fill block_id="minecraft:stone_bricks" x1=199 y1=64 z1=306 x2=209 y2=72 z2=318 hollow=true
fill block_id="minecraft:stone_bricks" x1=229 y1=64 z1=306 x2=239 y2=72 z2=318 hollow=true
```

## 16.4 Front porch

```text
fill block_id="minecraft:stone_bricks" x1=214 y1=64 z1=321 x2=224 y2=70 z2=326 hollow=true
```

## 16.5 Grand entrance

Front porch front wall is `z=326`.

```text
fill block_id="minecraft:air" x1=217 y1=64 z1=326 x2=221 y2=68 z2=326 hollow=false
```

Connection from porch into main hall:

```text
fill block_id="minecraft:air" x1=217 y1=64 z1=320 x2=221 y2=68 z2=321 hollow=false
```

Wing connections:

```text
fill block_id="minecraft:air" x1=209 y1=64 z1=311 x2=210 y2=67 z2=313 hollow=false
fill block_id="minecraft:air" x1=228 y1=64 z1=311 x2=229 y2=67 z2=313 hollow=false
```

## 16.6 Palace windows

Back main hall windows:

```text
fill block_id="minecraft:air" x1=212 y1=68 z1=304 x2=214 y2=71 z2=304 hollow=false
fill block_id="minecraft:air" x1=224 y1=68 z1=304 x2=226 y2=71 z2=304 hollow=false

fill block_id="minecraft:glass" x1=212 y1=68 z1=304 x2=214 y2=71 z2=304 hollow=false
fill block_id="minecraft:glass" x1=224 y1=68 z1=304 x2=226 y2=71 z2=304 hollow=false
```

West wing outer windows:

```text
fill block_id="minecraft:air" x1=199 y1=67 z1=309 x2=199 y2=69 z2=311 hollow=false
fill block_id="minecraft:air" x1=199 y1=67 z1=314 x2=199 y2=69 z2=316 hollow=false

fill block_id="minecraft:glass" x1=199 y1=67 z1=309 x2=199 y2=69 z2=311 hollow=false
fill block_id="minecraft:glass" x1=199 y1=67 z1=314 x2=199 y2=69 z2=316 hollow=false
```

East wing outer windows:

```text
fill block_id="minecraft:air" x1=239 y1=67 z1=309 x2=239 y2=69 z2=311 hollow=false
fill block_id="minecraft:air" x1=239 y1=67 z1=314 x2=239 y2=69 z2=316 hollow=false

fill block_id="minecraft:glass" x1=239 y1=67 z1=309 x2=239 y2=69 z2=311 hollow=false
fill block_id="minecraft:glass" x1=239 y1=67 z1=314 x2=239 y2=69 z2=316 hollow=false
```

Porch side windows:

```text
fill block_id="minecraft:air" x1=214 y1=66 z1=323 x2=214 y2=68 z2=324 hollow=false
fill block_id="minecraft:air" x1=224 y1=66 z1=323 x2=224 y2=68 z2=324 hollow=false

fill block_id="minecraft:glass" x1=214 y1=66 z1=323 x2=214 y2=68 z2=324 hollow=false
fill block_id="minecraft:glass" x1=224 y1=66 z1=323 x2=224 y2=68 z2=324 hollow=false
```

## 16.7 Floors

```text
fill block_id="minecraft:polished_andesite" x1=211 y1=64 z1=305 x2=227 y2=64 z2=319 hollow=false
fill block_id="minecraft:polished_andesite" x1=200 y1=64 z1=307 x2=208 y2=64 z2=317 hollow=false
fill block_id="minecraft:polished_andesite" x1=230 y1=64 z1=307 x2=238 y2=64 z2=317 hollow=false
fill block_id="minecraft:polished_andesite" x1=215 y1=64 z1=322 x2=223 y2=64 z2=325 hollow=false
```

## 16.8 Main hall columns

```text
fill block_id="minecraft:quartz_pillar" x1=213 y1=65 z1=307 x2=213 y2=74 z2=307 hollow=false
fill block_id="minecraft:quartz_pillar" x1=225 y1=65 z1=307 x2=225 y2=74 z2=307 hollow=false
fill block_id="minecraft:quartz_pillar" x1=213 y1=65 z1=317 x2=213 y2=74 z2=317 hollow=false
fill block_id="minecraft:quartz_pillar" x1=225 y1=65 z1=317 x2=225 y2=74 z2=317 hollow=false
```

## 16.9 Roofs

Main hall roof:

```text
fill block_id="minecraft:dark_oak_planks" x1=209 y1=76 z1=303 x2=229 y2=76 z2=321 hollow=false
```

West wing roof:

```text
fill block_id="minecraft:dark_oak_planks" x1=198 y1=73 z1=305 x2=210 y2=73 z2=319 hollow=false
```

East wing roof:

```text
fill block_id="minecraft:dark_oak_planks" x1=228 y1=73 z1=305 x2=240 y2=73 z2=319 hollow=false
```

Porch roof:

```text
fill block_id="minecraft:dark_oak_planks" x1=213 y1=71 z1=320 x2=225 y2=71 z2=327 hollow=false
```

## 16.10 Palace trim bands

Main hall upper trim:

```text
fill block_id="minecraft:quartz_block" x1=210 y1=75 z1=304 x2=228 y2=75 z2=304 hollow=false
fill block_id="minecraft:quartz_block" x1=210 y1=75 z1=320 x2=228 y2=75 z2=320 hollow=false
fill block_id="minecraft:quartz_block" x1=210 y1=75 z1=304 x2=210 y2=75 z2=320 hollow=false
fill block_id="minecraft:quartz_block" x1=228 y1=75 z1=304 x2=228 y2=75 z2=320 hollow=false
```

Wing upper trim:

```text
fill block_id="minecraft:quartz_block" x1=199 y1=72 z1=306 x2=209 y2=72 z2=306 hollow=false
fill block_id="minecraft:quartz_block" x1=199 y1=72 z1=318 x2=209 y2=72 z2=318 hollow=false
fill block_id="minecraft:quartz_block" x1=229 y1=72 z1=306 x2=239 y2=72 z2=306 hollow=false
fill block_id="minecraft:quartz_block" x1=229 y1=72 z1=318 x2=239 y2=72 z2=318 hollow=false
```

## 16.11 Lights

Main hall:

```text
fill block_id="minecraft:glowstone" x1=215 y1=75 z1=309 x2=215 y2=75 z2=309 hollow=false
fill block_id="minecraft:glowstone" x1=223 y1=75 z1=309 x2=223 y2=75 z2=309 hollow=false
fill block_id="minecraft:glowstone" x1=215 y1=75 z1=315 x2=215 y2=75 z2=315 hollow=false
fill block_id="minecraft:glowstone" x1=223 y1=75 z1=315 x2=223 y2=75 z2=315 hollow=false
```

Wings:

```text
fill block_id="minecraft:glowstone" x1=204 y1=72 z1=312 x2=204 y2=72 z2=312 hollow=false
fill block_id="minecraft:glowstone" x1=234 y1=72 z1=312 x2=234 y2=72 z2=312 hollow=false
```

Porch:

```text
fill block_id="minecraft:glowstone" x1=219 y1=70 z1=323 x2=219 y2=70 z2=323 hollow=false
```

## 16.12 Front steps and path

Grand steps:

```text
place_block block_id="minecraft:stone_brick_stairs" x=217 y=63 z=327 facing="south"
place_block block_id="minecraft:stone_brick_stairs" x=218 y=63 z=327 facing="south"
place_block block_id="minecraft:stone_brick_stairs" x=219 y=63 z=327 facing="south"
place_block block_id="minecraft:stone_brick_stairs" x=220 y=63 z=327 facing="south"
place_block block_id="minecraft:stone_brick_stairs" x=221 y=63 z=327 facing="south"
```

Path:

```text
fill block_id="minecraft:gravel" x1=218 y1=63 z1=328 x2=220 y2=63 z2=340 hollow=false
```

This palace skeleton is complete enough, but for a “large detailed palace”, add:

- corner towers
- gardens
- extra windows
- balconies
- statues
- courtyard
- roof decorations

---

# 17. Final response after building

After completing a build, respond briefly with:

```text
Done — built a [structure type] at approximately x=..., y=..., z=...
Included: foundation, walls, roof, entrance, windows, floor, lights, details.
```

If coordinates were user-specified, mention those.

Do not claim completion before the roof, foundation, entrance, and basic details are done.

---

# 18. Blueprint workflow: save, plan, build, resume

Use blueprints when copying an existing structure, repeating a module, preserving orientations, rotating a design, building a complex survival structure, or resuming a long project reliably.

## 18.1 Blueprint capture contract

`save_blueprint` accepts two inclusive world corners and a name.

Hard limits:

```text
name: 1-48 letters, digits, underscore, or hyphen
maximum X/Y/Z axis: 64 cells
maximum bounding volume: 32768 cells
chunks: every selected chunk must already be loaded
height: entire selection must be inside dimension build height
```

Saved data:

- only non-air blocks;
- namespaced block IDs and serializable block-state properties;
- source dimensions and blueprint dimensions;
- local coordinates relative to the minimum selected corner.

Deliberately excluded:

- all air cells, so a blueprint never means "clear this space";
- block-entity NBT, inventories, signs, command text, machine settings, books, loot, and entity data;
- `waterlogged`, preventing the blueprint from becoming a free fluid-copy mechanism.

Consequences: a chest shape can be copied but its contents cannot; configured machines need manual commissioning; mobs, paintings, armor stands, boats, and minecarts are not captured. Never advertise a blueprint as a full structure/NBT backup.

Safe capture procedure:

1. Determine exact inclusive minimum and maximum corners.
2. Calculate three axes and volume before calling the tool.
3. Inspect edge chunks and block entities inside the selection.
4. Choose a descriptive stable name such as `castle_gate_v2`, not a transient name like `test`.
5. Save once and record source orientation, intended front, footprint, and expected non-air count.
6. Run `plan_blueprint` at a harmless test anchor before relying on the file.

## 18.2 Anchor and rotation semantics

The target `(x,y,z)` is the world anchor for blueprint local `(0,0,0)`, which came from the minimum capture corner. It is not the center, doorway, player position, or original pivot.

Allowed Y-axis clockwise rotations are exactly:

```text
0, 90, 180, 270
```

Rotation changes both coordinates and rotatable block states. At 90/270 degrees the horizontal X/Z dimensions swap. Before placing a rotated design, calculate the resulting bounding box and confirm its front faces the intended direction. Do not "correct" a rotated build with broad fills afterward; fix the blueprint or anchor.

## 18.3 Planning is mandatory

Call `plan_blueprint` immediately before every first build invocation and after any meaningful world or inventory change. It never modifies the world.

Review all result categories:

| Result | Meaning | Required response |
|---|---|---|
| already correct | World position already satisfies the target | Leave it untouched. |
| placement | Target is available and needs a block | Ensure material and reach/path exist. |
| state fix | Same block type exists with a different saved state | In survival, verify that state is player-controllable; creative can restore exact state. |
| conflict | A different non-replaceable block occupies target | Stop and resolve deliberately; builder will not overwrite it. |
| missing material | Survival inventory is short | Gather/craft the listed amount and replan. |
| no block item / unsupported | State cannot be represented safely by current survival placement | Build that detail manually or use creative with permission. |

The response intentionally bounds previews to fit the network result limit. A short preview is not the complete placement list; use aggregate counts as authoritative and inspect representative coordinates.

## 18.4 Explicit confirmation and batch limits

`build_blueprint` refuses to start without `confirm=true`. Confirmation is valid only after reviewing a fresh plan with the same name, anchor, rotation, game mode, and relevant inventory.

Defaults and ceilings:

| Mode | Default batch | Maximum requested batch | Behavior |
|---|---:|---:|---|
| Survival | 64 | 256 | Real navigation and player placement; consumes items. |
| Creative | 2048 | 8192 | Exact saved state writes, rate-limited to 64 changes per tick. |

Use smaller batches for active multiplayer sites, unstable terrain, scarce materials, or mixed block states. Larger is not automatically better: a bounded batch gives a clean inspection checkpoint.

## 18.5 Resuming correctly

Repeat with the **identical blueprint name, anchor, and rotation**. The task replans against current world state when it starts, so correct completed blocks are skipped rather than placed twice.

After each batch:

1. Read `changed_this_batch`, completion state, remaining work, and failure details.
2. Inspect representative completed blocks and all reported conflict coordinates.
3. Replenish materials if required.
4. Run a fresh plan when the world or inventory changed.
5. Continue with the same parameters until the plan reports no required changes.

If the server restarts, a safely persisted active blueprint task returns to pending and recreates its executor. It then reloads the blueprint and replans the world. Do not submit a second tool call with a different ID merely because the task is temporarily pending; let the restored call reconnect or inspect task status first.

## 18.6 Survival limitations that must be surfaced

Survival placement reproduces only states a real player can safely create through the existing placement executor. Environmental properties such as fence connections, wall shapes, redstone power, and leaf distance are compared with tolerant rules rather than treated as inventory-controlled state.

The current planner marks same-cell multiple-use states unsupported in survival, including relevant examples such as:

- double slabs;
- stacked snow layers;
- multiple candles in one block;
- multi-count sea pickles;
- multi-egg turtle egg states.

Doors, beds, and double-height plants occupy multiple cells and require both halves to remain conflict-free. Never manually place one half and call the blueprint finished. Block entities are shapes only; contents and configuration must be restored separately.

## 18.7 Creative conflict policy

Creative blueprint building is exact, but not destructive:

- it never clears blueprint air;
- it refuses occupied conflicting blocks;
- it replans when the task actually starts;
- it checks height, chunk availability, and current occupancy before each write;
- it will not silently overwrite a block that appeared after planning.

Resolve a conflict by inspecting it and choosing one of: move the anchor, edit the site with explicit approval, save a corrected blueprint, or leave that position unbuilt. Never broad-clear the entire target as a convenience.

# 19. Large-project partitioning and checkpoints

## 19.1 Partition by structural ownership

For a build larger than one blueprint limit or one safe work session, split into stable modules:

```text
site grading
foundation
structural frame
exterior shell
roof
interior floors
room modules
utilities and lighting
landscape
final detail pass
```

Prefer boundaries at expansion joints, floor levels, tower edges, wall bays, bridge spans, or repeated modules. Avoid slicing through a door, bed, tall plant, staircase turn, redstone device, or block entity.

## 19.2 Coordinate ledger

Before executing a large plan, maintain a concise ledger:

```text
project name
dimension
global min/max bounding box
front direction and centerline
foundation/floor/ceiling/roof Y levels
module names and inclusive bounds
blueprint anchor + rotation for each module
material budget and reserve
protected coordinates
completed checkpoints
```

Use `todo_write` for multi-stage work. Mark a module done only after verification, not merely after dispatching its task.

## 19.3 Checkpoint policy

Create a checkpoint after foundation, each floor or major module, roof closure, utilities, and final decoration. At a checkpoint:

1. Pause queued tasks if inspection requires a stable world.
2. Query task state and inventory.
3. Inspect corners, joins, openings, supports, and representative stateful blocks.
4. Confirm the next module's anchor against the same ledger.
5. Resume only when no overlap or offset error exists.

One-block coordinate drift compounds across modules. If a join is wrong, stop and correct the ledger before building more.

# 20. Terrain, clearance, and protected-world rules

## 20.1 Terrain survey

Sample more than the four corners. For medium/large footprints inspect corners, center, entrance, each wing, and any steep elevation change. Detect:

- water/lava and waterlogged blocks;
- caves or ravines under foundations;
- sand/gravel that may fall;
- snow, vegetation, replaceable plants, and uneven topsoil;
- structures, claims, portals, farms, and redstone;
- insufficient headroom or world-height overflow.

Choose among conforming foundation piers, retaining walls, stepped terraces, a raised platform, limited grading, or relocation. Do not flatten an area by reflex.

## 20.2 Protected contents

Before clearing or replacing any occupied cell, inspect block identity and storage. Treat every container, machine, shulker, sign, lectern, spawner, beehive, decorated pot, command block, portal, and unknown mod block as protected until explicitly approved.

Blueprint capture does not preserve these contents. Creative `fill` does not preserve them either. A decorative facade is not worth destroying user data.

## 20.3 Fluid and gravity containment

- Build retaining walls before opening a water or lava boundary.
- Support falling blocks before removing what holds them.
- Keep temporary drainage and scaffolding out of the final blueprint unless intentionally part of it.
- Never place water-sensitive redstone or torches before the shell is weather/fluid tight.
- Reinspect after the first update tick; neighbor updates can reveal leaks or falling material after placement succeeds.

# 21. Verification by evidence

Mental verification is insufficient for a large or automated build. Use targeted inspection and counts.

## 21.1 Geometric checks

- Compare actual minimum/maximum corners to the ledger.
- Check centerline, mirrored offsets, repeated bay spacing, floor heights, and roof ridge.
- Inspect module joins for one-block gaps, overlaps, and double-thick unintended walls.
- Confirm doors, stairs, ladders, bridges, and corridors have usable clearance.

## 21.2 State checks

Inspect representative stairs, slabs, logs, pillars, doors, trapdoors, beds, rails, signs, and redstone-facing blocks. Correct block ID is not enough when facing, axis, half, shape, hinge, or open state matters.

In survival, accept environment-derived connections that the planner intentionally ignores, but verify the structure works visually and physically. In creative blueprint mode, exact saved state is expected except deliberately omitted `waterlogged`.

## 21.3 Functional checks

- Walk from exterior path through every intended entrance and major route.
- Verify no spawn-dangerous dark interior remains.
- Confirm roof and retaining walls prevent fluid/weather intrusion where applicable.
- Open each intended container or machine only after checking that it is the correct block; then commission contents/settings separately.
- Test doors, gates, bridges, elevators, redstone, and portals with reversible low-risk actions.

## 21.4 Final plan proof

For blueprint work, run one final `plan_blueprint` using the exact name, anchor, and rotation. Completion means no remaining placements, state fixes, unsupported requirements, or conflicts relevant to the promised result. If unsupported details remain, list them and either finish manually or report the build as partial.

# 22. Recovery and repair

## Wrong anchor or rotation detected early

Pause tasks immediately. Record the incorrect and intended bounds. Do not continue hoping later modules will align. In creative, remove only the proven erroneous cells after protected-content inspection; in survival, recover materials legitimately. Replan at the corrected anchor.

## Missing materials mid-build

Let the current task report the shortage, then inventory-check and gather/craft the exact missing items. Do not replace missing palette blocks with arbitrary alternatives unless the user approves a design change. Replan before resuming.

## Conflict appeared after planning

The builder should stop rather than overwrite it. Inspect the coordinate and identify who/what changed it. Resolve explicitly, replan, and continue. Never retry repeatedly against the same conflict.

## Server restart or disconnect

Check restored task state first. Active safe construction resumes as pending with remaining timeout budget and fresh world checks. Preserve pause state. Avoid duplicate `tool_call_id` submissions and avoid starting a competing build at the same anchor.

## Partial structure must be abandoned

Pause/cancel queued work, record the exact completed modules and bounds, secure hazards and openings, and leave a truthful checkpoint. Do not claim cleanup or rollback unless every affected coordinate was actually restored and verified.

# 23. Final completion report

Include:

```text
structure and dimension
approximate or exact bounding box
construction mode (creative fill / survival placement / blueprint)
blueprint name, anchor, and rotation when used
completed major modules
verification performed
remaining unsupported/manual details, if any
```

Do not claim "complete" merely because the task queue is empty. Completion is a verified world state.
