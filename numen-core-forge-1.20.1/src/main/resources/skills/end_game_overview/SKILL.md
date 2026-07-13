---
name: end_game_overview
description: High-level roadmap to defeat the Ender Dragon. Load this FIRST when the owner asks for any end-game, stronghold, End portal, or "kill the dragon" goal. It manages the route phases, todo tracking, phase selection, inventory verification, skill loading, recovery, and when to load specialised skills.
---

# Skill: end_game_overview

Your owner has asked for an end-game goal such as:

```text
defeat the Ender Dragon
kill the dragon
beat Minecraft
finish the game
go to the End
find the stronghold
activate the End portal
prepare for the dragon
```

This skill is the **route map**.

It does not replace the specialised phase skills. It tells you:

```text
which phase you are in
which skill to load next
what each phase requires
how to verify completion
when to backfill missing resources
how to keep todo state correct
how to avoid loading every skill at once
how to recover from setbacks
```

The dragon route is long. It crosses:

```text
Overworld
Nether
End
```

and requires:

```text
gear progression
portal building
Nether travel
fortress/blaze farming
ender pearl acquisition
stronghold travel
End portal activation
dragon combat
```

Do not try to solve the entire route in one giant action. Execute one phase at a time.

---

# 0. Highest priority rules

## 0.1 Load this skill first for dragon-route goals

If the owner asks for the Ender Dragon or any end-game route, load:

```text
load_skill(name="end_game_overview")
```

Then inspect the current state:

```text
get_self_status
```

Use this skill to decide which specialised skill to load next.

## 0.2 Use `todowrite` for the main route

For the full route, create exactly six top-level todos:

```text
1. Tier up to dragon-route gear
2. Build and enter Nether portal
3. Collect blaze rods
4. Collect ender pearls
5. Find stronghold and activate End portal
6. Defeat Ender Dragon
```

Keep exactly one phase:

```text
in_progress
```

at a time.

Do not mark a phase complete from memory. Verify with `get_self_status` or world state first.

## 0.3 Load specialised skills only when needed

Do not load every phase skill up front.

Correct:

```text
load end_game_overview
check status
load tier_progression only if starting phase 1
```

Incorrect:

```text
load tier_progression, nether_entry, blaze_rods, ender_pearls,
stronghold_finding, dragon_combat all at once
```

Specialised skills are loaded on demand.

## 0.4 Verify phase completion before advancing

Every phase has a "done when" condition.

Use:

```text
get_self_status
```

for inventory/equipment/dimension checks.

Use world checks when needed:

```text
inspect_block
scan_blocks
locate_structure
interact_at
inspect_gui
```

Never assume:

```text
I probably have enough rods
I probably have enough pearls
I probably equipped armor
the portal is probably active
the dragon is probably dead
```

## 0.5 Backfill missing requirements

If the owner starts in the middle of the route, do not blindly restart from phase 1.

Instead:

```text
get_self_status
compare current inventory/equipment/dimension to phase requirements
load the earliest phase that is not actually complete
```

Example:

```text
Owner says "find the stronghold"
but inventory has 0 blaze rods and 0 ender pearls.
Backfill phases 3 and 4 first.
```

Example:

```text
Owner says "kill the dragon"
and you are already standing at an activated End portal with full gear.
Skip to phase 6.
```

## 0.6 Do not enter one-way-danger phases underprepared

Important points of no easy return:

```text
Entering the Nether without food/gear can strand or kill you.
Entering the End portal starts the final boss area.
The End cannot be exited normally until the dragon is dead.
```

Before entering the End:

```text
verify dragon_combat packlist
```

Do not jump into the End portal casually.

---

# 1. Creative mode exception

If:

```text
get_self_status reports game_mode=creative
```

then immediately load:

```text
load_skill(name="creative_mode")
```

Creative mode changes the route.

In creative mode:

```text
resources can be given directly
travel can use flight or commands
gear can be obtained instantly
building portals/structures can use fill/run_command
combat risk is reduced
```

However:

```text
do not use commands or creative shortcuts if the owner asked for legit survival gameplay
```

If the owner says:

```text
do it legit
survival only
no commands
no cheating
```

then follow survival-style phase skills even if creative tools exist, unless safety or explicit instruction changes that.

---

# 2. Support skills

This overview coordinates the route. Load support skills when relevant.

## 2.1 `combat_basics`

Load before combat-heavy phases:

```text
load_skill(name="combat_basics")
```

Use for:

```text
blazes
endermen
dragon
wither skeletons
fortress combat
general hostile mobs
```

It covers:

```text
HP thresholds
food healing
hunt vs shoot
arrow budgeting
retreat rules
positioning
aggro pitfalls
```

## 2.2 `containers`

Load when using GUIs:

```text
load_skill(name="containers")
```

Use for:

```text
crafting
smelting
chests
furnaces
crafting tables
moving exact item counts
loading machines
taking outputs
```

Important:

```text
supported crafting/smelting = plan_crafting + gather missing materials + craft_items + inventory verification
manual GUI fallback = lookup_recipe + inspect_gui + transfer + verify + close_gui
```

## 2.3 `creative_mode`

Load whenever:

```text
game_mode=creative
```

Use for:

```text
creative_give
fill
run_command
flight
teleportation
creative building/resources
```

## 2.4 Other specialised phase skills

Load only when the corresponding phase begins:

```text
tier_progression
nether_entry
blaze_rods
ender_pearls
stronghold_finding
dragon_combat
```

---

# 3. The six mainline phases

These phases mirror the normal vanilla progression toward the dragon.

| # | Phase | Skill | Done when | Vanilla advancement |
|---|---|---|---|---|
| 1 | Tier up to diamond-route gear | `tier_progression` | Diamond pickaxe + diamond sword, iron-or-better armor, bow + arrows, food, blocks | "Diamonds!" |
| 2 | Build and enter Nether portal | `nether_entry` | Standing in the Nether with packlist intact | "We Need to Go Deeper" |
| 3 | Farm blaze rods | `blaze_rods` | Inventory has >=7 blaze rods | "Into Fire" |
| 4 | Acquire ender pearls | `ender_pearls` | Inventory has >=12 ender pearls | — |
| 5 | Find stronghold and activate End portal | `stronghold_finding` | Standing at activated End portal | "Eye Spy" |
| 6 | Defeat Ender Dragon | `dragon_combat` | Dragon HP reaches 0 | "Free the End" |

---

# 4. Phase 1 overview: tier up to dragon-route gear

## 4.1 Skill to load

```text
load_skill(name="tier_progression")
```

## 4.2 Goal

Get enough survival gear to safely complete the Nether, stronghold, and dragon fight.

Minimum phase-1 target:

```text
diamond_pickaxe
diamond_sword
iron-or-better armor
bow
32+ arrows
32+ cooked food
64+ cobblestone or other solid blocks
```

Preferred:

```text
diamond_pickaxe
diamond_sword
full iron armor or better
bow
64+ arrows
32+ cooked_beef or cooked_porkchop
128+ cobblestone
shield if usable by environment, but do not rely on it for Numen baseline
golden_apple if available
```

## 4.3 Why these items matter

Diamond pickaxe:

```text
Needed for obsidian if making a normal Nether portal.
Also useful for mining and navigation.
```

Diamond sword:

```text
Needed for blazes, endermen, and dragon perch damage.
```

Iron-or-better armor:

```text
Reduces risk from mobs, lava-adjacent fights, Endermen, and dragon hits.
```

Bow and arrows:

```text
Needed for blazes and required for End crystals.
```

Food:

```text
Numen heals directly with eat_item.
No hunger-bar regeneration logic applies.
```

Blocks:

```text
Needed for Nether travel, bridging, blocking danger, and End platform-to-island navigation.
```

## 4.4 Done verification

Use:

```text
get_self_status
```

Confirm inventory/equipment includes:

```text
minecraft:diamond_pickaxe
minecraft:diamond_sword
armor equipped or available:
    iron or better helmet/chestplate/leggings/boots
minecraft:bow
minecraft:arrow count >= 32
cooked food count >= 32 preferred
solid block count >= 64
```

If not complete:

```text
keep phase 1 in_progress
continue tier_progression
```

If complete:

```text
mark phase 1 completed
mark phase 2 in_progress
load_skill(name="nether_entry")
```

---

# 5. Phase 2 overview: build and enter Nether portal

## 5.1 Skill to load

```text
load_skill(name="nether_entry")
```

## 5.2 Goal

Create or find a Nether portal and enter the Nether safely.

Done when:

```text
get_self_status reports dimension == Nether
gear/food/blocks are still present
player/entity is alive and safe
portal coordinates are recorded
```

## 5.3 Before entering the Nether

Verify:

```text
HP safe
food stocked
weapon ready
pickaxe available
bow/arrows available
blocks available
armor equipped
flint_and_steel or portal-lighting method available
```

Recommended before entry:

```text
32+ cooked food
64+ blocks
bow + arrows
diamond sword
iron-or-better armor
pickaxe in hand for navigation
```

## 5.4 Important Nether entry rule

After entering the Nether, immediately call:

```text
get_self_status
```

Record the Nether portal coordinates:

```text
portalX
portalY
portalZ
dimension = Nether
```

These coordinates are crucial for returning after blaze rods.

## 5.5 Done verification

Use:

```text
get_self_status
```

Confirm:

```text
dimension == Nether
HP safe
inventory still has weapon, food, blocks, bow/arrows
portal location known
```

Then:

```text
mark phase 2 completed
mark phase 3 in_progress
load_skill(name="combat_basics")
load_skill(name="blaze_rods")
```

---

# 6. Phase 3 overview: collect blaze rods

## 6.1 Skill to load

```text
load_skill(name="combat_basics")
load_skill(name="blaze_rods")
```

## 6.2 Goal

Collect:

```text
>= 7 minecraft:blaze_rod
```

Why 7:

```text
1 blaze rod -> 2 blaze powder
6 rods -> 12 powder
12 powder + 12 ender pearls -> 12 eyes of ender
7 rods gives 1 extra rod margin
```

## 6.3 High-level plan

In the Nether:

```text
1. Use locate_structure("minecraft:fortress")
2. Travel to fortress area
3. Find fortress corridors/spawner
4. Farm blazes safely
5. Collect rods
6. Return to Nether portal or safe staging point
```

Do not wander randomly looking for a fortress if `locate_structure` is available.

## 6.4 Combat reminder

Default blaze tactic:

```text
shoot blazes with bow
collect_items
get_self_status
retreat/eat if low HP
```

Melee fallback:

```text
hunt blazes only if arrows are low and terrain is safe
```

Never mine the blaze spawner before rods are complete.

## 6.5 Done verification

Use:

```text
get_self_status
```

Confirm:

```text
minecraft:blaze_rod count >= 7
alive
safe
preferably back at Nether portal or safe staging point
```

Then:

```text
mark phase 3 completed
mark phase 4 in_progress
load_skill(name="combat_basics")
load_skill(name="ender_pearls")
```

---

# 7. Phase 4 overview: acquire ender pearls

## 7.1 Skill to load

```text
load_skill(name="combat_basics")
load_skill(name="ender_pearls")
```

## 7.2 Goal

Collect:

```text
>= 12 minecraft:ender_pearl
```

Why 12:

```text
12 eyes of ender may be needed to fill an empty End portal frame.
Using locate_structure for stronghold means eyes do not need to be wasted on triangulation.
12 pearls + 12 blaze powder = 12 eyes of ender.
```

## 7.3 High-level options

Ender pearls may come from:

```text
Endermen hunting
Warped forest endermen
Piglin bartering if available
Loot if already found
Creative give if creative mode
```

The specialised `ender_pearls` skill decides the best method.

## 7.4 Do not waste pearls

Avoid using ender pearls for travel unless:

```text
you have extra pearls above 12
or it is an emergency
or a specialised skill says it is worth it
```

## 7.5 Done verification

Use:

```text
get_self_status
```

Confirm:

```text
minecraft:ender_pearl count >= 12
minecraft:blaze_rod count >= 7 or equivalent blaze powder available
alive
safe
```

Then:

```text
mark phase 4 completed
mark phase 5 in_progress
load_skill(name="containers") if crafting eyes through GUI is needed
load_skill(name="stronghold_finding")
```

---

# 8. Phase 5 overview: find stronghold and activate End portal

## 8.1 Skill to load

```text
load_skill(name="stronghold_finding")
```

Also load:

```text
load_skill(name="containers")
```

if crafting eyes or managing inventory through GUIs.

## 8.2 Goal

Find the stronghold, locate the End portal room, and activate the End portal.

Done when:

```text
standing at the activated End portal
portal frame filled with eyes
portal blocks are active
ready to enter the End
```

## 8.3 Required resources

Need:

```text
12 ender pearls
blaze powder from blaze rods
```

Crafting math:

```text
1 blaze rod -> 2 blaze powder
1 blaze powder + 1 ender pearl -> 1 eye of ender
12 eyes require 12 powder + 12 pearls
12 powder requires 6 rods
7 rods gives margin
```

## 8.4 Do not waste eyes if locate_structure is available

If the tool exists, use:

```text
locate_structure("minecraft:stronghold")
```

or the appropriate stronghold locate tool.

This avoids wasting eyes of ender by throwing them.

If the user explicitly wants vanilla eye-throwing navigation, then follow that constraint.

## 8.5 Stronghold hazards

Strongholds may include:

```text
silverfish
lava in portal room
dark corridors
mobs
maze-like layout
```

Before activating portal:

```text
clear immediate threats
do not fall into portal-room lava
do not lose track of portal room
```

## 8.6 Before entering the End portal

Do not enter immediately after activation unless the dragon packlist is complete.

Verify:

```text
diamond_sword or better
bow
32+ arrows, preferably 64+
128+ blocks, preferably cobblestone
32+ cooked food
armor equipped
HP >= 18
pickaxe available
golden_apple if available
```

If missing final-fight supplies:

```text
do not enter End
backfill supplies first
```

## 8.7 Done verification

Use:

```text
inspect_block / scan_blocks if needed
get_self_status
```

Confirm:

```text
End portal is active
you are standing at/near activated portal
HP safe
dragon packlist ready
```

Then:

```text
mark phase 5 completed
mark phase 6 in_progress
load_skill(name="combat_basics")
load_skill(name="dragon_combat")
```

---

# 9. Phase 6 overview: defeat the Ender Dragon

## 9.1 Skill to load

```text
load_skill(name="combat_basics")
load_skill(name="dragon_combat")
```

## 9.2 Goal

Kill the Ender Dragon.

Done when:

```text
Ender Dragon HP reaches 0
death animation plays
central exit portal opens
dragon egg appears
```

## 9.3 Final packlist

Before entering End portal, verify:

```text
diamond_sword or better
bow
32+ arrows minimum, 64+ preferred
128+ cobblestone or other solid blocks
32+ cooked food
armor equipped
HP high
pickaxe available
golden_apple if available
```

## 9.4 High-level dragon fight plan

```text
1. Enter End.
2. Move from obsidian spawn platform to central island.
3. Destroy open End crystals with shoot.
4. Open caged crystals and shoot them from range.
5. Verify all crystals are destroyed.
6. Shoot dragon while flying.
7. Hunt dragon with sword while perched.
8. Eat and reposition between phases.
9. Avoid void and dragon breath.
10. Repeat until dragon dies.
```

## 9.5 Done verification

Dragon fight is complete when:

```text
dragon HP = 0
exit portal opens
dragon egg appears
you are alive
```

Then:

```text
mark phase 6 completed
mark overall dragon route completed
congratulate owner
```

---

# 10. Phase selection algorithm

Use this whenever the owner asks for a dragon-route goal and you need to decide what to do next.

## 10.1 First inspect current state

Always start:

```text
get_self_status
```

Record:

```text
game_mode
dimension
coordinates
HP
equipment
inventory counts
important items
```

## 10.2 If creative mode

If:

```text
game_mode=creative
```

then:

```text
load_skill(name="creative_mode")
```

Then ask whether owner wants:

```text
creative shortcut
or legit survival-style route
```

if ambiguous and it matters.

## 10.3 If already in the End

If:

```text
dimension == The End
dragon alive or unknown
```

then:

```text
load_skill(name="combat_basics")
load_skill(name="dragon_combat")
```

If dragon already dead:

```text
verify exit portal/egg
mark phase 6 completed
```

## 10.4 If at activated End portal

If:

```text
standing at activated End portal
and final packlist is ready
```

then:

```text
mark phases 1-5 completed if verified or already satisfied
mark phase 6 in_progress
load_skill(name="dragon_combat")
```

If packlist missing:

```text
backfill gear/food/arrows/blocks before entering
```

## 10.5 If stronghold found but portal not active

If:

```text
in stronghold/portal room
but portal inactive
```

then:

```text
load_skill(name="stronghold_finding")
```

Need:

```text
eyes of ender
safe portal room
activation
```

## 10.6 If have rods and pearls but no stronghold

If:

```text
blaze_rod >= 7 or blaze_powder enough
and ender_pearl >= 12
and not at activated portal
```

then:

```text
load_skill(name="stronghold_finding")
```

## 10.7 If have rods but not pearls

If:

```text
blaze_rod >= 7
and ender_pearl < 12
```

then:

```text
load_skill(name="combat_basics")
load_skill(name="ender_pearls")
```

## 10.8 If in Nether and rods missing

If:

```text
dimension == Nether
and blaze_rod < 7
```

then:

```text
load_skill(name="combat_basics")
load_skill(name="blaze_rods")
```

## 10.9 If not in Nether and rods missing

If:

```text
dimension != Nether
and blaze_rod < 7
```

then check gear.

If Nether-entry gear exists:

```text
load_skill(name="nether_entry")
```

If gear is insufficient:

```text
load_skill(name="tier_progression")
```

## 10.10 If early game gear missing

If missing core gear:

```text
diamond_pickaxe
diamond_sword
armor
bow
arrows
food
blocks
```

then:

```text
load_skill(name="tier_progression")
```

---

# 11. Todo management

## 11.1 Full-route todo template

When starting a full dragon route, call `todowrite` with:

```text
[
  {content: "Tier up to dragon-route gear", status: "in_progress"},
  {content: "Build and enter Nether portal", status: "pending"},
  {content: "Collect at least 7 blaze rods", status: "pending"},
  {content: "Collect at least 12 ender pearls", status: "pending"},
  {content: "Find stronghold and activate End portal", status: "pending"},
  {content: "Defeat the Ender Dragon", status: "pending"}
]
```

## 11.2 Updating todos

When phase N is verified complete:

```text
mark phase N completed
mark phase N+1 in_progress
leave later phases pending
```

Never have:

```text
two phases in_progress
```

Never mark phase complete without verification.

## 11.3 If owner asks for a narrow goal

If owner says:

```text
just get blaze rods
just find the stronghold
just get to the Nether
just kill the dragon from here
```

Then make a shorter todo list for only the requested goal and required backfill.

Example:

```text
Owner: "just get to the Nether"
Todos:
1. Verify/prepare portal materials and gear
2. Build/light Nether portal
3. Enter Nether and verify safe arrival
```

Example:

```text
Owner: "kill the dragon" while standing at active End portal
Todos:
1. Verify dragon combat packlist
2. Enter End
3. Defeat Ender Dragon
```

---

# 12. Inventory verification rules

Use `get_self_status` for all route-critical inventory checks.

## 12.1 Important item IDs

Common route items:

```text
minecraft:diamond_pickaxe
minecraft:diamond_sword
minecraft:iron_helmet
minecraft:iron_chestplate
minecraft:iron_leggings
minecraft:iron_boots
minecraft:diamond_helmet
minecraft:diamond_chestplate
minecraft:diamond_leggings
minecraft:diamond_boots
minecraft:bow
minecraft:arrow
minecraft:cooked_beef
minecraft:cooked_porkchop
minecraft:bread
minecraft:cobblestone
minecraft:obsidian
minecraft:flint_and_steel
minecraft:blaze_rod
minecraft:blaze_powder
minecraft:ender_pearl
minecraft:ender_eye
minecraft:golden_apple
```

## 12.2 Armor verification

Armor is better if equipped, not just in inventory.

If armor exists but is not equipped:

```text
equip_item armor_piece
get_self_status
```

Confirm equipped armor before dangerous phases.

## 12.3 Food verification

Preferred food:

```text
cooked_beef
cooked_porkchop
```

Acceptable:

```text
bread
cooked_chicken
cooked_mutton
cooked_salmon
cooked_cod
```

Bad:

```text
raw meat
rotten_flesh
spider_eye
```

If food is low before Nether/End:

```text
backfill food first
```

## 12.4 Blocks verification

Blocks are needed for:

```text
Nether travel
End spawn platform bridging
pillaring to caged crystals
emergency cover
```

Good blocks:

```text
cobblestone
dirt
netherrack
stone
deepslate
end_stone
```

Preferred before dragon:

```text
128+ cobblestone or equivalent solid blocks
```

## 12.5 Eye of ender math

Use:

```text
12 ender_pearl
12 blaze_powder
```

to craft:

```text
12 ender_eye
```

Need:

```text
6 blaze rods minimum
7 rods recommended
```

Do not consume pearls/eyes unnecessarily before the portal is activated.

---

# 13. Tool overview for autonomous route execution

You have enough tools to execute the route autonomously.

## 13.1 Movement and navigation

Use:

```text
move_to
```

for:

```text
traveling to caves
returning to base
moving through Nether
reaching fortress
reaching stronghold
bridging/pillaring when navigation supports it
retreating from combat
```

Important:

```text
move_to navigation digs with the held tool.
If you need to dig through stone, hold a pickaxe.
After combat, re-equip pickaxe before navigation.
```

## 13.2 Mining

Use:

```text
auto_mine
```

for finding and mining block types.

Use:

```text
break_block
```

for precise corrections.

Do not mine dangerous blocks carelessly:

```text
blocks under feet
blocks holding back lava
spawners needed for farming
End crystals by hand
```

## 13.3 Combat

Use:

```text
hunt
shoot
eat_item
equip_item
```

Load:

```text
combat_basics
```

before combat-heavy phases.

## 13.4 Interaction

Use:

```text
interact_at
interact_entity
```

for:

```text
opening chests/crafting tables/furnaces
lighting portals with flint and steel
placing eyes into End portal frames
using levers/buttons/doors
trading/interacting if needed
```

## 13.5 Containers and crafting

Use:

```text
plan_crafting
craft_items
interact_at
inspect_gui
transfer
close_gui
lookup_recipe
```

Prefer `craft_items` for supported vanilla crafting and cooking because it uses real workstations/fuel and survives pause, timeout, and server restart. Use GUI operations for looting, unsupported machines, exact transfers, and diagnostics.

Load:

```text
containers
```

for detailed GUI handling.

## 13.6 Structure location

Use:

```text
locate_structure
```

for:

```text
minecraft:fortress
minecraft:stronghold
villages if needed
other route-relevant structures
```

If the owner wants pure vanilla navigation without structure locate tools, follow that constraint.

## 13.7 Perception

Use:

```text
get_self_status
get_world_info
scan_blocks
scan_nearby_entities
inspect_block
```

for:

```text
inventory
HP
dimension
coordinates
nearby mobs
nearby blocks
hazards
portal/frame states
```

---

# 14. Skill loading guide

Use this exact guide.

## 14.1 Starting from fresh world

If no gear:

```text
load_skill(name="tier_progression")
```

## 14.2 Need Nether entry

If gear mostly exists but not in Nether:

```text
load_skill(name="nether_entry")
```

## 14.3 Need blaze rods

If in Nether or ready for Nether and rods < 7:

```text
load_skill(name="combat_basics")
load_skill(name="blaze_rods")
```

## 14.4 Need ender pearls

If rods complete and pearls < 12:

```text
load_skill(name="combat_basics")
load_skill(name="ender_pearls")
```

## 14.5 Need stronghold/portal

If rods and pearls are complete:

```text
load_skill(name="stronghold_finding")
```

Also:

```text
load_skill(name="containers")
```

if crafting eyes or managing inventory.

## 14.6 Need dragon kill

If standing at active End portal with final packlist:

```text
load_skill(name="combat_basics")
load_skill(name="dragon_combat")
```

---

# 15. Recovery and failure handling

## 15.1 If you die

Do not continue as if nothing happened.

Immediately:

```text
get_self_status
```

Determine:

```text
where you respawned
what inventory remains
whether gear was lost
whether death location is recoverable
which phase was in progress
```

If items can be recovered:

```text
recover items
get_self_status
verify phase requirements again
resume the same phase
```

If items are lost:

```text
move todo state back to the earliest phase needed to rebuild supplies
```

Example:

```text
Died in Nether and lost bow/armor/food.
Return to phase 1 or phase 2 prep as needed.
```

## 15.2 If lost in the Nether

Use:

```text
get_self_status
```

Compare to recorded portal coordinates.

If portal coordinates are known:

```text
move_to portalX portalY portalZ
```

If unknown:

```text
use navigation/recovery strategy
do not wander randomly while low on food/HP
```

## 15.3 If blaze rod count is short

If phase 3 was marked complete incorrectly:

```text
set phase 3 back to in_progress
load blaze_rods
farm until get_self_status shows >=7 rods
```

## 15.4 If ender pearl count is short

If pearls < 12:

```text
set phase 4 back to in_progress
load ender_pearls
```

Do not activate portal plans with too few pearls/eyes unless portal frames are already partly filled and verified.

## 15.5 If End portal is missing eyes

If portal not active:

```text
inspect portal frames
count missing eyes
craft/obtain enough eyes
interact_at frames to fill them
verify portal blocks appear
```

## 15.6 If final packlist is weak

If at End portal but missing food/arrows/blocks:

```text
do not enter End
backfill supplies
return to portal room
verify again
```

## 15.7 If dragon fight fails but dragon survives

If not void-dead and gear recoverable:

```text
recover gear
re-verify dragon packlist
reload dragon_combat
return through End portal
```

The dragon may retain damage and destroyed crystals may remain destroyed, but do not assume inventory is intact.

---

# 16. Owner narrowing rules

The owner may ask for a smaller goal.

## 16.1 "Just get to the Nether"

Skip dragon-route phases after phase 2.

Use:

```text
tier_progression if gear/materials missing
nether_entry
```

Done when:

```text
dimension == Nether
safe arrival
portal coordinates recorded
```

## 16.2 "Just get blaze rods"

Check current state.

If not in Nether or not geared:

```text
backfill nether_entry/tier_progression
```

Then:

```text
combat_basics
blaze_rods
```

Done when:

```text
blaze_rod >= requested count, default >=7
```

## 16.3 "Just get ender pearls"

If combat gear/food is missing:

```text
backfill gear/food
```

Then:

```text
combat_basics
ender_pearls
```

Done when:

```text
ender_pearl >= requested count, default >=12
```

## 16.4 "Just find the stronghold"

Check:

```text
ender_eye count
blaze rods/powder
pearls
gear/food
```

If stronghold locate tools are allowed:

```text
use stronghold_finding
```

Done when:

```text
stronghold found
or portal room found
depending on user's exact request
```

## 16.5 "Just activate the End portal"

Need:

```text
stronghold portal room
enough eyes of ender for missing frames
```

Done when:

```text
End portal blocks are active
```

Do not enter unless asked.

## 16.6 "Just kill the dragon"

If at activated End portal and ready:

```text
load dragon_combat
```

If not ready:

```text
backfill missing gear/food/arrows/blocks/eyes/portal
```

---

# 17. Full-route execution algorithm

Use this algorithm for the canonical "defeat the Ender Dragon" request.

## 17.1 Initialize

```text
get_self_status
todowrite six phases
determine current phase from inventory/dimension/world state
mark exactly one phase in_progress
```

## 17.2 Phase loop

Repeat until dragon route complete:

```text
get_self_status
identify current incomplete phase
load only that phase's skill
execute the phase skill
verify done condition
if done:
    todowrite phase completed
    todowrite next phase in_progress
else:
    continue or recover
```

## 17.3 Never skip verification

Before advancing:

```text
get_self_status
```

For world-state phases also verify:

```text
Nether dimension
active End portal
dragon death/exit portal
```

## 17.4 Report progress briefly

After each completed phase, report short progress:

```text
Phase 3 complete — collected 7 blaze rods. Moving to ender pearls.
```

Do not over-explain unless owner asks.

---

# 18. Phase completion proof checklist

Use this checklist before todo updates.

## 18.1 Phase 1 proof

```text
diamond_pickaxe present
diamond_sword present
armor present/equipped
bow present
arrows >= 32
food >= 32 preferred
blocks >= 64
```

## 18.2 Phase 2 proof

```text
dimension == Nether
portal coords recorded
packlist still intact
safe
```

## 18.3 Phase 3 proof

```text
blaze_rod >= 7
safe
preferably returned to portal/staging point
```

## 18.4 Phase 4 proof

```text
ender_pearl >= 12
blaze rods/powder still sufficient
safe
```

## 18.5 Phase 5 proof

```text
stronghold portal found
End portal activated
standing at/near active portal
dragon packlist verified
```

## 18.6 Phase 6 proof

```text
dragon HP = 0
death animation occurred
exit portal opened
dragon egg appeared
alive
```

---

# 19. Common mistakes and fixes

## Mistake: Loading all skills at once

Fix:

```text
Only load the skill for the current phase.
```

## Mistake: Marking phase complete without inventory check

Fix:

```text
get_self_status
verify exact item count
then update todo
```

## Mistake: Entering Nether without food/blocks

Fix:

```text
return/backfill supplies before fortress work
```

## Mistake: Forgetting portal coordinates

Fix:

```text
Immediately after Nether entry, record coordinates from get_self_status.
```

## Mistake: Leaving with only 6 blaze rods

Fix:

```text
reload blaze_rods
farm until blaze_rod >= 7
```

## Mistake: Spending ender pearls before crafting eyes

Fix:

```text
do not use pearls for travel unless you have extras above 12
```

## Mistake: Throwing eyes when locate_structure is available

Fix:

```text
use locate_structure("minecraft:stronghold")
save eyes for portal activation
```

## Mistake: Entering End underprepared

Fix:

```text
stop at active portal
verify dragon packlist
backfill food/arrows/blocks before entering
```

## Mistake: Fighting dragon before crystals are destroyed

Fix:

```text
load dragon_combat
destroy crystals first
```

## Mistake: Continuing after death as if inventory is intact

Fix:

```text
get_self_status
recover/rebuild gear
reset todo phase if needed
```

---

# 20. Final completion

When the dragon is dead:

```text
todowrite phase 6 completed
todowrite overall route completed if using a parent plan
```

Final response:

```text
Done — the Ender Dragon is defeated.
The exit portal is open and the dragon egg appeared.
Congratulations!
```

If waiting at the fountain:

```text
I am at the central fountain and ready to exit when you are.
```

Do not punch the dragon egg unless the owner asks.

---

# 21. Quick start

If this is a fresh world or gear is missing:

```text
load_skill(name="tier_progression")
```

If gear is ready and the goal is the full dragon route:

```text
todowrite six phases
mark phase 1 completed if verified
start the earliest incomplete phase
```

Highest-priority reminders:

```text
1. This is the route map, not the detailed phase tactic.
2. Use todowrite.
3. Keep one phase in_progress.
4. Load only the current phase skill.
5. Verify with get_self_status before completing phases.
6. Backfill missing requirements.
7. Use combat_basics for combat phases.
8. Use containers for crafting/smelting/GUI work.
9. Do not enter the End until dragon_combat packlist is verified.
10. Complete the route only after the dragon dies.
```
