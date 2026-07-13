---
name: projecte
description: Source-verified ProjectE 1.0.1 operations for Minecraft 1.20.1. Load for EMC, transmutation, collectors, relays, condensers, matter furnaces, Klein Stars, tools, armor, rings, amulets, ProjectE storage, automation, recipes, safety checks, or ProjectE commands.
---

# ProjectE / 等价交换 使用技能

ProjectE is an EMC-based item transmutation mod. An item has EMC only when the server's mapping process assigns it a positive value. Recipes, configuration, blacklists, datapacks, commands, and other mods can all change that result. Never assume every craftable item has EMC; verify in the ProjectE GUI, tooltip, or server command before consuming valuable items.

This skill targets **ProjectE 1.0.1 on Minecraft 1.20.1 / Forge 47.x** and follows the source tree and generated recipes supplied with this project. Do not substitute behavior remembered from Equivalent Exchange 2, old ProjectE releases, Tekkit packs, or another server configuration.

## AI execution boundary

ProjectE exposes several abilities through client keybind packets rather than ordinary Minecraft use interactions. The companion can reliably use ordinary movement, inventory inspection, GUI clicking, block interaction, equipment, crafting, and server-authorized commands. It must **not claim success** for a ProjectE keybind unless an available tool actually sends that keybind and the resulting state was verified.

| ProjectE action | Internal keybind | AI rule |
|---|---|---|
| Charge an item | `CHARGE` | Ask the player to press their bound Charge key if no key tool exists. |
| Cycle item mode | `MODE` | Do not infer a new mode from repeated right-clicks. Ask the player or inspect the displayed mode. |
| Extra function | `EXTRA_FUNCTION` | Do not promise portable crafting, aura, or special attacks without key support. |
| Fire projectile | `FIRE_PROJECTILE` | Normal `interact_at` is not equivalent. |
| Helmet toggle | `HELMET_TOGGLE` | Equipping the helmet does not prove its toggle is enabled. |
| Boots toggle | `BOOTS_TOGGLE` | Equipping Gem Boots does not prove flight is enabled. |

Ordinary right-click actions, such as opening a Tablet or machine, inserting a pedestal item, and placing fluid with an amulet, may be attempted through normal interaction. Reinspect the world, GUI, inventory, effects, or machine state after every consequential operation.

## Mandatory preflight

1. Inspect mode, inventory, armor, offhand, dimension, health, hunger, and position.
2. Use `search_items` or `lookup_recipe` when an ID or recipe is uncertain.
3. Inspect a placed machine and its storage before inserting or extracting anything.
4. In survival, use `plan_crafting` before `craft_items`; never replace legitimate crafting with `creative_give`.
5. Treat displayed EMC as server authority. Values below are defaults or capacities, not promises for a modified server.
6. Preserve NBT-bearing items such as charged Klein Stars and configured tools. Do not exchange them blindly for fresh copies.
7. Before an area effect, confirm nearby terrain, entities, containers, pets, farms, and players will not be harmed.
8. Verify the exact result afterward. A successful interaction packet is not proof that a mode changed or effect fired.

All ProjectE items and blocks use the `projecte:` prefix:
```
creative_give item_id="projecte:dark_matter" count=64
fill block_id="projecte:red_matter_block" x1=100 y1=64 z1=200 x2=110 y2=64 z2=210 hollow=false
run_command command="/projecte emc add @s 1000000"
```

---

# 0. Core concept: the EMC system

EMC = Energy-Matter Currency. ProjectE maps values from explicit mappings and recipes, subject to configuration and exclusions. Some items have no EMC; some recipes are deliberately excluded to prevent loops or exploits.

Three operations:
1. **Learn** — expose a positive-EMC item in an input/lock slot, or use the consume slot; its persistent item identity becomes known.
2. **Burn** — place an exact EMC-valued stack in the destructive consume slot; it becomes personal EMC.
3. **Create** — take an affordable known item from an output slot; exact EMC is deducted.

## Common vanilla EMC values (approximate, server-calculated)

| Item | EMC |
|---|---|
| Cobblestone | 1 |
| Dirt | 1 |
| Sand | 1 |
| Gravel | 4 |
| Oak Log | 32 |
| Coal | 128 |
| Iron Ingot | 256 |
| Redstone | 64 |
| Lapis Lazuli | 864 |
| Gold Ingot | 2,048 |
| Diamond | 8,192 |
| Emerald | 16,384 |
| Netherite Ingot | ~73,728 |
| Nether Star | ~139,264 |
| Dragon Egg | ~2,097,152 |

## ProjectE EMC values (approximate)

| Item | Approx EMC |
|---|---|
| Alchemical Coal | ~512 (4x coal) |
| Mobius Fuel | ~2,048 (4x alch coal) |
| Aeternalis Fuel | ~8,192 (4x mobius) |
| Dark Matter | ~139,264 (8 aeternalis, diamond block) |
| Red Matter | ~466,944 (3 aeternalis, 3 dark matter) |
| Klein Star Ein | 50,000 max |
| Klein Star Zwei | 200,000 max |
| Klein Star Drei | 800,000 max |
| Klein Star Vier | 3,200,000 max |
| Klein Star Sphere | 12,800,000 max |
| Klein Star Omega | 51,200,000 max |

EMC is per-player: each player has their own EMC pool and knowledge set.

---

# 1. Philosopher's Stone — the gateway item

**ID**: `projecte:philosophers_stone`

**Critically important: the Philosopher's Stone is NEVER consumed in crafting recipes.** It stays in the grid after every use. You only ever need ONE.

Recipe (shapeless, 3x3): Redstone / Glowstone / Redstone / Glowstone / Diamond / Glowstone / Redstone / Glowstone / Redstone = 4 Redstone + 4 Glowstone Dust + 1 Diamond. Or creative:
```
creative_give item_id="projecte:philosophers_stone" count=1
```

## Abilities

| Key | Action | Effect |
|---|---|---|
| C (default) | Extra Function | Opens portable 3x3 crafting grid anywhere |
| Right-click on block | World Transmutation | Transforms block (see Section 12) |
| Shift + Right-click on block | Reverse Transmutation | Transforms block in reverse direction |
| V (default Charge) | Charge | Cycles charge level 0..3, changing the affected radius |
| R (default) | Projectile | Fires Mob Randomizer projectile (transforms mobs) |
| G (default Mode) | Mode Change | Cycle Cube -> Panel -> Line transmutation modes |

## Three charge modes

- **Cube** (default): transmutes cube area around target block, size = charge+1
- **Panel**: transmutes flat panel perpendicular to the face you click
- **Line**: transmutes line along your facing axis, length = charge+1

---

# 2. Transmutation Table & Tablet

## 2.1 Transmutation Table (block)

**ID**: `projecte:transmutation_table`

Place and right-click. GUI layout:
```
+----------------------+----------------------+
| 8 input/star slots   | lock + 16 outputs    |
| charge or expose EMC | filtered known items |
+----------------------+----------------------+
| consume/learn slot   | unlearn slot         |
+----------------------+----------------------+
| EMC display, page/search controls           |
+---------------------------------------------+
```

**Input/star slots (8)**: Hold one eligible item each. Positive-EMC items become known; EMC holders placed here are charged from the personal EMC pool. Ordinary input items are not the same as the destructive consume slot.

**Consume/learn slot**: Inserting an EMC-valued stack learns it and consumes it into the personal EMC pool. A Tome is handled specially. This is destructive; verify the slot index and stack count before transfer.

**Lock slot**: Holds one filter/target item and narrows displayed outputs to learned items at or below that EMC value. An EMC holder placed here can be discharged into the personal pool.

**Unlearn slot**: Removes the item's persistent knowledge entry. The physical item is retained in the slot and returned when the menu closes. Do not confuse this with burning.

**Output ring (16 slots)**: Displays affordable learned items. Taking an output creates the chosen count and deducts exact EMC. Items cannot be inserted into output slots.

**Searching**: Type in the search bar to filter the right panel.

The input/lock state and knowledge/EMC pool belong to the player capability. Opening a block Table or portable Tablet for the same player accesses the same personal knowledge and EMC, subject to server rules.

### Safe GUI sequence

1. Open the Table/Tablet and call `inspect_gui`.
2. Identify slot classes/indices from the live menu; do not rely only on screen coordinates in this skill.
3. To learn without burning, place one inexpensive positive-EMC sample in an input slot, then verify knowledge/output changes.
4. To add EMC, use the consume slot with a deliberately chosen stack and exact count.
5. To charge a Klein Star, place it in an input slot and verify personal EMC decreases while star EMC increases.
6. To discharge a Star, use the lock slot and verify the reverse movement.
7. Take outputs only after checking displayed cost and available EMC.
8. Reinspect, return any retained input/lock/unlearn items, and close the GUI.

Place with:
```
place_block block_id="projecte:transmutation_table" x=... y=... z=...
```

## 2.2 Transmutation Tablet (portable)

**ID**: `projecte:transmutation_tablet`

Same functionality but hand-held. Right-click anywhere to open the Transmutation GUI.
```
creative_give item_id="projecte:transmutation_tablet" count=1
```

## 2.3 Tome of Knowledge

**ID**: `projecte:tome`

THE most important item in creative mode. Place it in a Transmutation Table's LEFT slot → instantly unlocks ALL items with EMC values. After using the Tome, you can pull any known item from the Tablet anywhere.

```
creative_give item_id="projecte:tome" count=1
```

**Usage flow:**
1. Give yourself a Transmutation Tablet and a Tome
2. Right-click the Tablet to open it
3. Put Tome in the LEFT slot → "Learned!" everything
4. Now search and pull any item from the right panel

---

# 3. EMC Fuel Chain — from Coal to Aeternalis

Each tier is 4x the previous. All recipes use Philosopher's Stone (not consumed).

## 3.1 Alchemical Coal

**ID**: `projecte:alchemical_coal`
**Block**: `projecte:alchemical_coal_block` (9x storage, also burns as fuel)

Recipe: 4 Coal + 1 Philosopher's Stone (shapeless)

Burn time: 6,400 ticks (4x coal = 64 items smelted). Also used as fuel in Energy Collectors to boost EMC generation.

## 3.2 Mobius Fuel

**ID**: `projecte:mobius_fuel`
**Block**: `projecte:mobius_fuel_block`

Recipe: 4 Alchemical Coal + 1 Philosopher's Stone (shapeless)

Burn time: 25,600 ticks (16x coal). Used to upgrade Klein Stars from Ein to Zwei.

## 3.3 Aeternalis Fuel

**ID**: `projecte:aeternalis_fuel`
**Block**: `projecte:aeternalis_fuel_block`

Recipe: 4 Mobius Fuel + 1 Philosopher's Stone (shapeless)

Burn time: 102,400 ticks (64x coal). Used to craft Dark Matter.

---

# 4. Dark Matter & Red Matter

## 4.1 Covalence Dust (precursor chain)

| ID | Recipe |
|---|---|
| `projecte:low_covalence_dust` | 1 Charcoal + 8 normal Cobblestone (shapeless) -> 40 dust |
| `projecte:medium_covalence_dust` | 1 Iron Ingot + 1 Redstone Dust (shapeless) -> 40 dust |
| `projecte:high_covalence_dust` | 1 Diamond + 1 Coal (shapeless) -> 40 dust |

The dusts are independent recipes; they are not upgraded from one tier to the next and do not use the Philosopher's Stone.

`projecte:iron_band` is shaped `III / ILI / III`, where `I` is an iron ingot and `L` is either a lava bucket or a Volcanite Amulet. It uses eight iron ingots. A lava bucket leaves its normal crafting remainder.

## 4.2 Dark Matter

**ID**: `projecte:dark_matter`
**Block**: `projecte:dark_matter_block` (9x storage, hardness 1M, blast resistance 3M, light level 14, fire-immune)

Recipe (shaped 3x3):
```
A A A
A D A     A = Aeternalis Fuel, D = Diamond Block
A A A
```
= 8 Aeternalis Fuel + 1 Diamond Block → 1 Dark Matter

## 4.3 Red Matter

**ID**: `projecte:red_matter`
**Block**: `projecte:red_matter_block` (9x storage, hardness 2M, blast resistance 6M, light level 14, fire-immune)

Recipe (shaped 3x3):
```
A A A
D D D     A = Aeternalis Fuel, D = Dark Matter
A A A
```
= 6 Aeternalis Fuel + 3 Dark Matter → 1 Red Matter

---

# 5. Klein Stars — EMC batteries

Portable EMC storage. All Klein Stars can be:
- Inserted with EMC at a Transmutation Table
- Used as EMC source for tools/armor/items (consumed before personal EMC pool)
- Upgraded to next tier

All tools and armor draw EMC in this priority: item buffer → Curios Klein Star slot → offhand → inventory Klein Stars → fuel items.

| ID | Max EMC |
|---|---|
| `projecte:klein_star_ein` | 50,000 |
| `projecte:klein_star_zwei` | 200,000 |
| `projecte:klein_star_drei` | 800,000 |
| `projecte:klein_star_vier` | 3,200,000 |
| `projecte:klein_star_sphere` | 12,800,000 |
| `projecte:klein_star_omega` | 51,200,000 |

**Ein recipe**: shaped `MMM / MDM / MMM`: 8 Mobius Fuel around 1 Diamond.
**Zwei recipe**: 4 Ein, shapeless.
**Drei recipe**: 4 Zwei, shapeless.
**Vier recipe**: 4 Drei, shapeless.
**Sphere recipe**: 4 Vier, shapeless.
**Omega recipe**: 4 Sphere, shapeless.

The five upgrades use ProjectE's Klein Star recipe type, which carries stored EMC into the upgraded star. Never substitute generic crafting logic that loses their stored EMC.

Creative mode: just grab the Omega.
```
creative_give item_id="projecte:klein_star_omega" count=1
```

---

# 6. Dark Matter Tools

All DM tools use matter tier level 3 and base speed 14. Their tier has zero durability uses and their item classes suppress durability loss, so they do not break from normal use. Charged/AOE special actions may have separate EMC costs; ordinary durability is not "repaired with EMC".

## 6.1 Tool list

| Tool | ID | Recipe (M=dark_matter, D=diamond) |
|---|---|---|
| Pickaxe | `projecte:dm_pick` | MMM / _ D _ / _ D _ (AOE vein mining, 3x Tall/Wide/Long modes) |
| Axe | `projecte:dm_axe` | MM _ / MD _ / _ D _ (AOE chopping) |
| Shovel | `projecte:dm_shovel` | _ M _ / _ D _ / _ D _ (AOE digging) |
| Sword | `projecte:dm_sword` | _ M _ / _ M _ / _ D _ (9 base damage, AOE sweep) |
| Hoe | `projecte:dm_hoe` | MM _ / _ D _ / _ D _ (AOE tilling) |
| Shears | `projecte:dm_shears` | _ M _ / M _ _ / _ D _ (AOE shearing) |
| Hammer | `projecte:dm_hammer` | MMM / MDM / _ D _ (3x3 mining) |

---

# 7. Red Matter Tools

All RM tools use matter tier level 4 and base speed 16. They also suppress normal durability loss. Charge and mode counts differ by tool; inspect tooltip/state rather than assigning one count to the whole tier.

| Tool | ID | Key Feature |
|---|---|---|
| Pickaxe | `projecte:rm_pick` | 3 charges, extended AOE modes |
| Axe | `projecte:rm_axe` | Extended AOE chopping |
| Shovel | `projecte:rm_shovel` | Extended AOE digging |
| Sword | `projecte:rm_sword` | 12 base damage. Modes: Slay Hostile (only hostiles) / Slay All |
| Hoe | `projecte:rm_hoe` | Extended AOE tilling |
| Shears | `projecte:rm_shears` | Extended AOE shearing |
| Hammer | `projecte:rm_hammer` | Extended AOE mining |
| **Katar** | `projecte:rm_katar` | 4 charges. COMBINED axe + shears + sword + hoe. Slay Hostile/All modes. Extra Function (C key) = Death Aura area damage. BEST combat tool. |
| **Morningstar** | `projecte:rm_morning_star` | 4 charges. COMBINED pickaxe + shovel + hammer. 3x Tall/Wide/Long modes. BEST mining tool. |

Creative mode: grab both Katar and Morningstar for all-in-one coverage.

---

# 8. Armor — non-damaging durability and EMC-backed reduction

All ProjectE matter armor suppresses item durability damage and cannot be enchanted through the enchanting table, books, or normal table application hooks. Its special damage reduction consumes available EMC/fuel according to ProjectE combat handling; without sufficient EMC, do not assume the advertised full-set reduction applies. Do not claim an anvil can bypass the explicit book-enchant restriction.

## 8.1 Dark Matter Armor (80% reduction)

| Piece | ID | Damage Absorption |
|---|---|---|
| Helmet | `projecte:dm_helmet` | 100 general / 350 explosion / 5 fall |
| Chestplate | `projecte:dm_chestplate` | 150 general / 350 explosion |
| Leggings | `projecte:dm_leggings` | 150 general / 350 explosion |
| Boots | `projecte:dm_boots` | 100 general / 350 explosion / 5 drowning |

Recipes: Dark Matter in armor pattern shapes (helmet = 5, chestplate = 8, leggings = 7, boots = 4).

## 8.2 Red Matter Armor (90% reduction)

| Piece | ID | Damage Absorption |
|---|---|---|
| Helmet | `projecte:rm_helmet` | 250 general / 500 explosion / 10 fall |
| Chestplate | `projecte:rm_chestplate` | 350 general / 500 explosion |
| Leggings | `projecte:rm_leggings` | 350 general / 500 explosion |
| Boots | `projecte:rm_boots` | 250 general / 500 explosion / 10 drowning |

## 8.3 Gem Armor (90% reduction + special abilities)

The ULTIMATE armor set. Each piece has a unique active ability triggered by keybind.

| Piece | ID | Ability | Key |
|---|---|---|---|
| Helmet | `projecte:gem_helmet` | Passive healing; toggleable night vision; offensive lightning ability when server configuration permits | Helmet Toggle affects night vision; offensive action uses ProjectE's dedicated key handling |
| Chestplate | `projecte:gem_chestplate` | Passive feeding and fire protection; offensive nova explosion when enabled by server config | Offensive action uses ProjectE key handling |
| Leggings | `projecte:gem_leggings` | Sneaking in air controls descent, repels nearby entities, and can damage entities beneath a fast descent | Sneak behavior, not Boots Toggle |
| Boots | `projecte:gem_boots` | Flight provider, fall-distance reset, strong movement-speed modifier, and toggleable step assist | Boots Toggle affects step assist; flight still depends on the flight capability/game state |

Gem Armor recipes (shapeless, each requires a Klein Star Omega):
```
gem_helmet  = rm_helmet  + evertide_amulet       + soul_stone  + klein_star_omega
gem_chestplate = rm_chestplate + volcanite_amulet + body_stone  + klein_star_omega
gem_leggings = rm_leggings + black_hole_band      + watch_of_flowing_time + klein_star_omega
gem_boots   = rm_boots   + swiftwolf_rending_gale + swiftwolf_rending_gale + klein_star_omega
```

Server configuration can require each Omega to be completely charged to **51,200,000 EMC**. Inspect the live recipe and charge before planning the set; four armor pieces require four separate Omegas.

**In creative, equip the full Gem set immediately:**
```
creative_give item_id="projecte:gem_helmet" count=1
creative_give item_id="projecte:gem_chestplate" count=1
creative_give item_id="projecte:gem_leggings" count=1
creative_give item_id="projecte:gem_boots" count=1
```

---

# 9. Rings, Amulets, Stones — equipment & accessories

These work when held OR in Curios/bauble slots. Most also work on a Dark Matter Pedestal for extended-area effects.

## 9.1 Rings

| Item | ID | Effect |
|---|---|---|
| Black Hole Band | `projecte:black_hole_band` | Sucks in nearby item drops. Acts as portable Alchemical Chest (right-click). Can pick up fluids. |
| Archangel's Smite | `projecte:archangel_smite` | Fires homing arrows at nearby hostile mobs (R key). Pedestal: fires every 40 ticks. EMC cost per arrow. |
| Harvest Goddess Band | `projecte:harvest_goddess_band` | Auto-grows crops and harvests mature crops near you. Pedestal: every 10 ticks. Passive (no EMC cost). |
| Ignition Ring | `projecte:ignition_ring` | Sets nearby mobs on fire (R key = fire projectile). Pedestal: combusts every 40 ticks. EMC cost. |
| Zero Ring | `projecte:zero_ring` | Extinguishes fire, freezes entities. Pedestal: every 40 ticks. EMC cost. |
| Swiftwolf's Rending Gale (SWRG) | `projecte:swiftwolf_rending_gale` | **Flight** (consumes EMC), repels entities, R key = lightning. 4 modes: Off / Repelling / Flying / Both. |
| Void Ring | `projecte:void_ring` | Voids items collected. Passive. |
| Ring of Arcana | `projecte:arcana_ring` | ALL-IN-ONE ring. Combines Zero(1), Ignition(2), Harvest(3), SWRG(4). Plus flight + fire protection. Survives crafting. |

**Creators for maximum mobility**: `creative_give item_id="projecte:swiftwolf_rending_gale" count=1` (flight) or `projecte:arcana_ring` (all effects).

## 9.2 Amulets

| Item | ID | Effect |
|---|---|---|
| Evertide Amulet | `projecte:evertide_amulet` | **Infinite water bucket** — FREE (0 EMC). Right-click = place water, R key = water projectile. Dispenser behavior. Pedestal: starts rain every 20 ticks. |
| Volcanite Amulet | `projecte:volcanite_amulet` | **Infinite lava bucket** — costs 32 EMC per use. Right-click = place lava, R key = lava projectile. Also grants FIRE IMMUNITY. Pedestal: prevents rain every 20 ticks. |

## 9.3 Stones

| Item | ID | Effect |
|---|---|---|
| Body Stone | `projecte:body_stone` | Auto-restores 0.5 hunger bar every few seconds. Pedestal: restores nearby players' hunger every 10 ticks. |
| Soul Stone | `projecte:soul_stone` | Auto-restores 0.5 heart every few seconds. Pedestal: heals nearby players every 10 ticks. |
| Mind Stone | `projecte:mind_stone` | Stores XP orbs (absorbs nearby orbs). Pedestal: sucks in XP orbs. |
| Life Stone | `projecte:life_stone` | Combines Body + Soul: restores BOTH hunger AND health. Pedestal: restores both every 5 ticks. |
| Repair Talisman | `projecte:repair_talisman` | Automatically repairs ALL damaged items in your inventory (every 20 ticks). Also works inside Alchemical Bags/Chests. |

## 9.4 Utility Items

| Item | ID | Effect |
|---|---|---|
| Watch of Flowing Time | `projecte:watch_of_flowing_time` | Speeds up block ticks around you. 2 modes: Fast-Forward (speeds adjacent blocks like furnaces/crops), Rewind. Has charge bar. Pedestal: gives +18 bonus ticks to nearby blocks per game tick + slows mobs to 10% speed. |
| Destruction Catalyst | `projecte:destruction_catalyst` | Destroys blocks in a volume (charge increases depth). Drops are collected. 8 EMC/block. Won't break blocks with hardness -1 or >= 50. |
| Hyperkinetic Lens | `projecte:hyperkinetic_lens` | Right-click throws explosive projectile. Charge increases radius (4-16 blocks). EMC cost scales with charge. |
| Catalytic Lens | `projecte:catalytic_lens` | Alternative Hyperkinetic Lens. Same functionality. |
| Mercurial Eye | `projecte:mercurial_eye` | Advanced building wand with Creation, Extension, Extension Classic, Transmutation, Transmutation Classic, and Pillar modes. Its internal inventory requires a Klein Star and, where applicable, a positive-EMC target block. |
| Gem of Eternal Density | `projecte:gem_of_eternal_density` | Condenses eligible lower-EMC inventory items toward Iron, Gold, Diamond, Dark Matter, or Red Matter. Right-click opens the filter GUI; sneak-right-click enables/disables it; the `MODE` key changes target. |
| Divining Rod Low | `projecte:divining_rod_1` | Mode 3: scans 3x3x3 area. Reports top 3 EMC values, average, and max EMC. |
| Divining Rod Medium | `projecte:divining_rod_2` | Adds Mode 16: 16x3x3 scan. |
| Divining Rod High | `projecte:divining_rod_3` | Adds Mode 64: 64x3x3 scan. |

### Mercurial Eye operating constraints

- Internal slot 0 is a Klein Star and is the only permitted EMC source for Eye placement/transmutation.
- Internal slot 1 is the target block item. That item must have positive EMC.
- Creation places the selected target into replaceable space. Extension can infer the clicked block type when it has EMC. Transmutation replaces eligible blocks with the target.
- The Eye will not replace a block entity. It also refuses an unbreakable zero-EMC block.
- If old-block EMC exceeds new-block EMC, the difference can be returned to the Klein Star. If the new block costs more, the difference is charged. Replacing a zero-EMC breakable block can drop the old block and charge the full new-block value.
- Charge changes area size. A mistaken high charge can alter many blocks in one action.
- The AI must not use the Eye autonomously unless the internal slots, current mode, charge, target region, and available Klein Star EMC are all verified.

### Gem of Eternal Density operating constraints

1. Right-click and configure its filter before activation.
2. Choose whitelist or blacklist semantics deliberately. Never leave valuable low-EMC materials exposed to a broad default filter.
3. The target cycle is Iron Ingot, Gold Ingot, Diamond, Dark Matter, Red Matter and is changed through `MODE`, not sneak-right-click.
4. Sneak-right-click toggles active state. Turning it off ejects its buffered consumed stacks and clears internal EMC accounting.
5. It consumes only positive-EMC items below the target value. By default, non-stackable items are skipped unless explicitly whitelisted.
6. It can operate while present in player inventory, an Alchemical Chest, or an Alchemical Bag.
7. Before activation, record important stack counts. After a short test, verify the target output and protected items before leaving it running.

---

# 10. Alchemical Storage

## 10.1 Alchemical Chest

**ID**: `projecte:alchemical_chest`

104-slot storage chest. Every placed Alchemical Chest is an independent block-entity inventory. It is not a player-global network and it does not automatically share contents with Alchemical Bags. Comparator output reflects fill level.

```
place_block block_id="projecte:alchemical_chest" x=... y=... z=...
```

## 10.2 Alchemical Bags (16 colors)

**IDs**: `projecte:white_alchemical_bag`, `projecte:orange_alchemical_bag`, `projecte:magenta_alchemical_bag`, `projecte:light_blue_alchemical_bag`, `projecte:yellow_alchemical_bag`, `projecte:lime_alchemical_bag`, `projecte:pink_alchemical_bag`, `projecte:gray_alchemical_bag`, `projecte:light_gray_alchemical_bag`, `projecte:cyan_alchemical_bag`, `projecte:purple_alchemical_bag`, `projecte:blue_alchemical_bag`, `projecte:brown_alchemical_bag`, `projecte:green_alchemical_bag`, `projecte:red_alchemical_bag`, `projecte:black_alchemical_bag`

Portable high-capacity storage. For one player, bags of the **same color** access that color's persistent bag inventory. Different colors are separate inventories, and another player has separate inventories. A placed Alchemical Chest is not part of this bag storage.

Recipe: 1 Alchemical Chest + 1 dye (shapeless).

---

# 11. Energy Blocks — Power Flower components

## 11.1 Energy Collectors

Generate EMC from the light level directly above the collector. The listed generation is the approximate maximum under full light; it is not a fixed rate. Obstruction and darkness reduce it. Ultra-warm dimensions use the maximum source-light factor. A collector can spend generated EMC upgrading fuel, charge an EMC holder, or send EMC when it is not doing either job.

| Tier | ID | Maximum-light generation | EMC storage | General input slots |
|---|---|---|---|---|
| MK1 | `projecte:collector_mk1` | about 4 EMC/s | 10,000 | 8 |
| MK2 | `projecte:collector_mk2` | about 12 EMC/s | 30,000 | 12 |
| MK3 | `projecte:collector_mk3` | about 40 EMC/s | 60,000 | 16 |

Auto-push EMC to adjacent Relays or Condensers. No cables needed — adjacency only.

## 11.2 Anti-Matter Relays

Relays accept EMC from adjacent providers, burn EMC-valued items or drain an EMC holder, charge one EMC holder in the output slot, and send stored EMC to adjacent acceptors. The listed ceiling is per game tick, not per second. Adjacency is six-directional; diagonal blocks do not connect.

| Tier | ID | Charge/transfer ceiling | Storage |
|---|---|---|---|
| MK1 | `projecte:relay_mk1` | 64 | 100,000 |
| MK2 | `projecte:relay_mk2` | 192 | 1,000,000 |
| MK3 | `projecte:relay_mk3` | 640 | 10,000,000 |

## 11.3 Energy Condensers

Consume EMC to produce copies of a target item. Put the target item in the TOP slot.

| Tier | ID | Output |
|---|---|---|
| MK1 | `projecte:condenser_mk1` | 1 lock/target slot plus 91 general inventory slots. |
| MK2 | `projecte:condenser_mk2` | 1 lock/target slot, 42 input slots, and 42 output slots. |

The lock/target stack defines the output and is not the produced-item buffer. Insert EMC-bearing source items into valid input inventory or feed EMC from adjacent ProjectE blocks. Keep space for the exact target stack. Before changing target, extract wanted contents and reinspect the GUI so valuable items are not consumed under the new target.

## 11.4 Power Flower topology and safe layout

ProjectE transfers EMC only between face-adjacent blocks. A valid path is:

```text
Collector -> Relay -> Condenser
```

Diagonal contact is not a connection. Do not place two devices at the same coordinate, bury the light-sensitive top of a collector, or assume vertically repeated layers connect automatically.

The smallest symmetric 3x3 flower uses exactly **one condenser, four relays, and four collectors**. Let the condenser be `(cx, cy, cz)`:

```text
z = cz-1:  K  R  K
z = cz:    R  C  R
z = cz+1:  K  R  K

C = condenser
R = relay
K = collector
```

Coordinates:

| Device | Relative coordinates |
|---|---|
| Condenser | `(0, 0, 0)` |
| Relays | `(-1,0,0)`, `(1,0,0)`, `(0,0,-1)`, `(0,0,1)` |
| Collectors | `(-1,0,-1)`, `(-1,0,1)`, `(1,0,-1)`, `(1,0,1)` |

Each corner collector touches two relays; each relay touches the center condenser. Keep every collector's block above clear or transparent enough to preserve light. Use `inspect_block` on all nine coordinates before placing anything. In survival, place real blocks individually; `fill` is creative-only.

### Power Flower commissioning procedure

1. Pick flat, loaded, protected terrain with open light above all collectors.
2. Verify all nine target cells are replaceable and no container or machine would be overwritten.
3. Place the condenser first, relays second, collectors last.
4. Open the condenser and set exactly one desired target item with positive EMC.
5. Close the GUI and wait long enough for generation; darkness or obstruction can make progress very slow.
6. Reopen or inspect storage. Confirm the target remains set and output count or stored progress increases.
7. If stalled, check light above collectors, face adjacency, machine tier IDs, output space, and whether the target has EMC.
8. Expand only after the basic unit works. For every added collector, trace an unbroken face-adjacent route to a relay and condenser.

Do not call this "infinite" in the strict sense. It is renewable EMC production with finite rates and finite buffers; unloaded chunks and server rules can stop it.

## 11.5 Machine automation and sided behavior

- Use `inspect_block_storage` for a read-only first look. Open the GUI only when a transfer is required.
- Never guess slot numbers from an older ProjectE version. Inspect the current menu and distinguish machine slots from the companion's inventory slots.
- Insert one small test stack before bulk automation, then verify which slot accepted it and what changed.
- Keep output paths unblocked. A full condenser, relay, collector upgrade output, or matter furnace output stalls processing.
- Extract produced items before changing targets or filters.
- ProjectE machines persist their own inventories. Breaking a machine is not an acceptable way to "refresh" it.
- Hoppers and other item handlers obey sided capabilities that differ by machine. Validate top, bottom, and side behavior with a disposable stack before building a large automation line.

---

# 12. World Transmutation — source-defined whitelist

World transmutation is a registered whitelist, not a general EMC exchange. Right-clicking an unregistered block does nothing. Servers and other mods may add, remove, or replace entries, so test one disposable block before applying an area mode.

Default ProjectE 1.20.1 mappings include:

| Origin | Normal result | Alternate/reverse behavior |
|---|---|---|
| Stone | Cobblestone | No alternate in the default entry |
| Cobblestone | Stone | No alternate in the default entry |
| Grass Block | Sand | No alternate in the default entry |
| Dirt | Sand | No alternate in the default entry |
| Sand | Grass Block | No alternate in the default entry |
| Gravel | Sandstone | Sandstone has a separate mapping back to Gravel |
| Water | Ice | Ice has a separate mapping back to Water |
| Lava | Obsidian | Obsidian has a separate mapping back to Lava |
| Melon | Pumpkin | Pumpkin has a separate mapping back to Melon |
| Granite | Diorite | Granite -> Diorite -> Andesite -> Granite |
| Soul Sand | Soul Soil | Soul Soil has a separate mapping back |
| Netherrack | Crimson Nylium | Crimson -> Warped -> Netherrack |

Ordered families cycle forward and, for state-preserving families, may expose the previous value as the alternate result:

- Overworld logs: Oak, Birch, Spruce, Jungle, Acacia, Dark Oak, Mangrove, Cherry.
- Stripped logs: the same eight-species order.
- Wood and stripped wood blocks: the same eight-species order.
- Leaves: the same eight-species order.
- Saplings/propagules: Oak, Birch, Spruce, Jungle, Acacia, Dark Oak, Mangrove Propagule, Cherry.
- Planks, slabs, stairs, fences, and wooden pressure plates: those eight woods followed by Bamboo.
- Color order: White, Orange, Magenta, Light Blue, Yellow, Lime, Pink, Gray, Light Gray, Cyan, Purple, Blue, Brown, Green, Red, Black.
- The color order applies separately to Concrete, Concrete Powder, Carpet, Wool, Terracotta, Stained Glass, and Stained Glass Panes.
- Nether pairs: Crimson/Warped Stem, stripped stems, hyphae, stripped hyphae, wart blocks, fungi, roots, planks, slabs, stairs, fences, and pressure plates.

For all-state mappings, ProjectE copies compatible block-state properties, such as axis or stair orientation. This does not make block entities safe to transform. Never area-transform a base, machine room, container wall, redstone circuit, portal, farm, or another player's structure without explicit approval and a scan.

### Safe world-transmutation procedure

1. Inspect the exact target block ID and state.
2. Confirm it appears in the whitelist or verify on one disposable sample.
3. Ensure the selected charge and mode will not include protected blocks.
4. Move pets, item drops, and players out of the effect region.
5. Trigger one operation.
6. Reinspect the target and representative edge cells.
7. Stop immediately if the result differs from the intended mapping.

The companion cannot reliably change charge or Cube/Panel/Line mode without ProjectE keybind support. If mode cannot be inspected and controlled, limit autonomous work to a verified single-block ordinary interaction or ask the player to set the mode first.

---

# 13. Other blocks

| Block | ID | Purpose |
|---|---|---|
| Dark Matter Furnace | `projecte:dm_furnace` | 9 input + 9 output slots, 10 ticks per item, 50% ore doubling chance, 1/3 raw-material doubling chance. |
| Red Matter Furnace | `projecte:rm_furnace` | 13 input + 13 output slots, 3 ticks per item, 100% ore doubling, 2/3 raw-material doubling chance. |
| Dark Matter Pedestal | `projecte:dm_pedestal` | Holds one pedestal-capable item and applies that item's configured effect within its own behavior. Effect bounds extend 4 blocks from the pedestal. |
| Interdiction Torch | `projecte:interdiction_torch` | Repels hostile mobs + projectiles in radius. Instant break. Can be placed on walls/floors. |
| Nova Catalyst | `projecte:nova_catalyst` | TNT-like explosive block. Ignitable by redstone or fire. |
| Nova Cataclysm | `projecte:nova_cataclysm` | Larger explosion variant. |

Matter Furnaces run on normal furnace fuel or an EMC-holder item in the fuel slot. When using stored EMC, the block consumes 2 EMC per active tick. Their doubling rules apply only to the relevant Forge ore/raw-material tags and remain probabilistic where listed. Do not promise an exact doubled count for Dark Matter Furnace or raw materials.

### Pedestal safety

- Only items exposing ProjectE's pedestal capability work; an arbitrary ring, tool, or mod item does not.
- Insertion and activation are separate state changes. Verify the displayed item and active state after each interaction.
- Redstone can change pedestal activation. Inspect nearby wiring before diagnosing an effect as broken.
- Effects and cooldowns are server configurable. Do not hard-code old EE2 timing.
- Never activate fire, weather, attraction, time acceleration, projectile, or entity-control effects near an unreviewed base.
- To remove or replace an effect item, deactivate first, verify the effect stops, then extract it.

---

# 14. Commands — /projecte

All via `run_command`. Permission `projecte.command` required.

## EMC management
```
run_command command="/projecte emc get @s"                              Check your EMC
run_command command="/projecte emc add @s 1000000"                      Add 1M EMC
run_command command="/projecte emc remove @s 500000"                    Remove 500K EMC
run_command command="/projecte emc set @s 10000000"                     Set to exactly 10M
run_command command="/projecte emc test @s 5000"                        Test if you have 5K EMC
```

## Knowledge management
```
run_command command="/projecte knowledge learn @s projecte:dark_matter"  Learn one item
run_command command="/projecte knowledge unlearn @s projecte:dark_matter" Forget one item
run_command command="/projecte knowledge test @s projecte:red_matter"     Check if known
run_command command="/projecte knowledge clear @s"                        Clear all knowledge
```

## EMC value management
```
run_command command="/projecte setemc 1 minecraft:cobblestone"           Set EMC value
run_command command="/projecte removeemc minecraft:dirt"                  Remove EMC value
run_command command="/projecte resetemc minecraft:diamond"                Reset custom override
```

## Other
```
run_command command="/projecte showbag red PlayerName"                    Open one color of an online player's bag
```

`showbag` requires an explicit dye color followed by an online player, or a color followed by an offline player's UUID. `setemc`, `removeemc`, and `resetemc` edit custom mapping data and announce that an EMC reload is required. Do not use mapping commands as ordinary gameplay shortcuts.

---

# 15. Creative mode workflow

## Quick-start: instant full setup

```
# Foundation item
creative_give item_id="projecte:philosophers_stone" count=1

# Learn everything instantly
creative_give item_id="projecte:tome" count=1

# Portable transmutation
creative_give item_id="projecte:transmutation_tablet" count=1

# Maximum EMC battery
creative_give item_id="projecte:klein_star_omega" count=1

# Best tools
creative_give item_id="projecte:rm_katar" count=1
creative_give item_id="projecte:rm_morning_star" count=1

# Full Gem Armor
creative_give item_id="projecte:gem_helmet" count=1
creative_give item_id="projecte:gem_chestplate" count=1
creative_give item_id="projecte:gem_leggings" count=1
creative_give item_id="projecte:gem_boots" count=1

# Quality of life
creative_give item_id="projecte:repair_talisman" count=1
creative_give item_id="projecte:black_hole_band" count=1
creative_give item_id="projecte:evertide_amulet" count=1
creative_give item_id="projecte:volcanite_amulet" count=1
creative_give item_id="projecte:swiftwolf_rending_gale" count=1
creative_give item_id="projecte:alchemical_chest" count=1

# EMC
run_command command="/projecte emc add @s 1000000000"
```

## After setup

1. Right-click the Transmutation Tablet → place Tome in LEFT slot → ALL items learned
2. Equip Gem Armor, then have the player set desired night-vision and step-assist toggles. Verify flight separately through the player's abilities/state; equipping alone is not proof of an active flight state.
3. Right-click Tablet anytime to pull any item from EMC
4. Build Power Flowers for passive resource generation
5. Use Morningstar for AOE mining, Katar for AOE combat

---

# 16. Survival mode progression

## Phase 1: Get the Philosopher's Stone
```
1. Mine 4 Redstone, 4 Glowstone Dust, 1 Diamond
2. Craft Philosopher's Stone (shapeless: Redstone/Glowstone/Redstone, Glowstone/Diamond/Glowstone, Redstone/Glowstone/Redstone)
```

## Phase 2: Build EMC fuel chain
```
1. Mine lots of coal (each Alchemical Coal = 4 coal + PhiloStone)
2. craft Alchemical Coal → Alchemical Coal (4x coal value, burns 4x as long!)
3. Upgrade: 4 Alchemical Coal → 1 Mobius Fuel
4. Upgrade: 4 Mobius Fuel → 1 Aeternalis Fuel
5. Use Aeternalis Fuel as furnace fuel (burns 64x coal worth)
```

## Phase 3: Build Transmutation Table
```
1. Place Alchemical Chest (or craft with obsidian, diamond, stone)
2. Smelt enough cobblestone → stone for the table
3. Craft Transmutation Table
4. Place it, right-click, start burning items for EMC
```

## Phase 4: Dark Matter
```
1. Craft 8 Aeternalis Fuel + 1 Diamond Block → 1 Dark Matter
2. Craft Dark Matter Pickaxe (3 DM + 2 Diamonds)
3. Mine faster with DM Pickaxe (AOE vein mining!)
4. Build Energy Collector MK1 → start passive EMC generation
```

## Phase 5: Red Matter
```
1. Craft Red Matter (3x2 block of Aeternalis + Dark Matter)
2. Craft RM Pickaxe and RM Sword
3. Build Collector MK2 → Relay → Condenser for automated production
```

## Phase 6: Endgame
```
1. Power Flower (MK3 everything)
2. Klein Star Omega (51.2M EMC battery)
3. Gem Armor (full set)
4. Ring of Arcana (all ring effects combined)
```

---

# 17. Common workflows

## Give a player 1 billion EMC
```
run_command command="/projecte emc add PlayerName 1000000000"
```

## Learn all items for the AI companion
```
creative_give item_id="projecte:tome" count=1
```
Then open the Transmutation Tablet and insert the Tome through the real GUI. Verify the knowledge list before claiming completion; if the current GUI tool cannot address the required slot safely, ask the player to perform this one step.

## Build a dark matter platform
```
creative_give item_id="projecte:dark_matter_block" count=64
fill block_id="projecte:dark_matter_block" x1=100 y1=4 z1=200 x2=120 y2=4 z2=220 hollow=false
```

## Convert stone building to brick
Equip Philosopher's Stone. Right-click stone blocks. Stone → Cobblestone. Then use furnace or DM Furnace to smelt cobblestone → stone → stone bricks (via crafting).

## Clear a large area
```
run_command command="/fill 100 64 200 150 80 250 minecraft:air"
```
Or use Destruction Catalyst with full charge.

## Produce infinite diamonds
1. Build a Power Flower (Section 11.4)
2. Put one Diamond in the Condenser's lock/target slot
3. Collectors generate EMC → Relays transfer → Condenser produces diamonds
4. Diamonds accumulate in output. Collect periodically.

## Automated mob farm defense
Place Interdiction Torches around the farm perimeter. They repel all hostile mobs and projectiles.

## Infinite water source anywhere
Equip Evertide Amulet. Right-click = place water. Free, never runs out.

## Infinite lava source anywhere
Equip Volcanite Amulet. Right-click = place lava (costs 32 EMC per use). Also gives fire immunity.

---

# 18. Keybindings (for the player, not AI)

| Key | Function |
|---|---|
| V (Charge) | Charge items (Philosopher's Stone, Destruction Catalyst, Lenses, tools) |
| C (Extra Function) | Philosopher's Stone crafting grid, Gem Helmet abilities, Katar Death Aura |
| R (Projectile) | Fire projectiles from rings, amulets, Philosopher's Stone |
| Mode key (binding is configurable) | Change mode for supported items. Sneaking may select an alternate action, but do not assume a fixed `Shift + R` binding. |
| Helmet Toggle | Gem Helmet Night Vision on/off |
| Boots Toggle | Gem Boots Flight on/off, Gem Leggings Step Assist on/off |

---

# 19. Key rules

1. **Philosopher's Stone is never consumed** — keep ONE, use it for ALL recipes
2. **Tome unlocks everything** — use it immediately on a Transmutation Table
3. **EMC is per-player** — each player has their own EMC pool and knowledge set
4. **Klein Stars store EMC** — Omega (51.2M) is the biggest battery
5. **Gem Boots can provide flight when enabled** — equipping them alone does not verify the toggle
6. **Matter tools/armor suppress normal durability loss** — special abilities and armor reduction can still require EMC
7. **Power Flowers generate resources passively** — set up and forget
8. **World Transmutation is whitelist-only** — test one safe block and verify the result
9. **Storage scopes differ** — chests are independent; same-player same-color bags share that color only
10. **In creative mode, use creative_give + run_command** — skip all crafting and mining

---

# 20. Failure diagnosis and proof of completion

## Transmutation GUI produces nothing

Check, in order:

1. The item has positive EMC on this server.
2. The companion/player has learned the exact item variant and NBT policy permits it.
3. The personal EMC pool covers one output.
4. Search/filter text is not hiding the item.
5. The GUI interaction targeted the ProjectE slot rather than the player inventory.
6. Server permissions/configuration have not disabled the operation.

Do not repeatedly click an uncertain slot. Close, reinspect inventory and EMC, reopen, and test with one inexpensive item.

## Collector or Power Flower is stalled

1. Inspect the block above each collector and current light.
2. Trace only face-adjacent links.
3. Check relay and condenser storage/output capacity.
4. Check whether a collector is busy charging an item or upgrading fuel.
5. Confirm the condenser target has positive EMC.
6. Confirm the chunk is loaded while testing.

## EMC is disappearing unexpectedly

Pause the task, then inspect active ProjectE items: Gem of Eternal Density, flight rings, stones, time watch, armor abilities, furnaces, relays, and charged tools. Many consume from internal EMC, Klein Stars, or fuel items. Record before/after values over a short controlled interval rather than guessing.

## Safe completion evidence

For a ProjectE request, final success requires relevant evidence:

- crafting: requested output exists and ingredients were consumed according to the recipe;
- charging: exact holder and stored EMC increased;
- machine setup: correct blocks, adjacency, target/filter, free output, and observable progress;
- storage transfer: source decreased and intended destination increased;
- transmutation: inspected block IDs/states match the requested result;
- equipment: correct item is equipped and any required player-controlled toggle was separately verified;
- command: command returned success and the changed EMC/knowledge/mapping was queried again.

Report partial completion honestly when a player keybind, GUI slot, permission, unloaded chunk, or server configuration prevents verification.
