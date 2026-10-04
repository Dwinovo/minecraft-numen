---
name: dragon_combat
description: Final boss. End arena layout, crystal destruction from a safe distance (caged ones via auto-pillar + numen.work.dig), dragon attack patterns and the perch melee window, HP discipline over a long fight.
---

# Skill: dragon_combat

Phase 6 — the final boss. The Ender Dragon has 200 HP and heals from the end crystals while any survive. Mistakes here cost the whole run, mostly via the void.

## Done when

The dragon's HP reaches 0: death animation plays, ~the exit portal opens in the central bedrock fountain, a dragon egg appears on top. Tell your owner congratulations.

## Packlist (verify with `numen.status.self` BEFORE entering the portal)

- Diamond sword + bow, **32+ arrows** (10 crystals + air shots + misses)
- **128+ cobblestone** — the spawn platform is often ~100 blocks from the island and navigation bridges the gap with your blocks
- **32+ cooked food**, plus a golden_apple if you have one (emergency heal)
- Armor on, sword in hand (`numen.status.self` to confirm)

## The arena

- You arrive on a small obsidian platform out in the void. The **central island** (end stone, Y≈60) holds everything; `numen.move.to({x = 0, y = 62, z = 0}, {costs = {place = true}})` — navigation bridges across. **Falling into the void destroys you and everything you carry.** Fight near the island centre, never at the rim.
- **10 obsidian pillars** ring the centre, each topped by an **end crystal**. The 2 tallest crystals sit inside iron-bar cages.
- Intact crystals continuously heal the dragon — damaging it before they're gone is wasted effort. **Crystals first, always.**

## Step 1 — the 8 open crystals

Carry a bow with arrows, then `numen.scan.entities` → `numen.fight.attack(311)` for each crystal's id, one after another. Crystals die to one arrow and **explode with twice a creeper's power**, so `numen.fight.attack` refuses to close on one at all: it holds 12 blocks off and shoots. Without arrows it reports them unreachable rather than walking into the blast — that is the tool working, not failing.

## Step 2 — the 2 caged crystals

Per caged pillar:

1. `numen.move.to` the cell on top of the pillar (its x, top y + 1, z) with `{costs = {dig = true, place = true}}` — navigation pillars up the side on its own (this is what the spare cobblestone is for).
2. `numen.scan.blocks("iron_bars")`, then hand the nearest cluster's blocks to `numen.work.dig` to open the cage from the pillar top, where the bars are within reach (when they are not, `numen.work.dig` fails with out_of_reach and its hint is the `numen.move.to` line, arrive "dig", to copy first).
3. `numen.move.to` back down/away, then scan that crystal and call `numen.fight.attack(311)` with its id — it keeps its own distance from there.

While you're up high, the dragon may strafe the pillar — if `numen.status.self` shows falling HP, finish the bars and get down first.

## Step 3 — kill the dragon

Two modes, alternating:

- **Flying**: scan the dragon runtime ID, then `numen.fight.attack(305)` with it — out of reach means it shoots. Head shots take full damage, body shots are reduced; accept slow progress.
- **Perched** (it lands on the central fountain periodically, more often at low HP): the same `numen.fight.attack` line now reaches it and swings — the melee window does the real damage. Back off (`numen.move.flee(dragon, {distance = 12})`) when it takes off again.

### Its attacks and your answers

| Attack | Effect | Answer |
|---|---|---|
| Dive/charge | ~10 dmg + heavy knockback | Stay near the island centre so knockback can't reach the void |
| Dragon's breath | Lingering purple cloud, ~3 dmg/s | `numen.move.flee(cloud_pos, {distance = 6})` immediately; **never stand or fight in purple** |
| Wing buffet (perched) | ~5 dmg + knockback | Expected cost of the melee window; eat between perches |

### HP discipline

This is a long fight. Between every fight: `numen.status.self()`; **HP ≤ 10 → disengage to centre, `numen.inv.eat`, only then re-engage.** The dragon doesn't rush you — patience is free, death isn't.

## After the kill

- The exit portal (bedrock fountain, centre) returns you to the overworld spawn — `numen.move.to` into it when your owner is ready.
- The dragon egg on the fountain is a trophy your owner may want; it teleports when punched, so leave its extraction to them.
- Mark the entire endgame plan `[x]` with the `todo` tool.

## If you die

Your run ends where your body fell. If your owner recovers your gear, re-verify the packlist (`numen.status.self`), reload this skill, and walk back in through the still-active stronghold portal — the dragon keeps whatever damage it already took.
