# World atlas: compact locator rules

- `locate_structure` and `locate_biome` require exact namespaced registry IDs or supported `#tags`; never guess.
- Search only the current dimension. Move dimensions first when the target cannot generate here.
- Common routes: Overworld `minecraft:stronghold`, `minecraft:ruined_portal`; Nether `minecraft:fortress`; warped forest biome `minecraft:warped_forest`; End structures only in the End.
- A locate coordinate is an anchor, not necessarily an entrance or safe Y. Travel near it, then scan blocks/terrain/entities to confirm.
- Biome results are approximate borders. Re-scan after arrival.
- Do not confuse Nether fortress with bastion, stronghold with trial/underground structures, or normal ruined portal variants with unsafe underwater targets.
- If an ID fails, read the returned registry error, correct namespace/category/dimension, and try a known exact ID. Do not repeat the same invalid request.

## Compact reference: early rules and setup

# Skill: world_atlas

Use this skill before calling:

```text
locate_structure
locate_biome
```

These tools require exact registry IDs or valid `#tags`.

A guessed ID wastes time. A wrong category wastes time. A wrong dimension wastes time.

This atlas is the reference for vanilla Minecraft 1.20.1:

```text
34 vanilla structures
65 vanilla biomes
common structure tags
common biome tags
classic wrong-name traps
dimension routing
objective-based search recipes
```

If an ID is not listed here, assume it does not exist in vanilla 1.20.1 unless a datapack/mod adds it. In particular, Trial Chambers, Pale Garden, Breeze, Vault, Heavy Core, Creaking, and Resin are from later Minecraft releases and must not be requested in this project.

---

# 0. Core rule

Use the right locator for the right registry:

```text
structure/building/room/chest/monument/portal/fortress -> locate_structure
terrain/climate/ecosystem/forest/desert/ocean/cave biome -> locate_biome
```

Examples:

```text
locate_structure("minecraft:stronghold")
locate_structure("#minecraft:village")
locate_biome(biome="minecraft:warped_forest")
locate_biome(biome="#minecraft:is_ocean")
```

Do not mix them:

```text
locate_structure("minecraft:warped_forest")     # wrong: biome
locate_biome(biome="minecraft:fortress")        # wrong: structure
```

---

# 1. Immediate usage rules

## 1.1 Use full namespace

Preferred format:

```text
minecraft:stronghold
minecraft:fortress
minecraft:warped_forest
#minecraft:village
```

Some tools may accept short IDs like:

```text
stronghold
fortress
warped_forest
```

But this atlas uses full IDs to avoid ambiguity.

## 1.2 Tags start with `#`

Exact ID:

```text
minecraft:village_plains
```

Family tag:

```text
#minecraft:village
```

A tag means:

```text
find any member of this family
```

Use tags when any variant is acceptable.

Use exact IDs when the variant matters.

## 1.3 Current dimension only

Locator searches only the current dimension.

Examples:

```text
minecraft:fortress -> Nether only
minecraft:bastion_remnant -> Nether only
minecraft:end_city -> End only
minecraft:stronghold -> Overworld only
minecraft:village_* -> Overworld only
minecraft:ancient_city -> Overworld only
```

If the target belongs to another dimension:

```text
go to that dimension first
then call locate_structure / locate_biome
```

## 1.4 Locate result is not always the entrance

A structure locate usually gives a structure position, not necessarily the safest entrance or the exact room you want.

Examples:

```text
stronghold locate -> stronghold area, not portal room
fortress locate -> fortress area, not blaze spawner
ancient_city locate -> city area, not safe loot route
```

After locating:

```text
move_to near the result
scan_blocks / scan_nearby_entities / inspect_block
then navigate inside
```

## 1.5 Biome locate is approximate

`locate_biome` often returns a point near or inside the biome, but not every useful feature in it.

After locating a biome:

```text
move_to returned x/z
scan_blocks for biome-specific blocks
scan_nearby_entities for expected mobs
move around locally if needed
```

Example for warped forest:

```text
locate_biome(biome="minecraft:warped_forest")
move_to x=<resultX> z=<resultZ>
scan_blocks("minecraft:warped_nylium", radius=64)
scan_nearby_entities("minecraft:enderman", radius=64)
```

---

# 2. Classic ID traps

Memorize these.

| Common wrong guess | Correct ID |
|---|---|
| `minecraft:woodland_mansion` | `minecraft:mansion` |
| `minecraft:ocean_monument` | `minecraft:monument` |
| `minecraft:jungle_temple` | `minecraft:jungle_pyramid` |
| `minecraft:desert_temple` | `minecraft:desert_pyramid` |
| `minecraft:witch_hut` | `minecraft:swamp_hut` |
| `minecraft:pillager_tower` / `minecraft:outpost` | `minecraft:pillager_outpost` |
| `minecraft:nether_fortress` | `minecraft:fortress` |
| `minecraft:bastion` | `minecraft:bastion_remnant` |
| `minecraft:trail_ruin` | `minecraft:trail_ruins` |
| `minecraft:woodland` | `minecraft:mansion` |
| `minecraft:ocean_ruins` | `#minecraft:ocean_ruin` or exact warm/cold ID |
| `minecraft:any_village` | `#minecraft:village` |
| `minecraft:ruined_portals` | `#minecraft:ruined_portal` |

Important village rule:

```text
Any village = #minecraft:village
Concrete village IDs:
minecraft:village_plains
minecraft:village_desert
minecraft:village_savanna
minecraft:village_snowy
minecraft:village_taiga
```

---

# 3. Dragon-route quick searches

These are the most important calls for the end-game route.

| Goal | Dimension | Call | Notes |
|---|---|---|---|
| Get obsidian from ruined portal | Overworld | `locate_structure("#minecraft:ruined_portal")` | Skip ocean variant if underwater. |
| Enter Nether safely | Overworld | no locate needed after portal built | Record portal coordinates. |
| Find Nether fortress | Nether | `locate_structure("minecraft:fortress")` | Blaze rods phase. |
| Find warped forest | Nether | `locate_biome(biome="minecraft:warped_forest")` | Best ender pearl hunting. |
| Find stronghold | Overworld | `locate_structure("minecraft:stronghold")` | Replaces eye throwing. |
| Find End city | End outer islands | `locate_structure("minecraft:end_city")` | Post-dragon elytra/shulker phase. |

Do not throw eyes to find the stronghold if `locate_structure` is available.

---

# 4. Locator workflows

## 4.1 Structure workflow

Use for buildings/rooms/loot/portals.

```text
get_self_status
confirm correct dimension
locate_structure("minecraft:<structure_id>")
record result coordinates
move_to near result
scan_blocks for signature blocks
inspect_block if needed
enter/handle hazards
```

Example: stronghold

```text
get_self_status
# must be Overworld
locate_structure("minecraft:stronghold")
move_to x=<strongholdX> y=60 z=<strongholdZ>
move_to x=<strongholdX> y=30 z=<strongholdZ>
scan_blocks("minecraft:end_portal_frame", radius=128)
```

Example: fortress

```text
get_self_status
# must be Nether
locate_structure("minecraft:fortress")
move_to x=<fortressX> y=70 z=<fortressZ>
scan_blocks("minecraft:nether_bricks", radius=128)
scan_blocks("minecraft:spawner", radius=64)
```

## 4.2 Biome workflow

Use for terrain/ecosystem searches.

## Additional compact constraints

eeded
enter/handle hazards
```

Example: stronghold

```text
get_self_status
# must be Overworld
locate_structure("minecraft:stronghold")
move_to x=<strongholdX> y=60 z=<strongholdZ>
move_to x=<strongholdX> y=30 z=<strongholdZ>
scan_blocks("minecraft:end_portal_frame", radius=128)
```

Example: fortress

```text
get_self_status
# must be Nether
locate_structure("minecraft:fortress")
move_to x=<fortressX> y=70 z=<fortressZ>
scan_blocks("minecraft:nether_bricks", radius=128)
scan_blocks("minecraft:spawner", radius=64)
```

## 4.2 Biome workflow

Use for terrain/ecosystem searches.

```text
get_self_status
confirm correct dimension
locate_biome(biome="minecraft:<biome_id>")
record result coordinates
move_to x=<biomeX> z=<biomeZ>
scan_blocks / scan_nearby_entities to confirm
```

Example: warped forest

```text
get_self_status
# must be Nether
locate_biome(biome="minecraft:warped_forest")
move_to x=<biomeX> y=70 z=<biomeZ>
scan_blocks("minecraft:warped_nylium", radius=64)
scan_nearby_entities("minecraft:enderman", radius=64)
```

## 4.3 Tag workflow

Use tags when any variant works.

```text
locate_structure("#minecraft:village")
locate_structure("#minecraft:ruined_portal")
locate_biome(biome="#minecraft:is_ocean")
```

If the returned variant is bad for the task, skip it and search elsewhere.

Example:

```text
# Need obsidian from ruined portal
locate_structure("#minecraft:ruined_portal")
# If result is ruined_portal_ocean, skip because underwater
move to another region or re-search
```

---

# 5. Dimension routing

## 5.1 Overworld-only structures

```text
minecraft:stronghold
minecraft:village_plains
minecraft:village_desert
minecraft:village_savanna
minecraft:village_snowy
minecraft:village_taiga
minecraft:mineshaft
minecraft:mineshaft_mesa
minecraft:desert_pyramid
minecraft:jungle_pyramid
minecraft:swamp_hut
minecraft:woodland_mansion
```

Use the exact ID appropriate to the owner’s target; tags may return any member and therefore need post-arrival verification.

## Final verification and recovery

After locating, record dimension and coordinates, travel to a safe nearby position, and confirm the actual structure/biome with scans. If a result is inaccessible or the wrong variant, move far enough before searching again. On resume, reuse verified saved coordinates; do not spend another locate call unless the prior result is invalid or the dimension changed.
