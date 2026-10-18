---
name: world_atlas
description: Every structure and biome of Minecraft 1.21.1 with its exact registry id (all 34 structures and 64 biomes from the 1.21.1 registry), the ids people usually get wrong, the family tags, and a short picture of each - what is there, its dangers and its loot.
---

# World atlas (1.21.1)

These are the complete vanilla lists for 1.21.1; an id that is not here is not vanilla (mods and datapacks can add more). Every id is in the `minecraft:` namespace. Structures are buildings with rooms and chests; biomes are regions of terrain and climate.

## Ids that are easy to get wrong

- Woodland mansion = `minecraft:mansion` (not woodland_mansion)
- Ocean monument = `minecraft:monument` (not ocean_monument)
- Jungle temple = `minecraft:jungle_pyramid`; desert temple = `minecraft:desert_pyramid`
- Nether fortress = `minecraft:fortress`
- There is no plain `village`: the five are `village_plains`, `village_desert`, `village_savanna`, `village_snowy`, `village_taiga`, and the tag `#minecraft:village` covers them all
- The pale garden and the creaking do not exist in 1.21.1 (they arrived in 1.21.4)

## Structures - Overworld

| id | What it is |
|---|---|
| `stronghold` | The End portal room (see `the_end`); libraries with enchanted books. 128 per world in rings around the origin |
| `village_plains`, `village_desert`, `village_savanna`, `village_snowy`, `village_taiga` | Beds, farms, workstations, villagers to trade with, an iron golem |
| `mineshaft` / `mineshaft_mesa` | Abandoned tunnels with rails and chest minecarts (iron, gold, diamonds, rails); cave spider spawners in cobwebs. The badlands version is at the surface |
| `desert_pyramid` | Sandstone temple; 4 chests in a hidden room under the centre; a pressure plate there sets off TNT. Some sand is suspicious sand (brush for pottery sherds) |
| `jungle_pyramid` | Mossy temple with tripwire arrow traps, a lever puzzle and 2 chests |
| `igloo` | Snow hut; sometimes a basement under the carpet with a brewing stand, a zombie villager, a splash potion of Weakness and a golden apple |
| `swamp_hut` | Witch hut on stilts with a witch and a black cat; no chest |
| `mansion` | Woodland mansion in dark forests, usually very far away; vindicators and evokers (totem of undying); allays in cells |
| `pillager_outpost` | Watchtower of pillagers; the captain drops an ominous bottle; caged allays or iron golems |
| `monument` | Ocean monument in deep oceans, entirely under water; guardians and 3 elder guardians (Mining Fatigue); 8 gold blocks, sponges |
| `ocean_ruin_cold`, `ocean_ruin_warm` | Stone or sandstone ruins under water; drowned; treasure maps, enchanted gear |
| `shipwreck`, `shipwreck_beached` | Wrecked ships, sunk or on a beach; supply, map and treasure chests (iron, gold, emeralds, treasure maps) |
| `buried_treasure` | One chest under a beach: a **heart of the sea**, iron, gold, diamonds, TNT |
| `ancient_city` | Deep dark ruins around Y -52; sculk shriekers and the **Warden**; enchanted books (Swift Sneak), echo shards, enchanted golden apples |
| `trail_ruins` | Mostly buried ruins; suspicious gravel to brush for sherds and armor trims |
| `trial_chambers` | Copper and tuff vaults around Y -40 to -20; trial spawners, breezes (breeze rods, the mace's heavy core), vaults opened by trial keys |
| `ruined_portal` (and `_desert`, `_jungle`, `_swamp`, `_mountain`, `_ocean`, `_nether`) | A broken Nether portal with obsidian, crying obsidian and a gold-loot chest; `#minecraft:ruined_portal` covers all seven |

## Structures - Nether and End

| id | What it is |
|---|---|
| `fortress` | Nether bricks; blaze spawners, wither skeletons, nether wart (see `the_nether`) |
| `bastion_remnant` | Blackstone; piglins, piglin brutes, gold blocks, netherite upgrade templates |
| `nether_fossil` | A few bone blocks in soul sand valleys |
| `end_city` | Purpur towers on the outer End islands; shulkers, good loot, elytra on end ships |

## Biomes - Overworld

| id | What it is |
|---|---|
| `plains`, `sunflower_plains` | Flat grassland; villages, horses, bees |
| `snowy_plains`, `ice_spikes` | Snow; igloos, villages, polar bears, rabbits; packed-ice spikes |
| `desert` | Sand, cactus, dead bushes; villages, desert temples, wells; husks |
| `forest`, `flower_forest`, `birch_forest`, `old_growth_birch_forest` | Oak and birch woods; flower forests have every flower and bees |
| `dark_forest` | Dense dark oak canopy dark enough for mobs to spawn by day; mansions, huge mushrooms |
| `taiga`, `snowy_taiga`, `old_growth_pine_taiga`, `old_growth_spruce_taiga` | Spruce forests; wolves, foxes, sweet berries; podzol and giant trees in the old-growth ones |
| `savanna`, `savanna_plateau`, `windswept_savanna` | Acacia and dry grass; villages, horses, llamas; the windswept one is broken steep terrain |
| `windswept_hills`, `windswept_gravelly_hills`, `windswept_forest` | Hills with emerald ore and llamas; the gravelly one has plenty of gravel |
| `jungle`, `sparse_jungle`, `bamboo_jungle` | Cocoa, melons, parrots, ocelots; jungle temples; pandas in bamboo |
| `badlands`, `eroded_badlands`, `wooded_badlands` | Terracotta layers; extra gold ore up to Y 256; mineshafts at the surface |
| `meadow`, `cherry_grove` | Mountain meadows with bees and donkeys; pink cherry trees |
| `grove`, `snowy_slopes`, `frozen_peaks`, `jagged_peaks`, `stony_peaks` | Mountains; goats, powder snow (sink and freeze; leather boots walk on it), exposed iron, coal and emerald |
| `swamp`, `mangrove_swamp` | Shallow water, clay, blue orchids, slimes at night, witch huts; mangroves with mud and frogs |
| `river`, `frozen_river` | Clay, sugar cane, kelp; drowned |
| `beach`, `snowy_beach`, `stony_shore` | Turtles, buried treasure; stone cliffs on the stony shore |
| `ocean`, `deep_ocean` | Kelp, squid, cod; monuments are in the deep oceans |
| `warm_ocean`, `lukewarm_ocean`, `deep_lukewarm_ocean` | Coral reefs (warm), tropical fish, pufferfish |
| `cold_ocean`, `deep_cold_ocean`, `frozen_ocean`, `deep_frozen_ocean` | Salmon; icebergs and polar bears in the frozen ones |
| `mushroom_fields` | Mycelium and mooshrooms; **no hostile mobs spawn** |
| `dripstone_caves`, `lush_caves` | Underground: dripstone, pointed dripstone and extra copper; moss, glow berries, axolotls, clay |
| `deep_dark` | Deep underground under mountains; sculk, ancient cities, the Warden |

## Biomes - Nether and End

| id | What it is |
|---|---|
| `nether_wastes` | Netherrack, lava, zombified piglins, ghasts |
| `crimson_forest` | Hoglins and piglins; crimson wood |
| `warped_forest` | Endermen; almost no other hostiles |
| `soul_sand_valley` | Soul sand and soil, skeletons, ghasts, fossils |
| `basalt_deltas` | Basalt and blackstone, magma cubes, rough terrain |
| `the_end` | The central island with the dragon |
| `end_highlands`, `end_midlands`, `end_barrens`, `small_end_islands` | Outer islands: chorus plants and end cities on the highlands; small islands over the void |
| `the_void` | Only in the superflat "void" preset; never found in a normal world |

## Family tags

- Biome tags: `#minecraft:is_forest`, `#minecraft:is_taiga`, `#minecraft:is_jungle`, `#minecraft:is_savanna`, `#minecraft:is_badlands`, `#minecraft:is_mountain`, `#minecraft:is_hill`, `#minecraft:is_river`, `#minecraft:is_beach`, `#minecraft:is_ocean`, `#minecraft:is_deep_ocean`, `#minecraft:is_overworld`, `#minecraft:is_nether`, `#minecraft:is_end`
- Structure tags: `#minecraft:village`, `#minecraft:ruined_portal`, `#minecraft:shipwreck`, `#minecraft:ocean_ruin`, `#minecraft:mineshaft`
- Fortresses and bastions exist only in the Nether, end cities only in the End, everything else in the overworld.
