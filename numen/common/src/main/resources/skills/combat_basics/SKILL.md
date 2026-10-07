---
name: combat_basics
description: Generic combat tactics for the Numen entity - target authorization, what the body decides for you, drops, retreat rules, and aggro pitfalls.
---

# Skill: combat_basics

Load this support skill before a combat-heavy phase.

## Choose and authorize targets

Combat does not scan by mob type. First call `numen.scan.entities`, pick the exact entity you intend to attack, then pass its runtime id — one entity per call:

```lua
numen.fight.attack(184)
```

Players and mobs use the same id. Never guess ids and never attack an entity you did not pick. The task re-resolves the moving target every tick and paths across terrain when it is far away. To fight several, call it once for each in a program; `numen.fight.clear()` (library) fights every hostile around you, nearest first:

```lua
for _, foe in ipairs(numen.scan.entities("hostile", {radius = 16})) do numen.fight.attack(foe.id) end
```

## What the body decides, not you

`numen.fight.attack` picks the weapon and the range on its own, every tick:

- **Can it reach the target?** Then it closes in and swings. This also conserves arrows.
- **Can it not get there** — the target is flying, across a chasm, on a pillar? Then it shoots, if it has a bow or crossbow with arrows.
- **Does the target explode?** Then it keeps its distance and shoots, or reports that it cannot take that fight.

You cannot see the distance at the moment it swings, the line of sight, or the arrow count. Do not try to specify a weapon — there is no parameter for it.

It also picks the strongest weapon you own **against that specific target**: a Smite sword beats a plain better one against undead, and Bane of Arthropods beats it against spiders.

## Before the fight

1. Use `numen.status.self` to check HP, equipment, food, and dimension.
2. Carry a melee weapon, and carry a bow with arrows if the phase involves anything airborne. Without arrows, an unreachable target is simply reported as unreachable.
3. Keep dense food available and heal with `numen.inv.eat` before critical HP. Combat does not interrupt an active eating, potion, bow, or other use action.

## During and after the fight

The task follows the target while it is out of reach, waits for weapon switching, target recovery and the vanilla attack cooldown, aims visibly, stops sprinting before the hit, and uses the native attack.

It does not pick up what the target drops: its result says where the drops lie, and `numen.work.collect()` walks onto them.

## Retreat rules

A program waits for each fight to end; check `numen.status.self()` between engagements.

- HP <= 8: `numen.task.stop()`, move 20+ blocks away, heal, then scan again because runtime IDs may have changed.
- Weapon about to break or no arrows: disengage and restock.
- Before a long `numen.move.to`, clear or outrun active pursuers (`numen.move.flee(mob)` gets away from one).
- Avoid cliff edges, lava corridors, deep water, and cramped ledges where knockback or drops become unsafe.

## Aggro pitfalls

- **Creepers**: the body will not melee one — it keeps outside the blast and shoots. Without a bow it reports the creeper as unreachable rather than trading a life for it. That is correct; get a bow or leave it.
- Zombified piglins group-aggro. Do not attack one unless the group fight is intentional.
- Piglins attack players without gold armor.
- Endermen teleport in a fight; rescanning may be needed if one leaves the loaded world.
- Wither skeletons apply Wither; kill quickly.

Per-enemy tactics live in `blaze_rods`, `ender_pearls`, and `dragon_combat`. Gear progression lives in `tier_progression`.
