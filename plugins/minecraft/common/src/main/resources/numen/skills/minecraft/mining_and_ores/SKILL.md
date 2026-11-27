---
name: mining_and_ores
description: Minecraft 1.21.1 mining facts - which pickaxe harvests which block, tool tiers and durability, the height every ore generates at (from the 1.21.1 worldgen data), what each ore drops, Fortune and Silk Touch, deepslate, and the hazards found at each depth.
---

# Mining and ores (1.21.1)

## Tool tiers

A block that needs a better tool than the one used breaks slowly and **drops nothing**.

| Pickaxe | Harvests (in addition to everything below it) |
|---|---|
| Hand / any tool | logs, dirt, sand, gravel, clay, leaves (shears or Silk Touch to keep them) |
| Wooden | stone, cobblestone, deepslate, coal ore, nether quartz ore, nether gold ore, netherrack, sandstone, furnace-type blocks |
| Stone | iron ore, copper ore, lapis ore |
| Iron | gold ore, redstone ore, diamond ore, emerald ore |
| Diamond | obsidian, crying obsidian, ancient debris, respawn anchor, netherite block |
| Netherite | same as diamond, faster |

Golden tools mine fast but harvest only what a wooden one does. Axes are the right tool for wood, shovels for dirt, sand, gravel, clay and snow, hoes for leaves, hay, sculk and moss, shears for wool, leaves and cobwebs.

| Tool material | Durability (uses) |
|---|---|
| Wood | 59 |
| Gold | 32 |
| Stone | 131 |
| Iron | 250 |
| Diamond | 1561 |
| Netherite | 2031 |

Recipes: a pickaxe is 3 of the material over 2 sticks; an axe 3 + 2 sticks; a shovel 1 + 2 sticks; a sword 2 + 1 stick; a hoe 2 + 2 sticks.

## Where ores generate (overworld, Y = height)

Numbers are the 1.21.1 placement ranges. "Peak" means the height where that ore is most common; "uniform" means equally common across the range.

| Ore | Range | Best height | Notes |
|---|---|---|---|
| Coal | 0 to 192 (peak 96) and 136 up to the build limit | ~95, or any exposed mountainside | Commonest ore; often visible on cliffs |
| Copper | -16 to 112 | 48 | Large veins in dripstone caves |
| Iron | -64 to 72 (small veins), -24 to 56 (peak 16), 80 to 384 (peak 232) | 16, or mountain peaks ~230 | Mountain iron is plentiful and above ground |
| Lapis lazuli | -32 to 32 (peak 0), plus buried -64 to 64 | 0 | The buried kind never touches air |
| Gold | -64 to 32 (peak -16), extra -64 to -48 | -16 | Badlands biomes add gold from 32 to 256 |
| Redstone | -64 to 15, and much more toward -64 | -58 | |
| Diamond | -64 to 16, rising steadily toward the bottom | -58 | Half of exposed diamond ore is removed at generation, so most is behind a wall |
| Emerald | -16 to 480 (peak 232), mountain biomes only | high mountains | Single blocks |

**Below Y 0** the stone is deepslate and every ore is its deepslate variant (`deepslate_iron_ore`, `deepslate_diamond_ore` …); they drop the same items. Deepslate takes about twice as long to mine as stone. The bedrock floor starts at Y -64.

Nether ores: nether quartz and nether gold everywhere between Y 10 and 117; **ancient debris** between Y 8 and 119, most common around Y 15 (see `the_nether`).

## What ores drop

| Ore | Drop without Fortune |
|---|---|
| Coal | 1 coal |
| Copper | 2-5 raw copper |
| Iron | 1 raw iron (smelt into an ingot) |
| Gold | 1 raw gold |
| Lapis | 4-9 lapis lazuli |
| Redstone | 4-5 redstone dust |
| Diamond | 1 diamond |
| Emerald | 1 emerald |
| Nether quartz | 1 quartz |
| Nether gold | 2-6 gold nuggets |
| Ancient debris | itself (smelt into netherite scrap) |

**Fortune** (I-III) multiplies the drops of coal, diamond, emerald, lapis, redstone, quartz, copper, iron and gold ore; Fortune III averages about 2.2x for single-drop ores. **Silk Touch** drops the ore block itself, which can be mined later with Fortune or smelted directly (no extra yield).

Mining ore also drops experience (except iron, copper and gold, which give it when smelted).

## Underground hazards

- **Lava**: open cave air below about Y -54 is filled with lava (the deep lava level), and lava lakes and springs appear at every depth. Lava flows 3 blocks per spread in the overworld (7 in the Nether).
- **Gravel and sand** fall when the block under them is removed; a column of them can bury whoever stands below.
- **Water** pockets (aquifers) sit inside caves at any height; breaking into one floods the tunnel.
- **Darkness**: hostile mobs spawn wherever the block light is 0, which is most of every cave.
- **Deep dark** biomes (below about Y 0, under mountains) hold sculk sensors and shriekers; enough noise summons the Warden (500 HP). Ancient cities are in the deep dark.
- **Silverfish** hide in "infested" stone blocks below Y 64 (they look like normal stone but break instantly).
- Mining straight down risks dropping into a cave, lava or a ravine; the block under the feet is the one not seen.

## Other materials underground

- **Obsidian** forms where flowing water meets a lava *source* block; lava pools are common around Y -54 and below. Ruined portals already contain obsidian.
- **Clay** in lush caves, rivers and swamps; **dripstone** in dripstone caves; **amethyst geodes** (smooth basalt shell) from -58 to 30.
- **Mineshafts** (oak planks, rails, cobwebs) cut through caves at many heights; their chest minecarts hold iron, gold, diamonds, rails and bread. Cave spider spawners sit inside cobweb-filled rooms.
