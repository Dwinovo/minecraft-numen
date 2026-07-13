# Tier progression: compact route

Goal before Nether: diamond pickaxe and sword, iron-or-better armor, bow/arrows, cooked food, building blocks, flint-and-steel resources and spare tools.

1. Inspect inventory first; skip completed tiers.
2. Wood -> crafting table -> wooden pickaxe; stone -> stone pickaxe/furnace; iron -> iron pickaxe, bucket, flint and steel, armor; diamond -> diamond pickaxe/sword.
3. Respect harvest tiers. Hold/equip the correct pickaxe before mining and navigation that may break blocks.
4. Use `craft_items`; for smelting use real furnace input/fuel/output roles and verify output.
5. Mine at appropriate depth, avoid lava/gravel falls, keep an escape path and do not spend reserved diamonds/iron on optional gear.
6. Cook food and maintain a useful cobblestone/block reserve. Confirm arrow count before relying on a bow.
7. Finish with `get_self_status`; verify every required item and amount, not merely completed crafting calls.

## Compact reference: early rules and setup

# Skill: tier_progression

This is Phase 1 of the dragon route.

Goal:

```text
Get strong enough gear for Nether entry, blaze rods, ender pearls, stronghold travel, and the dragon fight.
```

This phase turns a weak early-game inventory into a dragon-route-ready kit:

```text
diamond pickaxe
diamond sword
iron-or-better armor
bow
32+ arrows
32+ cooked food
64+ cobblestone or solid blocks
```

Do not skip this phase in survival-style play.

Under-geared Nether trips usually end with:

```text
lost inventory
lost portal route
death in lava
failed blaze farming
route reset
```

---

# 0. Completion condition

This skill is complete only when `get_self_status` verifies all of the following:

```text
minecraft:diamond_pickaxe exists
minecraft:diamond_sword exists
iron-or-better armor is worn or immediately available and equip-ready
minecraft:bow exists
minecraft:arrow count >= 32
cooked food count >= 32 preferred
solid block/cobblestone count >= 64
HP is safe
not currently under attack
```

Preferred final inventory:

```text
diamond_pickaxe
diamond_sword
full iron armor or better
bow
64+ arrows if possible
32+ cooked_beef or cooked_porkchop
64-128 cobblestone
crafting_table available or known nearby
furnace available or known nearby
some coal/charcoal
extra iron ingot for flint_and_steel later
```

Do not mark complete if:

```text
you only have iron tools
you have diamonds but did not craft diamond tools
you have raw food but not cooked food
you have bow but no arrows
you have arrows but no bow
you have armor in inventory but are not wearing it before danger
you used up cobblestone and have less than 64
you have not checked inventory with get_self_status
```

---

# 1. Absolute priority rules

## 1.1 Tool tier matters

`auto_mine` checks the held tool.

If the held tool is too weak:

```text
the block may break with no useful drop
```

Never mine important ores with the wrong pickaxe.

Examples:

```text
stone with hand -> no cobblestone
iron ore with wooden pickaxe -> no raw iron
diamond ore with stone pickaxe -> no diamond
obsidian with iron pickaxe -> no obsidian
```

Before mining, always:

```text
equip_item correct_pickaxe
get_self_status if unsure
```

## 1.2 Navigation also depends on held tool

`move_to` may dig, bridge, or pillar during navigation.

It digs using the currently held item.

Therefore:

```text
travel and mining movement should use pickaxe in hand
combat should use sword/bow only during combat
after combat, re-equip pickaxe
```

Common mistake:

```text
holding sword while trying to move_to underground target
```

Result:

```text
navigation cannot dig through stone
move_to may fail with no path
```

Fix:

```text
equip_item pickaxe
retry move_to
```

## 1.3 Do not enter Nether without this phase complete

Minimum before `nether_entry`:

```text
diamond_pickaxe
diamond_sword
armor
bow
arrows
food
blocks
```

If any are missing, stay in this skill and finish preparation.

## 1.4 Cook food

Raw food is poor healing.

For combat and route survival, use cooked food:

```text
cooked_beef
cooked_porkchop
cooked_mutton
cooked_chicken
cooked_salmon
cooked_cod
bread if meat unavailable
```

Preferred:

```text
cooked_beef
cooked_porkchop
```

## 1.5 Keep cobblestone stocked

Cobblestone is not junk.

It is needed for:

```text
Nether bridges
lava protection
ghast-resistant cover
End spawn platform bridge
pillaring to caged crystals
temporary shelters
navigation scaffold
```

Final target:

```text
64+ cobblestone minimum
128+ preferred before dragon phase
```

## 1.6 Verify with `get_self_status`

Do not rely on memory.

After every major step:

```text
get_self_status
```

Before marking phase complete:

```text
get_self_status
```

---

# 2. Required support skills

## 2.1 `containers`

Load for all crafting and smelting:

```text
load_skill(name="containers")
```

Use for:

```text
crafting table
tools
furnace
armor
bow
arrows
smelting raw iron
cooking food
moving items in GUIs
```

Supported crafting is preferably done by:

```text
plan_crafting item_id="<target>" count=<additional_count>
craft_items item_id="<target>" count=<additional_count>
get_self_status to verify
```

Supported smelting/cooking is preferably included in the same recursive `craft_items` workflow. For unsupported recipes or diagnosis, use the manual fallback:

```text
interact_at furnace
inspect_gui
transfer input
transfer fuel
wait/poll
transfer output
close_gui
```

## 2.2 `combat_basics`

Load before hunting animals at night or fighting hostile mobs:

```text
load_skill(name="combat_basics")
```

Use for:

```text
HP management
food healing
hunt vs shoot
retreat rules
skeleton/spider/creeper safety
```

## 2.3 `creative_mode`

If:

```text
get_self_status reports game_mode=creative
```

then load:

```text
load_skill(name="creative_mode")
```

If creative shortcuts are allowed, this phase can be completed directly with:

```text
creative_give
```

If the owner asked for legit survival-style play, still follow this skill without shortcuts.

---

# 3. Tools used in this skill

## Additional compact constraints

ob drops, cooked outputs if dropped, loot. |
| `eat_item` | Heal directly when HP is low. |
| `lookup_recipe` | Get exact crafting recipe. |
| `interact_at` | Open crafting table/furnace/chests, place/use blocks if needed. |
| `inspect_gui` | Read crafting/furnace/container slots. |
| `transfer` | Move ingredients into crafting grid, load furnace, take outputs. |
| `close_gui` | Close GUI after crafting/smelting. |
| `place_block` | Place crafting table, furnace, emergency blocks. |
| `inspect_block` | Check blocks before mining/placing if unsure. |
| `scan_blocks` | Find nearby ores, gravel, crafting table/furnace, etc. |
| `scan_nearby_entities` | Find animals, spiders, hostile mobs. |
| `wait` | Wait for furnace smelting/cooking, nightfall, or safety. |

---

# 4. Mining tier chain

## 4.1 Pickaxe progression

| Held tool | Can harvest | Needed for |
|---|---|---|
| Hand | logs, dirt, gravel | starting resources only |
| Wooden pickaxe | stone, coal ore | cobblestone, coal |
| Stone pickaxe | iron ore, lapis | raw iron |
| Iron pickaxe | diamond ore, gold ore, redstone | diamonds |
| Diamond pickaxe | obsidian | Nether portal obsidian |

## 4.2 Recipes

| Item | Recipe | Grid |
|---|---|---|
| Planks | 1 log -> 4 planks | 2x2 |
| Sticks | 2 planks vertical -> 4 sticks | 2x2 |
| Crafting table | 4 planks in 2x2 | 2x2 |
| Wooden pickaxe | 3 planks + 2 sticks | 3x3 |
| Stone pickaxe | 3 cobblestone + 2 sticks | 3x3 |
| Stone sword | 2 cobblestone + 1 stick | 3x3 or 2x2 depending recipe support |
| Furnace | 8 cobblestone ring | 3x3 |
| Iron pickaxe | 3 iron ingots + 2 sticks | 3x3 |
| Iron sword | 2 iron ingots + 1 stick | 3x3 or 2x2 depending recipe support |
| Diamond pickaxe | 3 diamonds + 2 sticks | 3x3 |
| Diamond sword | 2 diamonds + 1 stick | use the recipe selected by `craft_items` |

Recipe shape and alternatives are server-authoritative. Use `lookup_recipe` when needed and let `craft_items` choose a valid recipe; do not manually guess grid positions.

## Final verification and recovery

Inspect inventory and equipped armor after each tier. Reserve the diamonds, iron, food, arrows and blocks required by later phases. If interrupted, continue from the highest verified tool tier and remaining checklist instead of rebuilding early tools. Completion requires the entire Nether/dragon preparation set at once.
