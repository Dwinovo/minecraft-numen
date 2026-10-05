---
name: tier_progression
description: Progress from nothing → wood → stone → iron → diamond tier with concrete tool workflows (mining, crafting, smelting, food, bow + arrows). The foundation for every subsequent phase of the dragon route.
---

# Skill: tier_progression

Phase 1 of the dragon route. You need diamond tools before you can mine obsidian and survive the Nether. Skip nothing here — under-geared Nether trips end in a lost inventory.

## Done when (verify with `numen.status.self`)

- **Diamond pickaxe** (required for obsidian) and **diamond sword**, equipped as appropriate
- **Iron-or-better armor** worn (full iron is fine; diamond chestplate first if diamonds allow)
- **Bow + 32 arrows** (the dragon's crystals must be shot; blazes are safest shot too)
- **32+ cooked food** (cooked_beef / cooked_porkchop preferred)
- **64+ cobblestone** kept in inventory at all times — navigation spends it when bridging/pillaring (it is in the default `materials` of every walk)

## Tool tier chain

`numen.work.dig` picks the best tool you carry and skips blocks none of them harvests (a too-low tier breaks the block with **no drop**), naming the tier it needs. `numen.scan.block` when unsure.

`numen.work.dig` digs **blocks you hand it** (a cluster from a scan, its Blocks, or cells), not block types: look first — `numen.scan.blocks` for every variant returns the clusters nearest first, each with its `blocks`; then `numen.move.to` the cluster with arrive "dig" (it stands where your hand reaches the most of it), `numen.work.dig` it (`count` caps the cells), and `numen.work.collect()` for the drops — again until none is left; the built-in `numen.work.mine(cluster)` does all of it. Nothing is kept between programs: scan again to see what is left. `numen.work.dig` only digs what your hand reaches **from where you stand** and never walks; it says how many cells are still out of reach and the `numen.move.to` call to copy to get to them (add `costs = {dig = true, place = true}` to its options when the way needs digging).

The same rule gates navigation: **`numen.move.to` only digs through blocks your held tool can harvest.** Descending into stone with a sword in hand fails with "no path" — travel with the pickaxe in your main hand; switch to a weapon only for the fight, then switch back.

| Tier | Unlocks mining | Recipe |
|---|---|---|
| Hand | logs, dirt, gravel | — |
| Wooden pickaxe | stone, coal | 3 planks + 2 sticks |
| Stone pickaxe | iron, lapis | 3 cobblestone + 2 sticks |
| Iron pickaxe | diamond, gold, redstone | 3 iron ingots + 2 sticks |
| Diamond pickaxe | obsidian | 3 diamonds + 2 sticks |

## Where ores live (1.21+ worldgen)

Below Y 0 every ore is its **deepslate variant** — always pass both ids to `numen.scan.blocks` (e.g. `diamond_ore` *and* `deepslate_diamond_ore`).

| Resource | Target Y | Notes |
|---|---|---|
| Coal | Y 90–136 | Surface hillsides are fastest |
| Iron | Y 16 (or mountain surface Y 200+) | Drops `raw_iron`, smelt it |
| **Diamond** | **Y -58 to -59** | Highest density; lava pools at this depth — mine carefully |

## Recommended order

1. **Wood**: `numen.scan.blocks("oak_log", "birch_log", "spruce_log")`, then `numen.work.mine` the nearest tree — again until you hold 8+ logs (any `*_log`; hand works) → craft planks → sticks → a `wooden_pickaxe`. Crafting = `numen.inv.make` (it picks the recipe and lays the grid for you). 2×2 recipes (planks, sticks) use your own grid; a 3×3 (the pickaxe) needs a crafting table — `numen.inv.make` walks to one within 16 blocks, or puts down the one you carry: once you have planks, `numen.inv.make("crafting_table")` first. Remember the table's coordinates and reuse it.
2. **Stone**: `numen.gear.hold("wooden_pickaxe")` → `numen.scan.blocks("stone", {radius = 4})` → `numen.work.dig` the nearest cluster's blocks where you stand (`count` caps it), `numen.work.collect` — 20 cobblestone → craft a `stone_pickaxe`, `stone_sword`, and a `furnace`.
3. **Food**: scan cows/pigs/chickens and `numen.fight.attack` each runtime id (6+ total), `numen.work.collect()` after → cook the raw meat: `numen.use.block` a furnace, `numen.gui.quick` the raw food (it goes to the top slot) and the fuel (coal or planks, it goes below), set a `numen.task.timer`, then `numen.gui.quick` the cooked food out (see the `containers` skill). Always cook; raw meat barely heals.
4. **Iron**: descend (`numen.move.to({y = 16}, {costs = {dig = true, place = true}})` — navigation digs its own way down where you stand) → `numen.gear.hold("stone_pickaxe")` → `numen.scan.blocks("iron_ore", "deepslate_iron_ore")`, then `numen.work.mine` the nearest vein (it walks there, digging and pillaring its way, digs and collects), vein after vein, for 10+ → smelt `raw_iron` (same furnace flow) → craft an `iron_pickaxe`, `iron_sword`, then armor as ingots allow (helmet 5, chestplate 8, leggings 7, boots 4).
5. **Diamonds**: `numen.move.to({y = -58}, {costs = {dig = true, place = true}})` → `numen.gear.hold("iron_pickaxe")` → `numen.scan.blocks("deepslate_diamond_ore", "diamond_ore")` → `numen.work.mine` the nearest cluster, for 5+. Minimum 5 (pickaxe 3 + sword 2); 8+ if you also want a chestplate later. Watch HP near lava.
6. **Diamond gear**: craft a `diamond_pickaxe` + `diamond_sword` on the crafting table. Keep the pickaxe in hand for travel and mining; equip the sword only when a fight starts.
7. **Bow + arrows**: bow = 3 sticks + 3 string (scan spiders at night and `numen.fight.attack` each for string); arrows = 1 flint + 1 stick + 1 feather → 4 (flint drops from gravel you `numen.work.dig` at ~10%, feathers from chickens). Target 32 arrows — more is comfort, not requirement; melee + food covers what arrows don't.
8. **Top up**: 32+ cooked food, 64+ cobblestone. Re-run `numen.status.self` against the "done when" list.

## Enchanting

You cannot operate an enchanting table (GUI block). If your owner offers to enchant your gear — Sharpness on the sword, Power on the bow, Efficiency on the pickaxe — accept before moving on; it meaningfully raises dragon-fight odds. Never plan an enchanting step for yourself.

## What to load next

Checklist verified → mark phase 1 `[x]` with the `todo` tool, then load the `nether_entry` skill.
