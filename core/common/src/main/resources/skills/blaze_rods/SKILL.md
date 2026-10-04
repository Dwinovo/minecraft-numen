---
name: blaze_rods
description: Locate a Nether fortress, fight blazes at their spawner, and collect ≥7 blaze rods (each grinds into 2 blaze powder; 12 eyes of ender need 12 powder = 6 rods, +1 margin).
---

# Skill: blaze_rods

Phase 3 of the dragon route. Eyes of ender need blaze powder; `numen.locate.structure` means you waste zero eyes on throwing, so **7 rods (14 powder) is enough** with margin.

## Done when

- `numen.status.self` shows **≥7 blaze_rod**
- You're back at the Nether portal (or another safe spot), ready for phase 4

## Finding a fortress

1. **`numen.locate.structure("minecraft:fortress")`** — exact coordinates, direction and distance in one call (must be called while IN the Nether). Don't wander looking for it.
2. `numen.move.to` the returned x/z (the returned y is approximate — travel around y≈70), then `numen.scan.blocks("nether_bricks", {radius = 128})` to find the actual corridors — the nearest groups and how far they spread show where the brickwork runs; the structure spans many y-levels.
3. Beware the lookalike: blackstone with gold = **bastion** (`minecraft:bastion_remnant`) — different structure, avoid; its piglin brutes attack on sight.
4. Track your portal's coordinates so you can navigate home.

## Blazes

- 20 HP, fly/hover, volley of 3 fireballs (~5 dmg each + sets you on fire) every ~3s at line of sight, fire-immune.
- They spawn from **blaze spawners**: small fortress rooms with a caged spawner block, plus naturally on fortress bridges.
- **Carry a bow and ~6 arrows per blaze.** Blazes hover, so `numen.fight.attack` shoots the ones it cannot reach and closes on the ones it can — you do not pick. A diamond sword alone still works when arrows run out (3 hits kill) as long as you carry plenty of cooked food for the fireball damage; what you control is what is in the inventory, not the range.

## Farming loop

1. Find the spawner room (`numen.scan.blocks("spawner")` inside the fortress helps).
2. `numen.scan.entities` → `numen.fight.attack(184)` for each id it listed, one fight after another in one program.
3. `numen.work.collect` — rods drop on the floor; grab them before they burn in nearby lava... rods are fire-immune items, but lava destroys them. Don't let drops land in lava.
4. `numen.status.self` between batches: HP ≤ 8 → `numen.move.flee(spawner, {distance = 16})` out of its range, eat, return.
5. Repeat until `numen.status.self` shows ≥7 rods. Drop rate is 0–1 per kill (avg 0.5) → expect **~14 kills**, more if unlucky.

**Do not mine the spawner** — you need it spawning blazes until the count is met. (You *may* `numen.build.place({{name = "cobblestone", pos = {x = 120, y = 64, z = -35}}})` (with the cell's coordinates) a block or two to wall off excess sight-lines if too many blazes volley at once.)

## Hazards

- **Wither skeletons** roam fortress corridors; their hits apply Wither (damage over time). scan them and `numen.fight.attack` one runtime id at a time, or stay out of reach.
- Fortress bridges have no railings; knockback over the edge usually lands in lava. Fight away from edges (`combat_basics` positioning rules).

## What to load next

≥7 rods banked → mark phase 3 `completed`, then load the `ender_pearls` skill. Warped forests (teal trees, dense endermen) are worth noting on your way out — phase 4 can use them.
