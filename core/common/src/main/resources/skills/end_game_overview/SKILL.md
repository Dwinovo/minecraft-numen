---
name: end_game_overview
description: High-level roadmap to defeat the Ender Dragon. Load this FIRST when the owner asks for any end-game / "kill the dragon" goal — it tells you which specialised skill to load for the current phase.
---

# Skill: end_game_overview

Your owner has asked you to **defeat the Ender Dragon** — the canonical end-game of vanilla Minecraft. This skill is the map; the other skills are the territory. Each phase below maps 1:1 to a specialised skill you load on demand.

## How to run the journey

The full path from "fresh world" to "dead dragon" spans dozens of actions across three dimensions. Don't plan it all in one turn:

1. **Use the `todo` tool** to write the 6 mainline phases as a top-level plan, phase 1 `[>]`.
2. **Load the matching skill** with the skill tool only when you actually start that phase — loading all skills up front wastes tokens.
3. **Verify each phase's "done when" with `numen.status.self`** before marking it `completed` — never assume an item is in your inventory.
4. Keep exactly one phase `in_progress` at a time.

## The 6 mainline phases

These mirror the vanilla advancement chain, so your owner can track your progress on their own advancement screen.

| # | Phase | Skill to load | Done when | Vanilla advancement |
|---|---|---|---|---|
| 1 | Tier up to diamond | `tier_progression` | Diamond pickaxe + diamond sword, iron-or-better armor, bow + 32 arrows, 32+ cooked food, 64+ cobblestone | "Diamonds!" |
| 2 | Build a Nether portal, enter | `nether_entry` | Standing in the Nether with the packlist intact | "We Need to Go Deeper" |
| 3 | Farm blaze rods | `blaze_rods` | ≥7 blaze rods in inventory | "Into Fire" |
| 4 | Acquire ender pearls | `ender_pearls` | ≥12 ender pearls in inventory | — |
| 5 | Find the stronghold, activate the End portal | `stronghold_finding` | Standing at the activated End portal | "Eye Spy" |
| 6 | Kill the dragon | `dragon_combat` | Ender Dragon HP → 0 | "Free the End" |

One **support skill**: `combat_basics` — load before any combat-heavy phase (blazes, endermen, the dragon). HP management, target authorization, retreat rules.

## What you can do yourself

You have the full toolset: `numen.move.to` (plans a walk with `numen.route.plan` and walks it with `numen.move.go`; with `costs = {dig = true, place = true}` navigation digs, bridges and pillars on its own — but only digs what your held tool can harvest, so travel with a pickaxe in hand; `numen.move.flee` gets away from something), `numen.work.dig` (digs what your hand reaches from where you stand of the blocks you hand it: a scanned Block only while its cell still holds that block, a Pos whatever it holds; it never walks or picks up — `numen.move.to` there with arrive "dig" first, `numen.work.collect` the drops after; `numen.work.mine` does all three until none is left), `numen.build.place` (the Blocks you give it, within reach: one block, or a whole structure drawn with `numen.shape`; `numen.build.raise` walks the site and builds all of it; load `building_design` first for structures), `numen.work.collect`, `numen.gear.hold`, `numen.gear.wear`, `numen.inv.eat` (your healing), `numen.fight.attack` (it picks melee or bow/crossbow by what it can reach), `numen.use.block`/`numen.use.entity` (native crosshair use/attack on blocks, air, entities — flint & steel, ender eyes, levers, …), `numen.locate.structure` (strongholds, fortresses, #village, …). For any container or machine, the GUI primitives: `numen.use.block` to open it, `numen.gui.view` to read the slots, `numen.gui.quick` / `numen.gui.move` to move items one move per line (deposit / take / load / swap), `numen.gui.close` when done. **Crafting** = `numen.inv.make` (2×2 in your own grid, 3×3 at a crafting table it finds or puts down); **smelting** = `numen.inv.smelt`, or a furnace loaded with `numen.gui.put` (input + fuel) and a `numen.task.timer` to come back. Plus `numen.inv.drop`, `numen.task.timer` (furnace batches, nightfall), and perception (`numen.status.self` — HP, equipment AND full inventory in one call — `numen.status.world`, `numen.scan.blocks`, `numen.scan.entities`, `numen.scan.block`). Load the `containers` skill for the GUI/crafting/smelting details.

The whole route is therefore yours to execute autonomously. You can drive almost any GUI block this way — chests, furnaces, crafting tables, brewing stands, modded machines. The exception is picking an enchantment at an enchanting table (the enchant choice is a menu button, not a slot you can move items into): if the owner offers to enchant your gear, accept; never plan to enchant yourself.

## When the owner narrows the goal

If the owner asks for something more focused — *"just get to the Nether"*, *"find a stronghold"* — skip irrelevant phases and load only the skill(s) you need. Phases assume the previous phase's inventory; check `numen.status.self` and backfill gaps instead of blindly starting from phase 1.

## What to load next

Fresh world, no gear: load the `tier_progression` skill.

