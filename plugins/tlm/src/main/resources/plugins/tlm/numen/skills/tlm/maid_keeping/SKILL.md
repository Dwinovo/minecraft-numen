---
name: maid_keeping
description: How raising maids works in Touhou Little Maid — taming, what a maid wears and carries, baubles, backpacks, sitting and following, what each work mode needs, schedule / home / pickup settings, and after a maid dies: the tombstone, the film, and the conditions and costs of the two ways to revive her (altar and shrine).
---

# Raising Touhou Little Maid maids

## Taming

- A wild maid is tamed by right-clicking her with **cake**; she belongs to whoever tamed her from then on.
- Someone else's maid cannot be tamed with cake.

## Equipment, backpack and baubles

- A maid has four armor slots, a main hand and an off hand, six slots of her own, and more slots when she wears a backpack. Her things can be handled only within about 7 blocks of her, and not while she sleeps.
- Baubles go in separate bauble slots, which take only Touhou Little Maid's baubles. Two rows of them are open at favorability level 0 and 1, four rows at level 2, all of them from level 3.
- **A backpack is worn**: right-click her while holding one (for example `touhou_little_maid:maid_backpack_small`). Right-clicking her with **shears** takes the backpack off: the old backpack returns to the hand and what was in it drops at her feet.

## Sitting and following

- **Sneak-right-click** her with an empty hand to switch between sitting and standing. With a kappa compass in hand that click is the compass's use instead.
- A sitting maid does not follow and does not teleport to her owner.

## Work

- Only her owner may switch her work mode, and a mode may wait for something first.
- Each work mode needs particular things: ranged attack needs a bow in her hand and arrows in her slots; farming needs seeds in her slots.
- Settings: schedule (`day` works by day, `night` works by night and sleeps by day, `all` works around the clock); **home mode** (she works at home and no longer follows; if no schedule point was ever set, where she stands is home); the pickup switch (she does not pick things up off the ground).
- Power points are a per-player resource; the altar spends them.

## A maid's death and revival

- When she dies she leaves a **tombstone** where she died. It holds everything she had and her **film** (which records everything about her).
- Right-click the tombstone to take back what is inside; it gives nothing when the inventory has no room.
- There are two ways to revive her with the film:

| Way | Conditions | Cost |
|---|---|---|
| **Altar** | On a built altar place the film, lapis lazuli, a gold ingot, redstone, an iron ingot and coal (hold one and right-click a pillar to place it); she revives the moment the last one is placed | 0.5 power points |
| **Shrine** | Right-click the shrine with the film to put it in, then right-click the shrine with an empty hand | **The player's health is set straight to 0.25**; it is not allowed when health is not a little over half |

A film put into the shrine but not used is taken back by sneak-right-clicking the shrine with an empty hand (with something in hand, a sneak-right-click does not reach the shrine).
