# End-game roadmap: compact coordinator

Use `todowrite` and verify each phase before advancing:

1. `tier_progression`: diamond pickaxe/sword, iron or better armor, bow/arrows, cooked food, blocks and tools.
2. `nether_entry`: valid lit portal; record both-side coordinates and keep return supplies.
3. `blaze_rods`: collect at least 7 rods safely; never destroy the spawner.
4. `ender_pearls`: acquire at least 12 pearls; keep rods/pearls reserved.
5. `stronghold_finding`: craft eyes, locate stronghold, secure/activate portal, but do not enter underprepared.
6. `dragon_combat`: crystals first, then dragon, with strict HP/void safety.

Load only the current phase skill plus `combat_basics`/`containers` as needed. Re-check inventory and world state at every phase boundary. Backfill missing supplies instead of improvising in a one-way-danger area. In creative mode, load `creative_mode` and skip survival resource grinding while retaining verification and safety.

## Compact reference: early rules and setup

# Skill: end_game_overview

Your owner has asked for an end-game goal such as:

```text
defeat the Ender Dragon
kill the dragon
beat Minecraft
finish the game
go to the End
find the stronghold
activate the End portal
prepare for the dragon
```

This skill is the **route map**.

It does not replace the specialised phase skills. It tells you:

```text
which phase you are in
which skill to load next
what each phase requires
how to verify completion
when to backfill missing resources
how to keep todo state correct
how to avoid loading every skill at once
how to recover from setbacks
```

The dragon route is long. It crosses:

```text
Overworld
Nether
End
```

and requires:

```text
gear progression
portal building
Nether travel
fortress/blaze farming
ender pearl acquisition
stronghold travel
End portal activation
dragon combat
```

Do not try to solve the entire route in one giant action. Execute one phase at a time.

---

# 0. Highest priority rules

## 0.1 Load this skill first for dragon-route goals

If the owner asks for the Ender Dragon or any end-game route, load:

```text
load_skill(name="end_game_overview")
```

Then inspect the current state:

```text
get_self_status
```

Use this skill to decide which specialised skill to load next.

## 0.2 Use `todowrite` for the main route

For the full route, create exactly six top-level todos:

```text
1. Tier up to dragon-route gear
2. Build and enter Nether portal
3. Collect blaze rods
4. Collect ender pearls
5. Find stronghold and activate End portal
6. Defeat Ender Dragon
```

Keep exactly one phase:

```text
in_progress
```

at a time.

Do not mark a phase complete from memory. Verify with `get_self_status` or world state first.

## 0.3 Load specialised skills only when needed

Do not load every phase skill up front.

Correct:

```text
load end_game_overview
check status
load tier_progression only if starting phase 1
```

Incorrect:

```text
load tier_progression, nether_entry, blaze_rods, ender_pearls,
stronghold_finding, dragon_combat all at once
```

Specialised skills are loaded on demand.

## 0.4 Verify phase completion before advancing

Every phase has a "done when" condition.

Use:

```text
get_self_status
```

for inventory/equipment/dimension checks.

Use world checks when needed:

```text
inspect_block
scan_blocks
locate_structure
interact_at
inspect_gui
```

Never assume:

```text
I probably have enough rods
I probably have enough pearls
I probably equipped armor
the portal is probably active
the dragon is probably dead
```

## 0.5 Backfill missing requirements

If the owner starts in the middle of the route, do not blindly restart from phase 1.

Instead:

```text
get_self_status
compare current inventory/equipment/dimension to phase requirements
load the earliest phase that is not actually complete
```

Example:

```text
Owner says "find the stronghold"
but inventory has 0 blaze rods and 0 ender pearls.
Backfill phases 3 and 4 first.
```

Example:

```text
Owner says "kill the dragon"
and you are already standing at an activated End portal with full gear.
Skip to phase 6.
```

## 0.6 Do not enter one-way-danger phases underprepared

Important points of no easy return:

```text
Entering the Nether without food/gear can strand or kill you.
Entering the End portal starts the final boss area.
The End cannot be exited normally until the dragon is dead.
```

Before entering the End:

```text
verify dragon_combat packlist
```

Do not jump into the End portal casually.

---

# 1. Creative mode exception

If:

```text
get_self_status reports game_mode=creative
```

then immediately load:

```text
load_skill(name="creative_mode")
```

Creative mode changes the route.

In creative mode:

```text
resources can be given directly
travel can use flight or commands
gear can be obtained instantly
building portals/structures can use fill/run_command
combat risk is reduced
```

However:

```text
do not use commands or creative shortcuts if the owner asked for legit survival gameplay
```

If the owner says:

```text
do it legit
survival only
no commands
no cheating
```

then follow survival-style phase skills even if creative tools exist, unless safety or explicit instruction changes that.

---

# 2. Support skills

This overview coordinates the route. Load support skills when relevant.

## 2.1 `combat_basics`

Load before combat-heavy phases:

```text
load_skill(name="combat_basics")
```

Use for:

```text
blazes
endermen
dragon
wither skeletons
fortress combat
general hostile mobs
```

It covers:

```text
HP thresholds
food healing
hunt vs shoot
arrow budgeting
retreat rules
positioning
aggro pitfalls
```

## 2.2 `containers`

Load when using GUIs:

```text
load_skill(name="containers")
```

Use for:

```text
crafting
smelting
chests
furnaces
crafting tables
moving exact item counts
loading machines
taking outputs
```

Important:

```text
supported crafting/smelting = plan_crafting + gather missing materials + craft_items + inventory verification
manual GUI fallback = lookup_recipe + inspect_gui + transfer + verify + close_gui
```

## 2.3 `creative_mode`

Load whenever:

```text
game_mode=creative
```

Use for:

```text
creative_give
fill
run_command
flight
teleportation
creative building/resources
```

## 2.4 Other specialised phase skills

Load only when the corresponding phase begins:

```text
tier_progression
nether_entry
blaze_rods
ender_pearls
stronghold_finding
dragon_combat
```

---

# 3. The six mainline phases

These phases mirror the normal vanilla progression toward the dragon.

| # | Phase | Skill | Done when | Vanilla advancement |
|---|---|---|---|---|
| 1 | Tier up to diamond-route gear | `tier_progression` | Diamond pickaxe + diamond sword, iron-or-better armor, bow + arrows, food, blocks | "Diamonds!" |
| 2 | Build and enter Nether portal | `nether_entry` | Standing in the Nether with packlist intact | "We Need to Go Deeper" |
| 3 | Farm blaze rods | `blaze_rods` | Inventory has >=7 blaze rods | "Into Fire" |
| 4 | Acquire ender pearls | `ender_pearls` | Inventory has >=12 ender pearls | — |
| 5 | Find stronghold and activate End portal | `stronghold_finding` | Standing at activated End portal | "Eye Spy" |
| 6 | Defeat Ender Dragon | `dragon_combat` | Dragon HP reaches 0 | "Free the End" |

---

# 4. Phase 1 overview: tier up to dragon-route gear

Load `tier_progression`, inspect current inventory and mark already satisfied requirements. Do not redo completed tiers. The phase is complete only when gear, food, blocks, ranged supplies and portal prerequisites are present together.

## Coordinator recovery rules

After interruption, rebuild the todo state from current inventory, dimension, saved locations and active tasks. Resume the earliest incomplete prerequisite rather than restarting at wood. Preserve route materials with reservations. Before every irreversible dimension transition, verify the current phase completion condition and the next phase packlist.
