---
name: stronghold_finding
description: Phase 5 of the dragon route. Craft eyes of ender from blaze powder and ender pearls, locate the stronghold with locate_structure instead of throwing eyes, travel to it, find the End portal room, secure the silverfish/lava hazard, fill empty End portal frames using inspect_block + interact_at, verify activation, and prepare for dragon_combat.
---

# Skill: stronghold_finding

This is Phase 5 of the dragon route.

Goal:

```text
Craft enough eyes of ender.
Locate the stronghold.
Reach the End portal room.
Secure the room.
Fill all empty End portal frames.
Activate the End portal.
Verify dragon fight packlist.
Stop at the activated portal until ready for dragon_combat.
```

This skill ends when:

```text
You are standing at an activated End portal.
The purple/starfield End portal surface is visible.
Dragon-fight gear is still intact.
```

Do not enter the End casually. Entering the End starts the final boss phase and is one-way until the dragon dies.

---

# 0. Completion condition

This skill is complete only when all of these are true:

```text
Stronghold found.
End portal room found.
Silverfish/lava hazards controlled enough to stand safely.
All 12 End portal frames are filled or already had eyes.
End portal is active.
Purple/starfield portal surface is visible in the 3x3 center.
get_self_status confirms dragon_combat packlist is still intact.
Owner has been told the portal is active and where it is.
```

Do not mark complete if:

```text
You only located the stronghold but did not find the portal room.
You found the portal room but did not activate the portal.
Some frames are still empty.
You ran out of eyes.
You are low HP or being attacked.
The portal is active but dragon gear/food/arrows/blocks are missing.
You already jumped into the End without loading dragon_combat.
```

---

# 1. Absolute priority rules

## 1.1 Use `locate_structure`, not thrown eyes

Default stronghold navigation:

```text
locate_structure("minecraft:stronghold")
```

Do not throw eyes of ender for navigation when `locate_structure` exists.

Why:

```text
Eyes can break when thrown.
Eyes are needed to fill portal frames.
locate_structure gives the target directly.
Throwing eyes wastes resources and time.
```

Only use thrown-eye navigation if:

```text
locate_structure is unavailable
or the owner explicitly requests vanilla eye-throwing navigation
```

## 1.2 Eyes are for frames only

Use eyes of ender for:

```text
filling End portal frames
```

Do not use them for:

```text
random throwing
decoration
testing
unnecessary interactions
```

Eyes placed in portal frames cannot be removed.

## 1.3 Do not enter the End before preparation

Before entering the End portal:

```text
load_skill(name="dragon_combat")
get_self_status
verify dragon packlist
tell owner portal is active
```

If gear is missing:

```text
do not enter
backfill supplies first
```

## 1.4 Break the silverfish spawner

The End portal room has a silverfish spawner.

Unlike blaze spawners, this one is a liability.

Correct:

```text
auto_mine("minecraft:spawner")
```

Incorrect:

```text
leave the silverfish spawner active
```

## 1.5 Do not break End portal frames

End portal frames are not useful to mine and are required for activation.

Do not:

```text
auto_mine("minecraft:end_portal_frame")
break_block on portal frames
```

Only interact with empty frames using an eye of ender.

## 1.6 Do not block the 3x3 portal interior

You may place blocks around the portal room to make standing safer.

Do not place blocks into:

```text
the 3x3 center inside the End portal frame ring
the End portal frame blocks
the final active portal surface
```

Blocking the activation area can cause confusion and may prevent or obscure activation.

---

# 2. Required support skills

## 2.1 `containers`

Load for crafting eyes:

```text
load_skill(name="containers")
```

Use for:

```text
planning/crafting blaze powder
planning/crafting eyes of ender
manual crafting-grid fallback
checking exact counts
```

Use `plan_crafting` then `craft_items` for the supported vanilla conversions. This preserves real consumption while making the multi-step call resumable. Manual slot movement remains a fallback.

## 2.2 `combat_basics`

Load for stronghold and portal room combat:

```text
load_skill(name="combat_basics")
```

Use for:

```text
silverfish
zombies
skeletons
creepers
spiders
general cave/stronghold threats
HP management
retreat rules
```

## 2.3 `dragon_combat`

Load after the portal is active, before entering:

```text
load_skill(name="dragon_combat")
```

Do not wait until after jumping into the End.

## 2.4 `creative_mode`

If:

```text
get_self_status reports game_mode=creative
```

then:

```text
load_skill(name="creative_mode")
```

If creative shortcuts are allowed:

```text
creative_give item_id="minecraft:ender_eye" count=12
locate_structure("minecraft:stronghold")
fly/teleport as appropriate
```

If the owner asked for legit survival-style play, do not shortcut.

---

# 3. Tools used in this skill

| Tool | Use |
|---|---|
| `get_self_status` | Check dimension, coordinates, HP, inventory, food, eyes, pearls, rods, gear. |
| `locate_structure` | Find the stronghold directly. |
| `move_to` | Travel to stronghold and dig/navigate to it. |
| `scan_blocks` | Find stone bricks, End portal frames, spawner, lava, portal room blocks. |
| `inspect_block` | Check End portal frame state, especially whether it already has an eye. |
| `interact_at` | Insert eyes into frames, open doors/chests if needed. |
| `auto_mine` | Break silverfish spawner, mine stone/blocks if needed. |
| `break_block` | Small corrections only; do not break portal frames. |
| `place_block` | Make safe footing, block lava edges, mark paths. |
| `equip_item` | Equip pickaxe, sword, bow, food, blocks. |
| `hunt` | Kill silverfish and hostile mobs. |
| `eat_item` | Heal directly. |
| `lookup_recipe` | Get blaze powder / eye of ender recipe. |
| `plan_crafting` | Calculate required blaze rods/powder/pearls and missing base materials. |
| `craft_items` | Execute supported recipes with real ingredients and restart recovery. |
| `inspect_gui` | Read crafting grid and inventory slots. |
| `transfer` | Manual crafting fallback and exact container movement. |
| `close_gui` | Close crafting/container GUI. |

---

# 4. Phase prerequisites

Before starting, call:

```text
get_self_status
```

Verify you have or can craft:

```text
>= 12 ender pearls
enough blaze rods or blaze powder for 12 eyes
good weapon
pickaxe
food
blocks
bow/arrows for final phase
armor
```

## 4.1 Required materials for portal activation

Worst case:

```text
12 eyes of ender
```

Recipe:

```text
1 blaze powder + 1 ender pearl = 1 eye of ender
```

Blaze rod conversion:

```text
1 blaze rod = 2 blaze powder
```

Therefore:

```text
12 eyes need 12 blaze powder + 12 ender pearls
12 blaze powder needs 6 blaze rods
7 blaze rods gives margin
```

## 4.2 If missing blaze materials

If:

```text
blaze_rod < 7
and blaze_powder is not enough
```

then do not continue.

Load:

```text
load_skill(name="blaze_rods")
```

## 4.3 If missing pearls

If:

```text
ender_pearl < 12
and ender_eye count is not already enough
```

then do not continue.

Load:

```text
load_skill(name="ender_pearls")
```

## 4.4 If missing final fight supplies

If the portal can be activated but final packlist is weak:

```text
finish activating portal if safe
but do not enter End
backfill dragon supplies first
```

---

# 5. Start-state decision tree

Begin with:

```text
get_self_status
```

## 5.1 Already at active End portal

If already standing at an active End portal:

```text
verify dragon_combat packlist
tell owner portal is active
load_skill(name="dragon_combat")
```

This skill is complete.

## 5.2 In stronghold but portal not active

If in stronghold/portal room:

```text
craft eyes if needed
secure portal room
fill frames
activate portal
```

## 5.3 In Overworld with rods and pearls

If in Overworld and materials are ready:

```text
craft eyes
locate_structure("minecraft:stronghold")
move_to stronghold
find portal room
activate portal
```

## 5.4 In Nether after phase 4

Strongholds are in the Overworld.

If in Nether:

```text
return to Nether portal
enter Overworld
get_self_status
then continue stronghold_finding
```

Do not call stronghold locate in the Nether unless the tool explicitly supports cross-dimension results. Default: locate stronghold from the Overworld.

## 5.5 Missing materials

If missing rods or pearls:

```text
go back to phase 3 or 4
```

Do not start stronghold travel without enough eyes.

---

# 6. Crafting eyes of ender

Load:

```text
load_skill(name="containers")
```

Then call `plan_crafting` and `craft_items` for the required additional outputs. Use GUI crafting only if automatic execution reports an unsupported recipe or needs diagnosis.

Both conversions are small-grid crafting:

```text
blaze rod -> blaze powder
blaze powder + ender pearl -> eye of ender
```

A crafting table is not required unless your tool environment requires it; 2x2 inventory grid is enough.

## 6.1 Count existing resources first

Call:

```text
get_self_status
```

Record:

```text
ender_eye count
ender_pearl count
blaze_powder count
blaze_rod count
```

Target:

```text
ender_eye >= 12
```

If already:

```text
ender_eye >= 12
```

skip crafting.

## 6.2 Craft only what is needed

Compute:

```text
eyesNeeded = 12 - currentEnderEyeCount
```

Then:

```text
powderNeeded = eyesNeeded - currentBlazePowderCount
```

If powder is missing, convert rods.

Example:

```text
currentEnderEye = 0
currentBlazePowder = 0
eyesNeeded = 12
powderNeeded = 12
rodsToConvert = ceil(12 / 2) = 6
```

If you have 7 rods, you usually only need to convert 6 rods.

Keep spare rod/powder as margin if possible.

## 6.3 Craft blaze powder

Use:

```text
lookup_recipe item_id="minecraft:blaze_powder"
```

Then:

```text
close_gui if another GUI is open
inspect_gui
```

Find:

```text
blaze_rod slot
2x2 crafting grid cells
result slot
```

Place rods into one grid cell.

For 6 rods:

```text
transfer moves=[{from:<blaze_rod_slot>, to:<grid_cell>, count:6}]
transfer moves=[{from:<result_slot>}]
```

Expected:

```text
6 blaze rods -> 12 blaze powder
```

Then verify:

```text
get_self_status
```

## 6.4 Craft eyes of ender

Use:

```text
lookup_recipe item_id="minecraft:ender_eye"
```

Recipe:

```text
1 blaze powder
1 ender pearl
```

Usually shapeless.

Open/check inventory grid:

```text
inspect_gui
```

Find:

```text
blaze_powder slot
ender_pearl slot
two crafting grid cells
result slot
```

For 12 eyes:

```text
transfer moves=[
  {from:<blaze_powder_slot>, to:<grid_cell_1>, count:12},
  {from:<ender_pearl_slot>, to:<grid_cell_2>, count:12}
]
transfer moves=[{from:<result_slot>}]
```

Expected:

```text
12 blaze powder + 12 ender pearls -> 12 eyes of ender
```

Then:

```text
get_self_status
```

Verify:

```text
minecraft:ender_eye >= 12
```

## 6.5 If crafting result is empty

Possible causes:

```text
wrong item slot
wrong recipe
extra item in grid
not enough ingredients
wrong grid cells
GUI not showing crafting grid
```

Fix:

```text
inspect_gui
clear wrong grid items
lookup_recipe again
place only required ingredients
take result
```

## 6.6 Do not throw crafted eyes

After crafting:

```text
do not use interact_at in air to throw eyes
do not right-click the sky with eyes
```

Eyes are now reserved for portal frames.

---

# 7. Locate the stronghold

## 7.1 Must be in Overworld

Default stronghold locate should be done in:

```text
Overworld
```

If in Nether or End:

```text
return to Overworld first
```

## 7.2 Use locate_structure

Call:

```text
locate_structure("minecraft:stronghold")
```

Record:

```text
strongholdX
strongholdZ
direction
distance
```

Y may be approximate or absent.

Strongholds are often:

```text
1000-2500 blocks away
sometimes farther
underground
maze-like
```

## 7.3 Do not throw eyes

Do not do:

```text
interact_at with ender_eye into air
throw eye to triangulate
```

Use the locate result.

## 7.4 Travel preparation

Before long travel:

```text
get_self_status
```

Verify:

```text
HP safe
food available
pickaxe available
sword available
bow/arrows available
blocks available
ender_eye >= 12
armor equipped
```

Equip pickaxe for navigation:

```text
equip_item "minecraft:diamond_pickaxe"
```

Reason:

```text
move_to navigation digs with held tool
stronghold travel may require digging stone
```

## 7.5 Travel to stronghold area

Use surface-ish travel first:

```text
move_to x=<strongholdX> y=60 z=<strongholdZ>
```

If terrain is mountainous or oceanic, use a safe Y appropriate to travel.

Then descend/dig toward stronghold:

```text
move_to x=<strongholdX> y=30 z=<strongholdZ>
```

Strongholds commonly occupy underground Y levels.

If stronghold is deeper or shallower:

```text
scan/move around and adjust Y
```

Do not dig straight down manually.

---

# 8. Entering the stronghold

## 8.1 Stronghold block signs

Strongholds contain:

```text
minecraft:stone_bricks
minecraft:mossy_stone_bricks
minecraft:cracked_stone_bricks
minecraft:infested_stone_bricks
minecraft:iron_bars
minecraft:oak_planks
minecraft:bookshelves
minecraft:end_portal_frame
```

If you hit stone bricks underground near locate coordinates, you are likely inside.

## 8.2 Confirm stronghold

Use:

```text
scan_blocks("minecraft:stone_bricks", radius=64)
scan_blocks("minecraft:mossy_stone_bricks", radius=64)
scan_blocks("minecraft:cracked_stone_bricks", radius=64)
```

If found:

```text
move_to nearest safe stronghold corridor
```

## 8.3 Avoid excessive stronghold brick mining

Some stronghold blocks can be infested.

Breaking infested blocks can release silverfish.

Use controlled movement.

If silverfish appear:

```text
load_skill(name="combat_basics")
hunt("minecraft:silverfish")
get_self_status
```

---

# 9. Finding the End portal room

## 9.1 Scan for End portal frames

The portal room contains:

```text
minecraft:end_portal_frame
```

Use:

```text
scan_blocks("minecraft:end_portal_frame", radius=128)
```

If found:

```text
move_to near the frame cluster
```

There should be 12 frame blocks in a ring.

## 9.2 If scan does not find frames

Strongholds are maze-like.

Search systematically:

```text
move through corridors
scan_blocks("minecraft:end_portal_frame", radius=64)
repeat at intersections
```

Look for:

```text
stone brick corridors
doors
staircases
iron bars
libraries
fountains
portal room stairs
```

## 9.3 Systematic exploration rule

Do not wander randomly forever.

At each intersection:

```text
choose one corridor
move_to a safe point down that corridor
scan for end_portal_frame
if none, return/continue to next corridor
```

Use simple markers if needed:

```text
place_block cobblestone at explored turns
```

## 9.4 Keep orientation

Record useful coordinates:

```text
stronghold entry point
portal room if found
safe staging room
```

If lost:

```text
move_to stronghold locate coordinates
or retrace markers
```

## 9.5 Avoid hazards while searching

Strongholds can contain:

```text
mobs
ravines cutting through halls
lava
silverfish
dark rooms
water
jail cells
libraries with cobwebs
```

If combat starts:

```text
equip sword/bow
clear threat
get_self_status
eat if needed
re-equip pickaxe
continue
```

---

# 10. Portal room layout

The End portal room typically contains:

```text
a staircase entrance
silverfish spawner on/near the stairs
a 3x3 lava pool
12 End portal frame blocks around the center
some frames may already contain eyes
stone brick walls
```

Dangers:

```text
silverfish spawner
silverfish mobs
lava pool
hostile mobs entering from corridors
falling into lava
accidentally jumping into active portal before ready
```

---

# 11. Securing the portal room

## 11.1 Stop and check status

Before securing:

```text
get_self_status
```

If HP is low:

```text
eat_item
get_self_status
```

Equip appropriate tool:

```text
equip_item "minecraft:diamond_pickaxe"
```

## 11.2 Break silverfish spawner

Use:

```text
auto_mine("minecraft:spawner")
```

or if exact position is known:

```text
break_block x=<spawnerX> y=<spawnerY> z=<spawnerZ>
```

The silverfish spawner should be destroyed immediately.

Do not preserve it.

## 11.3 Kill existing silverfish

If silverfish are present:

```text
equip_item "minecraft:diamond_sword"
hunt("minecraft:silverfish")
get_self_status
```

If many silverfish spawn:

```text
move_to safer room/corridor
hunt in small batches
eat if needed
```

Then return and secure room.

## 11.4 Make safe footing

Use `place_block` to block dangerous standing edges.

Good uses:

```text
place cobblestone around lava edges where you stand
block small holes
make a safe approach path
block side openings if mobs enter
```

Do not place blocks into:

```text
the 3x3 portal interior
the frame blocks
the active portal surface
```

## 11.5 Check lava

If lava is exposed where you need to stand:

```text
place_block cobblestone on adjacent safe footing positions
```

Do not fall into the portal room lava.

Do not mine blocks holding back lava unless necessary and safe.

---

# 12. Inspecting End portal frames

The portal has 12 frame blocks.

Use:

```text
scan_blocks("minecraft:end_portal_frame", radius=32)
```

The result should list up to 12 positions.

If fewer than 12 are listed:

```text
move closer
increase radius
scan again
```

## 12.1 Inspect each frame

For every frame position:

```text
inspect_block x=<frameX> y=<frameY> z=<frameZ>
```

Look for a property such as:

```text
eye=true
has_eye=true
```

The exact name may vary by tool.

Interpretation:

```text
eye=true / has_eye=true  -> already filled
eye=false / has_eye=false -> empty, needs ender_eye
```

## 12.2 Count empty frames

Create counts:

```text
filledFrames = number of frames with eye=true
emptyFrames = 12 - filledFrames
```

Check inventory:

```text
get_self_status
```

Need:

```text
ender_eye count >= emptyFrames
```

If not enough:

```text
do not start filling randomly
go craft/obtain more eyes
```

## 12.3 Pre-filled frames

End portal frames generate with a chance to be pre-filled.

Do not overwrite or interact repeatedly with pre-filled frames.

Only interact with empty frames.

---

# 13. Filling the frames

## 13.1 Equip/hold eye if needed

If tool requires item in hand:

```text
equip_item "minecraft:ender_eye"
```

Then interact with each empty frame.

## 13.2 Use interact_at on each empty frame

For each empty frame:

```text
interact_at button=right x=<frameX> y=<frameY> z=<frameZ> item_id="minecraft:ender_eye"
```

Important:

```text
target the frame block itself
not the 3x3 center
not the lava
not air
```

## 13.3 Eyes cannot be retrieved

Once placed:

```text
eye is consumed
cannot be taken back
```

Therefore:

```text
inspect first
interact only empty frames
```

## 13.4 Re-inspect if uncertain

After a few placements, or if an interaction fails:

```text
inspect_block x=<frameX> y=<frameY> z=<frameZ>
get_self_status
```

Confirm:

```text
frame now has eye
ender_eye count decreased appropriately
```

## 13.5 Activation

When the 12th frame is filled:

```text
the 3x3 center activates
purple/starfield End portal surface appears
lava is covered/replaced visually by portal surface
```

After final eye placement:

```text
scan_blocks("minecraft:end_portal", radius=16)
```

or inspect the 3x3 center if possible.

Expected:

```text
minecraft:end_portal
```

---

# 14. Frame filling algorithm

Use this exact procedure.

```text
scan_blocks("minecraft:end_portal_frame", radius=32)

for each frame position:
    inspect_block frame
    record whether eye=true/has_eye=true

emptyFrames = all frames without eyes

get_self_status
if ender_eye count < emptyFrames:
    craft/obtain more eyes before continuing

for each frame in emptyFrames:
    interact_at button=right x=frameX y=frameY z=frameZ item_id="minecraft:ender_eye"
    if interaction uncertain:
        inspect_block frame

after all:
    scan_blocks("minecraft:end_portal", radius=16)
    inspect center if needed
```

Complete only if portal blocks exist.

---

# 15. Before entering the End

Activation is not the same as entering.

Before entering, stop and verify.

## 15.1 Load dragon skill now

Immediately after activation:

```text
load_skill(name="dragon_combat")
```

Also keep:

```text
combat_basics
```

available for final fight rules.

## 15.2 Record portal room coordinates

Call:

```text
get_self_status
```

Record:

```text
strongholdPortalX
strongholdPortalY
strongholdPortalZ
dimension = Overworld
```

Report to owner:

```text
The End portal is active at x=..., y=..., z=...
```

## 15.3 Verify dragon_combat packlist

Minimum:

```text
diamond_sword or better
bow
32+ arrows
128+ cobblestone/solid blocks
32+ cooked food
armor equipped
pickaxe available
golden_apple if available
HP >= 18 preferred
```

Preferred:

```text
diamond_sword
bow
64+ arrows
128-192 blocks
32+ cooked_beef/cooked_porkchop
golden_apple
iron/diamond armor
pickaxe
```

Use:

```text
get_self_status
```

If missing supplies:

```text
do not enter End
backfill supplies
return to portal room
```

## 15.4 Owner warning

Tell the owner:

```text
The End portal is active.
Entering starts the dragon phase.
Leaving the End normally requires killing the dragon.
Set respawn nearby if desired.
Come watch if desired.
```

Do not jump in until the next phase is ready.

---

# 16. Full phase algorithm

Use this for normal Phase 5.

## 16.1 Start and verify

```text
get_self_status

if ender_eye >= 12:
    skip crafting
else:
    verify blaze_rod/blaze_powder and ender_pearl
    load containers
    craft blaze_powder if needed
    craft ender_eye until count >= 12
```

## 16.2 Locate

```text
if dimension != Overworld:
    return to Overworld

locate_structure("minecraft:stronghold")
record coordinates
equip_item diamond_pickaxe
move_to x=<strongholdX> y=60 z=<strongholdZ>
move_to x=<strongholdX> y=30 z=<strongholdZ>
```

## 16.3 Find portal room

```text
scan_blocks("minecraft:stone_bricks", radius=64)
scan_blocks("minecraft:end_portal_frame", radius=128)

if end_portal_frame found:
    move_to near frame cluster
else:
    explore stronghold corridors
    repeat scans
```

## 16.4 Secure portal room

```text
get_self_status
if HP low:
    eat_item

auto_mine("minecraft:spawner")

if silverfish nearby:
    equip_item sword
    hunt("minecraft:silverfish")
    get_self_status

place_block cobblestone only where needed for safe footing
```

## 16.5 Activate portal

```text
scan_blocks("minecraft:end_portal_frame", radius=32)
inspect_block each frame
count empty frames
get_self_status

if ender_eye count < emptyFrames:
    craft/obtain more eyes

for each empty frame:
    interact_at button=right x=frameX y=frameY z=frameZ item_id="minecraft:ender_eye"

verify end_portal blocks exist
```

## 16.6 Finish

```text
get_self_status
verify dragon packlist
report active portal coordinates
load_skill(name="dragon_combat")
mark phase 5 completed
```

---

# 17. Common problems and fixes

## 17.1 Not enough eyes

Problem:

```text
ender_eye count < empty portal frames
```

Fix:

```text
craft more eyes if you have pearls + powder
otherwise return to ender_pearls or blaze_rods phase
```

## 17.2 Crafted too much blaze powder

Usually not fatal.

Keep extra:

```text
blaze_powder
blaze_rod
```

Do not throw away.

## 17.3 Accidentally threw an eye

Problem:

```text
eye may break
resource wasted
```

Fix:

```text
collect it if it dropped
get_self_status
verify remaining eyes
craft/obtain more if needed
stop throwing eyes
```

## 17.4 locate_structure called in wrong dimension

Problem:

```text
stronghold is Overworld structure
```

Fix:

```text
return to Overworld
call locate_structure("minecraft:stronghold") again
```

## 17.5 Stronghold found but portal room not found

Fix:

```text
scan_blocks("minecraft:end_portal_frame", radius=128)
move through corridors systematically
scan at intersections
look for stone brick stairs and iron bars
use markers
```

Do not wander endlessly without scanning.

## 17.6 Silverfish keep spawning

Cause:

```text
silverfish spawner still exists
or infested blocks are being broken
```

Fix:

```text
auto_mine("minecraft:spawner")
stop breaking random stronghold bricks
hunt silverfish
eat if needed
```

## 17.7 Fell into portal room lava

Action:

```text
move_to safe block immediately if possible
eat_item
get_self_status
place_block safer footing after recovery
```

Prevention:

```text
secure footing before filling frames
avoid standing on lava edge
```

## 17.8 Frame interaction does nothing

Possible causes:

```text
frame already has eye
wrong item
no ender_eye in inventory
clicked wrong block
too far away
GUI/action blocked by mob attack
```

Fix:

```text
inspect_block frame
get_self_status
equip_item ender_eye if needed
retry interact_at on empty frame
```

## 17.9 Portal did not activate after filling

Possible causes:

```text
one frame still empty
one interaction failed
not all 12 frames detected
mistook another block for frame
block state not updated
3x3 center obstructed
```

Fix:

```text
inspect all 12 frames again
find any eye=false/has_eye=false
fill remaining frame
clear non-frame obstruction from center if safe
scan_blocks("minecraft:end_portal", radius=16)
```

## 17.10 Entered End accidentally

If you fell or moved into active portal before loading dragon_combat:

```text
load_skill(name="dragon_combat") immediately
get_self_status
follow dragon_combat arrival procedure
```

Do not panic, but this is why you should not stand inside the active portal.

## 17.11 Dragon packlist incomplete

If portal is active but packlist is missing:

```text
do not enter
leave portal room safely
restock food/arrows/blocks
return to portal room
verify again
```

---

# 18. Safety rules inside stronghold

## 18.1 Keep pickaxe equipped for navigation

After combat:

```text
equip_item diamond_pickaxe
```

Stronghold navigation often involves digging.

## 18.2 Do not mine randomly

Avoid breaking:

```text
infested stone bricks
blocks under lava
portal frames
floor under yourself
```

## 18.3 Watch for mobs

Stronghold/cave mobs may include:

```text
silverfish
zombies
skeletons
creepers
spiders
```

If fighting:

```text
load combat_basics
use hunt/shoot as appropriate
eat if HP low
```

## 18.4 Keep food and HP high

Before entering the End, HP should be:

```text
>= 18 preferred
```

If not:

```text
eat_item
get_self_status
```

## 18.5 Preserve eyes until frames

Do not use eyes except on frames.

## 18.6 Do not leave the GUI open

If crafting before travel:

```text
close_gui
```

before moving.

---

# 19. Optional stronghold loot

Strongholds may contain:

```text
libraries
chests
books
paper
apples
iron
ender pearls sometimes
other loot
```

Do not detour heavily unless:

```text
you need supplies
owner wants loot
dragon packlist is missing
```

Priority remains:

```text
activate portal
prepare for dragon
```

---

# 20. Narrow goal handling

If owner asks:

```text
"find the stronghold"
```

Completion may be:

```text
standing inside/at the stronghold
coordinates reported
```

If owner asks:

```text
"find the End portal"
```

Completion is:

```text
portal room found and coordinates reported
```

If owner asks:

```text
"activate the End portal"
```

Completion is:

```text
portal active, but do not enter unless asked
```

If owner asks full dragon route:

```text
complete this phase, then load dragon_combat
```

---

# 21. Final response after this skill

When the portal is active, report:

```text
Phase 5 complete — the End portal is active.
Portal room coordinates: x=..., y=..., z=...
Dragon fight packlist verified.
Ready to load dragon_combat and enter when you are ready.
```

If something blocks completion, report the exact blocker:

```text
Cannot activate portal yet: need 3 more eyes of ender.
```

```text
Found the stronghold, but portal room not located yet.
```

```text
Portal is active, but dragon packlist is missing arrows/food/blocks, so I should not enter yet.
```

---

# 22. What to load next

After activation and packlist verification:

```text
load_skill(name="combat_basics")
load_skill(name="dragon_combat")
```

This is the final phase.

---

# 23. Highest-priority reminders

Always remember:

```text
1. Craft up to 12 eyes of ender.
2. Use locate_structure, not thrown eyes.
3. Stronghold locate belongs in the Overworld.
4. Hold pickaxe for navigation/digging.
5. Scan for end_portal_frame to find portal room.
6. Break the silverfish spawner immediately.
7. Kill silverfish if they spawn.
8. Make safe footing, but do not block the 3x3 portal center.
9. Inspect every frame before placing eyes.
10. Fill only empty frames.
11. Eyes cannot be removed once placed.
12. Verify the portal surface appears.
13. Do not enter End before loading dragon_combat.
14. Verify dragon packlist before jumping in.
15. Report portal coordinates to owner.
```
