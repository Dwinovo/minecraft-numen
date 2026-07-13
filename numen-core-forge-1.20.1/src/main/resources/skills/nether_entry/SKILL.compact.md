# Nether entry: compact route

Goal: obtain 10 normal obsidian, build/ignite a valid portal, record both portal coordinates, enter with supplies intact.

- Prefer ruined portals for obsidian; crying obsidian does not count. Mine normal obsidian only with a diamond-or-better pickaxe.
- Efficient frame uses 10 blocks. Verify all frame positions and clear portal interior before ignition.
- Carry flint and steel, combat gear, food, blocks, pickaxe and preferably one gold armor piece. Never use a bed in the Nether.
- Record Overworld portal coordinates before entry and Nether-side coordinates immediately after arrival.
- On arrival, assess lava/cliffs/hostiles, secure a retreat space and verify the return portal exists.
- If a portal fails to ignite, inspect frame dimensions, missing/wrong blocks and interior obstruction; repair the exact defect.
- Do not continue to fortress hunting until the packlist and return route are verified.

## Compact reference: early rules and setup

# Skill: nether_entry

This is Phase 2 of the dragon route.

Goal:

```text
Acquire 10 normal obsidian.
Build a valid Nether portal frame.
Ignite the portal.
Record the Overworld portal coordinates.
Enter the Nether.
Record the Nether-side portal coordinates.
Verify the packlist is intact.
```

Actual Nether survival and fortress work starts in:

```text
blaze_rods
```

This skill ends once you are safely standing in the Nether with the right supplies.

---

# 0. Completion condition

This skill is complete only when all of these are true:

```text
A lit Nether portal exists at a known Overworld location.
The Overworld portal coordinates have been recorded and reported to the owner.
You have entered the Nether.
get_self_status reports dimension == Nether.
The Nether-side portal coordinates have been recorded.
The required packlist is still present.
You are alive and not in immediate danger.
```

Do not mark this phase complete if:

```text
portal frame is built but not lit
portal is lit but you have not entered
you entered but did not record Nether-side coordinates
you entered with missing food/weapon/blocks/flint_and_steel
you are under attack and unsafe
you used crying obsidian in the required frame
```

---

# 1. Absolute priority rules

## 1.1 Use ruined portals for obsidian

For this route, get obsidian from:

```text
ruined portals
```

Do not use lava-casting as the planned route.

Reason:

```text
Freshly cast obsidian is usually fluid-adjacent.
Mining fluid-adjacent obsidian can release lava/water, burn items, flood tunnels, or trap you.
This agent should not mine blocks that are directly against dangerous fluids.
```

Correct:

```text
locate_structure("#minecraft:ruined_portal")
move_to ruined portal
auto_mine normal obsidian
```

Incorrect:

```text
cast obsidian with water over lava
then try to mine the new lava-adjacent obsidian wall
```

## 1.2 Only normal obsidian counts

A portal frame requires:

```text
minecraft:obsidian
```

It cannot use:

```text
minecraft:crying_obsidian
```

Crying obsidian has purple cracks/particles and is useless for the Nether portal frame.

`auto_mine("minecraft:obsidian")` should ignore crying obsidian automatically.

## 1.3 Need exactly 10 normal obsidian for the efficient frame

Minimum valid frame:

```text
4 blocks wide
5 blocks tall
corners omitted
10 obsidian total
inner opening is 2 wide x 3 tall
```

Do not place corners unless you intentionally have extra obsidian.

Corners are optional for a portal, but this route plans the 10-block frame.

## 1.4 Verify packlist before entering

Before igniting/entering the portal, use:

```text
get_self_status
```

Verify:

```text
food
weapon
bow
arrows
pickaxe
blocks
gold helmet or gold armor
flint_and_steel
armor
```

Do not enter the Nether unprepared.

## 1.5 Record portal coordinates

The portal is the way home.

Record and report:

```text
Overworld portal coordinates
Nether portal coordinates after arrival
```

Use:

```text
get_self_status
```

immediately before/after entering.

## 1.6 Never use a bed in the Nether

Beds explode in the Nether.

Do not:

```text
place bed in Nether
use bed in Nether
try to sleep in Nether
```

If a bed is in inventory, leave it unused.

---

# 2. Required support skills

## 2.1 `containers`

Load when crafting or looting containers:

```text
load_skill(name="containers")
```

Use for:

```text
planning/crafting flint_and_steel with real materials
planning/crafting gold helmet with real materials
looting ruined portal chest
moving exact item counts
```

For supported vanilla recipes, use `plan_crafting` then `craft_items`. Use manual crafting-grid transfers only as fallback or diagnosis.

## 2.2 `combat_basics`

Load if hostile mobs interfere:

```text
load_skill(name="combat_basics")
```

Use for:

```text
zombies
skeletons
spiders
ghasts after entry
piglin problems
Nether arrival danger
```

## 2.3 `creative_mode`

If:

```text
get_self_status reports game_mode=creative
```

then:

skip further obsidian gathering, verify flint and steel plus the Nether packlist, choose a safe build site and construct the frame. Do not consume spare obsidian unnecessarily.

## Final verification and recovery

The portal frame must be valid, lit and unobstructed. Save Overworld coordinates before entry and Nether coordinates after entry. Verify the return portal and inventory after dimension change. If interrupted, inspect the existing frame and repair only missing or wrong positions; do not build a duplicate portal blindly.

```text
load_skill(name="creative_mode")
```

In creative mode, if shortcuts are allowed, the portal can be built directly with creative items/commands. If the owner requested legit survival-style play, still follow this skill.

---

# 3. Tools used in this skill

| Tool | Use |
|---|---|
| `get_self_status` | Check inventory, HP, dimension, coordinates, equipped items. |
| `locate_structure` | Find nearest ruined portal. |
| `move_to` | Travel to ruined portal, base portal site, and enter portal. |
| `auto_mine` | Mine obsidian/gravel safely. |
| `equip_item` | Equip diamond pickaxe, flint and steel, armor, sword, etc. |
| `inspect_block` | Verify blocks before placing, lighting, or mining. |
| `scan_blocks` | Find chest/obsidian/portal blocks nearby. |
| `place_block` | Build the Nether portal frame block-by-block. |
| `interact_at` | Open chests, ignite portal with flint and steel, use blocks. |
| `lookup_recipe` | Get crafting recipe for flint_and_steel/gold helmet if needed. |
| `plan_crafting` | Recursively check whether required crafted items are possible and list missing base materials. |
| `craft_items` | Craft supported items with real inventory use and restart-safe execution. |
| `inspect_gui` | Read crafting/container slots. |
| `transfer` | Loot chests or manually craft only when automatic crafting is unsupported. |
| `close_gui` | Close crafting/container GUI. |
| `wait` | Wait in portal until dimension changes. |

---

# 4. Phase start decision tree

Start with:

```text
get_self_status
```

Then choose the correct subtask.

## 4.1 Already in the Nether

If:

```text
dimension == Nether
```

then this phase may already be complete.

Verify:

```text
food available
weapon available
bow/arrows available
blocks available
pickaxe available
flint_and_steel available
Nether portal coordinates known or recorded now
```

If packlist is intact:

```text
record Nether-side portal coordinates
mark phase 2 complete
load blaze_rods
```

If packlist is not intact:

```text
return/recover supplies before starting blaze_rods
```

## 4.2 Already have a lit Overworld portal

If standing near a lit portal:

```text
record Overworld portal coordinates
verify packlist
enter portal
record Nether portal coordinates
```

## 4.3 Have 10+ obsidian but no lit portal

If inventory has:

```text
minecraft:obsidian >= 10
```

then:

skip further obsidian gathering, verify flint and steel plus the Nether packlist, choose a safe build site and construct the frame. Do not consume spare obsidian unnecessarily.

## Final verification and recovery

The portal frame must be valid, lit and unobstructed. Save Overworld coordinates before entry and Nether coordinates after entry. Verify the return portal and inventory after dimension change. If interrupted, inspect the existing frame and repair only missing or wrong positions; do not build a duplicate portal blindly.
