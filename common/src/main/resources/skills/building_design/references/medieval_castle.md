# Style: medieval_castle — 城堡军事

Mass, symmetry, verticality: keep + curtain walls + corner towers.

## Proportions
- keep 15x15 up to 25x25, walls 8-12 tall; corner towers cylinder r=3-4, +4 taller than walls
- battlements: 1-high merlons every other block along wall tops
- gate: 3 wide x 4 tall arch on one face only

## Palette
- primary: stone_bricks; accent: cracked/mossy_stone_bricks mixed by hand (every 5-8 blocks)
- trim: chiseled_stone_bricks bands at tower tops; slits: no glass, 1x2 air gaps
- light: lantern, soul_lantern in dungeons

## Recipe
1. `walls` for curtain rect; `cylinder` hollow r=3 at each corner, height = walls+4
2. battlements: alternating `set` stone_brick cells along the top edge
3. gate arch: cut 3x4 air, `set` stone_brick_stairs (facing inward, half=top) as arch shoulders
4. keep inside: `walls` + `roof` or flat roof with battlements again

## Avoid
- windows bigger than 1x2; wood except doors/gates; perfect clean brick (mix in mossy/cracked)
