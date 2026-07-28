# Style: medieval_rustic — 中世纪乡村

Cozy village houses: timber frame, steep roofs, warm and worn.

## Proportions
- house 9x7 to 13x11, walls 3-4 tall, roof adds 4-6; door centered on the long side
- windows 1x1 every 2-3 blocks of wall, sill at wall_y+1

## Palette
- primary: oak_planks or white `smooth_quartz`-look walls? no — use `mud_bricks` or plain oak_planks
- frame/accent: dark_oak_log corners + horizontal dark_oak beams at wall top
- foundation: cobblestone; roof: dark oak (planks layers) or `deepslate_tile`-look; trim: fences
- glass: glass_pane; light: torch, lantern

## Roof recipe
`roof` op over wall rect; ridge along the long axis; add 1-block overhang later only
if the stance work supports it — skip overhang for now.

## Signature details (ops idioms)
- corner posts: `set` dark_oak_log axis=y at all four corners, full wall height
- wall-top beam: `box` dark_oak_log (axis=x/z via `set` per run) one course at wall top
- window boxes: `set` oak_trapdoor/flower under windows; `set` flower_pot on sills
- chimney: 1x1 cobblestone column from ground past the ridge +1, campfire on top
- door step: cobblestone `set` outside the door; path: `scatter` coarse_dirt/path blocks

## Avoid
- flat roofs; pure-cobblestone boxes; windows at floor level
