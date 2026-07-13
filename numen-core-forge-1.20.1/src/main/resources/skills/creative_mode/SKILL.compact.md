# Creative mode: compact execution rules

- Confirm `game_mode=creative`. Do not mine, farm, smelt or craft merely to obtain resources; use `creative_give` or narrowly scoped commands.
- Prefer normal tools for precise interactions and `fill` for regular bulk construction. Use the building skill for structures.
- Commands are powerful: use exact coordinates and narrow selectors. Never run global/destructive commands, mass kill, broad replace or world-rule changes unless explicitly requested.
- Fly or teleport for travel, but verify dimension and destination safety. Avoid void mistakes.
- Generate only reasonable quantities. Preserve owner builds, inventories and entities.
- After every command/fill/give, inspect the affected world/inventory and verify actual outcome.
- Creative removes resource and ordinary damage constraints, not the need for scope, correctness, confirmation and cleanup.

## Compact reference: early rules and setup

# Skill: creative_mode

You are in **creative mode**.

Creative mode changes almost every decision rule. You do not need to mine, craft, farm, eat, fight for resources, or avoid normal damage. You can fly, instantly break blocks, give yourself any item, place unlimited blocks, and use OP-level commands.

Load this skill immediately when:

```text
get_self_status reports game_mode=creative
```

Keep it active until:

```text
get_self_status reports game_mode is no longer creative
```

---

# 0. Core principle

In creative mode, do not play like survival.

Survival behavior:

```text
mine resources
craft tools
farm drops
avoid hunger
walk around obstacles
fight mobs for materials
slowly break blocks
```

Creative behavior:

```text
creative_give needed items
fly or teleport
use fill for rectangular builds
use run_command for vanilla commands
break blocks instantly
ignore hunger and normal damage
use commands to set time/weather/gamerules when helpful
```

If a survival-route skill asks for items and you are in creative mode, satisfy the item requirement directly unless the user explicitly asked for a legit survival method.

Example:

```text
Need 7 blaze rods in creative mode:
creative_give item_id="minecraft:blaze_rod" count=7
get_self_status
```

Do not spend time locating a fortress and killing blazes unless the user specifically wants the process.

---

# 1. Done condition for this skill

This skill is not a standalone task. It is an operating mode.

It is “active” when:

```text
game_mode=creative
```

It is “done” only when:

```text
The current user task is complete
or game_mode is no longer creative
```

For every task in creative mode, final success should mean:

```text
task objective completed
player safe
world not accidentally damaged
coordinates/status verified when needed
```

---

# 2. Creative-only tools

## 2.1 Tool table

| Tool | Purpose |
|---|---|
| `get_self_status` | Check game mode, dimension, coordinates, inventory, status. |
| `creative_give` | Give any item/block directly. No mining or crafting. |
| `fill` | Place or clear rectangular volumes safely and efficiently. |
| `run_command` | Run vanilla Minecraft commands with OP permissions. |
| `move_to` | Fly/move to any coordinate, including high Y coordinates. |
| `place_block` | Place single blocks, oriented blocks, doors, stairs, torches, details. |
| `break_block` | Instantly remove one block. |
| `inspect_block` | Check a block before modifying it. |
| `scan_blocks` | Find nearby block types if available. |
| `collect_items` | Collect dropped items if needed, though creative usually uses `creative_give`. |

## 2.2 Creative-only capabilities

In creative mode:

```text
You have unlimited item access.
You can fly.
You can break most blocks instantly.
You ignore hunger.
You ignore most normal damage.
You do not need tools to mine.
You do not need crafting tables or furnaces.
You can use commands to alter the world.
```

Important nuance:

```text
Creative does not mean careless.
Commands can still destroy builds, delete items, move players, or lag the world.
Use exact coordinates and narrow selectors.
```

---

# 3. First action in creative mode

Always begin with:

```text
get_self_status
```

Record:

```text
game_mode
dimension
x
y
z
looking direction if available
inventory if relevant
```

Then decide:

```text
Is this a building task?
Is this an item/resource task?
Is this a travel/navigation task?
Is this a world-control task?
Is this a decoration/effects task?
```

If building:

```text
load_skill(name="building")
```

Creative mode helps with building, but the `building` skill still controls architectural quality.

---

# 4. Safety rules for OP commands

`run_command` is powerful. Treat it as dangerous by default.

## 4.1 Prefer narrow commands

Good:

```text
run_command command="/kill @e[type=minecraft:creeper,distance=..20]"
```

Bad:

```text
run_command command="/kill @e"
```

Good:

```text
run_command command="/fill 100 64 100 110 70 110 minecraft:air replace minecraft:stone"
```

Bad:

```text
run_command command="/fill -1000 0 -1000 1000 255 1000 minecraft:air"
```

## 4.2 Do not run destructive global commands unless explicitly requested

Do not use these casually:

```text
/kill @e
/kill @a
/clear @a
/clear @s
/fill huge_area minecraft:air
/stop
/ban
/kick
/op
/deop
/save-off
/gamerule randomTickSpeed 1000000
/summon massive entity spam
/tp @a random_place
```

Only use dangerous commands if:

```text
The user explicitly requested it.
The target is narrow and controlled.
You understand the consequence.
```

## 4.3 Avoid modifying user builds accidentally

Before clearing or replacing blocks near existing structures:

```text
inspect_block
scan_blocks if useful
use small test area first
confirm coordinates
```

Never clear a large area blindly.

## 4.4 Use absolute coordinates for important operations

Relative coordinates are useful for small local actions, but big builds should use absolute coordinates.

Safer:

```text
/fill 100 64 200 120 74 220 minecraft:stone_bricks hollow
```

Riskier:

```text
/fill ~-20 ~ ~-20 ~20 ~10 ~20 minecraft:air
```

Relative commands depend on the current player position. If the player moved, the command may affect the wrong area.

---

# 5. `creative_give`: getting items

## 5.1 Use `creative_give` instead of mining/crafting

If you need any item:

```text
creative_give item_id="minecraft:diamond" count=64
```

Examples:

```text
creative_give item_id="minecraft:stone_bricks" count=64
creative_give item_id="minecraft:oak_planks" count=64
creative_give item_id="minecraft:glass" count=64
creative_give item_id="minecraft:glowstone" count=64
creative_give item_id="minecraft:diamond_sword" count=1
creative_give item_id="minecraft:elytra" count=1
creative_give item_id="minecraft:blaze_rod" count=7
creative_give item_id="minecraft:ender_pearl" count=16
creative_give item_id="minecraft:ender_eye" count=12
```

## 5.2 Do not craft if creative_give can solve it

Bad creative behavior:

```text
mine logs
craft planks
craft sticks
craft tools
```

Good creative behavior:

```text
creative_give item_id="minecraft:oak_planks" count=64
creative_give item_id="minecraft:diamond_pickaxe" count=1
```

## 5.3 Give enough, not absurd amounts

For common building blocks:

```text
count=64 is usually enough per call
repeat if many different blocks are needed
```

## Final verification and recovery

After creative actions, inspect the affected coordinates, inventory and entities. Confirm commands used the intended dimension, selector and bounds. If an action partially succeeded, repair the remaining scope only; never repeat a broad command without checking what already changed. Report completion with concrete evidence rather than relying on command acceptance.
