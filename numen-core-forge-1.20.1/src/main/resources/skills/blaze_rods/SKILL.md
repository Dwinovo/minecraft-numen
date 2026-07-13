---
name: blaze_rods
description: Locate a Nether fortress, safely farm blazes at/near a blaze spawner, and collect at least 7 blaze rods for Ender Eye crafting.
---

# Skill: blaze_rods

This skill is Phase 3 of the dragon route.

Goal:

```text
Find a Nether fortress.
Find blazes, preferably a blaze spawner.
Kill enough blazes.
Collect at least 7 blaze rods.
Return to the Nether portal or another safe staging point.
```

Why 7 rods:

```text
1 blaze rod -> 2 blaze powder
12 eyes of ender need 12 blaze powder
12 powder = 6 rods
7 rods = 14 powder, giving +1 rod margin
```

Because `locate_structure` can locate the stronghold later, do not waste eyes of ender by throwing them during this route unless another skill explicitly says so.

---

# 0. Completion condition

This skill is complete only when all of these are true:

```text
get_self_status shows inventory contains >= 7 minecraft:blaze_rod
Player is alive
Player is not in immediate danger
Player is back at the Nether portal, or at another safe, navigable Nether location
Ready to start phase 4: ender_pearls
```

Do not mark this skill complete if:

```text
You have fewer than 7 blaze rods.
You are still fighting blazes.
You are low HP and surrounded.
You are lost and cannot return.
You died and have not recovered rods.
You only saw a fortress but did not collect rods.
```

---

# 1. Absolute rules

## 1.1 Call `locate_structure` in the Nether

To find a fortress, use:

```text
locate_structure("minecraft:fortress")
```

Important:

```text
This must be called while IN the Nether.
Do not wander randomly looking for a fortress.
Do not confuse a bastion with a fortress.
Do not spend eyes of ender in this phase.
```

## 1.2 Track the portal coordinates before leaving it

Before traveling away from the Nether portal, call:

```text
get_self_status
```

Record:

```text
portalX
portalY
portalZ
dimension
```

You need these to return safely.

If the status shows you are not in the Nether, this skill cannot begin yet.

## 1.3 Blazes are dangerous; use range by default

Default blaze combat method:

```text
equip_item(bow)
shoot(blaze, small batch)
collect_items
check status
repeat
```

Melee is allowed only if ranged combat is unavailable or inefficient.

Melee fallback is acceptable when:

```text
You have a strong sword, preferably iron or diamond.
You have armor.
You have plenty of cooked food.
You are not standing near lava or bridge edges.
You fight one or two blazes at a time.
```

## 1.4 Do not mine the blaze spawner

Never break or mine the spawner before collecting enough rods.

The spawner is your renewable blaze source.

Incorrect:

```text
break_block spawner
```

Correct:

```text
Keep the spawner.
Control the area.
Kill spawned blazes.
Collect rods.
```

## 1.5 Health safety overrides farming

If health is low, retreat immediately.

Use this rule:

```text
Max HP is usually 20.
If HP <= 8, stop fighting and retreat.
If burning and HP <= 12, retreat and eat.
If hunger/food is low, stop farming and stabilize.
```

Do not keep shooting while dying.

---

# 2. Required and recommended equipment

## 2.1 Minimum equipment

Before committing to a fortress fight, prefer having:

```text
weapon: iron_sword or diamond_sword
ranged: bow
ammo: at least 32 arrows, preferably 48+
food: at least 12 cooked food
blocks: at least 64 solid blocks such as cobblestone/netherrack
pickaxe: iron_pickaxe or better
armor: any armor; iron armor preferred
```

## 2.2 Strong recommended equipment

Better setup:

```text
diamond_sword
bow with many arrows
shield if available
iron armor or better
cooked_beef / cooked_porkchop / bread
64-128 blocks
pickaxe
flint_and_steel if portal relighting is needed
```

## 2.3 Optional bonuses

Useful but not required:

```text
fire_resistance_potion
golden_apple
enchanted_bow
Power bow
Infinity bow
Looting sword
extra food
extra blocks
```

Looting helps because blaze rods are mob drops.

## 2.4 If equipment is poor

If you have:

```text
no food
no weapon
no bow and no armor
very low HP
```

Then do not start farming.

First retreat to a safe place and prepare.

---

# 3. Tool reference

Use these tools as available.

| Tool | Use |
|---|---|
| `get_self_status` | Check dimension, coordinates, HP, hunger, inventory, rod count. |
| `locate_structure` | Find exact fortress direction and distance. |
| `move_to` | Navigate to portal, fortress, safe spots, or spawner area. |
| `scan_blocks` | Find fortress blocks or spawners nearby. |
| `inspect_block` | Confirm suspicious blocks, ground, lava, spawner area. |
| `equip_item` | Equip bow, sword, food, blocks, shield, etc. |
| `shoot` | Safely attack blazes from range. |
| `hunt` | Melee fallback for blazes or wither skeletons. |
| `collect_items` | Pick up blaze rods and other drops. |
| `place_block` | Block line of sight, make barriers, bridge gaps, protect from lava. |
| `break_block` | Fix small mistakes, open single-block paths, never break spawner. |

In survival, barriers must use real inventory placement with `place_block`; `fill` is creative-only. If the companion is genuinely in creative mode and shortcuts are allowed, load `building` before using `fill` and inspect the fortress/spawner area so the spawner is not overwritten.

---

# 4. Phase overview

The complete phase is:

```text
1. Confirm you are in the Nether.
2. Record portal coordinates.
3. Locate nearest Nether fortress.
4. Travel safely toward fortress coordinates.
5. Scan for nether brick blocks to find the actual fortress structure.
6. Enter fortress carefully.
7. Find blaze spawner or natural blaze spawning area.
8. Set up safe fighting position.
9. Kill blazes in small batches.
10. Collect rods after every batch.
11. Repeat until >= 7 rods.
12. Retreat and return to portal/safe base.
13. Mark phase completed.
14. Load ender_pearls.
```

---

# 5. Starting checklist

Call:

```text
get_self_status
```

Confirm:

```text
dimension == Nether
HP is safe
food exists
weapon exists
coordinates are known
```

Record the portal or staging point:

```text
portalX = current x
portalY = current y
portalZ = current z
```

If you just came through the portal, the current coordinates are the portal coordinates.

If you moved away from the portal already, navigate back or use known portal coordinates before continuing.

---

# 6. Finding the fortress

## 6.1 Use locate_structure

Call:

```text
locate_structure("minecraft:fortress")
```

The result should give something like:

```text
fortressX
fortressZ
direction
distance
```

Important notes:

```text
The returned Y may be approximate or missing.
Fortresses span many Y levels.
Travel around y ~= 60-75 when possible.
Do not dig blindly straight through lava.
```

## 6.2 Move toward the fortress safely

Use:

```text
move_to x=fortressX z=fortressZ
```

If the tool requires Y, use a reasonable Nether travel height:

```text
y ~= 70
```

Avoid:

```text
lava seas
thin bridges
ghasts
large drops
piglin brutes
bastions
magma cube swarms
```

If direct path is unsafe, use intermediate waypoints.

Example:

```text
move_to a safe ledge near fortress direction
then move_to another safe point
then move_to fortress area
```

Do not sprint blindly across Nether terrain.

## 6.3 Scan for fortress blocks

Once near the locate result, call:

```text
scan_blocks("minecraft:nether_bricks", radius=128)
```

Also useful:

```text
scan_blocks("minecraft:nether_brick_fence", radius=128)
scan_blocks("minecraft:nether_brick_stairs", radius=128)
```

A real fortress has many:

```text
nether_bricks
nether_brick_fence
nether_brick_stairs
fortress bridges
corridors
lava wells
nether wart rooms
blaze spawner balconies
```

## 6.4 Do not confuse fortress and bastion

A bastion is not a fortress.

Bastions usually contain:

```text
blackstone
polished_blackstone
gilded_blackstone
gold blocks
piglins
piglin brutes
```

Fortresses usually contain:

```text
nether_bricks
nether_brick_fence
blazes
wither skeletons
nether wart
```

If you see blackstone and gold, treat it as a bastion and avoid it.

If unsure, scan blocks:

```text
scan_blocks("minecraft:nether_bricks", radius=64)
scan_blocks("minecraft:blackstone", radius=64)
```

If mostly blackstone/gold:

```text
This is probably a bastion.
Do not enter unless another skill says so.
Continue toward fortress result.
```

---

# 7. Fortress entry rules

## 7.1 Enter from a safe side

Good fortress entry:

```text
solid bridge/corridor
wide platform
not over lava
not surrounded by mobs
has retreat path
```

Bad fortress entry:

```text
thin bridge over lava
edge with no railing
directly under ghast fire
inside a wither skeleton group
jumping blindly onto lower bridge
```

## 7.2 Clear immediate threats

Before searching deeply:

```text
Kill nearby wither skeletons one at a time.
Shoot or avoid ghasts.
Avoid magma cubes near edges.
Avoid piglins unless provoked.
```

## 7.3 Create a safe retreat point

Near fortress entrance, identify or make a safe point:

```text
small corridor
blocked side path
area with walls
not near lava
not at bridge edge
```

Use blocks to block dangerous sight lines if needed.

Example:

```text
place_block solid block to cover open side
place_block solid block to stop skeleton path
place_block solid block to make a small corner
```

This safe point is where you retreat when:

```text
HP <= 8
too many blazes spawn
wither effect is active
food is needed
inventory check is needed
```

---

# 8. Finding a blaze spawner

## 8.1 Preferred method: scan for spawners

Inside or near the fortress, call:

```text
scan_blocks("minecraft:spawner", radius=64)
```

If not found, move through fortress corridors and bridges, then scan again.

Good scan sequence:

```text
scan_blocks("minecraft:spawner", radius=64)
move_to nearby fortress corridor
scan_blocks("minecraft:spawner", radius=64)
move_to next corridor/bridge
scan_blocks("minecraft:spawner", radius=64)
```

## 8.2 What a blaze spawner room looks like

Blaze spawners in fortresses are usually in open fenced balcony-like rooms.

Signs:

```text
nether brick platform
nether brick fences
spawner block in center
blazes nearby
open air around platform
often exposed to lava below
dangerous bridge edges
```

## 8.3 Validate the spawner

If you find a spawner, confirm:

```text
It is inside/attached to a Nether fortress.
Blazes appear nearby.
The surrounding blocks are nether brick/fence.
```

If tools show spawner type, check that it is a blaze spawner.

If not, assume a fortress spawner in a fortress blaze platform is a blaze spawner.

## 8.4 If no spawner is found

Blazes can also spawn naturally in fortresses.

Fallback:

```text
Patrol fortress bridges and corridors.
Kill naturally spawned blazes.
Collect rods.
Keep scanning for spawners.
```

Spawner farming is preferred, but natural blaze kills still count.

---

# 9. Blaze facts

## 9.1 Blaze stats and behavior

Blazes:

```text
Have 20 HP.
Fly/hover.
Are immune to fire.
Shoot volleys of 3 fireballs.
Set player on fire.
Attack at line of sight.
Can float over lava and edges.
Drop 0-1 blaze rod without Looting.
Average drop rate is about 0.5 rod per kill.
```

Expected kills:

```text
7 rods usually requires about 14 blaze kills.
Bad luck can require more.
Looting can reduce required kills.
```

## 9.2 Fireball danger

A blaze volley can:

```text
damage you
set you on fire
knock you off ledges
chain with other blazes
kill you after the fight due to burning
```

Water does not work in the Nether.

Do not rely on water buckets.

## 9.3 Drop safety

Blaze rods can be lost if they fall into dangerous places.

Treat drops as fragile:

```text
Collect quickly.
Avoid killing blazes directly above lava.
Avoid killing them over void-like fortress edges.
Avoid fighting on narrow bridges.
```

After every small batch:

```text
collect_items
get_self_status
```

---

# 10. Setting up a safe farming area

## 10.1 Do not stand directly on the spawner

Spawner mechanics reward being nearby, but standing too close can be dangerous.

Good position:

```text
close enough to activate spawner
far enough to retreat
has a wall or corner
not on an edge
not above lava
line of sight can be controlled
```

## 10.2 Control line of sight

Blazes need line of sight to shoot effectively.

Use blocks to create:

```text
a corner
a wall
a peek window
a fallback corridor
a barrier between you and open lava
```

Simple safe-fight pattern:

```text
Stand behind a wall/corner.
Peek out.
Shoot 1-3 blazes.
Duck back.
Eat if needed.
Collect rods.
Repeat.
```

## 10.3 Wall off extra openings

If too many blazes shoot from multiple angles, place blocks to reduce exposure.

Example use cases:

```text
block one side of a bridge
seal open fence gap
make a 2-block-high safety wall
create a corner near the spawner
```

Never trap yourself with no exit.

Always keep:

```text
one retreat path
enough headroom
no lava behind you
```

## 10.4 Do not completely block all spawn spaces

If you place too many blocks around the spawner, blazes may stop spawning or spawn awkwardly.

Goal:

```text
reduce line of sight
not remove all spawn room
```

Good:

```text
partial wall
corner
peek hole
safe retreat corridor
```

Bad:

```text
fill entire spawner room solid
break spawner
stand in open surrounded by blazes
```

---

# 11. Combat decision tree

Before each fight, call or mentally check:

```text
get_self_status
```

## 11.1 If bow and arrows are available

Use ranged combat.

Condition:

```text
bow exists
arrows >= 10
HP safe
```

Action:

```text
equip_item("minecraft:bow")
shoot("minecraft:blaze", small_count)
collect_items
get_self_status
```

Prefer small batches:

```text
shoot 1-3 blazes
then collect
then check HP/inventory
```

Do not shoot endlessly while rods are on the ground.

## 11.2 If arrows are plentiful

If arrows >= 32:

```text
Use bow as primary method.
Stay behind cover.
Kill blazes at range.
Avoid melee unless a blaze is very close.
```

## 11.3 If arrows are low

If arrows < 10:

```text
Use arrows only for dangerous/flying blazes.
Switch to sword when safe.
Fight near cover.
Avoid engaging multiple blazes.
```

## 11.4 If no arrows but good sword/armor/food

Melee fallback is allowed.

Condition:

```text
iron_sword or better
armor present
food plenty
HP safe
fighting area not near lava/edge
```

Action:

```text
equip_item(sword)
hunt("minecraft:blaze")
collect_items
retreat/eat after each kill or pair of kills
```

Use melee only in controlled terrain.

## 11.5 If no arrows and poor melee gear

Do not farm blazes aggressively.

Instead:

```text
retreat
prepare
find safer position
avoid spawner until ready
```

---

# 12. Ranged farming loop

Use this as the default loop.

## 12.1 Setup

```text
get_self_status
equip_item("minecraft:bow")
move_to safe firing position near spawner
```

Check:

```text
HP > 8
food available
arrows available
not standing near lava
not standing on bridge edge
retreat path known
```

## 12.2 Activate spawner

Stay close enough that blazes spawn.

If no blazes spawn:

```text
move slightly closer to spawner
wait briefly
scan for blazes
do not stand on top of spawner
```

## 12.3 Kill small batch

Use:

```text
shoot("minecraft:blaze", 1)
```

or:

```text
shoot("minecraft:blaze", 2)
```

or:

```text
shoot("minecraft:blaze", 3)
```

Small batches are safer than trying to clear ten at once.

## 12.4 Collect drops immediately

After each small batch:

```text
collect_items
```

Then:

```text
get_self_status
```

Check:

```text
blaze_rod count
HP
food
arrows
fire/burning status if visible
nearby mobs
```

## 12.5 Retreat condition

Retreat if:

```text
HP <= 8
burning and HP <= 12
hunger is low
wither effect is active
3+ blazes are shooting at once
wither skeletons enter the fight
you are knocked near an edge
arrows are nearly gone
inventory says >=7 blaze rods
```

Retreat action:

```text
move_to safe retreat point
eat
wait until fire stops if possible
kill pursuing mobs one at a time
return only when stable
```

## 12.6 Repeat

Repeat:

```text
spawn
shoot 1-3 blazes
collect_items
get_self_status
retreat if needed
```

Stop only when:

```text
blaze_rod >= 7
```

---

# 13. Melee fallback loop

Use this only when bow/arrows are insufficient or blazes are close and safe to hit.

## 13.1 Requirements

Before melee:

```text
HP > 12 preferred
food available
sword equipped
not on a bridge edge
not over lava
not surrounded
```

Best sword:

```text
diamond_sword
iron_sword
stone_sword only if desperate
```

## 13.2 Fight position

Good melee location:

```text
corridor
corner
against a wall
inside fortress hallway
away from open balcony edge
```

Bad melee location:

```text
open spawner balcony over lava
thin nether brick bridge
near multiple wither skeletons
near magma cubes
```

## 13.3 Melee action

Use:

```text
equip_item("minecraft:diamond_sword")
hunt("minecraft:blaze")
```

If no diamond sword:

```text
equip_item("minecraft:iron_sword")
hunt("minecraft:blaze")
```

After every kill:

```text
collect_items
get_self_status
```

## 13.4 Do not chase flying blazes over danger

If a blaze floats away over lava or a fortress edge:

```text
do not chase
switch to bow if possible
wait for it to approach
or ignore it
```

Your life is more important than one blaze.

---

# 14. Handling other fortress mobs

## 14.1 Wither skeletons

Wither skeletons are very dangerous.

They:

```text
carry stone swords
are tall
apply Wither effect
deal damage over time
can kill after the hit
spawn in fortress corridors
```

If seen:

```text
do not let several surround you
fight one at a time
use bow if possible
back into a corridor
avoid bridge edges
```

Action:

```text
shoot("minecraft:wither_skeleton", 1)
```

or:

```text
hunt("minecraft:wither_skeleton")
```

Retreat if withered and HP drops.

## 14.2 Ghasts

Ghasts can knock you off bridges.

If a ghast attacks during fortress travel:

```text
take cover
shoot ghast if clear
do not stand on exposed bridges
move under fortress roof/corridor if possible
```

## 14.3 Magma cubes

Magma cubes can knock you around.

Avoid fighting them near:

```text
lava
edges
bridges
spawner balconies
```

Kill only if they block path.

## 14.4 Piglins and piglin brutes

Piglins are not the main goal.

Avoid bastions.

If attacked:

```text
retreat
do not run into fortress/lava blindly
deal with immediate threat
continue fortress objective only once safe
```

---

# 15. Fortress navigation rules

## 15.1 Do not fall

Fortresses often have:

```text
long bridges
no railings
lava below
sudden drops
open sides
```

Move carefully.

Avoid fighting on edges.

If a blaze or skeleton attacks on a bridge:

```text
retreat to wider platform
fight from doorway/corner
use bow
```

## 15.2 Mark path if needed

If you may get lost, place simple markers.

Examples:

```text
place_block("minecraft:cobblestone") at corridor turns
place_block("minecraft:torch") if available
place_block unique block near exit path
```

Do not rely on memory alone.

## 15.3 Keep portal coordinates

Always know:

```text
portalX
portalY
portalZ
```

When done, use:

```text
move_to x=portalX y=portalY z=portalZ
```

If pathfinding fails, use intermediate waypoints.

---

# 16. Inventory checking

Call:

```text
get_self_status
```

After:

```text
arriving at fortress
finding spawner
each farming batch
taking major damage
collecting drops
before leaving fortress
after returning to portal
```

Look for:

```text
minecraft:blaze_rod count
HP
food count
arrows count
armor durability if visible
weapon status if visible
current coordinates
```

Completion threshold:

```text
minecraft:blaze_rod >= 7
```

Do not estimate rod count from kills.

Always verify inventory.

---

# 17. If rods are not dropping

Blaze rods are random.

Expected:

```text
roughly 1 rod per 2 blaze kills
```

If you killed many and got few rods:

```text
keep farming
collect_items carefully
check if drops fell below/behind you
avoid killing blazes over lava
use Looting sword if available
```

Do not leave early with 5 or 6 rods unless another skill explicitly changes the requirement.

---

# 18. If too many blazes spawn

If the spawner creates too many blazes:

```text
retreat out of line of sight
move farther than spawner activation range if needed
eat
let existing blazes spread out
kill them one by one from cover
return to spawner only when safe
```

Do not stand still in open space trading fireballs.

You may block sight lines:

```text
place_block to make a wall
place_block to seal a side angle
place_block to make a peek corner
```

Never block yourself in with blazes.

---

# 19. If health gets low

Low health rule:

```text
HP <= 8 means immediate retreat.
```

Action:

```text
move_to safe retreat point
equip_item(food)
eat/use food if tool supports it
wait briefly if burning
avoid new fights
check status again
```

If wither effect is active:

```text
retreat earlier
eat immediately
do not re-engage until effect ends and HP is safe
```

Do not attempt one more kill at low HP.

---

# 20. If lost

If lost in fortress:

```text
get_self_status
compare current coordinates to portal coordinates
move_to portalX portalY portalZ if path exists
```

If direct movement fails:

```text
return to known corridor
move to fortress entrance marker
move to previous waypoint
then move_to portal
```

If you are still near fortress and have enough rods:

```text
leaving safely is more important than exploring more
```

---

# 21. If portal is missing or broken

If you return to portal coordinates but portal is broken:

```text
check for obsidian frame
use flint_and_steel if available
```

If no flint and steel:

```text
look for fire source
use fire charge if available
or create/seek another portal only if another skill supports it
```

Do not wander randomly with blaze rods if you can stay safe and solve portal relight.

---

# 22. Detailed exact route algorithm

Use this algorithm whenever the user simply asks for blaze rods.

## 22.1 Start

```text
get_self_status
```

If not in Nether:

```text
Stop this skill.
Need Nether access first.
```

If in Nether:

```text
record portal/staging coordinates
```

## 22.2 Locate fortress

```text
locate_structure("minecraft:fortress")
```

Store:

```text
fortressX
fortressZ
distance
direction
```

## 22.3 Travel

```text
move_to x=fortressX z=fortressZ y~=70
```

During travel:

```text
avoid lava
avoid bastions
avoid ghasts
use blocks for safe bridges if needed
keep portal coordinates
```

## 22.4 Confirm fortress

```text
scan_blocks("minecraft:nether_bricks", radius=128)
```

If found:

```text
move_to nearest nether_bricks cluster
```

If not found:

```text
move around fortressX/fortressZ area
scan again
check nearby Y levels
```

## 22.5 Search spawner

Inside fortress:

```text
scan_blocks("minecraft:spawner", radius=64)
```

If spawner found:

```text
move_to safe point near spawner
```

If no spawner found:

```text
move through fortress corridors/bridges
kill natural blazes if safe
repeat scan
```

## 22.6 Farm

Loop:

```text
get_self_status
if blaze_rod >= 7: break loop
if HP <= 8: retreat/eat
equip bow if arrows available
shoot 1-3 blazes
collect_items
get_self_status
```

Fallback if no arrows:

```text
equip sword
hunt blaze carefully
collect_items
get_self_status
```

## 22.7 Exit

Once:

```text
blaze_rod >= 7
```

Then:

```text
collect_items one final time
get_self_status
move_to portalX portalY portalZ
get_self_status
```

If still:

```text
blaze_rod >= 7
safe at portal
```

Then complete.

---

# 23. Safe spawner micro-strategy

Use this if the spawner area is exposed.

## 23.1 Make a safe corner

Choose a spot:

```text
6-12 blocks from spawner
not over lava
near existing fortress wall
with retreat path
```

Place blocks to make a barrier if needed:

```text
place_block solid block at open side
place_block solid block at second open side
leave a 1-block viewing gap if useful
```

## 23.2 Fight through a controlled angle

Pattern:

```text
peek
shoot blaze
hide
wait for volley to hit wall
peek again
shoot
collect
```

This prevents taking repeated direct volleys.

## 23.3 Keep drops reachable

Try to kill blazes when they are:

```text
over platform
inside corridor
near solid ground
not above lava
not far outside fortress
```

If a blaze floats above lava, do not prioritize it unless it is attacking dangerously.

---

# 24. Natural blaze farming fallback

If no spawner is found but blazes spawn naturally:

```text
stay within fortress bounding area
patrol bridges/corridors
use bow from safe platforms
collect rods after each kill
keep scanning for spawner
```

Natural farming is slower but valid.

Rules:

```text
do not chase over lava
do not fight on bridge edges
do not overextend into unknown corridors
return to safe point after each few kills
```

---

# 25. Rod count targets

Minimum:

```text
7 blaze rods
```

Good if easy:

```text
8-10 blaze rods
```

Do not overfarm if:

```text
HP is low
food is low
arrows are low
portal route is risky
you already have >= 7 rods
```

The route only requires 7 rods with margin.

---

# 26. Common mistakes and fixes

## Mistake: Called `locate_structure` outside Nether

Fix:

```text
Enter Nether first.
Then call locate_structure("minecraft:fortress").
```

## Mistake: Went to a bastion instead of fortress

Signs:

```text
blackstone
gold
piglins
piglin brutes
```

Fix:

```text
leave immediately
scan for nether_bricks
follow fortress locate result
avoid bastion
```

## Mistake: Fighting blazes in the open

Fix:

```text
move behind wall/corner
place blocks for cover
shoot in small batches
retreat if too many fireballs
```

## Mistake: Forgot to collect rods

Fix:

```text
collect_items after every 1-3 kills
get_self_status to verify rod count
```

## Mistake: Left with only 6 rods

Fix:

```text
return to spawner/fortress
farm until get_self_status shows >= 7 rods
```

## Mistake: Broke the spawner

Fix:

```text
Cannot undo easily.
Use natural blaze spawns or find another spawner.
Never break future spawners.
```

## Mistake: Died after collecting rods

Fix:

```text
return to death location if possible
collect_items
check blaze_rod count
retreat safely
```

## Mistake: Lost portal coordinates

Fix:

```text
use any saved coordinates from earlier status
navigate to approximate portal area
if no data exists, stop and recover with navigation/portal strategy
```

---

# 27. Safety priority order

When making decisions, prioritize in this exact order:

```text
1. Do not die.
2. Do not fall into lava.
3. Do not lose portal route.
4. Do not destroy blaze spawner.
5. Collect dropped rods.
6. Reach >= 7 rods.
7. Return to portal/safe spot.
```

A single blaze rod is never worth dying in lava.

---

# 28. Example successful run

Example flow:

```text
get_self_status
# In Nether at portal: x=42 y=67 z=-18
# Record portal = 42,67,-18

locate_structure("minecraft:fortress")
# Fortress found at x=310 z=-460, distance 530

move_to x=310 y=70 z=-460
scan_blocks("minecraft:nether_bricks", radius=128)
# Found fortress cluster

move_to nearest nether_bricks corridor
scan_blocks("minecraft:spawner", radius=64)
# Found spawner nearby

move_to safe corner near spawner
equip_item("minecraft:bow")

shoot("minecraft:blaze", 2)
collect_items
get_self_status
# blaze_rod = 1

shoot("minecraft:blaze", 3)
collect_items
get_self_status
# blaze_rod = 3, HP safe

shoot("minecraft:blaze", 3)
collect_items
get_self_status
# blaze_rod = 5

retreat because HP <= 8
eat/recover

return to safe firing point
shoot("minecraft:blaze", 3)
collect_items
get_self_status
# blaze_rod = 7

move_to x=42 y=67 z=-18
get_self_status
# blaze_rod >= 7 and safe at portal
```

Skill complete.

---

# 29. Final output after completing this skill

When done, report:

```text
Completed blaze_rods.
Collected >=7 blaze rods.
Returned to Nether portal or safe staging point.
Ready to load ender_pearls.
```

Then load next skill:

```text
load_skill(name="ender_pearls")
```

If a warped forest was seen during travel, remember it for phase 4:

```text
Warped forest = teal trees / warped nylium / many endermen
```

Useful note:

```text
Warped forests are excellent for ender pearl farming.
```

---

# 30. Done when

Only mark this skill completed when:

```text
get_self_status confirms minecraft:blaze_rod >= 7
and player is alive
and player is safe
and player can return / has returned to portal or staging point
```

Then:

```text
mark phase 3 completed
load_skill(name="ender_pearls")
```
