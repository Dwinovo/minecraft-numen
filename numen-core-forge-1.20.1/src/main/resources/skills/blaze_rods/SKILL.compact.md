# Blaze rods: compact route

Goal: safely collect at least 7 blaze rods and retain a return route.

1. In the Nether, use `locate_structure` with `minecraft:fortress`; confirm fortress blocks after travel. Do not confuse a bastion.
2. Keep Nether portal coordinates in memory. Enter the fortress from stable terrain and establish a retreat point.
3. Locate blazes/spawner by scanning. Never mine the blaze spawner.
4. Prefer ranged attacks with enough arrows; use cover and avoid open lava edges. Heal/retreat early and clear nearby threats before farming.
5. Kill in controlled batches, collect drops, then verify rod count in inventory. Drop chance is not guaranteed.
6. Stop at 7+ rods unless the owner requested a larger reserve. Return to a safe staging point with gear and rods intact.

If equipment, food or ammo is inadequate, leave and resupply rather than gambling the route.

## Compact reference: early rules and setup

# Skill: blaze_rods

This skill is Phase 3 of the dragon route.

Goal:

```text
Find a Nether fortress.
Find blazes, preferably a blaze spawner.
Kill enough blazes.
Collect at least 7 blaze rods.
Return to the Nether portal or another safe staging point.
```

Why 7 rods:

```text
1 blaze rod -> 2 blaze powder
12 eyes of ender need 12 blaze powder
12 powder = 6 rods
7 rods = 14 powder, giving +1 rod margin
```

Because `locate_structure` can locate the stronghold later, do not waste eyes of ender by throwing them during this route unless another skill explicitly says so.

---

# 0. Completion condition

This skill is complete only when all of these are true:

```text
get_self_status shows inventory contains >= 7 minecraft:blaze_rod
Player is alive
Player is not in immediate danger
Player is back at the Nether portal, or at another safe, navigable Nether location
Ready to start phase 4: ender_pearls
```

Do not mark this skill complete if:

```text
You have fewer than 7 blaze rods.
You are still fighting blazes.
You are low HP and surrounded.
You are lost and cannot return.
You died and have not recovered rods.
You only saw a fortress but did not collect rods.
```

---

# 1. Absolute rules

## 1.1 Call `locate_structure` in the Nether

To find a fortress, use:

```text
locate_structure("minecraft:fortress")
```

Important:

```text
This must be called while IN the Nether.
Do not wander randomly looking for a fortress.
Do not confuse a bastion with a fortress.
Do not spend eyes of ender in this phase.
```

## 1.2 Track the portal coordinates before leaving it

Before traveling away from the Nether portal, call:

```text
get_self_status
```

Record:

```text
portalX
portalY
portalZ
dimension
```

You need these to return safely.

If the status shows you are not in the Nether, this skill cannot begin yet.

## 1.3 Blazes are dangerous; use range by default

Default blaze combat method:

```text
equip_item(bow)
shoot(blaze, small batch)
collect_items
check status
repeat
```

Melee is allowed only if ranged combat is unavailable or inefficient.

Melee fallback is acceptable when:

```text
You have a strong sword, preferably iron or diamond.
You have armor.
You have plenty of cooked food.
You are not standing near lava or bridge edges.
You fight one or two blazes at a time.
```

## 1.4 Do not mine the blaze spawner

Never break or mine the spawner before collecting enough rods.

The spawner is your renewable blaze source.

Incorrect:

```text
break_block spawner
```

Correct:

```text
Keep the spawner.
Control the area.
Kill spawned blazes.
Collect rods.
```

## 1.5 Health safety overrides farming

If health is low, retreat immediately.

Use this rule:

```text
Max HP is usually 20.
If HP <= 8, stop fighting and retreat.
If burning and HP <= 12, retreat and eat.
If hunger/food is low, stop farming and stabilize.
```

Do not keep shooting while dying.

---

# 2. Required and recommended equipment

## 2.1 Minimum equipment

Before committing to a fortress fight, prefer having:

```text
weapon: iron_sword or diamond_sword
ranged: bow
ammo: at least 32 arrows, preferably 48+
food: at least 12 cooked food
blocks: at least 64 solid blocks such as cobblestone/netherrack
pickaxe: iron_pickaxe or better
armor: any armor; iron armor preferred
```

## 2.2 Strong recommended equipment

Better setup:

```text
diamond_sword
bow with many arrows
shield if available
iron armor or better
cooked_beef / cooked_porkchop / bread
64-128 blocks
pickaxe
flint_and_steel if portal relighting is needed
```

## 2.3 Optional bonuses

Useful but not required:

```text
fire_resistance_potion
golden_apple
enchanted_bow
Power bow
Infinity bow
Looting sword
extra food
extra blocks
```

Looting helps because blaze rods are mob drops.

## 2.4 If equipment is poor

If you have:

```text
no food
no weapon
no bow and no armor
very low HP
```

Then do not start farming.

First retreat to a safe place and prepare.

---

# 3. Tool reference

Use these tools as available.

| Tool | Use |
|---|---|
| `get_self_status` | Check dimension, coordinates, HP, hunger, inventory, rod count. |
| `locate_structure` | Find exact fortress direction and distance. |
| `move_to` | Navigate to portal, fortress, safe spots, or spawner area. |
| `scan_blocks` | Find fortress blocks or spawners nearby. |
| `inspect_block` | Confirm suspicious blocks, ground, lava, spawner area. |
| `equip_item` | Equip bow, sword, food, blocks, shield, etc. |
| `shoot` | Safely attack blazes from range. |
| `hunt` | Melee fallback for blazes or wither skeletons. |
| `collect_items` | Pick up blaze rods and other drops. |
| `place_block` | Block line of sight, make barriers, bridge gaps, protect from lava. |
| `break_block` | Fix small mistakes, open single-block paths, never break spawner. |

In survival, barriers must use real inventory placement with `place_block`; `fill` is creative-only. If the companion is genuinely in creative mode and shortcuts are allowed, load `building` before using `fill` and inspect the fortress/spawner area so the spawner is not overwritten.

---

# 4. Phase overview

The complete phase is:

```text
1. Confirm you are in the Nether.
2. Record portal coordinates.
3. Locate nearest Nether fortress.
4. Travel safely toward fortress coordinates.
5. Scan for nether brick blocks to find the actual fortress structure.
6. Enter fortress carefully.
7. Find blaze spawner or natural blaze spawning area.
8. Set up safe fighting position.
9. Kill blazes in small batches.
10. Collect rods after every batch.
11. Repeat until >= 7 rods.
12. Retreat and return to portal/safe base.
13. Mark phase completed.
14. Load ender_pearls.
```

---

# 5. Starting checklist

Call:

```text
get_self_status
```

Confirm:

```text
dimension == Nether
HP is safe
food exists
weapon exists
coordinates are known
```

Record the portal or staging point:

```text
portalX = current x
portalY = current y
portalZ = current z
```

If you just came through the portal, the current coordinates are the portal coordinates.

If you moved away from the portal already, navigate back or use known portal coordinates before continuing.

---

# 6. Finding the fortress

## 6.1 Use locate_structure

Call:

```text
locate_structure("minecraft:fortress")
```

The result should give something like:

```text
fortressX
fortressZ
direction
distance
```

Important notes:

- Treat locate coordinates as an approach point; verify fortress blocks and safe elevation before final entry.
- If the direct path crosses lava or a cliff, route around it or build a guarded bridge instead of forcing movement.
- Keep the saved portal and fortress coordinates in the task state so pause/restart recovery does not lose the return route.

## Final verification and recovery

Before reporting completion, inspect inventory for the actual rod count, current HP, food, arrows and the route back. If the count is short, repeat only the controlled farm loop. If the spawner area becomes unsafe, retreat, heal and re-enter; do not discard gathered rods or reset the whole phase.
