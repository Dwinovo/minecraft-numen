# Stronghold and End portal: compact route

1. Count pearls, rods/powder and existing eyes. Craft enough eyes with `craft_items`, preserving fight supplies.
2. Use `locate_structure("minecraft:stronghold")`; do not waste eyes by throwing them.
3. Travel to the coordinate, then scan/search underground to find the actual stronghold and portal room.
4. Secure the room: kill threats, break the silverfish spawner, protect against lava, but never break portal frames or obstruct the 3x3 interior.
5. Inspect each frame. Insert eyes only into empty frames with verified interactions; do not repeat filled frames.
6. Verify the portal activated. Do not enter until dragon gear, food, blocks, bow/arrows and healing plan are ready.
7. Record portal-room coordinates and load `dragon_combat` before entry.

## Compact reference: early rules and setup

# Skill: stronghold_finding

This is Phase 5 of the dragon route.

Goal:

```text
Craft enough eyes of ender.
Locate the stronghold.
Reach the End portal room.
Secure the room.
Fill all empty End portal frames.
Activate the End portal.
Verify dragon fight packlist.
Stop at the activated portal until ready for dragon_combat.
```

This skill ends when:

```text
You are standing at an activated End portal.
The purple/starfield End portal surface is visible.
Dragon-fight gear is still intact.
```

Do not enter the End casually. Entering the End starts the final boss phase and is one-way until the dragon dies.

---

# 0. Completion condition

This skill is complete only when all of these are true:

```text
Stronghold found.
End portal room found.
Silverfish/lava hazards controlled enough to stand safely.
All 12 End portal frames are filled or already had eyes.
End portal is active.
Purple/starfield portal surface is visible in the 3x3 center.
get_self_status confirms dragon_combat packlist is still intact.
Owner has been told the portal is active and where it is.
```

Do not mark complete if:

```text
You only located the stronghold but did not find the portal room.
You found the portal room but did not activate the portal.
Some frames are still empty.
You ran out of eyes.
You are low HP or being attacked.
The portal is active but dragon gear/food/arrows/blocks are missing.
You already jumped into the End without loading dragon_combat.
```

---

# 1. Absolute priority rules

## 1.1 Use `locate_structure`, not thrown eyes

Default stronghold navigation:

```text
locate_structure("minecraft:stronghold")
```

Do not throw eyes of ender for navigation when `locate_structure` exists.

Why:

```text
Eyes can break when thrown.
Eyes are needed to fill portal frames.
locate_structure gives the target directly.
Throwing eyes wastes resources and time.
```

Only use thrown-eye navigation if:

```text
locate_structure is unavailable
or the owner explicitly requests vanilla eye-throwing navigation
```

## 1.2 Eyes are for frames only

Use eyes of ender for:

```text
filling End portal frames
```

Do not use them for:

```text
random throwing
decoration
testing
unnecessary interactions
```

Eyes placed in portal frames cannot be removed.

## 1.3 Do not enter the End before preparation

Before entering the End portal:

```text
load_skill(name="dragon_combat")
get_self_status
verify dragon packlist
tell owner portal is active
```

If gear is missing:

```text
do not enter
backfill supplies first
```

## 1.4 Break the silverfish spawner

The End portal room has a silverfish spawner.

Unlike blaze spawners, this one is a liability.

Correct:

```text
auto_mine("minecraft:spawner")
```

Incorrect:

```text
leave the silverfish spawner active
```

## 1.5 Do not break End portal frames

End portal frames are not useful to mine and are required for activation.

Do not:

```text
auto_mine("minecraft:end_portal_frame")
break_block on portal frames
```

Only interact with empty frames using an eye of ender.

## 1.6 Do not block the 3x3 portal interior

You may place blocks around the portal room to make standing safer.

Do not place blocks into:

```text
the 3x3 center inside the End portal frame ring
the End portal frame blocks
the final active portal surface
```

Blocking the activation area can cause confusion and may prevent or obscure activation.

---

# 2. Required support skills

## 2.1 `containers`

Load for crafting eyes:

```text
load_skill(name="containers")
```

Use for:

```text
planning/crafting blaze powder
planning/crafting eyes of ender
manual crafting-grid fallback
checking exact counts
```

Use `plan_crafting` then `craft_items` for the supported vanilla conversions. This preserves real consumption while making the multi-step call resumable. Manual slot movement remains a fallback.

## 2.2 `combat_basics`

Load for stronghold and portal room combat:

```text
load_skill(name="combat_basics")
```

Use for:

```text
silverfish
zombies
skeletons
creepers
spiders
general cave/stronghold threats
HP management
retreat rules
```

## 2.3 `dragon_combat`

Load after the portal is active, before entering:

```text
load_skill(name="dragon_combat")
```

Do not wait until after jumping into the End.

## 2.4 `creative_mode`

If:

```text
get_self_status reports game_mode=creative
```

then:

```text
load_skill(name="creative_mode")
```

If creative shortcuts are allowed:

```text
creative_give item_id="minecraft:ender_eye" count=12
locate_structure("minecraft:stronghold")
fly/teleport as appropriate
```

If the owner asked for legit survival-style play, do not shortcut.

---

# 3. Tools used in this skill

| Tool | Use |
|---|---|
| `get_self_status` | Check dimension, coordinates, HP, inventory, food, eyes, pearls, rods, gear. |
| `locate_structure` | Find the stronghold directly. |
| `move_to` | Travel to stronghold and dig/navigate to it. |
| `scan_blocks` | Find stone bricks, End portal frames, spawner, lava, portal room blocks. |
| `inspect_block` | Check End portal frame state, especially whether it already has an eye. |
| `interact_at` | Insert eyes into frames, open doors/chests if needed. |
| `auto_mine` | Break silverfish spawner, mine stone/blocks if needed. |
| `break_block` | Small corrections only; do not break portal frames. |
| `place_block` | Make safe footing, block lava edges, mark paths. |
| `equip_item` | Equip pickaxe, sword, bow, food, blocks. |
| `hunt` | Kill silverfish and hostile mobs. |
| `eat_item` | Heal directly. |
| `lookup_recipe` | Get blaze powder / eye of ender recipe. |
| `plan_crafting` | Calculate required blaze rods/powder/pearls and missing base materials. |
| `craft_items` | Execute supported recipes with real ingredients and restart recovery. |
| `inspect_gui` | Read crafting grid and inventory slots. |
| `transfer` | Manual crafting fallback and exact container movement. |
| `close_gui` | Close crafting/container GUI. |

---

# 4. Phase prerequisites

Before starting, call:

```text
get_self_status
```

Verify you have or can craft:

```text
>= 12 ender pearls
enough blaze rods or blaze powder for 12 eyes
good weapon
pickaxe
food
blocks
bow/arrows for final phase
armor
```

## 4.1 Required materials for portal activation

Worst case:

```text
12 eyes of ender
```

Recipe:

```text
1 blaze powder + 1 ender pearl = 1 eye of ender
```

Blaze rod conversion:

```text
1 blaze rod = 2 blaze powder
```

Therefore:

calculate powder requirements from the number of missing eyes, while retaining any owner-requested reserve. Craft only the required amount and verify eyes afterward.

## Final verification and recovery

Record stronghold and portal-room coordinates. Confirm the silverfish spawner is gone, lava is controlled, all frames are filled and the portal field is visibly active. On resume, inspect frames again before using another eye. Never enter the End automatically merely because activation succeeded; perform the dragon packlist gate first.
