---
name: nether_entry
description: Acquire obsidian, build a Nether portal with build, ignite it with flint & steel via numen.use.block, and enter the Nether with the right packlist.
---

# Skill: nether_entry

Phase 2 of the dragon route. Build a portal, ignite it, walk through. Actual Nether survival starts in `blaze_rods`.

## Done when

- A lit Nether portal stands at a known overworld location (**report its coordinates to your owner** — it's the way home)
- You are standing in the Nether with the packlist below intact

## Obsidian (need 10)

Mine it from a **ruined portal** — a structure that's just standing obsidian, no lava-casting. This is the only route: casting your own (water over lava) leaves every fresh obsidian block touching lava, and I refuse to mine fluid-adjacent blocks (it would flood or burn the dig), so a cast wall is unminable by design.

1. `numen.locate.structure("#minecraft:ruined_portal")` — searches the whole family and returns the nearest. **Skip `ruined_portal_ocean`** (underwater) if the result names it; re-search or pick a land one — I can't dive.
2. `numen.gear.hold("diamond_pickaxe")` (obsidian needs diamond), `numen.move.to` the portal coordinates.
3. `numen.scan.blocks("obsidian")`, then hand the frame's cluster to `numen.work.mine` — it walks within reach, digs, picks up the drops and goes on until none of them is left. ~9.4s per block is normal.

Notes:
- A portal's frame mixes plain **obsidian** with **crying obsidian** (purple particles). Crying obsidian is a *different block and useless for a portal frame* — a scan for `obsidian` alone leaves it out, so a single portal may yield fewer than 10. If you come up short, `numen.locate.structure("#minecraft:ruined_portal")` again for the next nearest and top up.
- If `numen.work.dig` reports obsidian that "can't be broken here" with fluid beside it, that portal sits in a wet/lava pocket — relocate to a cleaner one rather than fighting the fluid.

## Portal build

- Frame: 4 wide × 5 tall, **corners omitted = exactly 10 obsidian**, standing vertically. Inner opening is 2×3 air.
- Pick flat ground near your base. Draw the frame with `numen.shape` — two side columns of 3 plus top and bottom rows of 2 — and build it in one go with `numen.build.raise`; `numen.build.place` of a single Block handles any one-off correction:
  ```lua
  local S = numen.shape
  local o = S.pos(120, 64, -35)
  local frame = S.line(o:offset(0, 1, 0), o:offset(0, 3, 0), "obsidian")
    :union(S.line(o:offset(3, 1, 0), o:offset(3, 3, 0), "obsidian"))
    :union(S.line(o:offset(1, 0, 0), o:offset(2, 0, 0), "obsidian"))
    :union(S.line(o:offset(1, 4, 0), o:offset(2, 4, 0), "obsidian"))
  numen.build.raise(frame)
  ```
- **Flint & steel**: craft `flint_and_steel` = 1 iron ingot + 1 flint, a 2×2 recipe (`numen.inv.make` it; see the `containers` skill to lay a grid by hand). Flint drops from gravel you `numen.work.dig`, ~10%/block.
- **Ignite**: `numen.use.block({x = 121, y = 65, z = -35}, {item = "minecraft:flint_and_steel"})` aimed at an **empty air cell INSIDE the frame** (a bottom one), not at the obsidian. The fire lands in that cell and the portal forms.
- Enter: `numen.move.to` the portal cell and stand in it until the dimension changes (`numen.status.self` confirms).

## Packlist (verify with `numen.status.self` before igniting)

| Item | Qty | Why |
|---|---|---|
| Cooked food | 32+ | Your healing |
| Diamond sword + bow | 1 + 1 | Equip for combat only — hold the pickaxe while travelling (navigation digs with the held tool) |
| Arrows | 32+ | `numen.fight.attack` spends them only on what it cannot reach (~6 per blaze); run low → carry extra food and let it melee |
| Diamond pickaxe (+ iron backup) | 1 + 1 | Obsidian, digging |
| Cobblestone | 64+ | What navigation spends to bridge and pillar — bridging lava lakes eats it; cobblestone is in the default `materials` of every walk |
| Gold helmet (worn) | 1 | Piglin truce; 5 gold ingots if you must craft one |
| Flint & steel | 1 | Re-light the portal if a ghast blows it out |

**Never place or use a bed in the Nether — beds explode there.**

## Nether ground rules

- **Don't dig straight down**; lava oceans sit under most terrain. Navigation bridges lava when it must — keep cobblestone stocked.
- **Water doesn't exist here**: buckets won't place.
- **Zombified piglins are pacifists until hit — and then they ALL swarm.** Never `numen.fight.attack` them.
- **Ghasts** snipe from far; their fireballs can break the portal. On arrival, note the Nether-side portal coordinates (`numen.status.self`) and report them to your owner. Overworld↔Nether coordinates map 8:1 horizontally.

## What to load next

Standing in the Nether, packlist intact → mark phase 2 `completed`, load the `blaze_rods` skill. Load `combat_basics` too if you haven't — blazes are the first real combat test.

