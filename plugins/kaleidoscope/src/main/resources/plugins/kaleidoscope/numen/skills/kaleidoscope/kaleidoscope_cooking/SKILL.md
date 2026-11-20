---
name: kaleidoscope_cooking
description: How cooking works in Kaleidoscope Cookery — the stir-fry pot and the stockpot with their quirks and time limits, heat sources, oil, containers and utensils, the mod's four crops, the golden ratio and quality of flexible recipes, and which kitchen blocks cannot be used yet.
---

# Kaleidoscope Cookery

This world has Kaleidoscope Cookery installed. The cooking functions are in the `kaleidoscope.pot` group: `recipes` looks up the menu, `inspect` looks at a pot, `cook` makes one dish from start to finish,
and the pot's single steps are `oil` to pour oil, `base` to add a soup base, `fill` to add ingredients, `lid` to put the lid on or take it off, `stir` to stir-fry, `plate` to serve. Look up how to call them with
`numen.api.help("kaleidoscope.pot.cook")` and the like.

## Recipes

- A modpack can add dishes and remove them, so the menu is whatever `recipes` returns.
- Each line of `recipes` gives the dish's ingredients, its container (bowl, plate) and its utensil (spatula, lid).
- Some dishes are **flexible recipes** (the receipt says flex recipe), see the golden ratio below; the rest are fixed recipes, and adding the listed amounts makes that dish.

## The two pots

| | Stir-fry pot | Stockpot |
|---|---|---|
| Time limits | After the oil goes in, the window for adding ingredients is only about 1 minute; once done, the dish must be taken out within about 40 seconds or it burns, and left longer the whole pot turns to charcoal | Never burns; a finished stew waits as long as you like |
| Servings | One serving per pot | One pot serves several |
| Rules | Needs oil | With the lid on, nothing goes in and nothing comes out; the moment the lid is on with ingredients inside, it starts stewing |

- Both pots need **the block below them lit** (a stove or a campfire both work). Without fire the pot is completely still and no countdown runs; `inspect` shows `has_heat_source: false`.
- With no container in hand when the dish is done, it cannot be taken out and just waits to burn.
- A burnt dish becomes "dark cuisine". A burnt stir-fry usually means there was no container in hand when it was done, or the spatula was not in hand while stirring.
- One dish at a time: `cook` returns only when the dish is finished.

The **steamer, chopping board, millstone and grill** are not connected yet; they cannot be used.

## Where ingredients come from

The mod has four crops of its own, grown like wheat: till, sow, and right-click to harvest when ripe (harvesting sets the plant back to half-grown, so there is no need to sow again); a **sickle** (`kaleidoscope_cookery:sickle`) harvests a whole patch at once.

| Crop | Seed | Where it grows |
|---|---|---|
| Rice | `rice_seed` | **In water** (flooded farmland); grows two or three blocks tall, the harvest is rice panicles, and only panicles can be made into cooked rice |
| Tomato | `tomato_seed` | Ordinary farmland |
| Chili | `chili_seed` | Ordinary farmland |
| Lettuce | `lettuce_seed` | Ordinary farmland |

**Oil** is what the stir-fry pot cannot do without; its tag is `kaleidoscope_cookery:oil`. Pigs give fat; oil can be pressed into fat blocks for storage, poured into an oil pot (`oil_pot`),
or carried a spatula at a time by dipping the spatula into an enamel basin. No other fat can stand in for it.

## The golden ratio and quality

For a flexible recipe, **the same ingredients in different proportions give a different quality**: poor / normal / good / excellent. The quality sets how much hunger the dish restores and how long its status effects last.

- The golden ratio is **set by the world seed together with the recipe, one set per save**; it means nothing in another save, and a ratio from someone else's guide does not apply either.
- The amounts `recipes` gives are this save's golden ratio, and `cook` adds ingredients by it.
- What counts is the **ratio**, not the total: 2:1 and 4:2 are the same quality, so with few ingredients the smaller amounts waste nothing.
- A dish with a single ingredient is excellent however much goes in; one is enough.
