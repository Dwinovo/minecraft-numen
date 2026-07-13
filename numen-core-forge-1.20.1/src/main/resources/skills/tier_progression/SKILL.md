---
name: tier_progression
description: Phase 1 of the dragon route. Progress safely from nothing to wood, stone, iron, and diamond gear. Covers mining tiers, crafting workflows, smelting, food preparation, armor, diamond pickaxe/sword, bow and arrows, cobblestone stockpiling, safety, and verification before Nether entry.
---

# Skill: tier_progression

This is Phase 1 of the dragon route.

Goal:

```text
Get strong enough gear for Nether entry, blaze rods, ender pearls, stronghold travel, and the dragon fight.
```

This phase turns a weak early-game inventory into a dragon-route-ready kit:

```text
diamond pickaxe
diamond sword
iron-or-better armor
bow
32+ arrows
32+ cooked food
64+ cobblestone or solid blocks
```

Do not skip this phase in survival-style play.

Under-geared Nether trips usually end with:

```text
lost inventory
lost portal route
death in lava
failed blaze farming
route reset
```

---

# 0. Completion condition

This skill is complete only when `get_self_status` verifies all of the following:

```text
minecraft:diamond_pickaxe exists
minecraft:diamond_sword exists
iron-or-better armor is worn or immediately available and equip-ready
minecraft:bow exists
minecraft:arrow count >= 32
cooked food count >= 32 preferred
solid block/cobblestone count >= 64
HP is safe
not currently under attack
```

Preferred final inventory:

```text
diamond_pickaxe
diamond_sword
full iron armor or better
bow
64+ arrows if possible
32+ cooked_beef or cooked_porkchop
64-128 cobblestone
crafting_table available or known nearby
furnace available or known nearby
some coal/charcoal
extra iron ingot for flint_and_steel later
```

Do not mark complete if:

```text
you only have iron tools
you have diamonds but did not craft diamond tools
you have raw food but not cooked food
you have bow but no arrows
you have arrows but no bow
you have armor in inventory but are not wearing it before danger
you used up cobblestone and have less than 64
you have not checked inventory with get_self_status
```

---

# 1. Absolute priority rules

## 1.1 Tool tier matters

`auto_mine` checks the held tool.

If the held tool is too weak:

```text
the block may break with no useful drop
```

Never mine important ores with the wrong pickaxe.

Examples:

```text
stone with hand -> no cobblestone
iron ore with wooden pickaxe -> no raw iron
diamond ore with stone pickaxe -> no diamond
obsidian with iron pickaxe -> no obsidian
```

Before mining, always:

```text
equip_item correct_pickaxe
get_self_status if unsure
```

## 1.2 Navigation also depends on held tool

`move_to` may dig, bridge, or pillar during navigation.

It digs using the currently held item.

Therefore:

```text
travel and mining movement should use pickaxe in hand
combat should use sword/bow only during combat
after combat, re-equip pickaxe
```

Common mistake:

```text
holding sword while trying to move_to underground target
```

Result:

```text
navigation cannot dig through stone
move_to may fail with no path
```

Fix:

```text
equip_item pickaxe
retry move_to
```

## 1.3 Do not enter Nether without this phase complete

Minimum before `nether_entry`:

```text
diamond_pickaxe
diamond_sword
armor
bow
arrows
food
blocks
```

If any are missing, stay in this skill and finish preparation.

## 1.4 Cook food

Raw food is poor healing.

For combat and route survival, use cooked food:

```text
cooked_beef
cooked_porkchop
cooked_mutton
cooked_chicken
cooked_salmon
cooked_cod
bread if meat unavailable
```

Preferred:

```text
cooked_beef
cooked_porkchop
```

## 1.5 Keep cobblestone stocked

Cobblestone is not junk.

It is needed for:

```text
Nether bridges
lava protection
ghast-resistant cover
End spawn platform bridge
pillaring to caged crystals
temporary shelters
navigation scaffold
```

Final target:

```text
64+ cobblestone minimum
128+ preferred before dragon phase
```

## 1.6 Verify with `get_self_status`

Do not rely on memory.

After every major step:

```text
get_self_status
```

Before marking phase complete:

```text
get_self_status
```

---

# 2. Required support skills

## 2.1 `containers`

Load for all crafting and smelting:

```text
load_skill(name="containers")
```

Use for:

```text
crafting table
tools
furnace
armor
bow
arrows
smelting raw iron
cooking food
moving items in GUIs
```

Supported crafting is preferably done by:

```text
plan_crafting item_id="<target>" count=<additional_count>
craft_items item_id="<target>" count=<additional_count>
get_self_status to verify
```

Supported smelting/cooking is preferably included in the same recursive `craft_items` workflow. For unsupported recipes or diagnosis, use the manual fallback:

```text
interact_at furnace
inspect_gui
transfer input
transfer fuel
wait/poll
transfer output
close_gui
```

## 2.2 `combat_basics`

Load before hunting animals at night or fighting hostile mobs:

```text
load_skill(name="combat_basics")
```

Use for:

```text
HP management
food healing
hunt vs shoot
retreat rules
skeleton/spider/creeper safety
```

## 2.3 `creative_mode`

If:

```text
get_self_status reports game_mode=creative
```

then load:

```text
load_skill(name="creative_mode")
```

If creative shortcuts are allowed, this phase can be completed directly with:

```text
creative_give
```

If the owner asked for legit survival-style play, still follow this skill without shortcuts.

---

# 3. Tools used in this skill

| Tool | Use |
|---|---|
| `get_self_status` | Check inventory, HP, coordinates, gear, held item, game mode. |
| `get_world_info` | Check time/weather if hunting spiders/animals. |
| `auto_mine` | Mine logs, stone, coal, iron, gravel, diamonds. |
| `move_to` | Travel to resources, descend to mining Y levels, return to base. |
| `equip_item` | Equip correct pickaxe, sword, bow, armor, food. |
| `hunt` | Kill animals for food, spiders for string, chickens for feathers, hostile mobs if needed. |
| `collect_items` | Pick up mined drops, mob drops, cooked outputs if dropped, loot. |
| `eat_item` | Heal directly when HP is low. |
| `lookup_recipe` | Get exact crafting recipe. |
| `interact_at` | Open crafting table/furnace/chests, place/use blocks if needed. |
| `inspect_gui` | Read crafting/furnace/container slots. |
| `transfer` | Move ingredients into crafting grid, load furnace, take outputs. |
| `close_gui` | Close GUI after crafting/smelting. |
| `place_block` | Place crafting table, furnace, emergency blocks. |
| `inspect_block` | Check blocks before mining/placing if unsure. |
| `scan_blocks` | Find nearby ores, gravel, crafting table/furnace, etc. |
| `scan_nearby_entities` | Find animals, spiders, hostile mobs. |
| `wait` | Wait for furnace smelting/cooking, nightfall, or safety. |

---

# 4. Mining tier chain

## 4.1 Pickaxe progression

| Held tool | Can harvest | Needed for |
|---|---|---|
| Hand | logs, dirt, gravel | starting resources only |
| Wooden pickaxe | stone, coal ore | cobblestone, coal |
| Stone pickaxe | iron ore, lapis | raw iron |
| Iron pickaxe | diamond ore, gold ore, redstone | diamonds |
| Diamond pickaxe | obsidian | Nether portal obsidian |

## 4.2 Recipes

| Item | Recipe | Grid |
|---|---|---|
| Planks | 1 log -> 4 planks | 2x2 |
| Sticks | 2 planks vertical -> 4 sticks | 2x2 |
| Crafting table | 4 planks in 2x2 | 2x2 |
| Wooden pickaxe | 3 planks + 2 sticks | 3x3 |
| Stone pickaxe | 3 cobblestone + 2 sticks | 3x3 |
| Stone sword | 2 cobblestone + 1 stick | 3x3 or 2x2 depending recipe support |
| Furnace | 8 cobblestone ring | 3x3 |
| Iron pickaxe | 3 iron ingots + 2 sticks | 3x3 |
| Iron sword | 2 iron ingots + 1 stick | 3x3 or 2x2 depending recipe support |
| Diamond pickaxe | 3 diamonds + 2 sticks | 3x3 |
| Diamond sword | 2 diamonds + 1 stick | 3x3 or 2x2 depending recipe support |
| Bow | 3 sticks + 3 string | 3x3 |
| Arrows | 1 flint + 1 stick + 1 feather -> 4 arrows | 3x3 |
| Helmet | 5 ingots | 3x3 |
| Chestplate | 8 ingots | 3x3 |
| Leggings | 7 ingots | 3x3 |
| Boots | 4 ingots | 3x3 |

Always use:

```text
lookup_recipe
```

before crafting. Some recipes are shaped, and grid slot positions matter.

---

# 5. Ore and resource locations

## 5.1 1.18+ / 1.21+ worldgen reminder

Below Y=0, many ores appear as deepslate variants.

When mining deep ores, include both normal and deepslate block IDs when possible.

Examples:

```text
minecraft:diamond_ore
minecraft:deepslate_diamond_ore
```

```text
minecraft:iron_ore
minecraft:deepslate_iron_ore
```

## 5.2 Resource guide

| Resource | Good Y / location | Notes |
|---|---|---|
| Logs | surface trees | Mine by hand. |
| Stone | underground / hills | Wooden pickaxe required for cobblestone. |
| Coal | surface hills, Y 90-136 common | Fuel and torches. Wooden pickaxe can harvest. |
| Iron | Y 16, caves, mountains high Y | Drops raw iron; smelt to ingots. Stone pickaxe required. |
| Gravel | rivers, caves, mountains, beaches | Drops flint randomly. |
| Diamond | Y -58 to -59 | Iron pickaxe required; watch lava. |
| Animals | plains/forest/savanna | Cook meat. |
| Spiders | night/caves | Drop string for bow. |
| Chickens | surface | Drop feathers for arrows. |

## 5.3 Diamond level safety

Diamond mining at:

```text
Y = -58 or -59
```

has high diamond density, but lava is common.

Rules:

```text
do not dig straight down
keep HP high
keep food ready
avoid mining into lava
if lava appears, retreat and block it off
do not chase diamonds through lava
```

---

# 6. Resource budget

## 6.1 Minimum target resources

To complete this phase, aim for:

```text
8-16 logs
64+ cobblestone minimum
8+ coal or charcoal
28+ iron ingots minimum for iron pickaxe + full iron armor + flint steel ingot later
33+ iron ingots preferred for iron tools + armor + backup pickaxe
5 diamonds minimum
3 string
8 flint
8 feathers
32+ cooked food
```

## 6.2 Diamond budget

Minimum:

```text
3 diamonds -> diamond pickaxe
2 diamonds -> diamond sword
total = 5 diamonds
```

Preferred extras:

```text
8 more diamonds -> diamond chestplate
```

So:

```text
5 diamonds = required minimum
13 diamonds = required + diamond chestplate
```

Do not spend diamonds on decorative items before required tools.

## 6.3 Iron budget

Useful iron costs:

```text
iron pickaxe = 3 ingots
iron sword = 2 ingots
full iron armor = 24 ingots
flint_and_steel later = 1 ingot
backup iron pickaxe = 3 ingots
```

Minimum practical target:

```text
28 ingots = iron pickaxe + full armor + 1 spare ingot for flint_and_steel
```

Preferred:

```text
33 ingots = iron pickaxe + iron sword + full armor + flint_and_steel ingot + backup pickaxe
```

If iron is scarce, priority is:

```text
1. iron pickaxe
2. enough armor to survive
3. flint_and_steel ingot for next phase
4. backup pickaxe
```

## 6.4 Arrow budget

Arrows recipe:

```text
1 flint + 1 stick + 1 feather = 4 arrows
```

For 32 arrows:

```text
8 flint
8 sticks
8 feathers
```

Because flint drop is random:

```text
expect to mine many gravel blocks, often around 80 for 8 flint without Fortune
```

---

# 7. Start-state decision tree

Always start with:

```text
get_self_status
```

## 7.1 If already complete

If inventory already has:

```text
diamond_pickaxe
diamond_sword
armor
bow
32+ arrows
32+ cooked food
64+ cobblestone
```

then:

```text
mark phase 1 complete
load_skill(name="nether_entry")
```

## 7.2 If missing only final supplies

If diamond tools exist but missing food/arrows/blocks:

```text
only top up missing supplies
do not redo full progression
```

## 7.3 If no diamond tools

Continue progression from the earliest missing tier:

```text
no wood tools -> start with logs
wood pickaxe but no stone tools -> mine stone
stone tools but no iron -> mine iron
iron pickaxe but no diamonds -> mine diamonds
diamonds but no tools -> craft diamond tools
```

## 7.4 If in creative mode

If:

```text
game_mode=creative
```

load creative_mode. If survival-style play not required, complete by giving items.

---

# 8. Core crafting workflow

Load `containers` for crafting and GUI safety. Prefer the resumable automatic crafting path for supported recipes.

General pattern:

```text
load_skill(name="containers")
plan_crafting item_id="<target>" count=<additional_count>
gather any missing_base_materials
craft_items item_id="<target>" count=<additional_count>
get_self_status
```

Use `lookup_recipe` plus manual grid transfers only for unsupported/special recipes or diagnostics. `craft_items` consumes real materials and still requires nearby workstations where appropriate.

## 8.1 Own 2x2 grid

Use for:

```text
planks
sticks
crafting_table
simple small recipes
```

If no GUI is open:

```text
inspect_gui
```

should show the inventory 2x2 crafting grid.

## 8.2 Crafting table 3x3 grid

Use for:

```text
pickaxes
furnace
armor
bow
arrows
most tools
```

Place crafting table:

```text
place_block block_id="minecraft:crafting_table" x=<x> y=<y> z=<z>
```

Open it:

```text
interact_at button=right x=<x> y=<y> z=<z>
inspect_gui
```

Remember its coordinates.

Reuse the same table when possible.

## 8.3 If grid result is empty

Likely causes:

```text
wrong recipe
wrong grid cells
missing ingredient
extra item in grid
using 2x2 for a 3x3 recipe
wrong item variant
```

Fix:

```text
inspect_gui
clear grid if needed
lookup_recipe again
place ingredients cell-for-cell
```

---

# 9. Core smelting/cooking workflow

Use `containers`.

## 9.1 Furnace setup

Need:

```text
furnace
fuel
input item
```

Open furnace:

```text
interact_at button=right x=<furnaceX> y=<furnaceY> z=<furnaceZ>
inspect_gui
```

Load input:

```text
transfer moves=[{from:<input_item_slot>}]
```

Load fuel:

```text
transfer moves=[{from:<fuel_slot>}]
```

or exact fuel count:

```text
transfer moves=[{from:<coal_slot>, to:<furnace_fuel_slot>, count:<N>}]
```

Wait and collect:

```text
wait
inspect_gui
transfer moves=[{from:<output_slot>}]
close_gui
```

## 9.2 Fuel math

Common fuel:

```text
coal = 8 items
charcoal = 8 items
planks/logs = about 1.5 items
sticks = about 0.5 items
```

For coal:

```text
coalNeeded = ceil(itemsToSmelt / 8)
```

Examples:

```text
32 raw food -> 4 coal
24 raw iron -> 3 coal
32 raw iron -> 4 coal
```

## 9.3 Furnace uses in this phase

Use furnace for:

```text
raw_iron -> iron_ingot
raw_meat -> cooked_food
logs -> charcoal if coal unavailable
```

Always collect output before leaving.

---

# 10. Step 1: Wood tier

## 10.1 Mine logs

Use hand. No tool required.

Mine any normal logs:

```text
minecraft:oak_log
minecraft:birch_log
minecraft:spruce_log
minecraft:jungle_log
minecraft:acacia_log
minecraft:dark_oak_log
minecraft:mangrove_log
minecraft:cherry_log
```

Use:

```text
auto_mine("<nearby_log_type>", count=8)
collect_items
get_self_status
```

If unsure which tree type is nearby:

```text
scan_blocks("*_log", radius=64)
```

or try common local logs.

Target:

```text
8 logs minimum
16 logs preferred
```

## 10.2 Craft planks

Use own 2x2 grid:

```text
lookup_recipe item_id="minecraft:oak_planks"
inspect_gui
transfer log stack into one grid cell
transfer result slot to inventory
```

If using another wood type, craft the matching planks.

Any planks work for most recipes.

## 10.3 Craft sticks

Recipe:

```text
plank
plank
```

Use:

```text
lookup_recipe item_id="minecraft:stick"
inspect_gui
transfer planks into two vertical cells
transfer result to inventory
```

Craft enough sticks:

```text
16+ sticks preferred
```

Sticks are needed for:

```text
tools
swords
bow
arrows
torches
```

## 10.4 Craft crafting table

Recipe:

```text
plank plank
plank plank
```

Use 2x2 grid.

Then place it somewhere safe:

```text
place_block block_id="minecraft:crafting_table" x=<tableX> y=<tableY> z=<tableZ>
```

Record:

```text
craftingTableX
craftingTableY
craftingTableZ
```

## 10.5 Craft wooden pickaxe

Open crafting table:

```text
interact_at button=right x=<tableX> y=<tableY> z=<tableZ>
inspect_gui
lookup_recipe item_id="minecraft:wooden_pickaxe"
```

Place:

```text
3 planks across top row
2 sticks down center
```

Take result:

```text
transfer result to inventory
close_gui
```

Equip:

```text
equip_item "minecraft:wooden_pickaxe"
get_self_status
```

---

# 11. Step 2: Stone tier

## 11.1 Mine stone for cobblestone

With wooden pickaxe equipped:

```text
equip_item "minecraft:wooden_pickaxe"
auto_mine("minecraft:stone", count=20)
collect_items
get_self_status
```

Stone drops:

```text
minecraft:cobblestone
```

If stone is not nearby, move to hillside/cave or dig safely with wooden pickaxe.

Do not dig straight down.

## 11.2 Craft stone pickaxe

Use crafting table:

```text
lookup_recipe item_id="minecraft:stone_pickaxe"
interact_at button=right x=<tableX> y=<tableY> z=<tableZ>
inspect_gui
transfer cobblestone + sticks into recipe
transfer result to inventory
close_gui
```

Equip:

```text
equip_item "minecraft:stone_pickaxe"
```

## 11.3 Craft stone sword

Craft:

```text
minecraft:stone_sword
```

Use for early combat and animal hunting.

Equip only when fighting:

```text
equip_item "minecraft:stone_sword"
```

After combat:

```text
equip_item "minecraft:stone_pickaxe"
```

## 11.4 Craft furnace

Furnace recipe:

```text
8 cobblestone in a ring
center empty
```

Craft:

```text
lookup_recipe item_id="minecraft:furnace"
```

Place furnace near crafting table:

```text
place_block block_id="minecraft:furnace" x=<furnaceX> y=<furnaceY> z=<furnaceZ>
```

Record:

```text
furnaceX
furnaceY
furnaceZ
```

## 11.5 Mine more cobblestone

You will eventually need 64+ cobblestone after crafting.

Do not stop at exactly the cobblestone used for tools.

Use:

```text
equip_item "minecraft:stone_pickaxe"
auto_mine("minecraft:stone", count=80)
collect_items
get_self_status
```

Final count can be topped up later.

---

# 12. Step 3: Fuel and food

## 12.1 Get coal if possible

Coal is useful for:

```text
smelting iron
cooking food
torches
```

With wooden or better pickaxe:

```text
equip_item "minecraft:stone_pickaxe"
auto_mine("minecraft:coal_ore", count=8)
collect_items
get_self_status
```

If underground or deepslate coal is relevant:

```text
auto_mine("minecraft:coal_ore", "minecraft:deepslate_coal_ore", count=8)
```

If coal is unavailable, use:

```text
logs/planks as emergency fuel
or smelt logs into charcoal
```

## 12.2 Hunt animals

Preferred animals:

```text
cow -> beef
pig -> porkchop
sheep -> mutton
chicken -> chicken + feathers
```

Use:

```text
load_skill(name="combat_basics")
equip_item "minecraft:stone_sword"
hunt("minecraft:cow")
hunt("minecraft:pig")
hunt("minecraft:sheep")
hunt("minecraft:chicken")
collect_items
get_self_status
```

Target:

```text
32+ raw meat if possible
```

If cows/pigs are abundant, prefer:

```text
beef
porkchop
```

Chickens are especially useful because they also drop:

```text
feathers for arrows
```

## 12.3 Cook food

Open furnace:

```text
interact_at button=right x=<furnaceX> y=<furnaceY> z=<furnaceZ>
inspect_gui
```

Load raw food and fuel:

```text
transfer raw_food into input
transfer coal/planks into fuel
```

Wait and collect:

```text
wait
inspect_gui
transfer output cooked_food to inventory
close_gui
get_self_status
```

Target:

```text
32+ cooked food
```

If you cannot get 32 meat, use bread/crops as fallback, but cooked meat is preferred.

## 12.4 Food safety rule

If HP is low at any time:

```text
eat_item
get_self_status
```

Do not continue mining/combat at low HP.

---

# 13. Step 4: Iron tier

## 13.1 Descend to iron level

Iron is common around:

```text
Y = 16
```

Use:

```text
get_self_status
equip_item "minecraft:stone_pickaxe"
move_to x=<current_or_nearbyX> y=16 z=<current_or_nearbyZ>
```

Navigation may dig. It needs pickaxe held.

Do not dig straight down manually.

## 13.2 Mine iron ore

Use stone pickaxe.

```text
equip_item "minecraft:stone_pickaxe"
auto_mine("minecraft:iron_ore", "minecraft:deepslate_iron_ore", count=30)
collect_items
get_self_status
```

Target raw iron:

```text
28 minimum
33 preferred
```

If `auto_mine` cannot find enough iron:

```text
move_to a nearby cave or new mining area
scan_blocks for iron ore
try again
```

## 13.3 Smelt raw iron

Return to furnace or place a new furnace.

Open furnace:

```text
interact_at button=right x=<furnaceX> y=<furnaceY> z=<furnaceZ>
inspect_gui
```

Load:

```text
raw_iron into input
coal into fuel
```

Fuel estimate:

```text
28 raw iron -> 4 coal
33 raw iron -> 5 coal
```

Collect ingots:

```text
wait
inspect_gui
transfer output to inventory
close_gui
get_self_status
```

## 13.4 Craft iron pickaxe

Craft:

```text
minecraft:iron_pickaxe
```

Use crafting table:

```text
lookup_recipe item_id="minecraft:iron_pickaxe"
interact_at crafting table
inspect_gui
transfer ingredients
transfer result
close_gui
```

Equip:

```text
equip_item "minecraft:iron_pickaxe"
```

This unlocks diamond mining.

## 13.5 Craft iron sword if needed

Iron sword is useful before diamond sword exists.

Craft:

```text
minecraft:iron_sword
```

Use it for hostile mobs until diamond sword is crafted.

## 13.6 Craft armor

Full iron armor cost:

```text
helmet = 5
chestplate = 8
leggings = 7
boots = 4
total = 24 ingots
```

Priority if iron is limited:

```text
1. chestplate
2. leggings
3. helmet
4. boots
```

But for phase completion, aim for a full set of iron-or-better armor.

Craft and equip:

```text
minecraft:iron_chestplate
minecraft:iron_leggings
minecraft:iron_helmet
minecraft:iron_boots
```

Then:

```text
equip_item armor_piece
get_self_status
```

If you later use a gold helmet for Nether piglins, keep the iron helmet as backup.

## 13.7 Optional iron reserves

If possible, keep:

```text
1 iron ingot for flint_and_steel in nether_entry
3 iron ingots for backup pickaxe
```

---

# 14. Step 5: Diamond tier

## 14.1 Descend to diamond level

Diamond target:

```text
Y = -58 or -59
```

Before descending:

```text
get_self_status
HP safe
food available
iron pickaxe equipped
blocks available
```

Then:

```text
equip_item "minecraft:iron_pickaxe"
move_to x=<current_or_safeX> y=-58 z=<current_or_safeZ>
```

Do not descend with sword in hand.

## 14.2 Mine diamond ore

Use iron pickaxe.

```text
equip_item "minecraft:iron_pickaxe"
auto_mine("minecraft:deepslate_diamond_ore", "minecraft:diamond_ore", count=5)
collect_items
get_self_status
```

Need:

```text
5 diamonds minimum
```

Preferred:

```text
8+ diamonds extra if you want diamond chestplate later
13 total diamonds for pickaxe + sword + chestplate
```

## 14.3 Lava precautions

At Y -58:

```text
lava is common
```

Rules:

```text
keep HP high
do not mine into exposed lava
do not chase diamonds across lava
block off lava with cobblestone if needed
retreat if lava appears
eat if damaged
```

If `auto_mine` reports danger or skips lava-adjacent blocks:

```text
move to another mining area
do not force it
```

## 14.4 If not enough diamonds found

If diamonds < 5:

```text
move_to another nearby area at y=-58
auto_mine diamonds again
```

Do not spend diamonds until you have enough for:

```text
diamond_pickaxe + diamond_sword
```

---

# 15. Step 6: Craft diamond gear

## 15.1 Craft diamond pickaxe

Requires:

```text
3 diamonds
2 sticks
crafting table
```

Use:

```text
lookup_recipe item_id="minecraft:diamond_pickaxe"
interact_at crafting table
inspect_gui
transfer ingredients
transfer result
close_gui
get_self_status
```

## 15.2 Craft diamond sword

Requires:

```text
2 diamonds
1 stick
```

Use:

```text
lookup_recipe item_id="minecraft:diamond_sword"
```

Craft and verify:

```text
get_self_status
```

## 15.3 Equip rules

For mining/travel:

```text
equip_item "minecraft:diamond_pickaxe"
```

For combat:

```text
equip_item "minecraft:diamond_sword"
```

After combat:

```text
equip_item "minecraft:diamond_pickaxe"
```

## 15.4 Optional diamond armor

If diamonds allow, best first armor upgrade:

```text
diamond_chestplate
```

Cost:

```text
8 diamonds
```

Craft only after required diamond pickaxe and sword.

Do not delay route excessively for full diamond armor unless owner wants extra safety.

---

# 16. Step 7: Bow and arrows

## 16.1 Bow requirements

Bow recipe:

```text
3 sticks
3 string
```

Requires 3x3 crafting table.

Need:

```text
minecraft:string x3
```

## 16.2 Getting string

Default method:

```text
hunt spiders at night or in caves
```

Use:

```text
load_skill(name="combat_basics")
get_world_info
wait until night if needed
equip_item "minecraft:diamond_sword" or "minecraft:iron_sword"
hunt("minecraft:spider")
collect_items
get_self_status
```

Target:

```text
string >= 3
```

Safety:

```text
avoid creepers
avoid skeletons in open ground
retreat/eat if HP <= 8
```

Alternative string sources if encountered:

```text
cobwebs in mineshafts
dungeon/structure chests
spider spawners if safe
```

Do not detour too long if spiders are available.

## 16.3 Craft bow

Use crafting table:

```text
lookup_recipe item_id="minecraft:bow"
interact_at crafting table
inspect_gui
transfer sticks and string into recipe
transfer result
close_gui
get_self_status
```

## 16.4 Arrow requirements

Need:

```text
32 arrows minimum
```

Recipe:

```text
1 flint + 1 stick + 1 feather = 4 arrows
```

For 32 arrows:

```text
8 flint
8 sticks
8 feathers
```

## 16.5 Getting feathers

Hunt chickens:

```text
equip_item "minecraft:diamond_sword" or "minecraft:stone_sword"
hunt("minecraft:chicken")
collect_items
get_self_status
```

Target:

```text
feather >= 8
```

Chickens also provide raw chicken, which can be cooked.

## 16.6 Getting flint

Mine gravel:

```text
auto_mine("minecraft:gravel", count=80)
collect_items
get_self_status
```

Flint drop is random.

Repeat until:

```text
flint >= 8
```

If you get enough flint earlier, stop.

## 16.7 Craft arrows

Arrows need a 3-high recipe, so use crafting table.

Use:

```text
lookup_recipe item_id="minecraft:arrow"
interact_at crafting table
inspect_gui
```

For 32 arrows, place:

```text
8 flint
8 sticks
8 feathers
```

into the arrow recipe cells.

Then:

```text
transfer result slot to inventory
close_gui
get_self_status
```

Verify:

```text
arrow count >= 32
```

## 16.8 Arrow conservation

Before dragon route:

```text
do not waste arrows on animals
do not shoot endermen
hunt common mobs with sword
save arrows for blazes, End crystals, dragon
```

---

# 17. Step 8: Final top-up

Before completing the phase, top up all route supplies.

## 17.1 Cooked food

Target:

```text
32+ cooked food
```

If short:

```text
hunt animals
cook raw food
get_self_status
```

## 17.2 Cobblestone

Target:

```text
64+ cobblestone
```

Preferred:

```text
128+ if easy
```

If short:

```text
equip_item "minecraft:diamond_pickaxe" or "minecraft:iron_pickaxe"
auto_mine("minecraft:stone", count=128)
collect_items
get_self_status
```

Remember:

```text
stone drops cobblestone unless Silk Touch exists
```

If using Silk Touch somehow, mine cobblestone directly or use another block supply.

## 17.3 Fuel

Keep some fuel if possible:

```text
coal
charcoal
planks
```

Useful for later cooking/smelting.

## 17.4 Crafting table and furnace

Keep either:

```text
crafting_table in inventory
furnace in inventory
```

or remember placed coordinates.

Preferred:

```text
carry one crafting_table
carry one furnace if inventory allows
```

---

# 18. Full recommended algorithm

Use this for a fresh-world start.

## 18.1 Wood

```text
get_self_status
auto_mine nearby logs count=8-16
collect_items
load_skill(name="containers")
craft planks
craft sticks
craft crafting_table
place crafting_table
craft wooden_pickaxe
equip_item wooden_pickaxe
```

## 18.2 Stone

```text
auto_mine stone count=20
collect_items
craft stone_pickaxe
craft stone_sword
craft furnace
place furnace
equip_item stone_pickaxe
```

## 18.3 Food and fuel

```text
auto_mine coal_ore count=8 if available
hunt cows/pigs/chickens/sheep
collect_items
cook raw food in furnace
get_self_status
```

## 18.4 Iron

```text
equip_item stone_pickaxe
move_to y=16
auto_mine iron_ore + deepslate_iron_ore count=30+
collect_items
smelt raw_iron
craft iron_pickaxe
craft armor
craft iron_sword if useful
equip armor
equip_item iron_pickaxe
```

## 18.5 Diamonds

```text
equip_item iron_pickaxe
move_to y=-58
auto_mine deepslate_diamond_ore + diamond_ore count=5+
collect_items
craft diamond_pickaxe
craft diamond_sword
equip_item diamond_pickaxe
```

## 18.6 Bow and arrows

```text
hunt spiders until string >= 3
craft bow
hunt chickens until feathers >= 8
auto_mine gravel until flint >= 8
craft arrows until arrow >= 32
```

## 18.7 Top-up and verify

```text
cook food until cooked_food >= 32
auto_mine stone until cobblestone >= 64
get_self_status
verify done checklist
```

Then:

```text
mark phase 1 completed
load_skill(name="nether_entry")
```

---

# 19. Safety rules

## 19.1 HP thresholds

Use combat_basics thresholds.

General mining safety:

```text
HP >= 16 preferred
HP <= 8 means stop, retreat/eat
```

Before deep mining or night combat:

```text
eat_item if HP is not high
```

## 19.2 Do not dig straight down

Never manually dig straight down.

Use:

```text
move_to
```

with pickaxe equipped, or stair/controlled pathing if needed.

## 19.3 Cave combat

If hostile mobs appear:

```text
equip_item sword
hunt or shoot as appropriate
get_self_status
eat if needed
equip_item pickaxe again
```

Do not keep mining while skeletons/creepers attack.

## 19.4 Creepers

Creepers can destroy crafting/furnace setup and cause large damage.

If a creeper appears:

```text
shoot if bow exists
or careful hunt with sword
move away from valuable placed blocks
```

## 19.5 Lava

If lava appears:

```text
back away
place_block cobblestone if needed
do not mine the block holding lava back
do not chase drops into lava
```

## 19.6 Inventory protection

Do not throw away:

```text
diamonds
iron ingots
diamond pickaxe
diamond sword
bow
arrows
food
cobblestone
blaze-route materials later
```

---

# 20. Common mistakes and fixes

## Mistake: Mining stone with hand

Problem:

```text
no cobblestone drop
```

Fix:

```text
craft/equip wooden_pickaxe
mine stone again
```

## Mistake: Mining iron with wooden pickaxe

Problem:

```text
iron ore breaks with no raw iron
```

Fix:

```text
craft/equip stone_pickaxe
mine iron again elsewhere
```

## Mistake: Mining diamonds with stone pickaxe

Problem:

```text
diamond ore breaks with no diamond
```

Fix:

```text
craft/equip iron_pickaxe first
mine diamonds only with iron or better
```

## Mistake: Navigation fails underground

Problem:

```text
holding sword/bow/food instead of pickaxe
```

Fix:

```text
equip_item pickaxe
retry move_to
```

## Mistake: Crafted diamond sword before diamond pickaxe with only 3-4 diamonds

Problem:

```text
cannot craft required diamond pickaxe
```

Fix:

```text
mine more diamonds
priority order is diamond_pickaxe first, then diamond_sword
```

If exactly 5 diamonds:

```text
craft pickaxe and sword only
```

## Mistake: Raw food counted as food goal

Problem:

```text
raw meat is weak healing
```

Fix:

```text
cook it in furnace
verify cooked food count
```

## Mistake: Bow without arrows

Fix:

```text
get flint + sticks + feathers
craft 32+ arrows
```

## Mistake: Arrows without bow

Fix:

```text
hunt spiders for 3 string
craft bow
```

## Mistake: Not enough feathers

Fix:

```text
hunt chickens
collect_items
repeat until feathers >= 8
```

## Mistake: Not enough flint

Fix:

```text
auto_mine gravel
collect_items
repeat until flint >= 8
```

## Mistake: Armor crafted but not worn

Fix:

```text
equip_item each armor piece
get_self_status
```

## Mistake: Used all cobblestone crafting tools/furnaces

Fix:

```text
auto_mine stone
collect_items
verify cobblestone >= 64
```

## Mistake: Left crafting GUI open

Fix:

```text
close_gui
```

Then continue movement.

---

# 21. Optional upgrades

These are helpful but not required for phase completion.

## 21.1 Diamond chestplate

If you find 13+ diamonds total:

```text
craft diamond_pickaxe
craft diamond_sword
craft diamond_chestplate
equip diamond_chestplate
```

Diamond chestplate is the best first armor upgrade.

## 21.2 More arrows

Minimum:

```text
32 arrows
```

Comfortable:

```text
64 arrows
```

More arrows help with:

```text
blazes
ghasts
End crystals
dragon
```

## 21.3 More food

Minimum:

```text
32 cooked food
```

Comfortable:

```text
48-64 cooked food
```

## 21.4 More blocks

Minimum for phase 1:

```text
64 cobblestone
```

Preferred for full route:

```text
128+ cobblestone
```

## 21.5 Golden apple

If found or craftable:

```text
keep it
```

Use later as emergency boss/combat healing.

Do not eat casually.

---

# 22. Enchanting

You cannot reliably operate an enchanting table yourself because enchanting choices are menu buttons, not normal item slots handled by `transfer`.

Do not plan a self-enchanting step.

If the owner offers to enchant gear, accept useful enchantments:

```text
Sharpness on sword
Power on bow
Efficiency on pickaxe
Protection on armor
Feather Falling on boots
Unbreaking on important gear
```

Enchanting improves odds but is not required for the baseline route.

---

# 23. Final verification checklist

Before marking phase 1 complete, call:

```text
get_self_status
```

Verify:

```text
diamond_pickaxe present
diamond_sword present
armor worn or immediately equip-ready
bow present
arrows >= 32
cooked food >= 32 preferred
cobblestone/solid blocks >= 64
HP safe
not under attack
pickaxe equipped for travel
```

If any item is missing:

```text
continue this skill
```

If complete:

```text
todowrite phase 1 completed
todowrite phase 2 in_progress
load_skill(name="nether_entry")
```

---

# 24. Final response after completing this skill

Report briefly:

```text
Phase 1 complete — diamond pickaxe and sword crafted, armor ready, bow and arrows prepared, cooked food stocked, and cobblestone topped up.
Ready for nether_entry.
```

If incomplete, report the exact blocker:

```text
Not ready yet — missing 3 string for the bow.
```

```text
Not ready yet — only 18 cooked food, need 32+.
```

```text
Not ready yet — have 4 diamonds, need 5 for pickaxe + sword.
```

---

# 25. What to load next

When checklist is verified:

```text
load_skill(name="nether_entry")
```

This begins Phase 2:

```text
acquire obsidian
build Nether portal
ignite portal
enter Nether safely
```

---

# 26. Highest-priority reminders

Always remember:

```text
1. Use the right pickaxe tier.
2. Hold pickaxe for navigation.
3. Switch to sword/bow only for combat.
4. Re-equip pickaxe after combat.
5. Cook food; raw food is not enough.
6. Smelt raw iron before crafting.
7. Mine diamonds only with iron pickaxe or better.
8. Craft diamond pickaxe before optional diamond armor.
9. Need bow + 32 arrows.
10. Need 32+ cooked food.
11. Need 64+ cobblestone after crafting costs.
12. Verify everything with get_self_status.
13. Do not enter the Nether under-geared.
```
