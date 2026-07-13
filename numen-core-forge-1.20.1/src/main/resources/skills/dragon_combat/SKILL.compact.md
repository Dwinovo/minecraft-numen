# Ender Dragon: compact fight

## Before entry

Verify armor, sword, pickaxe, bow, ample arrows, food/healing and blocks. Record the portal room. Do not enter underprepared.

## Fight

1. On arrival, check whether the obsidian platform is separated/embedded; bridge or dig to the island without void exposure.
2. Move away from island edges and establish safe ground.
3. Destroy every end crystal before focusing the dragon. Shoot open crystals from distance. For caged crystals, approach/pillar safely, break bars, retreat, then destroy; never detonate at close range.
4. Re-scan pillars to confirm no healing crystal remains.
5. Shoot during safe flight windows. During a perch, attack the head with melee from the inner fountain area; avoid breath clouds and knockback paths.
6. Ignore/avoid endermen unless aggroed. Heal early, retreat from bad positioning, and never chase toward the void.
7. Verify dragon death and exit portal. Collect rewards only when safe; return through the portal after owner goals are complete.

## Compact reference: early rules and setup

# Skill: dragon_combat

This is Phase 6 of the dragon route: the final boss.

The Ender Dragon has:

```text
200 HP
high knockback attacks
flight movement
perch phases
healing from end crystals
void-based arena danger
```

The main danger is not just damage. The main danger is:

```text
falling into the void
getting knocked off the island
standing in dragon breath
exploding crystals at close range
running out of food/arrows
fighting before crystals are destroyed
```

Load this skill when:

```text
You are about to enter the End portal.
You are in the End dimension.
You are fighting the Ender Dragon.
You are destroying end crystals.
```

Also load:

```text
combat_basics
```

because this fight uses the general HP, food, retreat, hunt, shoot, and positioning rules.

---

# 0. Completion condition

This phase is complete only when:

```text
The Ender Dragon reaches 0 HP.
The death animation plays.
The central bedrock exit portal opens.
The dragon egg appears on top of the portal fountain.
The player/entity is alive.
The fight is over and the arena is safe.
```

After completion:

```text
Congratulate the owner.
Mark the endgame route completed.
Do not punch the dragon egg unless specifically asked.
Wait for owner instruction before entering the exit portal if appropriate.
```

Do not mark complete if:

```text
Some crystals are still alive.
The dragon is still flying.
The dragon is only low HP.
You died.
You are lost on the End island.
You escaped but dragon is alive.
```

---

# 1. Absolute priority rules

## 1.1 Crystals first, always

End crystals heal the dragon.

If any crystal remains alive:

```text
damaging the dragon is inefficient
dragon HP can regenerate
arrows and melee time are wasted
```

Therefore:

```text
Destroy all crystals before focusing dragon damage.
```

Correct priority:

```text
1. Get safely onto central island.
2. Destroy all open crystals.
3. Open and destroy caged crystals.
4. Verify no crystals remain.
5. Kill dragon.
```

Wrong priority:

```text
shoot dragon while crystals are still healing it
hunt dragon before crystals are gone
ignore caged crystals
```

## 1.2 Never fight near the island edge

The void is the true final boss.

Avoid:

```text
island rim
obsidian platform edge
pillar tops after crystal is exposed
bridges over void
cliffs
knockback-prone positions
```

Fight near:

```text
central island
bedrock fountain area
solid end stone
wide terrain
```

If the dragon knocks you near the edge:

```text
move_to back toward center immediately
```

## 1.3 Never detonate a crystal at close range

End crystals explode.

Do not:

```text
punch crystals
stand next to crystals
shoot a crystal from point-blank range
open a cage and instantly break the crystal while beside it
```

Correct:

```text
open cage
retreat at least 8-12 blocks or descend to ground
shoot crystal from range
```

## 1.4 HP discipline is stricter than normal combat

This is a long boss fight.

Between every major tool call:

```text
get_self_status
```

Boss fight thresholds:

```text
HP >= 18: good
HP 14-17: eat before risky action
HP 11-13: disengage and eat if safe
HP <= 10: mandatory retreat/eat
HP <= 6: emergency; use golden_apple if available
```

Do not continue attacking at low HP.

## 1.5 Do not trust auto-eat alone

`hunt` and `shoot` may auto-eat when HP drops low, but this fight can kill through knockback, crystal explosion, and breath clouds.

Use:

```text
eat_item
get_self_status
```

between phases.

## 1.6 Re-equip correct tools deliberately

Use:

```text
bow for crystals and flying dragon
sword for perched dragon
pickaxe/auto_mine for iron bars
blocks for navigation/bridging/pillaring
```

After mining or moving, re-equip the correct combat item.

---

# 2. Required tools

| Tool | Use |
|---|---|
| `get_self_status` | Check HP, inventory, arrows, food, armor, position, dimension. |
| `equip_item` | Equip sword, bow, pickaxe, food, or blocks. |
| `shoot` | Destroy end crystals and damage flying dragon. |
| `hunt` | Melee the dragon during perch phase. |
| `move_to` | Travel from spawn platform to island, reposition, retreat, climb/descend pillars. |
| `auto_mine` | Mine iron bars around caged crystals if available. |
| `break_block` | Fallback for individual iron bars if auto_mine is unavailable. |
| `place_block` | Emergency block placement, bridging, cover, or path correction. |
| `scan_nearby_entities` | Find dragon, end crystals, or aggroed endermen if available. |
| `scan_blocks` | Locate iron bars/cages or terrain features if available. |
| `eat_item` | Heal directly using food. |
| `collect_items` | Usually not important during fight, but can recover drops if needed. |

---

# 3. Packlist before entering the End

Verify with:

```text
get_self_status
```

before entering the End portal.

Do not enter if the packlist is incomplete unless the user explicitly accepts the risk.

## 3.1 Required gear

Minimum:

```text
diamond_sword or better
bow
32+ arrows
128+ solid blocks, preferably cobblestone
32+ cooked food
armor equipped
pickaxe or auto_mine capability for iron bars
```

Preferred:

```text
diamond_sword
bow
64+ arrows
128-192 cobblestone
32+ cooked_beef or cooked_porkchop
golden_apple x1 or more
full iron/diamond armor
pickaxe
extra blocks
```

## 3.2 Why each item matters

Sword:

```text
Main damage during perch phase.
Dragon takes good melee damage while perched.
```

Bow:

```text
Required for crystals.
Required for flying dragon damage.
Required for safe crystal explosions.
```

Arrows:

```text
10 crystals
misses
flying dragon shots
emergency ranged attacks
```

Blocks:

```text
The End spawn platform can be separated from the island.
Navigation may need to bridge across void.
Caged crystal pillars may require pillaring/climbing.
Emergency path repair may be needed.
```

Food:

```text
Numen heals directly by eating.
This is a long fight.
Dragon breath and knockback can stack damage.
```

Golden apple:

```text
Emergency heal for boss danger.
Use at HP <= 6, or when trapped/knocked/burning-style damage pressure occurs.
```

Armor:

```text
Reduces dragon damage and enderman hits.
Do not fight dragon unarmored unless in creative mode or explicitly requested.
```

Pickaxe/auto_mine:

Use it only for caged-crystal bars, safe escape digging or necessary terrain correction. Never mine obsidian pillars casually while exposed to knockback.

## Final verification and recovery

Between phases, check HP, food, arrows, weapon and remaining crystals. A timeout or lost sight does not mean the target died: re-scan before attacking again. Completion requires verified dragon death and an active exit portal. If supplies become inadequate, prioritize survival and ask the owner rather than making a fatal final attempt.
