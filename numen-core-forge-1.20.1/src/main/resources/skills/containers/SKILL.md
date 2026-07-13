---
name: containers
description: Reliable container and machine GUI operations, exact transfers, slot verification, automated resumable crafting, and manual crafting fallback. Covers inspect -> transfer -> verify -> close, bulk moves, crafting grids, furnaces, modded machines, and recovery.
---

# Skill: containers

You manipulate inventories through real Minecraft-style GUIs.

This skill applies to:

```text
chest
trapped_chest
barrel
shulker_box
furnace
blast_furnace
smoker
crafting_table
player 2x2 crafting grid
hopper
dispenser
dropper
brewing stand if supported
modded machines
modded crafting grids
modded storage blocks
```

Core idea:

```text
Open the block.
Inspect the GUI.
Read slot numbers.
Transfer items.
Verify result.
Close the GUI.
```

There is no magic per-item black box. You operate the menu like a player, but with reliable slot inspection and batch transfers.

---

# 0. Highest priority rules

## 0.1 Always inspect before transferring

Never guess slot numbers.

Always use:

```text
inspect_gui
```

before using:

```text
transfer
```

The GUI tells you:

```text
slot index
item type
stack count
which section the slot belongs to
whether slot is output-only
crafting grid layout if present
machine data values if present
```

## 0.2 Use `transfer` in batches

`transfer` accepts a list of moves.

Do not make one tool call per item unless necessary.

Good:

```text
transfer moves=[{from:S1}, {from:S2}, {from:S3}]
```

Bad:

```text
transfer moves=[{from:S1}]
transfer moves=[{from:S2}]
transfer moves=[{from:S3}]
```

Batch the whole operation into one call whenever the slot plan is already known.

## 0.3 Close the GUI when finished

Always finish with:

```text
close_gui
```

Walking away may auto-close the menu, but do not rely on it.

Leaving a GUI open can confuse later actions.

## 0.4 `inspect_gui` is the truth

Do not assume:

```text
chest slots are always numbered a certain way
inventory slots are always in a fixed order
crafting grid cells are consecutive
output slots accept items
machine slots are arranged like vanilla
```

Read the current GUI.

Use the slot indexes from `inspect_gui`.

## 0.5 Protect essential gear

Do not accidentally deposit or swap away essential route items unless the user explicitly wants it.

Essential items often include:

```text
pickaxe
sword
bow
arrows
food
armor
water bucket if relevant
blocks for bridging
blaze rods
ender pearls
eyes of ender
special quest items
```

When depositing “everything”, exclude essential tools and supplies unless the task says to store them.

---

# 1. When to load this skill

Load this skill when the task involves:

```text
opening a container
using a chest/barrel/shulker
putting items away
taking items out
moving exact counts
sorting inventory
crafting with a GUI
using a crafting table
using the 2x2 inventory grid
loading a furnace
collecting furnace output
using a modded machine
reading machine progress
fixing GUI transfer errors
```

Examples:

```text
"put the cobblestone in the chest"
"take 10 iron"
"craft sticks"
"smelt the raw iron"
"load the furnace"
"empty the barrel"
"move these items into the shulker"
"craft a pickaxe"
"take the output from the machine"
```

---

# 2. Completion condition

This skill is complete when:

```text
The requested item movement/crafting/smelting/container task is done.
The relevant GUI has been closed.
Inventory/container state has been verified if necessary.
No important item was accidentally left in the wrong place.
```

Do not consider the task complete if:

```text
the GUI is still open
the item count was not verified
the transfer failed or partially failed
the output is still sitting in the machine
you deposited required tools by mistake
you do not know where the item went
```

---

# 3. Tool reference

| Tool | Use |
|---|---|
| `get_self_status` | Check inventory, position, game mode, held item, and status. |
| `move_to` | Move near the container or machine if needed. |
| `interact_at` | Right-click the block to open its GUI. |
| `inspect_gui` | Read all slots, slot indexes, output slots, grid maps, and data values. |
| `transfer` | Move, merge, route, count-split, withdraw, deposit, craft, or swap items. |
| `close_gui` | Close the current GUI. |
| `inspect_block` | Confirm a block is the expected container/machine before opening. |
| `scan_blocks` | Find nearby containers/machines if coordinates are unknown. |
| `place_block` | Place a crafting table, furnace, chest, or machine if needed. |
| `break_block` | Remove a block if explicitly needed and safe. |

If in creative mode and the task is merely to obtain an item, use `creative_mode` and `creative_give` instead of containers.

If the task specifically says to move items in/out of a container, still use this GUI workflow.

---

# 4. The universal GUI loop

Every container/machine/crafting task follows this loop.

## 4.1 Open

Use:

```text
interact_at button=right x=<x> y=<y> z=<z>
```

Notes:

```text
interact_at can path to the block if needed.
You must be close enough to interact.
Use inspect_block first if unsure what block is there.
```

Example:

```text
inspect_block x=100 y=64 z=200
interact_at button=right x=100 y=64 z=200
```

## 4.2 Inspect

Immediately call:

```text
inspect_gui
```

Read:

```text
container slots
player inventory slots
hotbar slots
crafting grid slots
result/output slot
machine input/fuel/output slots
data values
empty slots
stack counts
```

Do not transfer until you know the relevant slot indexes.

## 4.3 Plan

Before transfer, decide:

```text
Which item should move?
From which slot?
To exact slot, or auto-route to other section?
Whole stack or exact count?
Will this merge, move, or swap?
Do I need to preserve tools/food?
Is destination full?
```

## 4.4 Move

Use one batched `transfer` call if possible:

```text
transfer moves=[{from:S1}, {from:S2}, {from:S3}]
```

For exact count:

```text
transfer moves=[{from:S1, to:D1, count:10}]
```

## 4.5 Verify

Read the transfer result.

If result is clear and successful, you may not need another `inspect_gui`.

If anything is unclear, call:

```text
inspect_gui
```

again.

## 4.6 Close

Finish with:

```text
close_gui
```

---

# 5. Understanding `inspect_gui`

`inspect_gui` lists the current open menu.

It may show entries like:

```text
slot 0 container: minecraft:iron_ingot x32
slot 1 container: empty
slot 27 inventory: minecraft:cobblestone x64
slot 28 inventory: minecraft:diamond_pickaxe x1
slot 36 hotbar: minecraft:bread x12
slot 2 furnace [output]: minecraft:iron_ingot x3
data values: [120, 1600, 40, 200]
```

The exact format may vary, but always extract:

```text
slot index
section
item
count
special flags like [output]
data values
```

## 5.1 Sections

Common GUI sections:

```text
container
your inventory
hotbar
crafting grid
result slot
input slot
fuel slot
output slot
machine slots
```

Important:

```text
The hotbar is still your inventory.
Container slots are not your inventory.
Machine output slots are usually take-only.
```

## 5.2 Empty slots

Empty slots are useful for exact-count transfers.

If you need to take exactly 10 iron from a chest:

```text
find iron source slot in container
find empty destination slot in your inventory
transfer count 10 from source to destination
```

## 5.3 Output-only slots

Slots marked:

```text
[output]
```

are usually take-only.

You can move items FROM output slots:

```text
transfer moves=[{from:<output_slot>}]
```

You cannot place items INTO output slots.

Bad:

```text
transfer moves=[{from:<item>, to:<output_slot>}]
```

This should fail.

## 5.4 Crafting grid maps

For crafting, `inspect_gui` may show a 2D map of grid slot numbers.

Example 2x2:

```text
crafting grid:
[1] [2]
[3] [4]
result: [0]
```

Example 3x3:

```text
crafting grid:
[10] [11] [12]
[13] [14] [15]
[16] [17] [18]
result: [9]
```

Do not assume the numbers are consecutive in a way that matches the recipe.

Read the grid map cell-for-cell.

## 5.5 Machine data values

Some GUIs show:

```text
data values: [...]
```

These are synced menu integers.

For vanilla furnace-like blocks:

```text
[litTime, litDuration, cookProgress, cookTotal]
```

Meaning:

```text
litTime > 0 means fuel is currently burning
litDuration is the total burn time of current fuel
cookProgress is current progress toward one item
cookTotal is required progress for one item
cookPercent = cookProgress / cookTotal
```

If `cookTotal` is 0, do not divide by it. The machine may not have a valid recipe or may be idle.

---

# 6. Understanding `transfer`

`transfer` takes:

```text
moves=[{from, to?, count?}, ...]
```

Each move runs in order.

## 6.1 `from`

Required.

`from` is the source slot index from `inspect_gui`.

Example:

```text
transfer moves=[{from:27}]
```

## 6.2 `to`

Optional.

If `to` is omitted:

```text
the menu routes the stack to the other section
```

Examples:

```text
inventory -> chest
chest -> inventory
inventory -> furnace input/fuel if the item fits
furnace output -> inventory
craft result -> inventory
machine output -> inventory
```

This is the easiest way to bulk deposit or withdraw whole stacks.

If `to` is provided:

```text
the item goes to that exact slot
```

Then:

```text
empty destination -> item moves there
same item destination -> stacks merge
different item destination -> the two slots swap
invalid destination -> move fails
output-only destination -> move fails
```

## 6.3 `count`

Optional, but only use it with `to`.

Good:

```text
transfer moves=[{from:3, to:29, count:10}]
```

Bad:

```text
transfer moves=[{from:3, count:10}]
```

Exact-count moves require an exact destination slot.

## 6.4 Whole-stack routing

Deposit a whole stack from inventory to chest:

```text
transfer moves=[{from:<inventory_slot>}]
```

Withdraw a whole stack from chest to inventory:

```text
transfer moves=[{from:<container_slot>}]
```

## 6.5 Exact-count movement

Take exactly 10 items from a chest:

```text
transfer moves=[{from:<container_iron_slot>, to:<empty_inventory_slot>, count:10}]
```

Deposit exactly 16 items into a chest:

```text
transfer moves=[{from:<inventory_item_slot>, to:<empty_container_slot>, count:16}]
```

## 6.6 Merge

If destination already contains the same item:

```text
transfer moves=[{from:<iron_slot>, to:<partial_iron_slot>, count:10}]
```

The items merge up to the max stack size.

If the destination stack becomes full, remaining items may stay in source. Read the transfer result.

## 6.7 Swap

If destination contains a different item:

```text
transfer moves=[{from:<pickaxe_slot>, to:<dirt_slot>}]
```

The two slots swap.

Use swaps intentionally.

If a swap happens unexpectedly, you likely picked a non-empty destination slot.

## 6.8 Move order matters

Moves are processed in order.

This matters when:

```text
using the same source slot multiple times
moving into slots that later become sources
swapping
crafting with limited ingredients
freeing space then withdrawing items
```

If a later move depends on the result of an earlier move, consider:

```text
transfer first batch
inspect_gui
transfer second batch
```

---

# 7. Common movement patterns

## 7.1 Deposit whole stacks into a chest

Goal:

```text
Move all cobblestone stacks from inventory to chest.
```

Steps:

```text
interact_at button=right x=<chestX> y=<chestY> z=<chestZ>
inspect_gui
find all inventory/hotbar slots containing minecraft:cobblestone
transfer moves=[{from:S1}, {from:S2}, {from:S3}]
close_gui
```

Example:

```text
transfer moves=[{from:27}, {from:30}, {from:35}]
```

Because `to` is omitted, each stack routes into the chest.

## 7.2 Deposit exact count

Goal:

```text
Put exactly 10 iron ingots into a chest.
```

Steps:

```text
open chest
inspect_gui
find inventory iron slot
find empty or same-item chest slot
transfer moves=[{from:<inventory_iron>, to:<container_empty_or_iron>, count:10}]
close_gui
```

## 7.3 Withdraw whole stack

Goal:

```text
Take a stack of arrows from a chest.
```

Steps:

```text
open chest
inspect_gui
find arrows in container slot
transfer moves=[{from:<container_arrows>}]
close_gui
```

Omitted `to` routes to your inventory.

## 7.4 Withdraw exact count

Goal:

```text
Take exactly 10 iron ingots from a chest.
```

Steps:

```text
open chest
inspect_gui
find iron in container slot
find empty or same-item inventory slot
transfer moves=[{from:<container_iron>, to:<inventory_slot>, count:10}]
close_gui
```

## 7.5 Move many exact counts

If you need more than one stack or multiple item types, batch moves.

Example:

```text
transfer moves=[
  {from:<iron_slot>, to:<empty_inventory_1>, count:64},
  {from:<iron_slot_2>, to:<empty_inventory_2>, count:16},
  {from:<coal_slot>, to:<empty_inventory_3>, count:8}
]
```

If you are using the same source slot and exact counts may deplete it, inspect after the first transfer if uncertain.

## 7.6 Swap two inventory slots

Use exact `to` with a different item.

Example:

```text
transfer moves=[{from:<pickaxe_slot>, to:<hotbar_slot_with_junk>}]
```

Use this to put tools in hotbar or arrange inventory.

Be careful:

```text
If you did not want a swap, choose an empty destination slot.
```

## 7.7 Empty a container into inventory

Steps:

```text
open container
inspect_gui
find all non-empty container slots
transfer moves=[{from:C1}, {from:C2}, {from:C3}, ...]
inspect_gui if inventory may be full
close_gui
```

If inventory is full, transfer result will show partial or failed moves.

## 7.8 Store inventory except essentials

When asked to “dump inventory” or “store everything”, preserve essentials unless explicitly told otherwise.

Do not deposit:

```text
best pickaxe
best sword
bow
arrows
food
armor
current route-critical items
items explicitly needed next
```

Deposit:

```text
excess blocks
raw materials
mob drops
extra tools
junk
nonessential loot
```

---

# 8. Slot planning rules

## 8.1 Never choose a destination blindly

Before exact `to` movement, confirm the destination is:

```text
empty
or contains the same item and has room
or intentionally contains a different item for swapping
```

## 8.2 Use omitted `to` for simple bulk movement

If the goal is just:

```text
put this stack into the chest
take this stack out of the chest
collect this output
```

then omit `to`.

Example:

```text
transfer moves=[{from:<slot>}]
```

## 8.3 Use exact `to` for exact counts

If the task says:

```text
take exactly 10
deposit exactly 32
put one item into each grid cell
move this tool into hotbar slot
```

then provide `to`.

## 8.4 Do not put items into output slots

Output slots are source-only.

Correct:

```text
transfer moves=[{from:<output_slot>}]
```

Incorrect:

```text
transfer moves=[{from:<inventory_slot>, to:<output_slot>}]
```

## 8.5 Watch stack limits

Most items stack to 64.

Some stack to 16:

```text
ender_pearl
snowball
egg
signs in some versions
```

Some do not stack:

```text
tools
weapons
armor
buckets with contents
beds in some versions
```

If a destination cannot accept all items, transfer may partially move. Read the result.

---

# 9. Opening containers

## 9.1 Chests and barrels

Open:

```text
interact_at button=right x=<x> y=<y> z=<z>
inspect_gui
```

Double chests:

```text
interacting either half opens the combined inventory
inspect_gui shows the actual slots
```

Do not assume left/right half slot order. Inspect.

## 9.2 Shulker boxes

Open like a chest:

```text
interact_at button=right x=<x> y=<y> z=<z>
inspect_gui
```

Rules:

```text
Do not accidentally put the opened shulker into itself.
If a shulker cannot be moved, inspect and choose a different slot.
Shulker boxes are useful for portable storage.
```

## 9.3 Hoppers, droppers, dispensers

Open and inspect like other containers.

Their slots may be fewer.

Use exact slots if item order matters.

## 9.4 Unknown or modded containers

For unknown blocks:

```text
inspect_block
interact_at button=right
inspect_gui
```

If it opens a GUI, use the same transfer rules.

If it does not open, it may not be a container or may require power/ownership/tool.

---

# 10. Crafting: automated execution first, manual GUI fallback

For supported recipes, prefer `plan_crafting` followed by `craft_items`. This path recursively plans dependencies, consumes real ingredients, walks to real workstations, uses real fuel, validates recipes, waits for processing, supports pause/timeout/restart recovery, and prevents duplicate consumption during recovery.

Supported stations include the player 2x2 grid, crafting table, furnace, blast furnace, smoker, campfire, and stonecutter. This is not a free-item command.

Use manual `inspect_gui` + `transfer` crafting only when:

- the recipe or modded machine is unsupported by `craft_items`;
- exact NBT or a custom GUI choice requires manual handling;
- diagnosing why a recipe does not match;
- the user explicitly asks to operate the GUI manually.

## 10.1 Crafting workflow

General workflow:

```text
plan_crafting item_id="<target>" count=<additional_count>
review craftable_now and missing_base_materials
gather missing materials if any
craft_items item_id="<target>" count=<additional_count>
get_self_status to verify final inventory count
```

`count` means additional output requested, not the desired final inventory total. Re-check current count before calling it. For a manual fallback, continue with the grid rules below.

## 10.2 2x2 crafting grid

For recipes that fit in 2x2:

```text
planks
sticks
crafting table
torches
simple small recipes
```

No crafting table is needed.

If no GUI is open, call:

```text
inspect_gui
```

This should show your own inventory and 2x2 crafting grid.

If another GUI is open:

```text
close_gui
inspect_gui
```

## 10.3 3x3 crafting grid

For most tools and larger recipes:

```text
pickaxe
axe
sword
furnace
chest
boat
shield if supported
many blocks
```

Use a crafting table.

Open it:

```text
interact_at button=right x=<tableX> y=<tableY> z=<tableZ>
inspect_gui
```

If you do not have a crafting table:

```text
craft one using 2x2 grid from 4 planks
place_block block_id="minecraft:crafting_table" x=<x> y=<y> z=<z>
interact_at button=right x=<x> y=<y> z=<z>
inspect_gui
```

## 10.4 Use `lookup_recipe`

Before crafting:

```text
lookup_recipe item_id="minecraft:stone_pickaxe"
```

or:

```text
lookup_recipe <item>
```

Use the result to determine:

```text
ingredients
shape
output count
whether recipe is shaped or shapeless
grid size needed
```

## 10.5 Shaped recipes

For shaped recipes:

```text
match the layout cell-for-cell
place the recipe top-left in the grid
do not shift it unless necessary
do not fill empty cells
```

Important:

```text
A 2-wide recipe in a 3-wide grid does not use three consecutive cells.
It uses the top-left cells and skips the unused right column.
```

Example 2x2 recipe in a 3x3 grid:

```text
recipe:
A A
A A

3x3 grid:
[10] [11] [12]
[13] [14] [15]
[16] [17] [18]

Use:
[10] [11]
[13] [14]

Do NOT use:
[10] [11] [12] [13]
```

## 10.6 Shapeless recipes

For shapeless recipes:

```text
place required ingredients in any grid cells
top-left compact placement is preferred
do not fill extra cells
```

Example:

```text
one coal + one stick for torches
```

Use two cells only.

## 10.7 Place one item per occupied cell

For one craft:

```text
transfer moves=[
  {from:<ingredient_slot>, to:<grid_cell_1>, count:1},
  {from:<ingredient_slot>, to:<grid_cell_2>, count:1}
]
```

Only place ingredients in non-empty recipe cells.

Do not fill unused cells.

## 10.8 Take the result

After the recipe is laid out, take the output:

```text
transfer moves=[{from:<result_slot>}]
```

Omitting `to` routes crafted output to your inventory.

This performs the craft.

## 10.9 Craft many at once

To craft many efficiently:

```text
put a stack count into each required grid cell
then take the result once
```

The result transfer should craft repeatedly until one ingredient cell runs dry or inventory cannot accept more.

Example:

```text
7 logs in one grid cell -> one result transfer can produce 28 planks
8 planks in each stick cell -> one result transfer can produce 32 sticks
```

If the output did not craft as many as expected:

```text
inspect_gui
check remaining ingredients
check inventory space
take result again if needed
```

## 10.10 Clear or close grid when done

When finished:

```text
close_gui
```

If ingredients remain in the grid and you need them back:

```text
transfer them from grid slots to inventory
```

or close the GUI if the system returns them automatically.

If unsure, inspect after closing or check inventory with `get_self_status`.

---

# 11. Crafting examples

## 11.1 Craft planks from logs

Goal:

```text
oak_log -> oak_planks
```

Steps:

```text
close_gui if any container is open
inspect_gui
find oak_log slot
find 2x2 grid map and result slot
transfer moves=[{from:<oak_log_slot>, to:<grid_cell>, count:<number_of_logs>}]
transfer moves=[{from:<result_slot>}]
inspect_gui if needed
```

One log makes four planks.

## 11.2 Craft sticks

Recipe:

```text
plank
plank
```

Vertical 1x2.

If 2x2 grid map is:

```text
[1] [2]
[3] [4]
result [0]
```

Use:

```text
transfer moves=[
  {from:<planks_slot>, to:1, count:1},
  {from:<planks_slot>, to:3, count:1}
]
transfer moves=[{from:0}]
```

For many sticks:

```text
transfer moves=[
  {from:<planks_slot>, to:1, count:8},
  {from:<planks_slot>, to:3, count:8}
]
transfer moves=[{from:<result_slot>}]
```

8 planks in each cell crafts 32 sticks.

## 11.3 Craft crafting table

Recipe:

```text
plank plank
plank plank
```

In 2x2 grid:

```text
transfer moves=[
  {from:<planks_slot>, to:<top_left>, count:1},
  {from:<planks_slot>, to:<top_right>, count:1},
  {from:<planks_slot>, to:<bottom_left>, count:1},
  {from:<planks_slot>, to:<bottom_right>, count:1}
]
transfer moves=[{from:<result_slot>}]
```

## 11.4 Craft wooden or stone pickaxe

Requires 3x3 crafting table.

Recipe:

```text
material material material
empty    stick    empty
empty    stick    empty
```

Use the 3x3 grid map from `inspect_gui`.

If grid is:

```text
[A] [B] [C]
[D] [E] [F]
[G] [H] [I]
```

Then transfer:

```text
material -> A
material -> B
material -> C
stick -> E
stick -> H
```

Take result.

Do not put sticks in `D` or `F`.

## 11.5 Craft chest

Requires 3x3 grid.

Recipe:

```text
plank plank plank
plank empty plank
plank plank plank
```

Place planks in all outer cells and leave center empty.

Take result.

---

# 12. Smelting and furnaces

Smelting is not crafting.

A furnace-like GUI usually has:

```text
input slot
fuel slot
output slot
data values
```

Do not use crafting logic for smelting.

## 12.1 Furnace workflow

```text
interact_at button=right x=<furnaceX> y=<furnaceY> z=<furnaceZ>
inspect_gui
transfer input item into input slot
transfer fuel into fuel slot
wait or do another safe task nearby
inspect_gui to check progress
transfer output slot to inventory
close_gui
```

## 12.2 Loading input

Simple method:

```text
transfer moves=[{from:<raw_item_slot>}]
```

Omitted `to` lets the furnace menu route the item to the input slot.

If auto-routing fails or machine has multiple inputs:

```text
transfer moves=[{from:<raw_item_slot>, to:<input_slot>}]
```

## 12.3 Loading fuel

Simple method:

```text
transfer moves=[{from:<fuel_slot_in_inventory>}]
```

Omitted `to` lets the furnace menu route fuel to the fuel slot.

Exact method:

```text
transfer moves=[{from:<coal_inventory_slot>, to:<furnace_fuel_slot>, count:<fuel_count>}]
```

## 12.4 Fuel math

Common fuel values:

| Fuel | Items smelted |
|---|---:|
| coal | 8 |
| charcoal | 8 |
| blaze rod | 12 |
| dried kelp block | 20 |
| lava bucket | 100 |
| log | about 1.5 |
| planks | about 1.5 |
| stick | about 0.5 |

For coal/charcoal:

```text
fuelNeeded = ceil(inputCount / 8)
```

Examples:

```text
8 raw iron -> 1 coal
16 raw iron -> 2 coal
24 raw iron -> 3 coal
64 raw iron -> 8 coal
```

Use slightly extra fuel if unsure.

## 12.5 Reading furnace progress

After opening furnace:

```text
inspect_gui
```

For vanilla furnace data values:

```text
[litTime, litDuration, cookProgress, cookTotal]
```

Interpretation:

```text
litTime > 0 = fuel is burning
cookProgress / cookTotal = progress for current item
output slot count increases when items finish
input count decreases as items start/finish processing
```

If:

```text
litTime = 0
cookProgress = 0
output empty
input still present
```

then likely:

```text
no fuel
invalid input
wrong machine type
output blocked
```

## 12.6 Collecting output

Output slot is usually marked `[output]`.

Collect:

```text
transfer moves=[{from:<output_slot>}]
```

Omit `to` so output routes to inventory.

Collecting furnace output may award smelting XP.

## 12.7 Keep output from blocking

If output slot becomes full, smelting can stall.

For large batches:

```text
poll with inspect_gui
collect output periodically
continue until input is empty and output collected
```

## 12.8 Blast furnace and smoker

Blast furnace:

```text
smelts ores/metals faster
does not cook food
```

Smoker:

```text
cooks food faster
does not smelt ores
```

If input does not smelt, check whether you are using the wrong machine.

---

# 13. Smelting example

Goal:

```text
Smelt 24 raw iron using coal.
```

Steps:

```text
interact_at button=right x=<furnaceX> y=<furnaceY> z=<furnaceZ>
inspect_gui
find raw_iron slot in inventory
find coal slot in inventory
transfer moves=[
  {from:<raw_iron_slot>},
  {from:<coal_slot>, to:<fuel_slot>, count:3}
]
inspect_gui
wait/poll until output contains iron_ingot
transfer moves=[{from:<output_slot>}]
inspect_gui
repeat wait/collect until input is gone
close_gui
```

Fuel count:

```text
24 / 8 = 3 coal
```

If omitted `to` successfully routes coal, exact fuel slot is not required.

---

# 14. Modded machines

Modded machines are handled with the same open/inspect/transfer loop.

## 14.1 Do not assume slot layout

For any modded machine:

```text
interact_at button=right x=<x> y=<y> z=<z>
inspect_gui
```

Read:

```text
input slots
output slots
upgrade slots
fuel slots
energy/item buffer slots
data values
progress values
```

Never guess based on another machine.

## 14.2 Single-input machines

If the machine has one obvious input:

```text
transfer moves=[{from:<input_item_slot>}]
```

Omitted `to` may route correctly.

If it does not:

```text
inspect_gui
transfer moves=[{from:<input_item_slot>, to:<machine_input_slot>}]
```

## 14.3 Multi-input machines

For machines with multiple specific input slots, use exact destinations.

Example:

```text
transfer moves=[
  {from:<item_A_slot>, to:<machine_input_A>},
  {from:<item_B_slot>, to:<machine_input_B>},
  {from:<catalyst_slot>, to:<machine_catalyst_slot>}
]
```

## 14.4 Modded crafting grids

If the machine has a crafting grid, use the crafting rules.

```text
inspect_gui
read 2D grid map
place recipe top-left
transfer one ingredient per non-empty recipe cell
take result from output
```

## 14.5 Energy/fluid machines

If a machine needs energy or fluid and these are not represented as item slots, `transfer` may not solve it.

Check:

```text
data values
special slots
fuel slots
battery slots
fluid containers
```

If a bucket or battery item is accepted, transfer it into the correct slot.

If the machine needs external power, use the relevant mod-specific skill or world setup.

---

# 15. Inventory sorting and storage

## 15.1 Store one item type

Goal:

```text
Store all cobblestone into chest.
```

Algorithm:

```text
open chest
inspect_gui
find every inventory/hotbar slot containing cobblestone
exclude any slot you need to keep if relevant
transfer all those slots in one call with omitted to
verify result
close_gui
```

## 15.2 Store all junk

Define junk as:

```text
blocks not needed now
extra mob drops
extra seeds
excess stone/dirt/gravel
duplicate low-tier tools
non-route items
```

Do not store essentials unless requested.

## 15.3 Take a kit from storage

Goal:

```text
Take sword, bow, arrows, food, blocks.
```

Algorithm:

```text
open storage
inspect_gui
find each needed item
find free inventory slots
transfer whole stacks or exact counts
inspect_gui or get_self_status to verify
close_gui
```

## 15.4 Exact kit example

Take:

```text
1 iron sword
1 bow
32 arrows
16 cooked beef
64 cobblestone
```

Use:

```text
transfer moves=[
  {from:<sword_slot>},
  {from:<bow_slot>},
  {from:<arrows_slot>, to:<empty_inventory_slot>, count:32},
  {from:<beef_slot>, to:<empty_inventory_slot>, count:16},
  {from:<cobblestone_slot>, to:<empty_inventory_slot>, count:64}
]
```

If exact-count destination slots already contain same items, use those slots to merge.

---

# 16. Working with full inventories

## 16.1 Inventory full while withdrawing

If transfer reports inventory full:

```text
inspect_gui
find junk in inventory
deposit junk into container
or use another container
then retry withdrawal
```

Do not throw away valuable items unless requested.

## 16.2 Container full while depositing

If chest is full:

```text
inspect_gui
look for empty container slots
look for same-item partial stacks
if none exist, find another container
```

Options:

```text
open another chest/barrel/shulker
craft/place a new chest if appropriate
take unneeded items out only if allowed
```

## 16.3 Output blocked

If a furnace/machine output cannot be collected because inventory is full:

```text
deposit some inventory items first
then collect output
```

If output slot is full, processing may stop.

---

# 17. Error recovery

The transfer result tells you what happened.

Use it.

## 17.1 "No GUI open"

Cause:

```text
you never opened a GUI
you closed it
you walked away and it auto-closed
interaction failed
```

Fix:

```text
interact_at button=right x=<x> y=<y> z=<z>
inspect_gui
retry transfer
```

## 17.2 "Nothing moved"

Possible causes:

```text
destination is full
source slot is empty
item cannot go into that slot
slot is output-only
wrong machine slot
inventory full
container full
invalid count
```

Fix:

```text
inspect_gui
choose a valid source
choose an empty/same-item destination
or omit to for routing
retry
```

## 17.3 Unexpected swap

Cause:

```text
you provided a to slot that contained a different item
```

Fix:

```text
inspect_gui
transfer the two slots back if needed
choose an empty destination next time
or omit to for auto-routing
```

## 17.4 Output-only slot rejected item

Cause:

```text
you tried to move an item into an [output] slot
```

Fix:

```text
only transfer FROM output slots
never transfer TO output slots
```

## 17.5 Exact count failed

Possible causes:

```text
count used without to
destination had no room
source had fewer items than requested
item has small stack limit
slot cannot accept the item
```

Fix:

```text
inspect_gui
select destination slot
use count with to
split into multiple moves if needed
```

## 17.6 Craft result empty

Possible causes:

```text
wrong recipe
wrong grid cells
recipe shifted incorrectly
used wrong item variant
missing ingredient
extra item in grid
using 2x2 grid for 3x3 recipe
wrong crafting station
```

Fix:

```text
inspect_gui
clear incorrect grid cells
lookup_recipe again
read 2D grid map
place recipe top-left
take result
```

## 17.7 Furnace not smelting

Possible causes:

```text
no fuel
invalid input
wrong furnace type
output slot full
fuel item not accepted
machine has no power
input in wrong slot
```

Fix:

```text
inspect_gui
check input slot
check fuel slot
check output slot
check data values
load correct fuel/input
collect output if full
```

## 17.8 Items left in crafting grid

Fix:

```text
inspect_gui
transfer grid items back to inventory
or close_gui if the system returns them automatically
inspect inventory if important
```

## 17.9 Slot indexes changed

Slot contents can change after transfers, especially when:

```text
stacks merge
items swap
crafting consumes ingredients
furnace output appears
hoppers move items
other players interact
```

Fix:

```text
inspect_gui again
recalculate slot indexes
continue
```

---

# 18. Safety and ownership

## 18.1 Do not steal unless instructed

If a chest or container may belong to another player/village/base and the user did not ask to take from it, avoid taking items.

You may inspect if needed, but do not loot without permission.

## 18.2 Do not destroy container contents

Avoid:

```text
breaking chests full of items
clearing inventories blindly
depositing lava/fire items into important storage
mixing sorted storage without permission
```

## 18.3 Be careful with "store everything"

User may mean:

```text
store junk
```

not:

```text
store all tools and become helpless
```

Preserve essentials unless they specifically say to empty inventory completely.

## 18.4 Do not leave machines running dangerously

Usually machines are safe, but still avoid:

```text
leaving valuable output uncollected
leaving inventory full so output stalls
leaving important fuel/input in wrong machine
```

---

# 19. GUI task algorithms

## 19.1 Generic deposit algorithm

```text
interact_at button=right x=<containerX> y=<containerY> z=<containerZ>
inspect_gui
identify inventory slots to deposit
exclude essential gear unless requested
transfer moves=[{from:S1}, {from:S2}, ...]
read transfer result
inspect_gui if anything failed
close_gui
```

## 19.2 Generic withdrawal algorithm

```text
interact_at button=right x=<containerX> y=<containerY> z=<containerZ>
inspect_gui
identify container slots to take
choose omitted to for whole stacks
choose exact to+count for exact counts
transfer moves=[...]
read transfer result
inspect_gui or get_self_status to verify item count
close_gui
```

## 19.3 Generic crafting algorithm

```text
lookup_recipe <target_item>
close_gui if another GUI is open
open crafting table if recipe needs 3x3
inspect_gui
read grid map and result slot
place ingredients with transfer to exact grid cells
transfer from result slot to inventory
inspect_gui if making many or if result unexpected
close_gui if table is open
```

## 19.4 Generic smelting algorithm

```text
interact_at button=right x=<machineX> y=<machineY> z=<machineZ>
inspect_gui
load input item
load enough fuel
inspect_gui to confirm lit/progress
wait or poll
transfer from output slot when items finish
repeat until input is done and output collected
close_gui
```

## 19.5 Generic modded machine algorithm

```text
interact_at button=right x=<machineX> y=<machineY> z=<machineZ>
inspect_gui
identify input/output/fuel/energy/upgrade slots
transfer required inputs to exact slots if auto-routing is unclear
read data values for progress
wait/poll
collect output
close_gui
```

---

# 20. Common command-style examples

## 20.1 Store all cobblestone

```text
interact_at button=right x=100 y=64 z=200
inspect_gui
# Suppose inventory cobblestone slots are 29, 30, 31
transfer moves=[{from:29}, {from:30}, {from:31}]
close_gui
```

## 20.2 Take exactly 10 iron

```text
interact_at button=right x=100 y=64 z=200
inspect_gui
# Suppose chest iron is slot 5 and empty inventory slot is 32
transfer moves=[{from:5, to:32, count:10}]
close_gui
```

## 20.3 Swap pickaxe into hotbar

```text
inspect_gui
# Suppose pickaxe is slot 28 and hotbar junk slot is 36
transfer moves=[{from:28, to:36}]
```

This swaps pickaxe and junk.

## 20.4 Empty furnace output

```text
interact_at button=right x=105 y=64 z=200
inspect_gui
# Find [output] slot
transfer moves=[{from:<output_slot>}]
close_gui
```

## 20.5 Load furnace with input and fuel

```text
interact_at button=right x=105 y=64 z=200
inspect_gui
transfer moves=[
  {from:<raw_iron_inventory_slot>},
  {from:<coal_inventory_slot>, to:<fuel_slot>, count:3}
]
inspect_gui
close_gui
```

## 20.6 Craft sticks

```text
close_gui
inspect_gui
# Read 2x2 grid cells and result slot
transfer moves=[
  {from:<planks_slot>, to:<top_cell>, count:8},
  {from:<planks_slot>, to:<bottom_cell>, count:8}
]
transfer moves=[{from:<result_slot>}]
inspect_gui
```

---

# 21. Final verification checklist

Before ending a container task, verify:

```text
requested items moved
exact counts correct if specified
important gear still in inventory
output slots collected if needed
crafting grid not accidentally holding valuable items
machine not blocked if ongoing
transfer result had no unresolved failures
GUI closed
```

Use:

```text
inspect_gui
get_self_status
close_gui
```

as needed.

---

# 22. Final response after completing task

After finishing a container task, respond briefly.

Examples:

```text
Done — deposited the cobblestone into the chest and closed the GUI.
```

```text
Done — took exactly 10 iron ingots from the chest.
```

```text
Done — loaded the furnace with 24 raw iron and 3 coal.
```

```text
Done — crafted sticks and returned the result to inventory.
```

If something could not be completed, explain the exact blocker:

```text
Could not take 10 iron because the chest only contained 6.
```

```text
Could not deposit all items because the chest was full.
```

```text
The furnace is not smelting because it has no valid fuel.
```

---

# 23. Highest-priority reminders

Always remember:

```text
1. Open with interact_at.
2. Inspect with inspect_gui.
3. Never guess slot numbers.
4. Use batched transfer moves.
5. Omit to for easy whole-stack routing.
6. Use to+count for exact counts.
7. Different-item destination means swap.
8. Output slots are take-only.
9. Craft by placing ingredients into grid cells exactly.
10. Smelt by loading input and fuel, then collecting output.
11. Re-inspect after errors or dependent moves.
12. Close the GUI when done.
```
