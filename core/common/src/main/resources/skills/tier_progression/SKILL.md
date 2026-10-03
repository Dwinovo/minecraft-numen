---
name: tier_progression
description: Progress from nothing → wood → stone → iron → diamond tier with concrete tool workflows (mining, crafting, smelting, food, bow + arrows). The foundation for every subsequent phase of the dragon route.
---

# Skill: tier_progression

Phase 1 of the dragon route. You need diamond tools before you can mine obsidian and survive the Nether. Skip nothing here — under-geared Nether trips end in a lost inventory.

## Done when (verify with `status.self`)

- **Diamond pickaxe** (required for obsidian) and **diamond sword**, equipped as appropriate
- **Iron-or-better armor** worn (full iron is fine; diamond chestplate first if diamonds allow)
- **Bow + 32 arrows** (the dragon's crystals must be shot; blazes are safest shot too)
- **32+ cooked food** (cooked_beef / cooked_porkchop preferred)
- **64+ cobblestone** kept in inventory at all times — navigation spends it as throwaway blocks when bridging/pillaring (your `throwaway` list)

## Tool tier chain

`work.dig` picks the best tool you carry and skips blocks none of them harvests (a too-low tier breaks the block with **no drop**), naming the tier it needs. `scan.block` when unsure.

`work.dig` digs an **area** (or cells), not block types: look first — `scan.blocks` for every variant with into ores (the area is made if you have none by that name), then `move.goto_("ores", {arrive = "dig"})` (it stands where your hand reaches the most of it), `work.dig("ores")` (or one part of it, ores/g1; `count` caps the cells), and `work.collect()` for the drops — again while `area.has("ores")` is true; the built-in `work.mine("ores")` does all of it. `area.parts("ores")` lists the parts, and `area.drop` / `area.minus` / `area.filter` trim it; the area stays across restarts. `work.dig` only digs what your hand reaches **from where you stand** and never walks; it says how many cells are still out of reach and the call to copy to get to them (add `alter = "natural"` to the move.goto_ options when the way needs digging).

The same rule gates navigation: **`move.goto_` only digs through blocks your held tool can harvest.** Descending into stone with a sword in hand fails with "no path" — travel with the pickaxe in your main hand; switch to a weapon only for the fight, then switch back.

| Tier | Unlocks mining | Recipe |
|---|---|---|
| Hand | logs, dirt, gravel | — |
| Wooden pickaxe | stone, coal | 3 planks + 2 sticks |
| Stone pickaxe | iron, lapis | 3 cobblestone + 2 sticks |
| Iron pickaxe | diamond, gold, redstone | 3 iron ingots + 2 sticks |
| Diamond pickaxe | obsidian | 3 diamonds + 2 sticks |

## Where ores live (1.21+ worldgen)

Below Y 0 every ore is its **deepslate variant** — always pass both ids to `scan.blocks` (e.g. `diamond_ore` *and* `deepslate_diamond_ore`).

| Resource | Target Y | Notes |
|---|---|---|
| Coal | Y 90–136 | Surface hillsides are fastest |
| Iron | Y 16 (or mountain surface Y 200+) | Drops `raw_iron`, smelt it |
| **Diamond** | **Y -58 to -59** | Highest density; lava pools at this depth — mine carefully |

## Recommended order

1. **Wood**: `scan.blocks("oak_log", "birch_log", "spruce_log", {into = "logs"})`, then `move.goto_("logs", {arrive = "dig"})`, `work.dig("logs")`, `work.collect` — again until you hold 8+ logs (any `*_log`; hand works) → craft planks → sticks → a `wooden_pickaxe`. Crafting = `inv.craft` (it finds the recipe and lays the grid for you). 2×2 recipes (planks, sticks) use your own grid; a 3×3 (the pickaxe) needs a crafting table within reach — once you have planks, craft one and put it down with `build.place({x = 120, y = 64, z = -35}, {block = "crafting_table"})` (a cell next to you). Remember the table's coordinates and reuse it.
2. **Stone**: `gear.wear("wooden_pickaxe")` → `scan.blocks("stone", {into = "stone"})` → `work.dig("stone")` where you stand (and `move.goto_("stone", {arrive = "dig"})` for more), `work.collect` — 20 cobblestone → craft a `stone_pickaxe`, `stone_sword`, and a `furnace`.
3. **Food**: scan cows/pigs/chickens and `fight.attack` each runtime id (6+ total), `work.collect()` after → cook the raw meat: `use.block` a furnace, `use.shift` the raw food (it goes to the top slot) and the fuel (coal or planks, it goes below), set a `task.timer`, then `use.shift` the cooked food out (see the `containers` skill). Always cook; raw meat barely heals.
4. **Iron**: descend (`move.goto_({y = 16}, {alter = "natural"})` — navigation digs its own way down where you stand) → `gear.wear("stone_pickaxe")` → `scan.blocks("iron_ore", "deepslate_iron_ore", {into = "iron"})`, `move.goto_("iron", {arrive = "dig", alter = "natural"})` to reach the nearest vein → `work.dig("iron")`, `work.collect`, again while `area.has("iron")`, for 10+ → smelt `raw_iron` (same furnace flow) → craft an `iron_pickaxe`, `iron_sword`, then armor as ingots allow (helmet 5, chestplate 8, leggings 7, boots 4).
5. **Diamonds**: `move.goto_({y = -58}, {alter = "natural"})` → `gear.wear("iron_pickaxe")` → `scan.blocks("deepslate_diamond_ore", "diamond_ore", {into = "diamonds"})`, `move.goto_("diamonds", {arrive = "dig", alter = "natural"})` → `work.dig("diamonds")`, `work.collect`, for 5+. When `work.dig` ends saying more are out of reach, copy the line it gives to get there and dig again. Minimum 5 (pickaxe 3 + sword 2); 8+ if you also want a chestplate later. Watch HP near lava.
6. **Diamond gear**: craft a `diamond_pickaxe` + `diamond_sword` on the crafting table. Keep the pickaxe in hand for travel and mining; equip the sword only when a fight starts.
7. **Bow + arrows**: bow = 3 sticks + 3 string (scan spiders at night and `fight.attack` each for string); arrows = 1 flint + 1 stick + 1 feather → 4 (flint drops from gravel you `work.dig` at ~10%, feathers from chickens). Target 32 arrows — more is comfort, not requirement; melee + food covers what arrows don't.
8. **Top up**: 32+ cooked food, 64+ cobblestone. Re-run `status.self` against the "done when" list.

## Enchanting

You cannot operate an enchanting table (GUI block). If your owner offers to enchant your gear — Sharpness on the sword, Power on the bow, Efficiency on the pickaxe — accept before moving on; it meaningfully raises dragon-fight odds. Never plan an enchanting step for yourself.

## What to load next

Checklist verified → mark phase 1 `completed` in `todo.write`, then `skill.load("nether_entry")`.
