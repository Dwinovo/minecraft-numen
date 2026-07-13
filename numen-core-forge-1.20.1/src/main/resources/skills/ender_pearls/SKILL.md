---
name: ender_pearls
description: Phase 4 of the dragon route. Acquire at least 12 ender pearls, preferably by hunting endermen in a Nether warped forest. Covers locate_biome, hunting loops, HP discipline, Overworld fallback, piglin bartering fallback, rain/water issues, and transition to stronghold_finding.
---

# Skill: ender_pearls

This is Phase 4 of the dragon route.

Goal:

```text
Acquire at least 12 minecraft:ender_pearl.
Stay alive.
Keep blaze rods/powder safe.
End in a safe spot, preferably ready to start stronghold_finding.
```

Why 12 pearls:

```text
1 ender pearl + 1 blaze powder = 1 eye of ender
An End portal can require up to 12 eyes of ender
Stronghold portal frames may spawn partly filled, but never rely on that
12 pearls is the safe target
```

If you have:

```text
>= 12 ender_pearl
```

this phase is complete.

Do not waste pearls before the End portal is activated.

---

# 0. Completion condition

This skill is complete only when:

```text
get_self_status shows minecraft:ender_pearl count >= 12
player/entity is alive
player/entity is not in immediate danger
blaze rods/blaze powder are still safe
```

Preferred completion state:

```text
At Nether portal, Overworld base, or another safe staging point
Ready to load stronghold_finding
```

Do not mark complete if:

```text
you only killed many endermen but did not verify pearl count
pearls are on the ground and not collected
HP is low and enemies are attacking
you died and inventory is not verified
you have fewer than 12 pearls
```

---

# 1. Absolute priority rules

## 1.1 Use `hunt`, not `shoot`

Endermen teleport away from arrows.

Correct:

```text
equip_item diamond_sword
hunt("minecraft:enderman")
```

Incorrect:

```text
shoot("minecraft:enderman")
```

Do not waste arrows on endermen.

## 1.2 Your gaze does not aggro endermen

For the Numen entity:

```text
Looking at endermen does not anger them.
Endermen become hostile when you hit them.
```

This differs from a normal player.

However, if the owner/player is nearby:

```text
Warn the owner not to stare at endermen.
The owner may still trigger normal player gaze aggro.
```

No pumpkin tricks are needed for Numen.

## 1.3 Warped forest is the best hunting location

Best default location:

```text
minecraft:warped_forest in the Nether
```

Why:

```text
high enderman density
no rain
often already in Nether after blaze_rods
more reliable than Overworld night
```

Do not wander randomly if `locate_biome` exists.

Use:

```text
locate_biome biome="minecraft:warped_forest"
```

## 1.4 Do not fight near lava, water, cliffs, or void-like drops

Endermen teleport and reposition unpredictably.

Avoid hunting near:

```text
lava
water
Nether cliffs
thin bridges
soul sand valleys with ghasts nearby
Overworld ravines
large drops
```

Kill endermen on safe, dry, solid ground.

## 1.5 Verify count with `get_self_status`

Ender pearl drops are random.

Do not estimate from kills.

After hunting batches:

```text
collect_items
get_self_status
```

Stop only when:

```text
ender_pearl >= 12
```

---

# 2. Required support skills

Load before combat:

```text
load_skill(name="combat_basics")
```

Use combat_basics for:

```text
HP thresholds
food healing
hunt vs shoot rules
retreat logic
positioning
post-fight recovery
```

If in creative mode:

```text
load_skill(name="creative_mode")
```

In creative mode, if the owner allows shortcuts:

```text
creative_give item_id="minecraft:ender_pearl" count=12
```

Do not shortcut if the owner asked for legit survival gameplay.

---

# 3. Useful tools

| Tool | Use |
|---|---|
| `get_self_status` | Check HP, inventory, dimension, pearl count, weapon, food. |
| `get_world_info` | Check time, rain, and environment for Overworld hunting. |
| `locate_biome` | Find warped forest directly. |
| `move_to` | Travel to warped forest, reposition, retreat, return to portal. |
| `scan_nearby_entities` | Confirm endermen nearby. |
| `scan_blocks` | Confirm warped forest blocks such as warped_nylium. |
| `equip_item` | Equip sword, pickaxe, food, gold armor if needed. |
| `hunt` | Melee endermen. Correct combat method. |
| `eat_item` | Heal directly. |
| `collect_items` | Pick up dropped pearls after kills. |
| `drop_items` | Drop gold ingots for piglin bartering fallback. |
| `wait` | Wait for night, wait during bartering, or let danger pass. |
| `interact_entity` | Optional piglin interaction if supported. |

---

# 4. Enderman facts

## 4.1 Stats and behavior

Endermen:

```text
have 40 HP
deal strong melee damage, around 7 damage per hit
teleport when hit
often reappear behind or near you
drop 0-1 ender pearl without Looting
average about 0.5 pearls per kill
```

Expected kills:

```text
12 pearls usually requires about 24 enderman kills
bad luck can require more
```

If you already have some pearls:

```text
pearlsNeeded = 12 - currentPearls
expectedKills = pearlsNeeded * 2
```

Example:

```text
currentPearls = 5
pearlsNeeded = 7
expectedKills ~= 14
```

## 4.2 Looting

If you already have a Looting sword:

```text
use it
```

Looting improves pearl yield.

Do not plan the route around enchanting unless another skill or owner explicitly provides it.

## 4.3 Endermen and water/rain

Endermen avoid and teleport away from water and rain.

Therefore:

```text
Do not hunt next to water.
Do not hunt in rain.
Do not use water as a normal farming method for Numen.
```

Water can be emergency safety, but it makes kills slower because endermen teleport away.

## 4.4 Endermen and arrows

Endermen usually teleport away from arrows.

Rule:

```text
Never use shoot for normal enderman farming.
```

Save arrows for:

```text
blazes
ghasts
End crystals
dragon fight
```

---

# 5. Location choice

## 5.1 Best location: Nether warped forest

Use this by default after blaze rods.

Advantages:

```text
high enderman spawn density
no rain
usually reachable while already in Nether
good for repeated hunting batches
```

Signs of warped forest:

```text
warped_nylium
warped_stem
warped_wart_block
nether_sprouts
twisting_vines
teal/blue-green forest appearance
many endermen
```

Confirm with:

```text
scan_blocks("minecraft:warped_nylium", radius=64)
scan_nearby_entities("minecraft:enderman", radius=64)
```

## 5.2 Overworld night fallback

Use only if warped forest is unavailable or Nether travel is not practical.

Good Overworld hunting areas:

```text
plains
desert
savanna
flat open terrain
dry areas away from rivers/oceans
```

Requirements:

```text
night or dark enough for endermen
not raining
not near water
safe flat terrain
```

Check:

```text
get_world_info
```

If raining:

```text
wait
or switch to Nether hunting
```

If daytime:

```text
wait until night
or use Nether hunting
```

## 5.3 Soul sand valley fallback

Soul sand valley can have some endermen.

Pros:

```text
available in Nether
no rain
some endermen
```

Cons:

```text
soul sand slows movement
ghasts are dangerous
skeletons may spawn
terrain can be awkward
```

Use only if warped forest cannot be found soon.

## 5.4 Piglin bartering fallback

Use only if you already have a large amount of gold.

Piglin bartering is high variance and often slower than warped forest hunting.

Good if:

```text
you already looted or mined lots of gold ingots
you have gold armor equipped
you have safe piglins nearby
you do not need to enter a dangerous bastion
```

Bad if:

```text
you must mine gold from scratch
you are near piglin brutes
you are in a bastion
you have little food/armor
```

---

# 6. Choosing the method

Use this decision tree.

## 6.1 Start with status

Always begin:

```text
get_self_status
```

Check:

```text
dimension
HP
food
weapon
armor
current ender_pearl count
blaze_rod/blaze_powder count
portal coordinates if in Nether
```

If:

```text
ender_pearl >= 12
```

then phase is already complete.

## 6.2 If in creative mode

If:

```text
game_mode=creative
```

then:

```text
load_skill(name="creative_mode")
```

If shortcuts are allowed:

```text
creative_give item_id="minecraft:ender_pearl" count=12
get_self_status
```

Complete when count is verified.

## 6.3 If in Nether

If in Nether, prefer:

```text
locate_biome biome="minecraft:warped_forest"
```

Then hunt there.

This is the usual state after `blaze_rods`.

## 6.4 If in Overworld with Nether access

If Nether portal is available and gear is safe:

```text
go to Nether
locate warped forest
hunt endermen there
```

This is usually better than waiting for night.

## 6.5 If no safe Nether access

Use Overworld night hunting.

Check:

```text
get_world_info
```

If night and not raining:

```text
hunt in flat dry open area
```

If day/rain:

```text
wait
or backfill Nether access
```

## 6.6 If lots of gold exists

Consider piglin bartering only if:

```text
gold_ingot count is high
gold armor can be worn
piglin area is safe
```

Otherwise hunt endermen.

---

# 7. Finding a warped forest

## 7.1 Use locate_biome

While in the Nether:

```text
locate_biome biome="minecraft:warped_forest"
```

This should return:

```text
target coordinates
direction
distance
```

The result may be approximate, often within about 64 blocks.

## 7.2 Travel to the biome

Use:

```text
move_to x=<warped_forest_x> y=<safe_y> z=<warped_forest_z>
```

Recommended Nether travel Y:

```text
around y=60-90 when possible
```

Avoid:

```text
lava seas
thin bridges
bastions
ghasts over open lava
steep cliffs
piglin brutes
```

If direct travel is dangerous:

```text
use intermediate waypoints
move through safer terrain
do not sprint blindly
```

## 7.3 Confirm the biome

After arrival:

```text
scan_blocks("minecraft:warped_nylium", radius=64)
scan_blocks("minecraft:warped_stem", radius=64)
scan_nearby_entities("minecraft:enderman", radius=64)
```

If warped blocks and endermen are present:

```text
begin hunting
```

If not:

```text
move around the target area
scan again
```

## 7.4 If locate_biome fails

If no warped forest is found:

```text
travel a few thousand blocks in a safe direction
retry locate_biome
```

Do not wander randomly forever.

Pattern:

```text
locate_biome warped_forest
if not found:
    move_to safe point 1500-3000 blocks away
    locate_biome again
```

---

# 8. Warped forest hunting setup

## 8.1 Choose a safe hunting patch

Good area:

```text
flat or gently sloped warped nylium
solid ground
no lava immediately nearby
not at a cliff edge
no ghast line of sight if possible
room to move
several endermen nearby
```

Bad area:

```text
next to lava
on narrow ledges
under overhangs that trap pathfinding
beside steep drops
near bastion/piglin brute danger
where ghasts can knock you into lava
```

## 8.2 Prepare before attacking

Use:

```text
get_self_status
```

Confirm:

```text
HP >= 16, preferably 18+
diamond_sword or best sword available
food available
armor equipped
not standing near lava/cliff
```

Then:

```text
equip_item "minecraft:diamond_sword"
```

If no diamond sword:

```text
equip best available sword
```

Do not hunt many endermen with bad gear.

## 8.3 Batch size

Recommended batch sizes:

| Gear/state | Batch size |
|---|---:|
| Diamond sword, armor, plenty of food, safe terrain | 3-4 endermen |
| Iron sword, armor, moderate food | 1-2 endermen |
| Low HP or dangerous terrain | 1 enderman |
| No food or bad weapon | Do not hunt |

Default:

```text
hunt("minecraft:enderman", 4)
```

But reduce batch size if unsafe.

## 8.4 Hunting loop

Use this loop:

```text
get_self_status
equip_item diamond_sword
scan_nearby_entities("minecraft:enderman", radius=64)

if endermen nearby:
    hunt("minecraft:enderman", 4)

collect_items
get_self_status

if HP <= 8:
    move_to safe point
    eat_item
    get_self_status

repeat until ender_pearl >= 12
```

If too few endermen are nearby:

```text
move_to another safe patch inside warped forest
scan_nearby_entities again
```

## 8.5 Collect after every batch

Endermen teleport while fighting, so drops can be scattered.

After each batch:

```text
collect_items
get_self_status
```

Do not leave pearls on the ground.

Do not chase pearls into:

```text
lava
cliff edge
enemy swarm
dangerous ghast area
```

---

# 9. Overworld night hunting

Use this fallback when Nether warped forest is unavailable.

## 9.1 Check conditions

Call:

```text
get_world_info
get_self_status
```

Confirm:

```text
nighttime or sufficiently dark
not raining
safe HP
food available
sword equipped
flat dry terrain nearby
```

If raining:

```text
wait until rain stops
or go to Nether
```

If daytime:

```text
wait until night
or go to Nether
```

## 9.2 Choose terrain

Good:

```text
plains
desert
savanna
flat open fields
dry terrain
wide sight lines
```

Avoid:

```text
rivers
oceans
swamps
forests with water pools
mountain cliffs
ravines
villages with many obstacles
```

## 9.3 Scan and hunt

Use:

```text
scan_nearby_entities("minecraft:enderman", radius=64)
```

If endermen found:

```text
equip_item diamond_sword
hunt("minecraft:enderman", 1 or 2)
collect_items
get_self_status
```

Use smaller batches in the Overworld because:

```text
other hostile mobs may join
creepers may explode
skeletons may shoot
enderman density is lower
```

## 9.4 Handle other mobs

If zombies/spiders/skeletons/creepers interfere:

```text
load_skill(name="combat_basics")
clear immediate threats
do not let creepers explode near you
do not fight endermen at cliff/water edges
```

If overwhelmed:

```text
retreat
eat
reposition
```

## 9.5 If dawn comes

Endermen may disappear or become harder to farm.

If pearl count is still low:

```text
wait until next night
or switch to Nether warped forest
```

---

# 10. Soul sand valley fallback

Use only if necessary.

## 10.1 Setup

Confirm:

```text
HP safe
food available
bow available for ghasts if needed
sword equipped for endermen
terrain not near lava edge
```

## 10.2 Threats

Soul sand valleys may have:

```text
ghasts
skeletons
slow movement from soul sand
large open gaps
lava hazards
```

Do not fight endermen while ghasts are actively shooting over lava.

## 10.3 Hunt carefully

Use small batches:

```text
hunt("minecraft:enderman", 1)
collect_items
get_self_status
```

Move back to safe terrain after each kill.

---

# 11. Piglin bartering fallback

Piglin bartering can produce ender pearls, but it is not the default plan.

## 11.1 When to use

Use piglin bartering if:

```text
you already have lots of gold ingots
you are in the Nether
you can wear gold armor
there are adult piglins nearby
the area is safe
```

Do not use if:

```text
you must enter a bastion
piglin brutes are nearby
you have very little gold
you are low HP
you have no food
```

## 11.2 Bartering facts

Modern Minecraft behavior is version-dependent, but generally:

```text
piglins have a low chance to barter ender pearls
a successful pearl barter usually gives a small bundle, often 2-4 pearls
expect high variance
```

Rough expectation in modern versions:

```text
about 45-50 gold ingots per successful pearl bundle
about 15-17 gold ingots per pearl on average
12 pearls can require around 180-200 gold ingots on average
bad luck can require much more
```

Therefore:

```text
bartering is fallback only unless gold is abundant
```

## 11.3 Bartering safety

Before bartering:

```text
equip gold armor if available
avoid piglin brutes
avoid bastions unless another skill says otherwise
do not attack piglins
do not open chests near piglins
do not mine gold near piglins
choose safe ground away from lava
```

## 11.4 Bartering procedure

Steps:

```text
get_self_status
equip_item gold armor piece if needed
move_to safe piglin area
drop_items item_id="minecraft:gold_ingot" count=<safe_batch>
wait for piglins to barter
collect_items
get_self_status
repeat until ender_pearl >= 12 or gold is not worth continuing
```

Use small gold batches first if the area is unsafe.

Do not throw all gold into lava-adjacent chaos.

## 11.5 If piglins attack

If piglins attack:

```text
retreat
do not continue bartering
stabilize HP
check whether gold armor is equipped
avoid hitting more piglins
```

---

# 12. HP and combat discipline

Endermen hit hard.

Use stricter rules than for weak mobs.

## 12.1 HP thresholds

Assuming max HP is 20:

| HP | Action |
|---:|---|
| 18-20 | Good to hunt. |
| 14-17 | Eat before large batch. |
| 9-13 | Hunt only one if necessary; better to eat first. |
| <= 8 | Stop hunting, retreat, eat. |
| <= 6 | Critical; emergency retreat. |

## 12.2 Before each batch

Call:

```text
get_self_status
```

If HP is low:

```text
eat_item
get_self_status
```

Then fight.

## 12.3 After each batch

Always:

```text
collect_items
get_self_status
```

If HP <= 8:

```text
move_to safe point
eat_item
get_self_status
```

## 12.4 If food runs out

If food is gone:

```text
stop hunting
retreat
restock food
do not continue enderman farming
```

Endermen are not safe to farm with no healing.

---

# 13. Positioning rules

## 13.1 Good enderman fighting positions

Use:

```text
flat dry ground
open space
solid footing
away from lava/water/cliffs
near a safe retreat path
inside warped forest but not on steep ledges
```

## 13.2 Bad positions

Avoid:

```text
lava shores
water shores
riverbanks
Nether cliff edges
soul sand near ghasts
thin bridges
forest tangles that block pathing
ravines
areas crowded with other hostile mobs
```

## 13.3 Enderman teleport behavior

Endermen may teleport:

```text
behind you
beside you
onto nearby ledges
away from water/rain
away after being hit
```

This is normal.

Do not panic.

Use:

```text
hunt
```

and let the combat tool continue closing distance.

## 13.4 Optional safety roof

If too many endermen hit you and you have blocks, a low shelter can help.

Concept:

```text
Endermen are tall.
A 2-block-high space can protect smaller entities/players from direct enderman access.
```

Use only if necessary.

Do not spend excessive time building during the route.

Do not trap yourself.

---

# 14. Inventory management

## 14.1 Required count

Target:

```text
minecraft:ender_pearl >= 12
```

If you already have some:

```text
missing = 12 - current count
```

## 14.2 Preserve blaze materials

Do not lose:

```text
minecraft:blaze_rod
minecraft:blaze_powder
```

They are needed for eyes of ender.

## 14.3 Do not craft eyes too early unless needed

This phase is about pearls.

Crafting eyes usually belongs in:

```text
stronghold_finding
```

Reason:

```text
You may need to count exact portal requirements.
Pearls may be useful as emergency mobility only if extras exist.
Avoid confusion between pearls and eyes.
```

If you do craft eyes early, ensure:

```text
you still have enough for up to 12 eyes total
```

## 14.4 Do not throw pearls casually

Ender pearls can teleport you and cause damage/risk.

Do not use them for travel unless:

```text
you have extras above 12
it is an emergency
another skill explicitly says to use them
```

---

# 15. Main warped forest algorithm

Use this as the default Phase 4 route.

## 15.1 Start

```text
load_skill(name="combat_basics")
get_self_status
```

If:

```text
ender_pearl >= 12
```

then complete.

If in Nether:

```text
continue
```

If not in Nether but Nether access exists:

```text
return/enter Nether if safe
```

## 15.2 Locate biome

```text
locate_biome biome="minecraft:warped_forest"
```

If found:

```text
move_to x=<biomeX> y=<safeY> z=<biomeZ>
```

If not found:

```text
move_to a safe point 1500-3000 blocks away
retry locate_biome
```

## 15.3 Confirm location

```text
scan_blocks("minecraft:warped_nylium", radius=64)
scan_nearby_entities("minecraft:enderman", radius=64)
```

If confirmed:

```text
choose safe hunting area
```

If not:

```text
move within target area
scan again
```

## 15.4 Hunt loop

```text
while ender_pearl < 12:
    get_self_status

    if HP <= 8:
        move_to safe point
        eat_item
        get_self_status

    if food missing:
        stop and restock

    equip_item diamond_sword or best sword

    scan_nearby_entities("minecraft:enderman", radius=64)

    if endermen found:
        hunt("minecraft:enderman", 4)
        collect_items
        get_self_status
    else:
        move_to another safe patch in warped forest
```

## 15.5 Finish

When:

```text
get_self_status shows ender_pearl >= 12
```

then:

```text
return to Nether portal or safe staging point if practical
mark phase 4 completed
load_skill(name="stronghold_finding")
```

---

# 16. Overworld fallback algorithm

Use if Nether warped forest is unavailable.

```text
load_skill(name="combat_basics")
get_world_info
get_self_status

if raining:
    wait or go to Nether

if daytime:
    wait until night or go to Nether

move_to flat dry open area
scan_nearby_entities("minecraft:enderman", radius=64)

while ender_pearl < 12 and night/dry:
    equip_item sword
    hunt("minecraft:enderman", 1 or 2)
    collect_items
    get_self_status

    if HP <= 8:
        move_to safe point
        eat_item

    if too many other mobs:
        clear/retreat/reposition
```

If night ends before completion:

```text
wait for next night
or switch to Nether method
```

---

# 17. Common mistakes and fixes

## Mistake: Shooting endermen

Problem:

```text
Endermen teleport away from arrows.
Arrows are wasted.
```

Fix:

```text
equip sword
use hunt
```

## Mistake: Hunting in rain

Problem:

```text
Endermen teleport away or fail to remain in fight.
```

Fix:

```text
wait until rain stops
or hunt in Nether
```

## Mistake: Fighting near water

Problem:

```text
Endermen teleport away from water.
Kills become unreliable.
```

Fix:

```text
move_to dry area
hunt there
```

## Mistake: Fighting near lava

Problem:

```text
Endermen teleport around.
You can be knocked or path into lava.
Drops may be lost.
```

Fix:

```text
move_to safe dry solid ground
```

## Mistake: Forgetting to collect pearls

Fix:

```text
collect_items after every batch
get_self_status to verify pearl count
```

## Mistake: Leaving with fewer than 12 pearls

Fix:

```text
continue hunting until get_self_status shows >=12
```

## Mistake: Running out of food

Fix:

```text
retreat
restock food
do not continue hunting
```

## Mistake: Starting piglin bartering with little gold

Problem:

```text
high variance and slow
```

Fix:

```text
hunt endermen instead
```

## Mistake: Entering a bastion for bartering

Problem:

```text
piglin brutes are dangerous
bastions are not required
```

Fix:

```text
avoid bastion
use warped forest hunting
```

## Mistake: Crafting/using pearls before count is secure

Fix:

```text
preserve 12 pearls for eyes of ender
only use extras for travel/emergency
```

---

# 18. Recovery

## 18.1 If you die

Immediately:

```text
get_self_status
```

Check:

```text
inventory
location
pearl count
blaze rod/powder count
gear status
```

If items are recoverable:

```text
recover items
collect_items
get_self_status
```

If pearls or gear are lost:

```text
backfill missing gear/resources
resume phase 4 only when safe
```

## 18.2 If lost in Nether

Use recorded portal coordinates if available:

```text
move_to x=<portalX> y=<portalY> z=<portalZ>
```

If portal coordinates are unknown:

```text
stop wandering randomly
use navigation/recovery plan
keep HP and food safe
```

## 18.3 If warped forest is dangerous

If terrain is bad:

```text
move to another part of the biome
choose flatter ground
avoid ghasts/lava/cliffs
```

Do not force fights in unsafe terrain.

---

# 19. Transition to next phase

When complete:

```text
get_self_status confirms minecraft:ender_pearl >= 12
```

Then:

```text
mark phase 4 completed
load_skill(name="stronghold_finding")
```

Before stronghold phase, ensure:

```text
blaze_rod >= 7 or enough blaze_powder exists
ender_pearl >= 12
gear is still intact
food remains
arrows remain
blocks remain
```

The next phase will craft/use eyes of ender and find the stronghold.

---

# 20. Final response after completing this skill

When done, report briefly:

```text
Completed ender_pearls.
Collected at least 12 ender pearls.
Ready to load stronghold_finding.
```

If relevant:

```text
I am at a safe staging point / Nether portal and ready for the stronghold phase.
```

---

# 21. Highest-priority reminders

Always remember:

```text
1. Need >=12 ender pearls.
2. Use hunt, never shoot.
3. Your gaze does not aggro endermen.
4. Warped forest is best.
5. Use locate_biome, do not wander randomly.
6. Avoid rain, water, lava, cliffs, and narrow terrain.
7. Hunt in small batches.
8. Collect_items after each batch.
9. Check get_self_status after each batch.
10. HP <= 8 means retreat and eat.
11. Piglin bartering is fallback only.
12. Preserve pearls for eyes of ender.
13. Complete only after get_self_status confirms >=12 pearls.
```
