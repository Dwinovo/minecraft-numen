# ProjectE 1.0.1: compact execution rules

Target: Minecraft 1.20.1 / Forge. Server config, datapacks and other mods can change EMC. Verify actual GUI/tooltips/commands; never assume an item has EMC from memory.

## Mandatory boundary

- Ordinary movement, inventory GUI, crafting, placement and interaction are available.
- Charge/mode/extra-function ProjectE actions are keybind packets. Do not claim they happened unless a tool sends that key and the result is verified; otherwise ask the owner to press the key.
- Never destroy or dump a Klein Star, Transmutation Tablet, valuable learned inventory, collector/relay/condenser setup, or high-EMC item without explicit intent.

## Safe transmutation

1. Open the table/tablet and inspect the GUI.
2. Verify the target is learned and has positive EMC in this world.
3. Add only intended fuel/items, observe available EMC, select the target and take the exact amount.
4. Re-inspect inventory and EMC. Do not consume the last copy of an unlearned/important item.

## Progression core

- Philosopher’s Stone unlocks core recipes and transformations; it is normally a catalyst, so verify it remains after crafting.
- Fuel chain: coal -> alchemical coal -> Mobius fuel -> Aeternalis fuel. Recipes and EMC are server-authoritative.
- Dark Matter and Red Matter require the correct fuel/matter recipes; use `lookup_recipe`/`craft_items`, not remembered grids.
- Klein Stars store EMC. Check tier/capacity and current stored EMC before moving or using them.

## Automation

- Collector generates EMC/light-upgrades adjacent relays/condensers; relay moves EMC; condenser targets the item placed in its target slot.
- Before automation: identify exact machine, coordinates, tier, target item, input/fuel and output slots.
- Use `inspect_gui` semantic roles, load a single test batch, verify progress/output, then scale.
- Condenser target must be intentional and obtainable. A wrong target can consume large EMC reserves.
- Matter furnaces are real processing machines: verify accepted input, fuel/EMC source, progress and output before leaving.

## Tools, armor and utilities

- DM/RM tools, armor, rings and amulets may need EMC and/or keybind modes. Equipping is not proof an active ability ran.
- Check inventory EMC sources and mode before combat/mining. Ask the owner when a required mode cannot be controlled.

## Recovery

On failure, re-open GUI and verify current slots, EMC, target and output. Never repeat a deposit/craft blindly. Report exact missing item/EMC/keybind requirement and use source-verified recipes for this installed version.

## Compact reference: early rules and setup

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

## Additional compact constraints

ection |
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

**Unlearn slot**: Removes the item's persistent knowledge entry. The physical item is retained and must be returned to inventory after the operation. Never place an item here unless the owner actually intends to remove its learned entry.

## Final verification and recovery

After every ProjectE GUI action, verify inventory, personal/stored EMC, learned target, machine target and output. Because many actions are destructive or keybind-driven, do not retry from an assumed state. Re-open the GUI, inspect exact slots, and resume only the missing step. Report server-specific recipe/EMC differences rather than overriding them with remembered values.
