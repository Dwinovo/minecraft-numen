---
name: combat_basics
description: Generic combat tactics for the Numen entity - target authorization, melee_attack vs ranged_attack choice, drops, retreat rules, and aggro pitfalls.
---

# Skill: combat_basics

Load this support skill before a combat-heavy phase.

## Choose and authorize targets

Combat tools do not scan by mob type. First call `scan_nearby_entities`, select the exact entities you intend to attack, then pass 1-20 returned runtime integer IDs:

```json
{"entity_ids":[184,207,215]}
```

Players and mobs use the same ID field. Never guess IDs and never include an entity you do not intend to attack. The task re-resolves moving targets every tick, paths across terrain when they are far away, and attacks only the authorized IDs.

## Before the fight

1. Use `get_self_status` to check HP, equipment, food, and dimension.
2. Carry a sword or axe. `melee_attack` selects a usable melee weapon from the inventory and visibly switches it into the hand.
3. Keep dense food available and heal with `eat_item` before critical HP. Combat does not interrupt an active eating, potion, bow, or other use action.

After terrain-heavy travel, `melee_attack` can recover its weapon from the full inventory even if navigation temporarily selected a tool or block.

## melee_attack vs ranged_attack

| Situation | Tool | Why |
|---|---|---|
| Blazes | `ranged_attack`, or `melee_attack` when arrows are low | Range avoids fireball volleys |
| End crystals | `ranged_attack` | They explode and are not living melee targets |
| Dragon flying | `ranged_attack` | It is outside melee reach |
| Dragon perched | `melee_attack` | Use the melee window |
| Endermen | `melee_attack` | They evade arrows |
| Common mobs and food animals | `melee_attack` | Preserve arrows and collect drops automatically |

Bow and crossbow combat stays in `ranged_attack`; never pass those fights to `melee_attack`.

## During and after melee

The task follows the nearest authorized living entity while it is out of reach, waits for weapon switching, target recovery and vanilla attack cooldown, aims visibly, stops sprinting before the hit, and uses the native melee attack. If it is crowded inside its preferred reach while waiting to swing, it backs away before re-engaging. If the target moves out of reach, it resumes following.

After every kill, target selection pauses while the body walks over newly spawned drops around that death point. Do not call `collect_items` for ordinary melee drops. The final result reports defeated, lost and unreachable IDs plus `loot_gained`.

## Retreat rules

Combat tasks run in the background. Check `task_finished` and `get_self_status` between engagements.

- HP <= 8: stop the task, move 20+ blocks away, heal, then scan again because runtime IDs may have changed.
- Weapon about to break or no arrows: disengage and restock.
- Before a long `goto`, clear or outrun active pursuers.
- Avoid cliff edges, lava corridors, deep water, and cramped ledges where knockback or drops become unsafe.

## Aggro pitfalls

- Zombified piglins group-aggro. Do not authorize one unless the group fight is intentional.
- Piglins attack players without gold armor.
- Endermen teleport during melee; rescanning may be needed if one leaves the loaded world.
- Wither skeletons apply Wither; kill quickly or stay at range.

Per-enemy tactics live in `blaze_rods`, `ender_pearls`, and `dragon_combat`. Gear progression lives in `tier_progression`.
