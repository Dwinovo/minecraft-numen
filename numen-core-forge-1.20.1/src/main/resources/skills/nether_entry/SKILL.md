---
name: nether_entry
description: Phase 2 of the dragon route. Acquire at least 10 normal obsidian from ruined portals, craft or obtain flint and steel, build a 10-block Nether portal frame with place_block, ignite it with interact_at, enter the Nether, record both portal coordinates, and verify the Nether packlist is intact.
---

# Skill: nether_entry

This is Phase 2 of the dragon route.

Goal:

```text
Acquire 10 normal obsidian.
Build a valid Nether portal frame.
Ignite the portal.
Record the Overworld portal coordinates.
Enter the Nether.
Record the Nether-side portal coordinates.
Verify the packlist is intact.
```

Actual Nether survival and fortress work starts in:

```text
blaze_rods
```

This skill ends once you are safely standing in the Nether with the right supplies.

---

# 0. Completion condition

This skill is complete only when all of these are true:

```text
A lit Nether portal exists at a known Overworld location.
The Overworld portal coordinates have been recorded and reported to the owner.
You have entered the Nether.
get_self_status reports dimension == Nether.
The Nether-side portal coordinates have been recorded.
The required packlist is still present.
You are alive and not in immediate danger.
```

Do not mark this phase complete if:

```text
portal frame is built but not lit
portal is lit but you have not entered
you entered but did not record Nether-side coordinates
you entered with missing food/weapon/blocks/flint_and_steel
you are under attack and unsafe
you used crying obsidian in the required frame
```

---

# 1. Absolute priority rules

## 1.1 Use ruined portals for obsidian

For this route, get obsidian from:

```text
ruined portals
```

Do not use lava-casting as the planned route.

Reason:

```text
Freshly cast obsidian is usually fluid-adjacent.
Mining fluid-adjacent obsidian can release lava/water, burn items, flood tunnels, or trap you.
This agent should not mine blocks that are directly against dangerous fluids.
```

Correct:

```text
locate_structure("#minecraft:ruined_portal")
move_to ruined portal
auto_mine normal obsidian
```

Incorrect:

```text
cast obsidian with water over lava
then try to mine the new lava-adjacent obsidian wall
```

## 1.2 Only normal obsidian counts

A portal frame requires:

```text
minecraft:obsidian
```

It cannot use:

```text
minecraft:crying_obsidian
```

Crying obsidian has purple cracks/particles and is useless for the Nether portal frame.

`auto_mine("minecraft:obsidian")` should ignore crying obsidian automatically.

## 1.3 Need exactly 10 normal obsidian for the efficient frame

Minimum valid frame:

```text
4 blocks wide
5 blocks tall
corners omitted
10 obsidian total
inner opening is 2 wide x 3 tall
```

Do not place corners unless you intentionally have extra obsidian.

Corners are optional for a portal, but this route plans the 10-block frame.

## 1.4 Verify packlist before entering

Before igniting/entering the portal, use:

```text
get_self_status
```

Verify:

```text
food
weapon
bow
arrows
pickaxe
blocks
gold helmet or gold armor
flint_and_steel
armor
```

Do not enter the Nether unprepared.

## 1.5 Record portal coordinates

The portal is the way home.

Record and report:

```text
Overworld portal coordinates
Nether portal coordinates after arrival
```

Use:

```text
get_self_status
```

immediately before/after entering.

## 1.6 Never use a bed in the Nether

Beds explode in the Nether.

Do not:

```text
place bed in Nether
use bed in Nether
try to sleep in Nether
```

If a bed is in inventory, leave it unused.

---

# 2. Required support skills

## 2.1 `containers`

Load when crafting or looting containers:

```text
load_skill(name="containers")
```

Use for:

```text
planning/crafting flint_and_steel with real materials
planning/crafting gold helmet with real materials
looting ruined portal chest
moving exact item counts
```

For supported vanilla recipes, use `plan_crafting` then `craft_items`. Use manual crafting-grid transfers only as fallback or diagnosis.

## 2.2 `combat_basics`

Load if hostile mobs interfere:

```text
load_skill(name="combat_basics")
```

Use for:

```text
zombies
skeletons
spiders
ghasts after entry
piglin problems
Nether arrival danger
```

## 2.3 `creative_mode`

If:

```text
get_self_status reports game_mode=creative
```

then:

```text
load_skill(name="creative_mode")
```

In creative mode, if shortcuts are allowed, the portal can be built directly with creative items/commands. If the owner requested legit survival-style play, still follow this skill.

---

# 3. Tools used in this skill

| Tool | Use |
|---|---|
| `get_self_status` | Check inventory, HP, dimension, coordinates, equipped items. |
| `locate_structure` | Find nearest ruined portal. |
| `move_to` | Travel to ruined portal, base portal site, and enter portal. |
| `auto_mine` | Mine obsidian/gravel safely. |
| `equip_item` | Equip diamond pickaxe, flint and steel, armor, sword, etc. |
| `inspect_block` | Verify blocks before placing, lighting, or mining. |
| `scan_blocks` | Find chest/obsidian/portal blocks nearby. |
| `place_block` | Build the Nether portal frame block-by-block. |
| `interact_at` | Open chests, ignite portal with flint and steel, use blocks. |
| `lookup_recipe` | Get crafting recipe for flint_and_steel/gold helmet if needed. |
| `plan_crafting` | Recursively check whether required crafted items are possible and list missing base materials. |
| `craft_items` | Craft supported items with real inventory use and restart-safe execution. |
| `inspect_gui` | Read crafting/container slots. |
| `transfer` | Loot chests or manually craft only when automatic crafting is unsupported. |
| `close_gui` | Close crafting/container GUI. |
| `wait` | Wait in portal until dimension changes. |

---

# 4. Phase start decision tree

Start with:

```text
get_self_status
```

Then choose the correct subtask.

## 4.1 Already in the Nether

If:

```text
dimension == Nether
```

then this phase may already be complete.

Verify:

```text
food available
weapon available
bow/arrows available
blocks available
pickaxe available
flint_and_steel available
Nether portal coordinates known or recorded now
```

If packlist is intact:

```text
record Nether-side portal coordinates
mark phase 2 complete
load blaze_rods
```

If packlist is not intact:

```text
return/recover supplies before starting blaze_rods
```

## 4.2 Already have a lit Overworld portal

If standing near a lit portal:

```text
record Overworld portal coordinates
verify packlist
enter portal
record Nether portal coordinates
```

## 4.3 Have 10+ obsidian but no lit portal

If inventory has:

```text
minecraft:obsidian >= 10
```

then:

```text
craft/obtain flint_and_steel if needed
build portal frame
ignite
enter
```

## 4.4 Have fewer than 10 obsidian

If obsidian count is:

```text
< 10
```

then:

```text
locate ruined portal
mine/loot obsidian until count >= 10
```

## 4.5 Missing diamond pickaxe

If no:

```text
minecraft:diamond_pickaxe
```

then do not start obsidian mining.

Backfill with:

```text
load_skill(name="tier_progression")
```

Obsidian requires diamond pickaxe or better.

---

# 5. Packlist before entering the Nether

Verify with:

```text
get_self_status
```

before igniting or entering the portal.

## 5.1 Required packlist

| Item | Minimum | Preferred | Why |
|---|---:|---:|---|
| Cooked food | 16 | 32+ | Direct healing through `eat_item`. |
| Diamond sword | 1 | 1 | Combat, blazes/endermen later. |
| Bow | 1 | 1 | Blazes, ghasts, later dragon route. |
| Arrows | 32 | 48-64+ | `shoot` consumes arrows. |
| Diamond pickaxe | 1 | 1 | Obsidian and navigation digging. |
| Backup pickaxe | optional | iron_pickaxe x1 | If main pickaxe breaks/gets misplaced. |
| Cobblestone/solid blocks | 64 | 128+ | Bridging, blocking lava, scaffolding, shelter. |
| Gold helmet/gold armor | 1 worn piece | gold_helmet worn | Piglin truce. |
| Flint and steel | 1 | 1 + spare flint/iron optional | Light/re-light portal. |
| Armor | iron-or-better | mostly iron/diamond + gold helmet | Survival in Nether. |

## 5.2 Food

Preferred:

```text
minecraft:cooked_beef
minecraft:cooked_porkchop
```

Acceptable:

```text
minecraft:bread
minecraft:cooked_chicken
minecraft:cooked_mutton
minecraft:cooked_salmon
minecraft:cooked_cod
```

Avoid relying on:

```text
raw meat
rotten_flesh
spider_eye
```

## 5.3 Blocks

Good Nether travel blocks:

```text
minecraft:cobblestone
minecraft:stone
minecraft:deepslate
minecraft:dirt
minecraft:netherrack
```

Cobblestone is preferred because ghasts cannot break it with fireballs.

## 5.4 Gold armor

A gold helmet is the standard packlist item.

Any worn gold armor piece can satisfy piglin neutrality, but this skill expects:

```text
minecraft:golden_helmet
```

If no gold helmet but enough gold ingots:

```text
craft golden_helmet using 5 gold ingots
equip_item golden_helmet
get_self_status
```

## 5.5 Held item before travel

For normal travel/navigation:

```text
equip_item diamond_pickaxe
```

Reason:

```text
navigation digs with the held tool
pickaxe lets navigation break stone/netherrack/obsidian-compatible blocks where appropriate
```

For combat only:

```text
equip_item diamond_sword
equip_item bow
```

After combat, re-equip pickaxe.

---

# 6. Finding ruined portals

## 6.1 Locate structure

Use:

```text
locate_structure("#minecraft:ruined_portal")
```

This should search the ruined portal structure family.

Record:

```text
ruinedPortalX
ruinedPortalY if provided
ruinedPortalZ
direction
distance
structure variant if provided
```

## 6.2 Skip ocean ruined portals

If the result is:

```text
ruined_portal_ocean
```

or obviously underwater:

```text
skip it
```

Reason:

```text
this route does not rely on diving
underwater mining/interacting is slower and riskier
```

If an ocean portal is returned:

```text
move away inland or to another region
locate_structure("#minecraft:ruined_portal") again
```

Do not spend time fighting underwater access.

## 6.3 Move to the ruined portal

Before moving:

```text
equip_item diamond_pickaxe
```

Then:

```text
move_to x=<ruinedPortalX> y=<safeY_if_needed> z=<ruinedPortalZ>
```

If Y is approximate or missing:

```text
move_to x=<ruinedPortalX> z=<ruinedPortalZ>
```

After arrival:

```text
get_self_status
scan_blocks("minecraft:obsidian", radius=32)
scan_blocks("minecraft:crying_obsidian", radius=32)
scan_blocks("minecraft:chest", radius=32)
```

---

# 7. Looting ruined portal chest

Ruined portals often have a chest.

It may contain useful items:

```text
obsidian
flint_and_steel
flint
gold ingots
gold armor
iron nuggets
fire charges
food sometimes
```

## 7.1 Find chest

Use:

```text
scan_blocks("minecraft:chest", radius=32)
```

If found:

```text
load_skill(name="containers")
interact_at button=right x=<chestX> y=<chestY> z=<chestZ>
inspect_gui
```

## 7.2 Take useful items

Useful items include:

```text
minecraft:obsidian
minecraft:flint_and_steel
minecraft:flint
minecraft:gold_ingot
minecraft:golden_helmet
minecraft:golden_boots
minecraft:fire_charge
minecraft:iron_nugget
minecraft:iron_ingot
```

Use `transfer` from the chest slots to inventory.

Example pattern:

```text
transfer moves=[{from:<obsidian_slot>}, {from:<flint_and_steel_slot>}, {from:<gold_slot>}]
close_gui
```

Do not waste time taking useless items unless inventory has room and owner wants loot.

## 7.3 Re-check obsidian count

After looting:

```text
get_self_status
```

If:

```text
obsidian >= 10
```

you may not need to mine more.

If:

```text
obsidian < 10
```

mine normal obsidian from the ruined frame.

---

# 8. Mining obsidian from ruined portal

## 8.1 Equip diamond pickaxe

Before mining:

```text
equip_item "minecraft:diamond_pickaxe"
get_self_status
```

Confirm diamond pickaxe exists and is held.

## 8.2 Mine normal obsidian only

Use:

```text
auto_mine("minecraft:obsidian", count=10, radius=24)
```

or equivalent syntax:

```text
auto_mine block_id="minecraft:obsidian" count=10 radius=24
```

Important:

```text
normal obsidian counts
crying obsidian does not count
```

Mining obsidian takes time.

Expected:

```text
about 9.4 seconds per block with diamond pickaxe
```

Do not assume failure just because mining is slow.

## 8.3 Stop at 10 total obsidian

After mining:

```text
get_self_status
```

Check:

```text
minecraft:obsidian count
```

Need:

```text
>= 10
```

Do not mine unnecessary dangerous blocks once 10 is reached.

## 8.4 If portal has fewer than 10 obsidian

Ruined portals often contain:

```text
normal obsidian
crying obsidian
missing blocks
lava pockets
buried blocks
```

If after mining/looting:

```text
obsidian < 10
```

then:

```text
locate_structure("#minecraft:ruined_portal") again
travel to another land ruined portal
top up obsidian
```

Do not use crying obsidian.

## 8.5 If auto_mine skips fluid-adjacent blocks

If result says blocks were skipped because of:

```text
lava
water
fluid adjacency
dangerous access
```

then:

```text
abandon that ruined portal's dangerous blocks
find another ruined portal
```

Do not force mining next to lava/water.

## 8.6 Avoid ruined portal hazards

Ruined portals may have:

```text
lava pockets
magma blocks
gold blocks
hostile mobs
netherrack/fire
uneven terrain
```

Rules:

```text
do not step into lava
do not mine blocks holding back lava
do not dig straight down around the portal
clear hostile mobs if necessary
```

---

# 9. Getting flint and steel

A Nether portal needs ignition.

Acceptable ignition items:

```text
minecraft:flint_and_steel
minecraft:fire_charge if supported
```

This skill expects:

```text
minecraft:flint_and_steel
```

## 9.1 Check if already available

Use:

```text
get_self_status
```

If inventory contains:

```text
minecraft:flint_and_steel
```

skip crafting.

## 9.2 Recipe

Flint and steel requires:

```text
1 minecraft:iron_ingot
1 minecraft:flint
```

Crafting grid:

```text
2x2 inventory grid is enough
```

Use:

```text
load_skill(name="containers")
lookup_recipe item_id="minecraft:flint_and_steel"
inspect_gui
transfer ingredients into grid
transfer result to inventory
```

It is commonly a shapeless recipe, but always follow `lookup_recipe`.

## 9.3 Getting flint

Flint comes from gravel.

Use:

```text
auto_mine("minecraft:gravel", count=<enough>, radius=<reasonable>)
collect_items
get_self_status
```

Gravel drops flint randomly.

Approximate drop chance:

```text
about 10% without Fortune
```

If you need 1 flint, expect to mine multiple gravel blocks.

Repeat until:

```text
minecraft:flint >= 1
```

## 9.4 Getting iron ingot

Phase 1 should already have iron.

If no iron ingot exists but you have iron nuggets:

```text
craft 9 nuggets -> 1 iron ingot
```

If no iron at all:

```text
backfill with tier_progression
```

Do not enter Nether without basic iron/diamond progression.

## 9.5 Crafting flint and steel

General GUI pattern:

```text
load_skill(name="containers")
close_gui if another GUI is open
inspect_gui
lookup_recipe item_id="minecraft:flint_and_steel"
transfer moves=[
  {from:<iron_ingot_slot>, to:<grid_cell_1>, count:1},
  {from:<flint_slot>, to:<grid_cell_2>, count:1}
]
transfer moves=[{from:<result_slot>}]
get_self_status
```

If recipe is shapeless, any two valid grid cells should work. Still prefer the layout from `lookup_recipe`.

---

# 10. Crafting and equipping gold helmet

Piglins are neutral if you wear one piece of gold armor.

This skill expects:

```text
golden_helmet
```

## 10.1 Check if already worn or available

Use:

```text
get_self_status
```

If a gold helmet is in inventory but not worn:

```text
equip_item "minecraft:golden_helmet"
get_self_status
```

## 10.2 Craft if needed

Golden helmet recipe:

```text
gold gold gold
gold empty gold
```

Needs:

```text
5 minecraft:gold_ingot
3x3 crafting table
```

If no crafting table, craft/place one with `containers` skill.

Then:

```text
lookup_recipe item_id="minecraft:golden_helmet"
interact_at button=right x=<craftingTableX> y=<craftingTableY> z=<craftingTableZ>
inspect_gui
transfer gold into recipe grid
transfer result to inventory
close_gui
equip_item "minecraft:golden_helmet"
get_self_status
```

## 10.3 If using different gold armor

If you have gold boots/chestplate/leggings but no helmet:

```text
equip one gold armor piece
```

Any gold armor helps with piglins.

Helmet is preferred by this route, but any worn gold piece is better than none.

---

# 11. Choosing portal build site

Pick a safe Overworld location.

Good portal site:

```text
near base or known safe area
flat ground
not underwater
not inside a village house
not on a cliff edge
not next to lava
not inside dense trees
has room around the frame
easy to find again
```

Avoid:

```text
deep caves
ravine edges
underwater
mob-heavy dark areas
inside important builds
right against flammable structures
```

## 11.1 Record build site

Before building:

```text
get_self_status
```

Choose and record:

```text
overworldPortalX
overworldPortalY
overworldPortalZ
```

Report these coordinates to the owner after the portal is lit.

## 11.2 Clear space

Need space for:

```text
4 wide x 5 tall vertical frame
2 wide x 3 tall internal opening
standing room in front and behind
```

If blocks are in the way:

```text
break_block individual obstructions
```

Do not destroy owner builds or valuables unless asked.

---

# 12. Nether portal frame geometry

## 12.1 Minimum 10-block portal

A valid efficient frame:

```text
width: 4
height: 5
corners omitted
inner opening: 2 wide x 3 tall
normal obsidian blocks used: 10
```

Visual front view:

```text
  O O
O     O
O     O
O     O
  O O
```

Where:

```text
O = minecraft:obsidian
spaces = air/portal opening
corners = omitted
```

## 12.2 Coordinate definitions

Default portal plane:

```text
vertical frame lies in constant Z plane
width runs along X
height runs along Y
```

Variables:

```text
baseX = left side of the 4-wide frame
baseY = bottom row Y
baseZ = portal plane Z
```

Frame occupies:

```text
x = baseX .. baseX+3
y = baseY .. baseY+4
z = baseZ
```

Inner opening is:

```text
x = baseX+1 .. baseX+2
y = baseY+1 .. baseY+3
z = baseZ
```

## 12.3 The 10 required blocks

Place obsidian at:

```text
bottom row:
(baseX+1, baseY,   baseZ)
(baseX+2, baseY,   baseZ)

left side:
(baseX,   baseY+1, baseZ)
(baseX,   baseY+2, baseZ)
(baseX,   baseY+3, baseZ)

right side:
(baseX+3, baseY+1, baseZ)
(baseX+3, baseY+2, baseZ)
(baseX+3, baseY+3, baseZ)

top row:
(baseX+1, baseY+4, baseZ)
(baseX+2, baseY+4, baseZ)
```

Total:

```text
2 + 3 + 3 + 2 = 10 obsidian
```

## 12.4 Do not put crying obsidian in these positions

Every required frame position must be:

```text
minecraft:obsidian
```

Not:

```text
minecraft:crying_obsidian
```

Corners may be air, dirt, stone, or anything else, because they are not part of the required frame.

---

# 13. Building the portal frame

## 13.1 Default frame placement commands

Use `place_block` because the 10-block frame omits corners and is not one solid rectangle.

For portal plane at constant `z=baseZ`:

```text
place_block block_id="minecraft:obsidian" x=<baseX+1> y=<baseY>   z=<baseZ>
place_block block_id="minecraft:obsidian" x=<baseX+2> y=<baseY>   z=<baseZ>

place_block block_id="minecraft:obsidian" x=<baseX>   y=<baseY+1> z=<baseZ>
place_block block_id="minecraft:obsidian" x=<baseX>   y=<baseY+2> z=<baseZ>
place_block block_id="minecraft:obsidian" x=<baseX>   y=<baseY+3> z=<baseZ>

place_block block_id="minecraft:obsidian" x=<baseX+3> y=<baseY+1> z=<baseZ>
place_block block_id="minecraft:obsidian" x=<baseX+3> y=<baseY+2> z=<baseZ>
place_block block_id="minecraft:obsidian" x=<baseX+3> y=<baseY+3> z=<baseZ>

place_block block_id="minecraft:obsidian" x=<baseX+1> y=<baseY+4> z=<baseZ>
place_block block_id="minecraft:obsidian" x=<baseX+2> y=<baseY+4> z=<baseZ>
```

Replace placeholders with actual numbers.

## 13.2 Example frame

Example:

```text
baseX = 100
baseY = 64
baseZ = 200
```

Place:

```text
place_block block_id="minecraft:obsidian" x=101 y=64 z=200
place_block block_id="minecraft:obsidian" x=102 y=64 z=200

place_block block_id="minecraft:obsidian" x=100 y=65 z=200
place_block block_id="minecraft:obsidian" x=100 y=66 z=200
place_block block_id="minecraft:obsidian" x=100 y=67 z=200

place_block block_id="minecraft:obsidian" x=103 y=65 z=200
place_block block_id="minecraft:obsidian" x=103 y=66 z=200
place_block block_id="minecraft:obsidian" x=103 y=67 z=200

place_block block_id="minecraft:obsidian" x=101 y=68 z=200
place_block block_id="minecraft:obsidian" x=102 y=68 z=200
```

Inner opening:

```text
x=101..102
y=65..67
z=200
```

Ignition target:

```text
x=101 or 102
y=65
z=200
```

## 13.3 Alternative orientation

If the portal plane should be constant X instead of constant Z, width runs along Z.

Variables:

```text
baseX = portal plane X
baseY = bottom row Y
baseZ = left/front side of width
```

Required blocks:

```text
bottom row:
(baseX, baseY,   baseZ+1)
(baseX, baseY,   baseZ+2)

left side:
(baseX, baseY+1, baseZ)
(baseX, baseY+2, baseZ)
(baseX, baseY+3, baseZ)

right side:
(baseX, baseY+1, baseZ+3)
(baseX, baseY+2, baseZ+3)
(baseX, baseY+3, baseZ+3)

top row:
(baseX, baseY+4, baseZ+1)
(baseX, baseY+4, baseZ+2)
```

Use whichever orientation fits the terrain.

## 13.4 Verify frame before ignition

Use:

```text
inspect_block
```

on the 10 required frame positions if uncertain.

Confirm:

```text
all required blocks are minecraft:obsidian
inner opening is air
no crying obsidian in required frame positions
```

---

# 14. Igniting the portal

## 14.1 Equip flint and steel

```text
equip_item "minecraft:flint_and_steel"
get_self_status
```

Confirm it is present/held.

## 14.2 Ignite lower interior cell

Use:

```text
interact_at button=right x=<innerX> y=<baseY+1> z=<baseZ> item_id="minecraft:flint_and_steel"
```

For the example frame:

```text
interact_at button=right x=101 y=65 z=200 item_id="minecraft:flint_and_steel"
```

Target:

```text
an empty air cell inside the frame
preferably the lower inner opening
```

Do not aim at random outside blocks.

## 14.3 Verify portal lit

After ignition, inspect or observe:

```text
purple portal blocks appear in the 2x3 inner opening
```

If available:

```text
inspect_block x=<innerX> y=<baseY+1> z=<baseZ>
```

Expected:

```text
minecraft:nether_portal
```

or equivalent portal block.

## 14.4 If only fire appears but portal does not form

Likely causes:

```text
frame is invalid
crying obsidian is in required frame
inner opening is blocked
frame dimensions wrong
clicked outside frame
missing obsidian block
portal orientation/coordinates wrong
```

Fix:

```text
inspect all 10 required frame blocks
clear inner opening
replace crying_obsidian with normal obsidian
retry interact_at inside the frame
```

## 14.5 If flint and steel breaks or is missing

Fix:

```text
craft another flint_and_steel
or use fire_charge if available and supported
```

Do not enter Nether without a relight method if possible.

---

# 15. Entering the portal

## 15.1 Record Overworld portal coordinates first

Before entering:

```text
get_self_status
```

Record:

```text
overworldPortalX
overworldPortalY
overworldPortalZ
dimension = Overworld
```

Report to owner:

```text
Overworld Nether portal is at x=..., y=..., z=...
```

## 15.2 Move into portal block

Move into the purple portal interior.

Example:

```text
move_to x=<innerX> y=<baseY+1> z=<baseZ>
```

Then wait until dimension changes:

```text
wait 5
get_self_status
```

If still in Overworld:

```text
make sure you are standing inside portal blocks
wait again
get_self_status
```

## 15.3 Confirm Nether arrival

Completion requires:

```text
get_self_status reports dimension == Nether
```

After transition:

```text
do not sprint away immediately
first record portal coordinates
```

---

# 16. Nether arrival procedure

Immediately after arriving:

```text
get_self_status
```

Record:

```text
netherPortalX
netherPortalY
netherPortalZ
dimension = Nether
```

Report or remember:

```text
Nether-side portal is at x=..., y=..., z=...
```

## 16.1 Overworld-Nether coordinate relation

Horizontal coordinates map roughly:

```text
Nether X ~= Overworld X / 8
Nether Z ~= Overworld Z / 8

Overworld X ~= Nether X * 8
Overworld Z ~= Nether Z * 8
```

Y does not scale.

This is useful for navigation, but portal linking can adjust exact positions.

## 16.2 Check immediate danger

After arrival, check:

```text
near lava?
near cliff?
ghast firing?
piglins nearby?
zombified piglins nearby?
portal floating or exposed?
portal extinguished?
```

If danger is present:

```text
move carefully to safe solid ground
do not hit zombified piglins
do not attack piglins unless necessary
protect portal if needed
```

## 16.3 If portal is exposed to ghasts

Ghasts can extinguish portals.

If a ghast attacks:

```text
take cover
shoot ghast if necessary
keep flint_and_steel available
re-light portal if extinguished
```

If time/supplies allow, place cobblestone around the portal as protection.

Do not build a huge shelter in this skill unless needed; actual Nether survival continues in `blaze_rods`.

## 16.4 Verify packlist after arrival

Use:

```text
get_self_status
```

Confirm:

```text
food still available
diamond sword available
bow available
arrows >= 32 preferred
diamond pickaxe available
blocks >= 64 preferred
gold helmet/armor worn
flint_and_steel available
HP safe
```

If anything critical is missing:

```text
return to Overworld and restock if possible
```

---

# 17. Nether ground rules

These rules apply immediately after entry and continue into later skills.

## 17.1 Do not dig straight down

The Nether has:

```text
lava pockets
lava oceans
sudden drops
caves
```

Never dig straight down.

## 17.2 Water does not work

In the Nether:

```text
water buckets cannot be placed normally
```

Do not rely on water for:

```text
fall breaks
fire extinguishing
lava handling
enderman control
```

## 17.3 Do not hit zombified piglins

Zombified piglins are neutral until attacked.

If you hit one:

```text
nearby zombified piglins group-aggro
you can be swarmed
```

Rule:

```text
Never hunt zombified piglins.
Avoid accidental hits.
```

## 17.4 Wear gold for piglins

Piglins may attack if you are not wearing gold armor.

Before Nether work:

```text
equip gold helmet or other gold armor
get_self_status
```

Avoid:

```text
opening chests near piglins
mining gold near piglins
attacking piglins
entering bastions casually
```

## 17.5 Avoid bastions

Bastions are not required for Nether entry.

They contain:

```text
blackstone
gold
piglins
piglin brutes
```

Piglin brutes attack on sight.

Avoid unless a later skill explicitly requires it.

## 17.6 Ghasts

Ghasts:

```text
fly far away
shoot fireballs
can knock you off ledges
can extinguish portals
can break weak blocks
```

Use:

```text
bow/shoot
cover
cobblestone shelter
```

Do not fight ghasts on narrow bridges over lava.

## 17.7 Lava

Nether lava is everywhere.

Rules:

```text
do not fight near lava edges
do not mine blocks holding back lava
keep cobblestone stocked
use navigation bridges carefully
```

---

# 18. Full algorithm

Use this full algorithm for normal Phase 2.

## 18.1 Start

```text
get_self_status
```

If missing diamond pickaxe:

```text
load_skill(name="tier_progression")
stop nether_entry until pickaxe exists
```

If already have obsidian >= 10:

```text
skip to flint_and_steel / build portal
```

## 18.2 Get obsidian

```text
locate_structure("#minecraft:ruined_portal")
```

If ocean/underwater:

```text
skip and locate another land ruined portal
```

Then:

```text
equip_item "minecraft:diamond_pickaxe"
move_to ruined portal coordinates
scan_blocks("minecraft:chest", radius=32)
loot useful chest items if chest exists
get_self_status
```

If obsidian < 10:

```text
auto_mine("minecraft:obsidian", count=10, radius=24)
get_self_status
```

If still obsidian < 10:

```text
locate another ruined portal
repeat until obsidian >= 10
```

## 18.3 Get flint and steel

```text
get_self_status
```

If no flint_and_steel:

```text
ensure iron_ingot >= 1
ensure flint >= 1
if flint missing:
    auto_mine("minecraft:gravel")
    collect_items
    get_self_status
craft flint_and_steel using containers skill
```

## 18.4 Get/wear gold helmet

```text
get_self_status
```

If no gold armor worn:

```text
equip available gold armor
```

If no gold armor but gold_ingot >= 5:

```text
craft golden_helmet
equip_item "minecraft:golden_helmet"
```

## 18.5 Verify packlist

```text
get_self_status
```

Confirm:

```text
obsidian >= 10
flint_and_steel >= 1
food sufficient
diamond_sword
bow
arrows
diamond_pickaxe
blocks
gold armor worn
armor safe
```

## 18.6 Build portal

Choose safe site.

Record:

```text
baseX
baseY
baseZ
```

Place 10 obsidian:

```text
bottom two
left three
right three
top two
```

Do not place crying obsidian.

## 18.7 Ignite

```text
equip_item "minecraft:flint_and_steel"
interact_at button=right x=<innerX> y=<baseY+1> z=<baseZ> item_id="minecraft:flint_and_steel"
```

Verify portal blocks appear.

## 18.8 Enter

```text
get_self_status
record Overworld portal coordinates
move_to portal interior
wait
get_self_status
```

Repeat wait/check until:

```text
dimension == Nether
```

## 18.9 After arrival

```text
get_self_status
record Nether portal coordinates
verify packlist intact
```

If safe:

```text
mark phase 2 completed
load_skill(name="combat_basics")
load_skill(name="blaze_rods")
```

---

# 19. Common mistakes and fixes

## Mistake: Used crying obsidian

Problem:

```text
crying_obsidian cannot form a Nether portal
```

Fix:

```text
mine/obtain normal obsidian
replace crying obsidian in required frame positions
retry ignition
```

## Mistake: Only 9 obsidian

Fix:

```text
locate another ruined portal
auto_mine normal obsidian until count >= 10
```

## Mistake: Portal will not light

Check:

```text
frame has 10 required normal obsidian blocks
inner opening is 2x3 air
frame is vertical
no crying obsidian in required positions
interact_at targeted inside the frame
flint_and_steel exists
```

Fix any issue, then retry.

## Mistake: Clicked obsidian instead of interior

Fix:

```text
target lower inner air cell inside the frame
```

Example:

```text
interact_at button=right x=<baseX+1> y=<baseY+1> z=<baseZ> item_id="minecraft:flint_and_steel"
```

## Mistake: Ruined portal is underwater

Fix:

```text
skip ocean ruined portal
move away/re-locate a land ruined portal
```

## Mistake: Obsidian mining skipped due to lava/water

Fix:

```text
do not force fluid-adjacent mining
find another ruined portal
```

## Mistake: Entered Nether without recording Overworld portal

Fix:

```text
if possible, go back through portal
get_self_status at Overworld side
record coordinates
```

If not possible, at least record Nether-side coordinates and use 8:1 mapping to estimate Overworld side.

## Mistake: Portal blown out by ghast

Fix:

```text
equip_item flint_and_steel
interact_at inside portal frame to relight
take cover or kill ghast
```

## Mistake: Piglins attack

Check:

```text
gold armor worn?
did you attack/open chest/mine gold near piglins?
```

Fix:

```text
retreat
equip gold armor if missing
avoid further provocation
```

## Mistake: Accidentally hit zombified piglin

Fix:

```text
retreat far away
do not keep fighting the group unless unavoidable
recover HP
```

## Mistake: Tried to use a bed in Nether

Problem:

```text
beds explode in the Nether
```

Fix:

```text
never use/place beds in Nether
```

---

# 20. Narrow goal handling

If the owner asks only:

```text
"build a Nether portal"
```

then completion may be:

```text
lit portal built and coordinates reported
```

Do not enter unless asked.

If the owner asks:

```text
"go to the Nether"
```

then completion is:

```text
standing in Nether safely with coordinates recorded
```

If the owner asks full dragon route:

```text
continue to blaze_rods after this phase
```

---

# 21. Final response after completion

When this phase is complete, report:

```text
Phase 2 complete — Nether portal built and entered.
Overworld portal: x=..., y=..., z=...
Nether portal: x=..., y=..., z=...
Packlist verified and ready for blaze rods.
```

Then load next skills:

```text
load_skill(name="combat_basics")
load_skill(name="blaze_rods")
```

---

# 22. Highest-priority reminders

Always remember:

```text
1. Need 10 normal obsidian.
2. Crying obsidian does not work.
3. Use ruined portals, not lava-cast mining.
4. Skip underwater ruined portals.
5. Mine obsidian with diamond pickaxe.
6. Craft or loot flint_and_steel.
7. Build 4x5 frame, corners omitted.
8. Ignite lower inside air cell.
9. Verify packlist before entering.
10. Record Overworld portal coordinates.
11. Enter and record Nether-side coordinates.
12. Wear gold armor for piglins.
13. Never hit zombified piglins.
14. Never use beds in the Nether.
15. Load blaze_rods after safe Nether arrival.
```
