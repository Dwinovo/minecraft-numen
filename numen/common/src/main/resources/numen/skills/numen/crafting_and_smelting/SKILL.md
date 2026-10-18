---
name: crafting_and_smelting
description: Minecraft 1.21.1 crafting and smelting facts - the 2x2 and 3x3 grids, the recipes the early game runs on, armor and tool costs, furnace / blast furnace / smoker differences, fuel burn values, what a furnace's slots and progress numbers mean, and the workstation blocks.
---

# Crafting and smelting (1.21.1)

## Grids

- The **inventory grid is 2x2**: planks, sticks, a crafting table, torches and anything else that fits in 2x2 can be made anywhere.
- A **crafting table** gives a 3x3 grid; most tools, armor and machines need it.
- **Shaped** recipes keep their pattern but can sit anywhere in a larger grid (a 2x2 recipe works in any 2x2 corner of the 3x3); **shapeless** recipes only care about the ingredients.

## The early-game chain

| Item | Recipe | Notes |
|---|---|---|
| Planks | 1 log → 4 planks | Any wood type; stripped logs and wood work too |
| Sticks | 2 planks (stacked) → 4 sticks | |
| Crafting table | 4 planks (2x2) | |
| Chest | 8 planks (ring) | |
| Furnace | 8 cobblestone, cobbled deepslate or blackstone (ring) | |
| Torch | 1 coal or charcoal over 1 stick → 4 torches | |
| Wooden / stone / iron / diamond pickaxe | 3 material over 2 sticks | Material = planks, cobblestone (or cobbled deepslate, blackstone), iron ingots, diamonds |
| Sword | 2 material over 1 stick | |
| Axe | 3 material + 2 sticks | |
| Shovel | 1 material + 2 sticks | |
| Bucket | 3 iron ingots (V shape) | Carries water, lava, milk, powder snow, fish |
| Shield | 6 planks + 1 iron ingot | |
| Flint and steel | 1 iron ingot + 1 flint (shapeless) | Flint: 10% of gravel broken without Silk Touch |
| Bow | 3 sticks + 3 string | |
| Arrow | 1 flint, 1 stick, 1 feather (column) → 4 arrows | |
| Bed | 3 wool (same colour) over 3 planks | |
| Ladder | 7 sticks (H shape) → 3 | |
| Boat | 5 planks (U shape) | |
| Bread | 3 wheat (row) | |
| Iron block / gold block / diamond block | 9 of the item; and back to 9 | Compact storage |
| Blast furnace | 5 iron ingots + 1 furnace + 3 smooth stone | |
| Smoker | 1 furnace + 4 logs | |
| Smithing table | 2 iron ingots over 4 planks | |
| Anvil | 3 iron blocks + 4 iron ingots | |
| Enchanting table | 1 book + 2 diamonds + 4 obsidian | |
| Brewing stand | 1 blaze rod over 3 cobblestone | |
| Eye of ender | 1 ender pearl + 1 blaze powder | Blaze rod → 2 blaze powder |

## Armor cost and protection

| Piece | Material cost | Iron | Diamond |
|---|---|---|---|
| Helmet | 5 | 2 armor | 3 armor |
| Chestplate | 8 | 6 | 8 |
| Leggings | 7 | 5 | 6 |
| Boots | 4 | 2 | 3 |
| Full set | 24 | 15 | 20 (+8 toughness) |

Leather totals 7, gold 11, chainmail 12 (chainmail cannot be crafted), netherite 20 with 12 toughness and knockback resistance. Netherite is not crafted directly: diamond gear is upgraded at a smithing table (see `the_nether`).

## Smelting

| Block | Smelts | Time per item |
|---|---|---|
| Furnace | everything smeltable | 10 s (200 ticks) |
| Blast furnace | ores, raw metal, metal tools and armor only | 5 s |
| Smoker | food only | 5 s |
| Campfire | food only, 4 at a time, no fuel needed | 30 s |

Common smelts: raw iron/gold/copper → ingot; sand → glass; cobblestone → stone → (again) smooth stone; logs → charcoal; clay ball → brick; raw meat and fish → cooked; potato → baked potato; kelp → dried kelp; cactus → green dye; netherrack → nether brick; ancient debris → netherite scrap; wet sponge → sponge.

### Fuel (items smelted per one fuel in a furnace)

| Fuel | Items |
|---|---|
| Lava bucket | 100 (the bucket is returned) |
| Block of coal | 80 |
| Dried kelp block | 20 |
| Blaze rod | 12 |
| Coal or charcoal | 8 |
| Log, planks, wooden blocks | 1.5 |
| Wooden slab | 0.75 |
| Stick, sapling, wool | 0.5 |
| Bamboo, scaffolding | 0.25 |

Blast furnaces and smokers burn fuel twice as fast, so the same fuel smelts the same number of items. Charcoal (smelted from a log) is a full replacement for coal.

### What a furnace window shows

A furnace-type block has three slots: **input** on top, **fuel** below it, **output** to the right. The output slot only takes items out. Its progress numbers are, in order: fuel time left (ticks), the total burn time of the current fuel, cook progress (ticks), and total cook time (200 in a furnace, 100 in a blast furnace or smoker). Smelting continues while the block stays loaded, with nobody at it. Taking the output out awards the smelting experience.

## Workstations

| Block | What it does |
|---|---|
| Crafting table | 3x3 crafting |
| Furnace / blast furnace / smoker | smelting (above) |
| Stonecutter | one stone block → stairs, slabs, walls, bricks without waste |
| Smithing table | netherite upgrades and armor trims |
| Anvil | renames, repairs, and combines enchanted items and books (costs levels) |
| Grindstone | removes enchantments (returns some experience) and repairs by combining two of the same item |
| Enchanting table / brewing stand | see `enchanting_and_brewing` |
| Loom | patterns on banners |
| Cartography table | copies and expands maps |

Most workstations are also villager job sites (see `villagers_and_trading`).
