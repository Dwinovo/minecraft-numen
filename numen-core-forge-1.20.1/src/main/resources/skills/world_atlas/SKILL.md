---
name: world_atlas
description: Vanilla Minecraft 1.20.1 atlas for locate_structure and locate_biome. Lists exact registry IDs, structure/biome routing rules, dimension limits, classic ID traps, family tags, search recipes, dangers, loot, and error recovery. Load before using locate_structure or locate_biome.
---

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
minecraft:igloo
minecraft:swamp_hut
minecraft:mansion
minecraft:pillager_outpost
minecraft:monument
minecraft:ocean_ruin_cold
minecraft:ocean_ruin_warm
minecraft:shipwreck
minecraft:shipwreck_beached
minecraft:buried_treasure
minecraft:ancient_city
minecraft:trail_ruins
minecraft:ruined_portal
minecraft:ruined_portal_desert
minecraft:ruined_portal_jungle
minecraft:ruined_portal_mountain
minecraft:ruined_portal_ocean
minecraft:ruined_portal_swamp
```

## 5.2 Nether-only structures

```text
minecraft:fortress
minecraft:bastion_remnant
minecraft:nether_fossil
minecraft:ruined_portal_nether
```

## 5.3 End-only structures

```text
minecraft:end_city
```

## 5.4 Biome dimension groups

Overworld biomes:

```text
plains, forests, deserts, oceans, mountains, caves, deep_dark, etc.
```

Nether biomes:

```text
minecraft:nether_wastes
minecraft:crimson_forest
minecraft:warped_forest
minecraft:soul_sand_valley
minecraft:basalt_deltas
```

End biomes:

```text
minecraft:the_end
minecraft:end_highlands
minecraft:end_midlands
minecraft:end_barrens
minecraft:small_end_islands
minecraft:the_void
```

---

# 6. Structures — vanilla Minecraft 1.20.1 catalog

## 6.1 Overworld structures

| ID | Picture / use / danger |
|---|---|
| `minecraft:stronghold` | Stronghold underground; contains End portal room, libraries, silverfish, stone brick maze. Dragon route phase 5. |
| `minecraft:village_plains` | Plains village; beds, farms, villagers, iron golem, hay bales, basic loot. |
| `minecraft:village_desert` | Desert village; beds/farms/villagers in desert; useful food/trading shelter. |
| `minecraft:village_savanna` | Savanna village; acacia buildings, farms, villagers, iron golem. |
| `minecraft:village_snowy` | Snowy village; beds, villagers, farms, cold-biome shelter. |
| `minecraft:village_taiga` | Taiga village; spruce style, villagers, farms, berries nearby. |
| `#minecraft:village` | Tag for any of the five village types; use when any village is acceptable. |
| `minecraft:mineshaft` | Abandoned mineshaft; rails, cobwebs/string, cave spider spawners, minecart chests, ore access. |
| `minecraft:mineshaft_mesa` | Badlands/mesa mineshaft; often exposed at surface height, easy access to rails/chests/gold regions. |
| `#minecraft:mineshaft` | Tag for either normal or mesa mineshaft. |
| `minecraft:desert_pyramid` | Desert pyramid/temple; 4 hidden chests below center; pressure plate triggers TNT. Dig side, never step center. |
| `minecraft:jungle_pyramid` | Jungle temple; tripwire dispenser traps, arrows, small loot, lever puzzle. |
| `minecraft:igloo` | Snow igloo; possible hidden basement under carpet with brewing stand, weakness potion, golden apple, zombie villager. |
| `minecraft:swamp_hut` | Witch hut; witch + black cat, cauldron, no chest. Useful mostly for witch farms. |
| `minecraft:mansion` | Woodland mansion; very far, vindicators/evokers, Totem of Undying, allays may be jailed, easy to get lost. |
| `minecraft:pillager_outpost` | Pillager tower; crossbow pillagers, banner captain gives Bad Omen/Ominous effect risk, cages may hold allays/iron golems. |
| `minecraft:monument` | Ocean monument; underwater guardians, elder guardians, Mining Fatigue, sponge rooms, 8 gold blocks. Requires water plan. |
| `minecraft:ocean_ruin_cold` | Cold ocean ruin; stone/brick underwater ruins, drowned, treasure map/chest chance. |
| `minecraft:ocean_ruin_warm` | Warm ocean ruin; sandstone underwater ruins, drowned, treasure map/chest chance. |
| `#minecraft:ocean_ruin` | Tag for warm or cold ocean ruin. |
| `minecraft:shipwreck` | Shipwreck underwater; map/supply/treasure chests, iron/gold/emeralds/books. Drowned risk. |
| `minecraft:shipwreck_beached` | Beached shipwreck; same general loot, easier because no diving needed. |
| `#minecraft:shipwreck` | Tag for underwater or beached shipwreck. |
| `minecraft:buried_treasure` | Buried treasure chest under beach/ocean sand/gravel; Heart of the Sea, iron/gold/diamonds possible. Dig at locate point. |
| `minecraft:ancient_city` | Deep dark city around Y -52; Swift Sneak books, echo shards, loot; Warden/sculk shrieker danger. Sneak, avoid shriekers. |
| `minecraft:trail_ruins` | Trail ruins; mostly buried archaeology site; suspicious gravel needs brush; pottery sherds, armor trims. |
| `minecraft:ruined_portal` | Standard Overworld ruined portal; obsidian/crying obsidian/gold block/chest; dragon route obsidian source. |
| `minecraft:ruined_portal_desert` | Desert ruined portal variant; sand/desert terrain, same general loot/obsidian role. |
| `minecraft:ruined_portal_jungle` | Jungle ruined portal variant; vegetation/terrain may obstruct access. |
| `minecraft:ruined_portal_mountain` | Mountain ruined portal variant; elevation/cliffs possible. |
| `minecraft:ruined_portal_ocean` | Underwater ruined portal; skip if you cannot dive or mine underwater safely. |
| `minecraft:ruined_portal_swamp` | Swamp ruined portal variant; water/mud/witch area possible. |
| `#minecraft:ruined_portal` | Tag for all ruined portal variants, including Nether variant when in Nether. |

## 6.2 Nether structures

| ID | Picture / use / danger |
|---|---|
| `minecraft:fortress` | Nether fortress; blaze spawners, blaze rods, wither skeletons, nether wart. Required for dragon route phase 3. |
| `minecraft:bastion_remnant` | Bastion; gold blocks, piglin loot, netherite upgrade template chance; piglin brutes attack even with gold. Dangerous. |
| `minecraft:nether_fossil` | Nether fossil in soul sand valley; bone blocks only, usually not worth a dedicated search. |
| `minecraft:ruined_portal_nether` | Nether ruined portal; obsidian/crying obsidian/chest, may help portal recovery but lava/terrain can be dangerous. |

## 6.3 End structures

| ID | Picture / use / danger |
|---|---|
| `minecraft:end_city` | End city on outer End islands; shulkers, loot, shulker shells, End ship with elytra. Usually post-dragon via gateway. |

---

# 7. Biomes — vanilla Minecraft 1.20.1 catalog

Use `locate_biome(biome="minecraft:<id>")`.

Rows may group related IDs, but every exact ID is listed.

---

## 7.1 Overworld plains, forests, and surface biomes

| ID | Picture / use / danger |
|---|---|
| `minecraft:plains` | Flat open grassland; villages, horses, bees, easy building and animal hunting. |
| `minecraft:sunflower_plains` | Plains variant with sunflowers; sunflowers face east, useful natural direction cue. |
| `minecraft:snowy_plains` | Snowy flatland; snowy villages, igloos, rabbits, polar bears nearby in cold regions. |
| `minecraft:ice_spikes` | Snowy biome with packed ice spikes; packed ice source, rough terrain. |
| `minecraft:desert` | Sand, cactus, dead bushes; desert villages/pyramids/wells; husks at night do not burn. |
| `minecraft:forest` | Oak/birch forest; common wood, animals, bees. Basic starting biome. |
| `minecraft:flower_forest` | Flower-rich forest; many flower types, bees, dyes, beehives. |
| `minecraft:birch_forest` | Birch trees; easy wood visibility, mild terrain. |
| `minecraft:old_growth_birch_forest` | Tall birch variant; more vertical trees, still safe forest terrain. |
| `minecraft:dark_forest` | Dense roofed forest; hostile mobs can spawn under canopy even in daytime; mushrooms; mansion biome family. |
| `minecraft:taiga` | Spruce forest; wolves, foxes, sweet berries, villages possible. |
| `minecraft:snowy_taiga` | Snowy spruce forest; wolves/foxes, snow cover, cold terrain. |
| `minecraft:old_growth_pine_taiga` | Giant pine/spruce old-growth terrain; podzol, mushrooms, big trees. |
| `minecraft:old_growth_spruce_taiga` | Giant spruce old-growth terrain; podzol, large trunks, wolves/foxes. |
| `minecraft:savanna` | Acacia grassland; villages, horses, llamas, dry open terrain. |
| `minecraft:savanna_plateau` | Elevated savanna plateau; flatter high areas, cliff risk. |
| `minecraft:windswept_savanna` | Broken extreme savanna terrain; huge vertical drops, floating terrain, fall risk. |
| `minecraft:jungle` | Dense jungle; cocoa, melons, parrots, ocelots, vines, jungle pyramid possible. |
| `minecraft:sparse_jungle` | Less dense jungle edge-style biome; easier movement, jungle resources. |
| `minecraft:bamboo_jungle` | Bamboo, pandas, jungle resources; bamboo for scaffolding. |
| `minecraft:badlands` | Terracotta, red sand, extra gold at many heights, no passive animal spawns. |
| `minecraft:eroded_badlands` | Spiky badlands terrain; terracotta pillars, exposed mineshafts possible, fall risk. |
| `minecraft:wooded_badlands` | Badlands with trees at high layers; terracotta/gold plus some wood access. |
| `minecraft:mushroom_fields` | Mushroom island; hostile mobs do not normally spawn, mooshrooms, very safe base location. |

---

## 7.2 Overworld hills, mountains, and peaks

| ID | Picture / use / danger |
|---|---|
| `minecraft:windswept_hills` | Old mountain/hill terrain; emerald ore, llamas, cliffs. |
| `minecraft:windswept_gravelly_hills` | Gravel-heavy mountain; good for flint, but unstable drops/cliffs. |
| `minecraft:windswept_forest` | Forested mountain terrain; wood plus cliffs. |
| `minecraft:meadow` | Mountain meadow; flowers, bees, donkeys/rabbits, villages sometimes nearby. |
| `minecraft:cherry_grove` | Pink cherry trees, petals, bees; good decorative wood. |
| `minecraft:grove` | Snowy mountain forest; spruce, powdered snow danger. |
| `minecraft:snowy_slopes` | Snowy mountain slope; powdered snow traps, goats. Leather boots prevent powder snow sinking. |
| `minecraft:frozen_peaks` | Ice/snow mountain peaks; goats, snow/ice, harsh terrain. |
| `minecraft:jagged_peaks` | Sharp high peaks; goats, extreme fall risk. |
| `minecraft:stony_peaks` | Stone peaks; exposed coal/iron/emerald, no snow, useful ore access. |

---

## 7.3 Overworld wetlands, rivers, beaches, and oceans

| ID | Picture / use / danger |
|---|---|
| `minecraft:swamp` | Swamp; slimes at night, witch huts, clay, blue orchids, shallow water. |
| `minecraft:mangrove_swamp` | Mangrove trees, mud, frogs, warm swamp terrain; dense roots can slow travel. |
| `minecraft:river` | Rivers; clay, sugar cane, fish, drowned risk. |
| `minecraft:frozen_river` | Frozen river; ice cover, cold terrain, drowned possible under ice. |
| `minecraft:beach` | Sand beach; turtles, buried treasure often nearby, easy digging. |
| `minecraft:snowy_beach` | Cold beach; snow/ice, turtles less useful, buried treasure possible. |
| `minecraft:stony_shore` | Stone/gravel shore at cliffs; exposed stone, rough travel. |
| `minecraft:ocean` | Normal ocean; kelp, squid, cod, shipwrecks/ruins possible. |
| `minecraft:deep_ocean` | Deep ocean; ocean monuments require deep ocean types. More dangerous diving. |
| `minecraft:warm_ocean` | Coral reefs, tropical fish, pufferfish; warm ruins. |
| `minecraft:lukewarm_ocean` | Warmer ocean; tropical fish/coral nearby, ruins/shipwrecks possible. |
| `minecraft:deep_lukewarm_ocean` | Deep warm-ish ocean; deeper diving risk, monument-compatible areas. |
| `minecraft:cold_ocean` | Cold ocean; cod/salmon, cold ruins, ice nearby sometimes. |
| `minecraft:deep_cold_ocean` | Deep cold ocean; monument-compatible, more diving danger. |
| `minecraft:frozen_ocean` | Icebergs, cold ocean mobs, shipwreck/ruins, polar bear regions. |
| `minecraft:deep_frozen_ocean` | Deep frozen ocean; icebergs + deep water, monument-compatible, difficult navigation. |

---

## 7.4 Overworld cave biomes and deep danger

| ID | Picture / use / danger |
|---|---|
| `minecraft:dripstone_caves` | Dripstone caves; pointed dripstone, lava drip farms, extra copper, stalactite fall danger. |
| `minecraft:lush_caves` | Lush caves; glow berries, moss, clay, axolotls, safer food/light sources underground. |
| `minecraft:deep_dark` | Deep dark; sculk, ancient cities, Warden. This is stealth territory, not combat territory. |

---

## 7.5 Nether biomes

| ID | Picture / use / danger |
|---|---|
| `minecraft:nether_wastes` | Basic Nether; netherrack, lava seas, zombified piglins, piglins, ghasts. |
| `minecraft:crimson_forest` | Crimson forest; hoglins are reliable Nether food, piglins, crimson wood. |
| `minecraft:warped_forest` | Warped forest; highest enderman density, few hostile mobs, no rain. Best pearl biome. |
| `minecraft:soul_sand_valley` | Soul sand/soil, skeletons and ghasts, slow travel, fossils. Dangerous open terrain. |
| `minecraft:basalt_deltas` | Basalt/blackstone, magma cubes, lava pockets, jagged terrain. Hardest Nether biome to traverse. |

---

## 7.6 End biomes

| ID | Picture / use / danger |
|---|---|
| `minecraft:the_end` | Main End island; dragon fight, obsidian pillars, End crystals, Endermen. |
| `minecraft:end_highlands` | Outer End highlands; chorus fruit and End cities can generate here. |
| `minecraft:end_midlands` | Outer End midlands; chorus terrain, transition areas. |
| `minecraft:end_barrens` | Outer island edges; void risk, sparse terrain. |
| `minecraft:small_end_islands` | Small outer islands; void crossing danger, end gateway areas. |
| `minecraft:the_void` | Void biome used in special presets; not a normal survival search target. If found in search intent, likely wrong objective. |

---

# 8. Common structure tags

Use these with `locate_structure`.

| Tag | Members / use |
|---|---|
| `#minecraft:village` | Any village: plains, desert, savanna, snowy, taiga. |
| `#minecraft:ruined_portal` | Any ruined portal variant in current dimension. |
| `#minecraft:shipwreck` | Underwater or beached shipwreck. |
| `#minecraft:ocean_ruin` | Warm or cold ocean ruin. |
| `#minecraft:mineshaft` | Normal or mesa mineshaft. |

Practical notes:

```text
Use #minecraft:village when you need any village.
Use #minecraft:ruined_portal when you need obsidian quickly.
Use exact IDs if water/biome variant matters.
```

Examples:

```text
locate_structure("#minecraft:village")
locate_structure("#minecraft:ruined_portal")
locate_structure("#minecraft:shipwreck")
```

---

# 9. Common biome tags

Use these with `locate_biome`.

Exact tag availability can vary with datapacks/version, but these are common routing tags in this environment:

| Tag | Use |
|---|---|
| `#minecraft:is_forest` | Any forest-like biome. Wood, animals, mansion-adjacent categories. |
| `#minecraft:is_ocean` | Any ocean biome. Shipwrecks, ruins, monument-compatible deep variants. |
| `#minecraft:is_mountain` | Mountain/peak biomes. Emerald, goats, cliffs. |
| `#minecraft:is_jungle` | Jungle family. Bamboo, cocoa, parrots, jungle temple. |
| `#minecraft:is_badlands` | Badlands family. Terracotta, gold, mesa mineshafts. |

Examples:

```text
locate_biome(biome="#minecraft:is_forest")
locate_biome(biome="#minecraft:is_ocean")
locate_biome(biome="#minecraft:is_badlands")
```

Use exact biome ID when the objective is specific:

```text
minecraft:warped_forest
minecraft:deep_dark
minecraft:mushroom_fields
```

---

# 10. Objective-based search recipes

## 10.1 Dragon route

| Need | Best search |
|---|---|
| Obsidian without casting | `locate_structure("#minecraft:ruined_portal")` |
| Blaze rods | `locate_structure("minecraft:fortress")` in Nether |
| Ender pearls | `locate_biome(biome="minecraft:warped_forest")` in Nether |
| Stronghold | `locate_structure("minecraft:stronghold")` in Overworld |
| Elytra post-dragon | `locate_structure("minecraft:end_city")` in End outer islands |

## 10.2 Food

| Need | Search |
|---|---|
| Passive animals | `locate_biome(biome="minecraft:plains")` or `minecraft:savanna` |
| Nether food | `locate_biome(biome="minecraft:crimson_forest")` for hoglins |
| Safe camp | `locate_biome(biome="minecraft:mushroom_fields")` |

## 10.3 Villagers and beds

| Need | Search |
|---|---|
| Any village | `locate_structure("#minecraft:village")` |
| Desert village | `locate_structure("minecraft:village_desert")` |
| Snow village | `locate_structure("minecraft:village_snowy")` |

## 10.4 Loot structures

| Need | Search |
|---|---|
| Early loot + TNT trap | `locate_structure("minecraft:desert_pyramid")` |
| Map/iron from ship | `locate_structure("#minecraft:shipwreck")` |
| Heart of the Sea | `locate_structure("minecraft:buried_treasure")` |
| Totem of Undying | `locate_structure("minecraft:mansion")` |
| Elytra | `locate_structure("minecraft:end_city")` |
| Swift Sneak / echo shards | `locate_structure("minecraft:ancient_city")` |

## 10.5 Materials

| Need | Search |
|---|---|
| Flint/gravel | `locate_biome(biome="minecraft:windswept_gravelly_hills")` |
| Gold | `locate_biome(biome="minecraft:badlands")` |
| Bamboo | `locate_biome(biome="minecraft:bamboo_jungle")` |
| Clay | `locate_biome(biome="minecraft:lush_caves")` or rivers/swamps |
| Coral | `locate_biome(biome="minecraft:warm_ocean")` |
| Packed ice | `locate_biome(biome="minecraft:ice_spikes")` |
| Bees/flowers | `locate_biome(biome="minecraft:flower_forest")` or `minecraft:meadow` |

---

# 11. Search safety notes

## 11.1 Underwater targets

Structures often underwater:

```text
minecraft:monument
minecraft:ocean_ruin_cold
minecraft:ocean_ruin_warm
minecraft:shipwreck
minecraft:ruined_portal_ocean
```

Do not route to them unless you can handle:

```text
drowning
drowned
guardians
mining fatigue
slow swimming
poor visibility
```

Prefer land alternatives when possible:

```text
minecraft:shipwreck_beached
non-ocean ruined portals
desert_pyramid
village
```

## 11.2 Deep underground targets

Deep structures/biomes:

```text
minecraft:ancient_city
minecraft:deep_dark
```

Need:

```text
good pickaxe
food
armor
blocks
combat/stealth plan
```

Do not casually enter deep dark. Warden is not a normal combat target.

## 11.3 Nether targets

Nether searches require:

```text
gold armor for piglins
food
blocks
bow
pickaxe
portal coordinates recorded
```

Fortress and bastion look different:

```text
fortress = nether bricks, blazes, wither skeletons
bastion = blackstone, gold, piglins, piglin brutes
```

Do not confuse them.

## 11.4 End targets

End city search is usually post-dragon.

Need:

```text
safe route to outer islands
blocks or gateway access
void safety
food
weapon
projectile plan for shulkers
```

Void death can erase inventory.

---

# 12. Error recovery

## 12.1 Unknown ID

If locator says the ID is unknown:

```text
check spelling
add minecraft: namespace
check this atlas
check if it is structure vs biome
```

Example fixes:

```text
woodland_mansion -> minecraft:mansion
ocean_monument -> minecraft:monument
jungle_temple -> minecraft:jungle_pyramid
```

## 12.2 Wrong category

If you tried:

```text
locate_structure("minecraft:warped_forest")
```

Fix:

```text
locate_biome(biome="minecraft:warped_forest")
```

If you tried:

```text
locate_biome(biome="minecraft:stronghold")
```

Fix:

```text
locate_structure("minecraft:stronghold")
```

## 12.3 Wrong dimension

If target cannot exist in current dimension:

```text
move to correct dimension
then retry
```

Examples:

```text
fortress -> Nether
stronghold -> Overworld
end_city -> End
warped_forest -> Nether
```

## 12.4 Found bad variant

Example:

```text
#minecraft:ruined_portal returns ruined_portal_ocean
```

If underwater is unacceptable:

```text
skip result
move to another region
retry locate_structure("#minecraft:ruined_portal")
```

Example:

```text
#minecraft:shipwreck returns underwater shipwreck
```

If diving is unsafe:

```text
search minecraft:shipwreck_beached
or choose another loot route
```

## 12.5 Target too far

If distance is huge:

```text
decide if target is worth it
use intermediate waypoints
travel safely
or search for a different tag/objective
```

For dragon route, long travel is acceptable for:

```text
stronghold
fortress
warped_forest if no better pearl source exists
```

## 12.6 Locate returns but scan confirms nothing

Possible causes:

```text
Y level is wrong
structure is underground
structure spans multiple levels
biome result is approximate
you are at edge of biome/structure
```

Fix:

```text
scan wider radius
try nearby Y levels
move around local area
look for signature blocks
```

Examples:

```text
fortress -> scan nether_bricks radius 128
stronghold -> scan stone_bricks / end_portal_frame
warped_forest -> scan warped_nylium / warped_stem
```

---

# 13. Signature block/entity confirmations

Use these after locating.

| Target | Confirm with |
|---|---|
| Stronghold | `stone_bricks`, `mossy_stone_bricks`, `end_portal_frame` |
| Fortress | `nether_bricks`, `nether_brick_fence`, `spawner`, blazes |
| Bastion | `blackstone`, `polished_blackstone`, `gilded_blackstone`, piglins |
| Ruined portal | `obsidian`, `crying_obsidian`, `gold_block`, chest |
| Village | beds, workstations, villagers, farms, iron golem |
| Desert pyramid | sandstone, orange/blue terracotta pattern, hidden TNT chamber |
| Jungle pyramid | mossy cobblestone, tripwire, dispensers |
| Monument | prismarine, sea lanterns, guardians |
| Ancient city | sculk, reinforced_deepslate, wool paths, chests |
| Warped forest | `warped_nylium`, `warped_stem`, endermen |
| Crimson forest | `crimson_nylium`, `crimson_stem`, hoglins/piglins |
| Deep dark | sculk blocks, sculk sensors, sculk shriekers |

---

# 14. Call examples

## 14.1 Exact structure

```text
locate_structure("minecraft:stronghold")
```

```text
locate_structure("minecraft:fortress")
```

## 14.2 Structure tag

```text
locate_structure("#minecraft:village")
```

```text
locate_structure("#minecraft:ruined_portal")
```

```text
locate_structure("#minecraft:shipwreck")
```

## 14.3 Exact biome

```text
locate_biome(biome="minecraft:warped_forest")
```

```text
locate_biome(biome="minecraft:mushroom_fields")
```

```text
locate_biome(biome="minecraft:deep_dark")
```

## 14.4 Biome tag

```text
locate_biome(biome="#minecraft:is_ocean")
```

```text
locate_biome(biome="#minecraft:is_badlands")
```

```text
locate_biome(biome="#minecraft:is_mountain")
```

---

# 15. What this skill is not

This skill does not explain full tactics for every place.

Load specialised skills for execution:

```text
nether_entry
blaze_rods
ender_pearls
stronghold_finding
dragon_combat
combat_basics
containers
creative_mode
```

Examples:

```text
Need blaze rods -> use world_atlas only to confirm fortress ID, then load blaze_rods.
Need stronghold -> use world_atlas only to confirm stronghold ID, then load stronghold_finding.
Need warped forest -> use world_atlas only to confirm biome ID, then load ender_pearls.
```

---

# 16. Final reminders

Always remember:

```text
1. Structures use locate_structure.
2. Biomes use locate_biome.
3. Use exact minecraft: IDs or valid #minecraft: tags.
4. Search only works in the current dimension.
5. Tags find any family member.
6. Use exact IDs when variant matters.
7. Confirm located targets with scan_blocks / scan_nearby_entities.
8. Avoid underwater/deep/dangerous structures unless prepared.
9. For dragon route: ruined portal -> fortress -> warped forest -> stronghold -> dragon.
10. If an ID is not in this atlas, assume it is not vanilla Minecraft 1.20.1.
```
