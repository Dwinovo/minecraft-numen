---
name: combat_basics
description: Generic combat tactics for the Numen entity - target authorization, what the body decides for you, drops, retreat rules, and aggro pitfalls.
---

# Skill: combat_basics

Load this support skill before a combat-heavy phase.

## Choose and authorize targets

Combat does not scan by mob type. First call `scan_nearby_entities`, select the exact entities you intend to attack, then pass 1-20 returned runtime integer IDs:

```json
{"entity_ids":[184,207,215]}
```

Players and mobs use the same ID field. Never guess IDs and never include an entity you do not intend to attack. The task re-resolves moving targets every tick, paths across terrain when they are far away, and attacks only the authorized IDs.

## What the body decides, not you

`attack` picks the weapon and the range on its own, every tick:

- **Can it reach the target?** Then it closes in and swings. This also conserves arrows.
- **Can it not get there** — the target is flying, across a chasm, on a pillar? Then it shoots, if it has a bow or crossbow with arrows.
- **Does the target explode?** Then it keeps its distance and shoots, or reports that it cannot take that fight.

You cannot see the distance at the moment it swings, the line of sight, or the arrow count. Do not try to specify a weapon — there is no parameter for it.

It also picks the strongest weapon you own **against that specific target**: a Smite sword beats a plain better one against undead, and Bane of Arthropods beats it against spiders.

## Before the fight

1. Use `get_self_status` to check HP, equipment, food, and dimension.
2. Carry a melee weapon, and carry a bow with arrows if the phase involves anything airborne. Without arrows, an unreachable target is simply reported as unreachable.
3. Keep dense food available and heal with `eat_item` before critical HP. Combat does not interrupt an active eating, potion, bow, or other use action.

## During and after the fight

The task follows the nearest authorized entity while it is out of reach, waits for weapon switching, target recovery and the vanilla attack cooldown, aims visibly, stops sprinting before the hit, and uses the native attack.

After every kill, target selection pauses while the body walks over newly spawned drops around that death point. Do not call `collect_items` for ordinary combat drops. The final result reports defeated, lost and unreachable IDs plus `loot_gained`.

## Retreat rules

Combat runs in the background. Check `task_finished` and `get_self_status` between engagements.

- HP <= 8: stop the task, move 20+ blocks away, heal, then scan again because runtime IDs may have changed.
- Weapon about to break or no arrows: disengage and restock.
- Before a long `goto`, clear or outrun active pursuers.
- Avoid cliff edges, lava corridors, deep water, and cramped ledges where knockback or drops become unsafe.

## Aggro pitfalls

- **Creepers**: the body will not melee one — it keeps outside the blast and shoots. Without a bow it reports the creeper as unreachable rather than trading a life for it. That is correct; get a bow or leave it.
- Zombified piglins group-aggro. Do not authorize one unless the group fight is intentional.
- Piglins attack players without gold armor.
- Endermen teleport in a fight; rescanning may be needed if one leaves the loaded world.
- Wither skeletons apply Wither; kill quickly.

Per-enemy tactics live in `blaze_rods`, `ender_pearls`, and `dragon_combat`. Gear progression lives in `tier_progression`.
