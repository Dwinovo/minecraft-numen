---
name: creative_mode
description: How to operate when game_mode=creative. Load this immediately when get_self_status reports game_mode=creative. Explains creative inventory, flight, commands, teleporting, building, item giving, world control, and safe OP command usage.
---

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

For non-stackable items:

```text
count=1
```

Examples:

```text
creative_give item_id="minecraft:diamond_sword" count=1
creative_give item_id="minecraft:bow" count=1
creative_give item_id="minecraft:shield" count=1
```

## 5.4 When to use `/give` instead

Use `run_command /give` when you need:

```text
custom NBT/components
enchanted items
command blocks
debug/special items
version-specific custom data
```

Examples:

```text
run_command command="/give @s minecraft:command_block 1"
run_command command="/give @s minecraft:barrier 64"
run_command command="/give @s minecraft:light 64"
```

NBT/component syntax changes by Minecraft version. If a custom `/give` command fails, simplify and use `creative_give`.

---

# 6. Flight and movement

## 6.1 Creative flight rule

In creative mode, you can fly.

Use:

```text
move_to x=... y=... z=...
```

Unlike survival, moving to high Y coordinates is allowed.

Good travel pattern:

```text
1. move_to currentX currentY+15 currentZ
2. move_to targetX safeHighY targetZ
3. move_to targetX targetGroundY+2 targetZ
```

This avoids terrain, lava, mobs, cliffs, trees, and buildings.

## 6.2 Safe flying height

Useful travel heights:

```text
Overworld building travel: y=90 to y=140
Nether travel: y=80 to y=115, depending on terrain
End travel: y=80 to y=120
```

Do not fly extremely high unless needed.

## 6.3 Use `/tp` for instant travel

If `move_to` is slow, stuck, or unnecessary, use:

```text
run_command command="/tp @s 100 80 200"
```

Examples:

```text
run_command command="/tp @s ~ ~20 ~"
run_command command="/tp @s 0 100 0"
run_command command="/tp @s 250 90 -400"
```

Use high Y when unsure:

```text
run_command command="/tp @s 250 120 -400"
```

Then descend with `move_to` or another `/tp`.

## 6.4 Cross-dimension teleporting

For Java-style commands, use `execute in`.

Overworld:

```text
run_command command="/execute in minecraft:overworld run tp @s 0 100 0"
```

Nether:

```text
run_command command="/execute in minecraft:the_nether run tp @s 0 90 0"
```

End:

```text
run_command command="/execute in minecraft:the_end run tp @s 0 100 0"
```

Always teleport to a safe high Y first, not inside ground.

## 6.5 Avoid void and command death

Creative ignores most normal damage, but still avoid:

```text
void travel
/tp to extremely low Y
/kill commands
suffocating into unloaded or invalid spaces
```

Safe default:

```text
If uncertain, teleport to y=100 or higher.
```

---

# 7. `run_command`: command basics

## 7.1 Syntax

Use:

```text
run_command command="/command arguments..."
```

Examples:

```text
run_command command="/time set day"
run_command command="/weather clear"
run_command command="/tp @s 100 80 200"
run_command command="/setblock 100 64 200 minecraft:stone_bricks"
```

If the command tool rejects the leading `/`, retry without it:

```text
run_command command="time set day"
```

But prefer including `/` in examples.

## 7.2 Target selectors

Use selectors carefully.

| Selector | Meaning | Safe default? |
|---|---|---|
| `@s` | Yourself / command executor | Yes |
| `@p` | Nearest player | Sometimes |
| `@a` | All players | Dangerous unless intended |
| `@e` | All entities | Dangerous unless filtered |
| `@r` | Random player | Rarely useful |

Prefer:

```text
@s
```

Avoid broad selectors:

```text
@a
@e
```

Unless filtered.

Good filtered selector:

```text
@e[type=minecraft:item,distance=..20]
```

Bad unfiltered selector:

```text
@e
```

## 7.3 Relative coordinates

Minecraft command coordinate types:

```text
100 64 200 = absolute coordinates
~ ~ ~ = current position
~5 ~ ~-3 = relative to current position
^ ^ ^5 = local coordinates relative to facing direction
```

Use relative coordinates only for small local operations.

Examples:

```text
run_command command="/tp @s ~ ~10 ~"
run_command command="/setblock ~ ~-1 ~ minecraft:gold_block"
run_command command="/fill ~-3 ~-1 ~-3 ~3 ~-1 ~3 minecraft:stone"
```

For large or important operations, calculate absolute coordinates.

---

# 8. World setup commands

Use these to improve visibility and building conditions.

## 8.1 Time

Set day:

```text
run_command command="/time set day"
```

Set noon:

```text
run_command command="/time set noon"
```

Freeze daylight cycle:

```text
run_command command="/gamerule doDaylightCycle false"
```

Only freeze time if helpful for the task.

## 8.2 Weather

Clear weather:

```text
run_command command="/weather clear"
```

Disable weather cycle:

```text
run_command command="/gamerule doWeatherCycle false"
```

Use for building/showcase tasks.

## 8.3 Visibility effects

Night vision:

```text
run_command command="/effect give @s minecraft:night_vision 999999 0 true"
```

Clear effects when done if needed:

```text
run_command command="/effect clear @s"
```

## 8.4 Movement effects

Speed if walking/flying manually is slow:

```text
run_command command="/effect give @s minecraft:speed 999999 1 true"
```

Do not use extreme amplifier values; they make movement hard to control.

## 8.5 Mob control

If mobs interfere with building:

```text
run_command command="/gamerule doMobSpawning false"
```

If creepers/endermen could damage builds:

```text
run_command command="/gamerule mobGriefing false"
```

Only use these if relevant. Do not disable spawning if the user wants a mob farm, combat arena, zoo, or survival-like environment.

Peaceful mode:

```text
run_command command="/difficulty peaceful"
```

Use only if hostile mobs are unwanted.

---

# 9. Breaking and clearing blocks

## 9.1 Single block removal

Use:

```text
break_block x=... y=... z=...
```

or:

```text
run_command command="/setblock 100 64 200 minecraft:air"
```

## 9.2 Rectangular clearing

Use `fill` or `/fill air`.

Preferred tool:

```text
fill block_id="minecraft:air" x1=100 y1=64 z1=200 x2=110 y2=70 z2=210 hollow=false
```

Command version:

```text
run_command command="/fill 100 64 200 110 70 210 minecraft:air"
```

## 9.3 Never loop single-block breaking for a rectangle

Bad:

```text
break_block every block in a 10x10 floor manually
```

Good:

```text
fill block_id="minecraft:air" x1=100 y1=64 z1=100 x2=109 y2=64 z2=109 hollow=false
```

## 9.4 Creative breaking usually does not give drops

If you need the item, do not break blocks and collect drops.

Use:

```text
creative_give item_id="minecraft:item_id" count=...
```

---

# 10. Building in creative mode

## 10.1 Always load building skill for building tasks

For any building task:

```text
load_skill(name="building")
```

The building skill controls:

```text
foundation
walls
rooms
roofs
doors
windows
symmetry
floors
decoration
```

Creative mode only changes how fast and powerfully you can execute.

## 10.2 Tool priority for building

Use tools in this order:

```text
1. save_blueprint -> plan_blueprint -> build_blueprint for safe reusable copies, rotations, and exact saved states
2. fill for simple rectangular volumes after target inspection
3. run_command /fill for special vanilla fill modes or block states
4. place_block for single details and oriented blocks
5. run_command /setblock for exact block states
6. break_block only for small corrections
```

Never place a large rectangular wall/floor block-by-block.

## 10.3 Creative building workflow

Standard workflow:

```text
1. get_self_status
2. choose safe build site
3. optional: /time set day and /weather clear
4. creative_give all materials
5. fill solid foundation
6. fill hollow room shells
7. fill floors and roof
8. fill air for doors/windows
9. fill glass into windows
10. place_block details: stairs, doors, torches, lanterns, signs
11. use blueprints for reusable modules; reserve commands for particles, lights, effects, summon, or explicitly chosen clone operations
12. inspect/verify final result
```

## 10.4 Native `fill` vs command `/fill`

Use native `fill` for most rectangular building:

```text
fill block_id="minecraft:stone_bricks" x1=100 y1=64 z1=200 x2=120 y2=74 z2=220 hollow=true
```

Use command `/fill` when you need vanilla modes:

```text
run_command command="/fill 100 64 200 120 74 220 minecraft:stone_bricks hollow"
run_command command="/fill 100 64 200 120 74 220 minecraft:stone_bricks outline"
run_command command="/fill 100 64 200 120 74 220 minecraft:air replace minecraft:stone_bricks"
```

## 10.5 Fill modes in vanilla `/fill`

Common modes:

```text
replace = replace blocks normally
hollow = create shell and clear inside
outline = create shell but do not clear inside
keep = only fill air blocks
destroy = break replaced blocks and drop items
```

Examples:

```text
run_command command="/fill 100 64 100 110 70 110 minecraft:stone_bricks replace"
run_command command="/fill 100 64 100 110 70 110 minecraft:stone_bricks hollow"
run_command command="/fill 100 64 100 110 70 110 minecraft:stone_bricks outline"
run_command command="/fill 100 64 100 110 70 110 minecraft:glass keep"
run_command command="/fill 100 64 100 110 70 110 minecraft:air replace minecraft:dirt"
```

## 10.6 Fill size limit

Vanilla `/fill` has a maximum block limit, often around 32768 blocks depending on server settings.

Native tool `fill` may have its own limit, such as 20000 blocks.

Calculate:

```text
volume = width * height * depth
width  = abs(x2 - x1) + 1
height = abs(y2 - y1) + 1
depth  = abs(z2 - z1) + 1
```

If too large, split into smaller fills.

---

# 11. `/setblock`: precise single-block command placement

Use `/setblock` for exact blocks at exact coordinates.

Basic:

```text
run_command command="/setblock 100 64 200 minecraft:stone_bricks"
```

Air:

```text
run_command command="/setblock 100 64 200 minecraft:air"
```

With replace mode:

```text
run_command command="/setblock 100 64 200 minecraft:gold_block replace"
```

Keep existing blocks:

```text
run_command command="/setblock 100 64 200 minecraft:lantern keep"
```

## 11.1 Block states

Some blocks need states.

Examples:

```text
run_command command="/setblock 100 64 200 minecraft:oak_log[axis=y]"
run_command command="/setblock 100 64 200 minecraft:stone_brick_stairs[facing=south,half=bottom]"
run_command command="/setblock 100 64 200 minecraft:oak_slab[type=bottom]"
run_command command="/setblock 100 64 200 minecraft:lantern[hanging=true]"
run_command command="/setblock 100 64 200 minecraft:light[level=15]"
```

If block-state syntax fails, use `place_block` if it supports facing/half/type parameters.

---

# 12. Doors, stairs, slabs, and oriented blocks

## 12.1 Prefer `place_block` for oriented details

Use `place_block` for:

```text
stairs
doors
trapdoors
torches
wall torches
buttons
levers
beds
signs
lanterns
fences
decorative details
```

Example:

```text
place_block block_id="minecraft:stone_brick_stairs" x=100 y=64 z=200 facing="south" half="bottom"
```

## 12.2 Door command placement

Doors have two block halves.

If using `/setblock`, place both lower and upper halves.

Example:

```text
run_command command="/setblock 100 64 200 minecraft:oak_door[facing=south,half=lower]"
run_command command="/setblock 100 65 200 minecraft:oak_door[facing=south,half=upper]"
```

If `place_block` can handle doors, prefer it.

## 12.3 Stairs command placement

Example:

```text
run_command command="/setblock 100 64 200 minecraft:stone_brick_stairs[facing=south,half=bottom]"
```

Common stair states:

```text
facing=north/south/east/west
half=bottom/top
shape=straight/inner_left/inner_right/outer_left/outer_right
```

Use simple straight stairs unless complex corners are needed.

---

# 13. Blueprint copying first; `/clone` as a deliberate command fallback

Prefer `save_blueprint`, `plan_blueprint`, and `build_blueprint`. Blueprints are owner-scoped, preview materials/conflicts, support 0/90/180/270 rotation, never copy block-entity NBT, refuse occupied conflicts, and can resume in batches. Load `building` for the full workflow.

Use `/clone` only when the exact vanilla box-copy behavior is explicitly useful and its overwrite/NBT behavior has been reviewed. Unlike the blueprint builder, `/clone` can copy block-entity data and overwrite a destination, so it has a larger destructive and duplication risk.

Syntax:

```text
/clone x1 y1 z1 x2 y2 z2 destX destY destZ [replace|masked|filtered] [normal|force|move]
```

Example:

```text
run_command command="/clone 100 64 100 110 74 110 120 64 100 replace normal"
```

This copies the source box:

```text
100 64 100 to 110 74 110
```

To destination starting at:

```text
120 64 100
```

## 13.1 Use cases

Use `/clone` for:

```text
copying towers
duplicating rooms
mirroring repeated modules manually
copying decorative patterns
reusing roof segments
```

## 13.2 Clone safety

Before cloning:

```text
verify source bounds
verify destination bounds
make sure destination is clear or intended to overwrite
avoid cloning huge areas
```

If unsure, inspect destination first.

---

# 14. `/summon`: spawning entities

Use `/summon` for decorations, mobs, villagers, armor stands, or effects.

Examples:

```text
run_command command="/summon minecraft:villager 100 65 200"
run_command command="/summon minecraft:armor_stand 100 65 200"
run_command command="/summon minecraft:cat 100 65 200"
```

Do not summon many entities unless requested.

Bad:

```text
summon 500 mobs
```

Good:

```text
summon 1-5 decorative mobs
```

If entities become a problem, remove only nearby specific types:

```text
run_command command="/kill @e[type=minecraft:item,distance=..20]"
run_command command="/kill @e[type=minecraft:zombie,distance=..30]"
```

Do not use:

```text
/kill @e
```

---

# 15. Particles, sounds, and titles

Use these for decoration/showcase only.

## 15.1 Particles

Example at exact coordinates:

```text
run_command command="/particle minecraft:happy_villager 100 66 200 1 1 1 0.02 50 normal"
```

Example near player:

```text
run_command command="/particle minecraft:firework ~ ~2 ~ 0.5 0.5 0.5 0.05 30 normal"
```

Useful particles:

```text
minecraft:happy_villager
minecraft:flame
minecraft:end_rod
minecraft:portal
minecraft:firework
minecraft:cloud
minecraft:heart
```

Particle syntax can vary by version. If a particle command fails, simplify it.

## 15.2 Sounds

Example:

```text
run_command command="/playsound minecraft:block.note_block.pling master @s ~ ~ ~ 1 1"
```

Use sounds sparingly.

## 15.3 Titles

Example:

```text
run_command command="/title @s title {\"text\":\"Build Complete\",\"color\":\"gold\"}"
```

Use `@s` unless the user wants all players to see it.

---

# 16. Lighting and invisible utility blocks

Creative mode can use special blocks.

## 16.1 Light block

Give:

```text
creative_give item_id="minecraft:light" count=64
```

Set exact light level:

```text
run_command command="/setblock 100 66 200 minecraft:light[level=15]"
```

Use light blocks for invisible lighting in builds.

## 16.2 Barrier block

Give:

```text
creative_give item_id="minecraft:barrier" count=64
```

Use barriers only when needed.

Do not trap players with invisible barriers unless explicitly requested.

## 16.3 Structure block and command block

Give:

```text
run_command command="/give @s minecraft:structure_block 1"
run_command command="/give @s minecraft:command_block 1"
```

Only use command blocks if the user asks for command-block systems.

Do not create repeating command blocks or permanent command loops unless explicitly requested.

---

# 17. Locating structures in creative mode

If a locate tool exists, prefer it:

```text
locate_structure("minecraft:village_plains")
```

If using commands:

```text
run_command command="/locate structure minecraft:village_plains"
run_command command="/locate structure minecraft:fortress"
run_command command="/locate structure minecraft:stronghold"
run_command command="/locate structure minecraft:ancient_city"
```

After locating:

```text
run_command command="/tp @s x y z"
```

Use safe Y if the locate result has no Y:

```text
run_command command="/tp @s locatedX 120 locatedZ"
```

Then descend carefully.

---

# 18. Creative mode and survival-route skills

When creative mode is active, resource-grinding skills should be shortcut unless user says otherwise.

## 18.1 Blaze rods

Instead of farming:

```text
creative_give item_id="minecraft:blaze_rod" count=7
get_self_status
```

## 18.2 Ender pearls

Instead of killing endermen or trading:

```text
creative_give item_id="minecraft:ender_pearl" count=16
get_self_status
```

## 18.3 Eyes of ender

Instead of crafting:

```text
creative_give item_id="minecraft:ender_eye" count=12
get_self_status
```

## 18.4 Food, tools, armor

No need to cook, mine, or craft:

```text
creative_give item_id="minecraft:cooked_beef" count=64
creative_give item_id="minecraft:diamond_pickaxe" count=1
creative_give item_id="minecraft:diamond_sword" count=1
creative_give item_id="minecraft:diamond_chestplate" count=1
```

## 18.5 If user asks for legitimate survival gameplay

If the user says:

```text
do it legit
no commands
survival style
don't cheat
```

Then do not shortcut with `creative_give` or commands, even if in creative mode, unless safety requires it.

Ask or follow the user’s constraint.

---

# 19. Inventory management

## 19.1 Inventory is not a limitation

Creative inventory is unlimited in practice.

Do not worry about:

```text
item durability
food count
block count
tool availability
crafting materials
```

## 19.2 Avoid clearing inventory

Do not use:

```text
run_command command="/clear @s"
```

unless the user asks.

Even in creative, clearing inventory may remove useful or sentimental items.

## 19.3 Use targeted clearing only if needed

If you must remove one item:

```text
run_command command="/clear @s minecraft:dirt 64"
```

Use carefully.

---

# 20. Chunk loading and command range

Commands usually work best near loaded chunks.

If a command fails far away:

```text
fly/teleport near the target first
then run the command
```

Example:

```text
run_command command="/tp @s 100 120 200"
run_command command="/fill 90 64 190 110 80 210 minecraft:stone_bricks hollow"
```

Use `/forceload` only if needed and remove it after.

Add forceload:

```text
run_command command="/forceload add 0 0"
```

Remove forceload:

```text
run_command command="/forceload remove 0 0"
```

Do not leave unnecessary forceloaded chunks.

---

# 21. Common creative command recipes

## 21.1 Prepare nice building conditions

```text
run_command command="/time set noon"
run_command command="/weather clear"
run_command command="/gamerule doDaylightCycle false"
run_command command="/gamerule doWeatherCycle false"
run_command command="/effect give @s minecraft:night_vision 999999 0 true"
```

## 21.2 Teleport upward

```text
run_command command="/tp @s ~ ~20 ~"
```

## 21.3 Teleport to coordinates

```text
run_command command="/tp @s 100 100 200"
```

## 21.4 Give building materials

```text
creative_give item_id="minecraft:stone_bricks" count=64
creative_give item_id="minecraft:dark_oak_planks" count=64
creative_give item_id="minecraft:polished_andesite" count=64
creative_give item_id="minecraft:glass" count=64
creative_give item_id="minecraft:glowstone" count=64
```

## 21.5 Build a solid platform

Native tool:

```text
fill block_id="minecraft:stone_bricks" x1=100 y1=63 z1=200 x2=120 y2=63 z2=220 hollow=false
```

Command:

```text
run_command command="/fill 100 63 200 120 63 220 minecraft:stone_bricks"
```

## 21.6 Build a hollow room

Native tool:

```text
fill block_id="minecraft:stone_bricks" x1=102 y1=64 z1=202 x2=118 y2=72 z2=218 hollow=true
```

Command:

```text
run_command command="/fill 102 64 202 118 72 218 minecraft:stone_bricks hollow"
```

## 21.7 Carve a doorway

Native tool:

```text
fill block_id="minecraft:air" x1=109 y1=64 z1=218 x2=111 y2=67 z2=218 hollow=false
```

Command:

```text
run_command command="/fill 109 64 218 111 67 218 minecraft:air"
```

## 21.8 Fill windows with glass

```text
fill block_id="minecraft:glass" x1=105 y1=67 z1=218 x2=106 y2=68 z2=218 hollow=false
```

## 21.9 Place invisible light

```text
run_command command="/setblock 110 70 210 minecraft:light[level=15]"
```

## 21.10 Remove dropped items nearby

```text
run_command command="/kill @e[type=minecraft:item,distance=..20]"
```

Do not remove all entities globally.

---

# 22. Command error handling

If `run_command` fails:

```text
1. Check spelling.
2. Check item/block ID.
3. Check coordinates.
4. Check whether the command needs or rejects leading slash.
5. Check whether the command syntax differs by Minecraft version.
6. Try a simpler command.
7. Use native tool instead.
```

Example fallback:

If this fails:

```text
run_command command="/fill 100 64 100 120 70 120 minecraft:stone_bricks hollow"
```

Try native tool:

```text
fill block_id="minecraft:stone_bricks" x1=100 y1=64 z1=100 x2=120 y2=70 z2=120 hollow=true
```

If custom item command fails:

```text
use creative_give item_id="minecraft:item" count=...
```

---

# 23. Creative mode decision table

| Task type | Best creative action |
|---|---|
| Need item | `creative_give` |
| Need many blocks placed in rectangle | `fill` or `/fill` |
| Need one exact block | `place_block` or `/setblock` |
| Need oriented detail | `place_block`, or `/setblock` with block states |
| Need travel | fly with `move_to`, or `/tp` |
| Need locate structure | `locate_structure`, or `/locate structure` |
| Need clear weather/daylight | `/weather clear`, `/time set day` |
| Need resource drop | `creative_give`, not farming |
| Need reusable/rotated/safe structure copy | Blueprint workflow from `building` |
| Need exact vanilla box clone with reviewed overwrite/NBT behavior | `/clone` |
| Need visual effect | `/particle`, `/playsound`, `/title` |
| Need mobs | `/summon`, carefully |
| Building task | load `building` skill |

---

# 24. Creative mode verification checklist

After any major operation, verify:

```text
get_self_status
inspect_block if needed
scan_blocks if needed
```

For building:

```text
foundation exists
walls/roof/floor complete
doors/windows open correctly
lighting exists
no accidental giant holes
player is not trapped
```

For item tasks:

```text
inventory contains required item count
```

For teleport/navigation:

```text
dimension correct
coordinates correct
not inside blocks
safe to continue
```

For commands:

```text
command affected intended target only
no global accidental effects
```

---

# 25. Common mistakes and fixes

## Mistake: Mining or crafting in creative

Fix:

```text
Use creative_give.
```

## Mistake: Walking long distances

Fix:

```text
Fly with move_to or use /tp.
```

## Mistake: Using many place_block calls for a rectangle

Fix:

```text
Use fill or /fill.
```

## Mistake: Clearing too large an area

Fix:

```text
Stop.
Recalculate coordinates.
Use smaller fills.
Inspect area before continuing.
```

## Mistake: Using @e without filter

Fix:

```text
Use type= and distance= filters.
```

Example:

```text
/kill @e[type=minecraft:item,distance=..20]
```

## Mistake: Running commands in wrong dimension

Fix:

```text
get_self_status
use /execute in minecraft:dimension run ...
```

Example:

```text
run_command command="/execute in minecraft:overworld run tp @s 0 100 0"
```

## Mistake: Creative resource skill still grinding mobs

Fix:

```text
creative_give required item directly unless user requested legit gameplay.
```

---

# 26. Final response style after creative tasks

When finishing a creative-mode task, report briefly:

```text
Done — completed [task] in creative mode.
Used creative tools/commands as needed.
Location: x=..., y=..., z=... if relevant.
```

For building:

```text
Done — built [structure] at approximately x=..., y=..., z=...
Included foundation, walls, roof, entrance, windows, lighting, and details.
```

For item tasks:

```text
Done — gave/obtained [item count] using creative mode.
```

Do not over-explain command details unless the user asks.

---

# 27. Highest-priority reminders

Always remember:

```text
1. If game_mode=creative, load this skill.
2. Use get_self_status first.
3. Use creative_give for items.
4. Use fly/move_to or /tp for movement.
5. Use fill for rectangles.
6. Use run_command for vanilla OP commands.
7. Use @s by default, not @a or @e.
8. Do not run destructive global commands.
9. For building, also load building skill.
10. Verify after major actions.
```
