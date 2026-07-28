# Style: nordic_viking — 北欧长屋

One big longhouse: boat-like proportions, all wood, steep shaggy roof to the ground.

## Proportions
- long and narrow: 17x7 up to 21x9; walls only 2-3 tall — the ROOF is the building
- roof slopes start at wall_y+0 and nearly reach the ground on the long sides

## Palette
- spruce everything: spruce_log frame, spruce_planks walls, spruce stairs/planks roof layers
- dark oak trim; foundation: cobblestone/stone; light: campfire + lantern

## Recipe
1. low `walls`, then oversized `roof` whose base rect extends 1-2 beyond the walls
2. gable ends: keep vertical, add exposed X-bracing with `set` log cells
3. ridge: full-length log run (`set` axis along ridge) with crossed rafter tips at both ends

## Signature details
- carved prow: extend the ridge log 1-2 past each gable, tip with a fence+trapdoor curl
- fire pit inside: campfire centered, smoke hole (leave 1 air gap in the ridge above it)
- shields on walls: alternating banner colors along the long face
