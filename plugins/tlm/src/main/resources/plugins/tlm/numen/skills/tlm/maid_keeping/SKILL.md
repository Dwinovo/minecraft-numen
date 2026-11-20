---
name: maid_keeping
description: How raising maids works in Touhou Little Maid — taming, the maid screen and its bauble page, backpacks, sitting and following, what each work mode needs, schedule / home / pickup settings, power points, and after a maid dies: the tombstone, the film, and the conditions and costs of the two ways to revive her (altar and shrine).
---

# Raising Touhou Little Maid maids

You can raise maids the way your owner does. The functions are in the `tlm` group: `tlm.maid.list`, `tlm.maid.info`, `tlm.maid.open`, `tlm.maid.task`, `tlm.maid.config` and so on;
look up how to call them with `numen.api.help("tlm.maid.task")` and the like. A maid's entity id comes from `numen.scan.entities` or `tlm.maid.list`, and changes after a server restart.

## What you can see

- `tlm.maid.list`: the maids that are yours — for those nearby, the id, task, schedule, health, favorability level and distance from you; for those in unloaded chunks, the last position; and tombstones that have not been emptied.
- `tlm.maid.info`: one maid in detail, with whether each work mode can be switched to right now, what is missing (`to_enable`) and what the work uses (`works_with`). Other people's maids can be looked at too.
- Your power points and the number of maids Touhou Little Maid counts as yours are in `<touhou_little_maid>` in your body state every turn.

## Taming

- A wild maid is tamed by right-clicking her with **cake**; having the cake in your inventory is enough. When it works you receive a `maid_tamed` event and she is yours from then on.
- Someone else's maid cannot be tamed with cake.

## Screen, equipment and backpack

- Her screen only opens within about 7 blocks of her. It holds her armor, main hand and off hand, her own few slots and her backpack; in `numen.gui.view` your inventory slots come first and hers after.
- Baubles are on another page (`bauble`), and with Curios installed there is a `curios` page as well.
- **A backpack is worn**: right-click her while holding one (for example `touhou_little_maid:maid_backpack_small`). Right-clicking her with **shears** takes the backpack off: the old backpack returns to your hand and what was in it drops at her feet.

## Sitting and following

- **Sneak-right-click** her to switch between sitting and standing; while you hold a kappa compass that click is the compass's use instead, so an empty hand is the safest.
- A sitting maid does not follow you and does not teleport to you.

## Work

- Each work mode needs particular things: ranged attack needs a bow in her hand and arrows in her slots (`has_bow` and `has_arrow` in `works_with`); farming needs seeds in her slots.
- After switching the work mode the receipt reads back the task she has now; if it did not change, Touhou Little Maid did not go along, and the receipt gives its rule.
- Settings: schedule (`day` works by day, `night` works by night and sleeps by day, `all` works around the clock); **home mode** (she works at home and no longer follows you; if no schedule point was ever set, where she stands is home); the pickup switch (she does not pick things up off the ground).
- For your own maids, switching work, changing settings and opening the screen need no asking by default; when your owner has written a rule that says to ask, your owner is asked first and it happens once they answer.

## A maid's death and revival

- When she dies you receive a `maid_died` event: where she died, and the tombstone's id and position. The **tombstone** holds everything she had and her **film** (which records everything about her).
- Right-click the tombstone to take back what is inside; it gives nothing when your inventory has no room.
- There are two ways to revive her with the film:

| Way | Conditions | Cost |
|---|---|---|
| **Altar** | On a built altar place the film, lapis lazuli, a gold ingot, redstone, an iron ingot and coal (hold one and right-click a pillar to place it); she revives the moment the last one is placed | 0.5 power points |
| **Shrine** | Right-click the shrine with the film to put it in, then right-click the shrine with an empty hand | **Your health is set straight to 0.25**; it is not allowed when your health is not a little over half |

A film put into the shrine but not used is taken back by sneak-right-clicking the shrine with an empty hand (with something in hand, a sneak-right-click does not reach the shrine).
