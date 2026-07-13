# Containers and machines: compact execution rules

Use the real GUI and real inventory. Never invent slot numbers.

## Universal loop

1. `interact_at` the exact container/machine/workstation.
2. `inspect_gui`; its menu name, slot indices, semantic `role=` labels, cursor and machine data are authoritative.
3. Plan all moves, then call `transfer` once with an ordered `moves` list when possible.
4. `inspect_gui` again and verify source/destination counts and machine state.
5. Return cursor-held items and `close_gui` when finished.

## Transfer rules

- Omit `to` to use vanilla quick-move routing between player and container. This is safest for ordinary deposits/takes.
- Use `to` + `count` only for exact placement/merging.
- For machines prefer `to_role` over guessed numbers: furnace-like menus use `input` and `fuel`; output is take-only.
- Never write into an output slot. Never move protected gear/material reservations without the matching purpose.
- Batch moves run in order; later indices refer to the resulting GUI state, so re-inspect after unexpected results.

## Crafting

- Prefer `craft_items`; it selects a valid recipe, clears/uses real grids correctly, consumes items and can resume.
- Use `lookup_recipe` when alternatives or workstation requirements are unclear. If multiple materially different recipes remain and owner intent matters, ask.
- Manual fallback: inspect the 2x2/3x3 map, clear stale grid items, place ingredients in the displayed cells exactly, take output, repeat, then return leftovers.

## Machines

- Furnace/blast furnace/smoker: input item -> `input`, valid fuel -> `fuel`, wait for progress, take `output`.
- Brewing/stonecutter/smithing/anvil/loom/cartography/enchanting: trust the semantic roles reported by `inspect_gui`.
- Before waiting, verify the machine actually accepted input/fuel and progress started. On resume, re-open and re-check instead of loading duplicate items.

Never claim success until final counts and output are verified.

## Compact reference: early rules and setup

# Skill: containers

You manipulate inventories through real Minecraft-style GUIs.

This skill applies to:

```text
chest
trapped_chest
barrel
shulker_box
furnace
blast_furnace
smoker
crafting_table
player 2x2 crafting grid
hopper
dispenser
dropper
brewing stand if supported
modded machines
modded crafting grids
modded storage blocks
```

Core idea:

```text
Open the block.
Inspect the GUI.
Read slot numbers.
Transfer items.
Verify result.
Close the GUI.
```

There is no magic per-item black box. You operate the menu like a player, but with reliable slot inspection and batch transfers.

---

# 0. Highest priority rules

## 0.1 Always inspect before transferring

Never guess slot numbers.

Always use:

```text
inspect_gui
```

before using:

```text
transfer
```

The GUI tells you:

```text
slot index
item type
stack count
which section the slot belongs to
whether slot is output-only
crafting grid layout if present
machine data values if present
```

## 0.2 Use `transfer` in batches

`transfer` accepts a list of moves.

Do not make one tool call per item unless necessary.

Good:

```text
transfer moves=[{from:S1}, {from:S2}, {from:S3}]
```

Bad:

```text
transfer moves=[{from:S1}]
transfer moves=[{from:S2}]
transfer moves=[{from:S3}]
```

Batch the whole operation into one call whenever the slot plan is already known.

## 0.3 Close the GUI when finished

Always finish with:

```text
close_gui
```

Walking away may auto-close the menu, but do not rely on it.

Leaving a GUI open can confuse later actions.

## 0.4 `inspect_gui` is the truth

Do not assume:

```text
chest slots are always numbered a certain way
inventory slots are always in a fixed order
crafting grid cells are consecutive
output slots accept items
machine slots are arranged like vanilla
```

Read the current GUI.

Use the slot indexes from `inspect_gui`.

## 0.5 Protect essential gear

Do not accidentally deposit or swap away essential route items unless the user explicitly wants it.

Essential items often include:

```text
pickaxe
sword
bow
arrows
food
armor
water bucket if relevant
blocks for bridging
blaze rods
ender pearls
eyes of ender
special quest items
```

When depositing “everything”, exclude essential tools and supplies unless the task says to store them.

---

# 1. When to load this skill

Load this skill when the task involves:

```text
opening a container
using a chest/barrel/shulker
putting items away
taking items out
moving exact counts
sorting inventory
crafting with a GUI
using a crafting table
using the 2x2 inventory grid
loading a furnace
collecting furnace output
using a modded machine
reading machine progress
fixing GUI transfer errors
```

Examples:

```text
"put the cobblestone in the chest"
"take 10 iron"
"craft sticks"
"smelt the raw iron"
"load the furnace"
"empty the barrel"
"move these items into the shulker"
"craft a pickaxe"
"take the output from the machine"
```

---

# 2. Completion condition

This skill is complete when:

```text
The requested item movement/crafting/smelting/container task is done.
The relevant GUI has been closed.
Inventory/container state has been verified if necessary.
No important item was accidentally left in the wrong place.
```

Do not consider the task complete if:

```text
the GUI is still open
the item count was not verified
the transfer failed or partially failed
the output is still sitting in the machine
you deposited required tools by mistake
you do not know where the item went
```

---

# 3. Tool reference

| Tool | Use |
|---|---|
| `get_self_status` | Check inventory, position, game mode, held item, and status. |
| `move_to` | Move near the container or machine if needed. |
| `interact_at` | Right-click the block to open its GUI. |
| `inspect_gui` | Read all slots, slot indexes, output slots, grid maps, and data values. |
| `transfer` | Move, merge, route, count-split, withdraw, deposit, craft, or swap items. |
| `close_gui` | Close the current GUI. |
| `inspect_block` | Confirm a block is the expected container/machine before opening. |
| `scan_blocks` | Find nearby containers/machines if coordinates are unknown. |
| `place_block` | Place a crafting table, furnace, chest, or machine if needed. |
| `break_block` | Remove a block if explicitly needed and safe. |

If in creative mode and the task is merely to obtain an item, use `creative_mode` and `creative_give` instead of containers.

If the task specifically says to move items in/out of a container, still use this GUI workflow.

---

# 4. The universal GUI loop

Every container/machine/crafting task follows this loop.

## 4.1 Open

Use:

```text
interact_at button=right x=<x> y=<y> z=<z>
```

Notes:

```text
interact_at can path to the block if needed.
You must be close enough to interact.
Use inspect_block first if unsure what block is there.
```

Example:

```text
inspect_block x=100 y=64 z=200
interact_at button=right x=100 y=64 z=200
```

## 4.2 Inspect

Immediately call:

```text
inspect_gui
```

Read:

```text
container slots
player inventory slots
hotbar slots
crafting grid slots
result/output slot
machine input/fuel/output slots
data values
empty slots
stack counts
```

Do not transfer until you know the relevant slot indexes.

## 4.3 Plan

Before transfer, decide:

- exact source stack and amount;
- semantic destination role or verified slot index;
- whether vanilla quick-move routing is safer;
- which items must remain reserved;
- expected source and destination counts afterward.

## Final verification and recovery

Inspect again after every batch. Cursor must be empty, requested outputs must be in inventory, protected items must remain, and machines must show accepted input/fuel or completed output. If any transfer differs from expectation, stop and inspect; never repeat the same click sequence blindly because earlier moves may already have changed slot contents.
