# Decoration recipes — 让房子像有人住

Snippets for the pass AFTER the shell. Pick what the building needs; running all
of them makes a showroom, not a home.

Read the Interiors section of SKILL.md first for the budget and the rules. The
short version: on an inhabited floor, 35-50% of the cells are fittings, 98% of
furniture stands against a wall, and every level gets furnished.

## The trapdoor, four ways

Trapdoors are the most useful detail block in the game — measured at 397 of 941
furnishing cells on a hand-built compound, in seven different woods. They are the
only thin block you can put in every orientation, so learn all four states:

| properties | reads as |
| --- | --- |
| `half=bottom, open=true` | a vertical panel: screen, shutter, cupboard front, railing infill |
| `half=top, open=false` | a shelf hanging under a beam; a ceiling panel |
| `half=bottom, open=false` | a low ledge: a step, a hearth lip, a platform edge |
| `half=top, open=true` | a panel hanging down from above; a valance under an eave |

Mix wood types across one room — spruce, oak, dark_oak, jungle, bamboo — and the
fittings read as separate pieces of furniture instead of one repeated part.

## Interior fittings by room

Three or four props make a room's purpose legible. More than that and it turns
into a shop display.

- **kitchen** — `smoker` + `cauldron` + a run of `barrel` + `campfire`
  (`signal_fire=false`); a `composter` in the corner
- **study** — `lectern` facing a chair, a wall of `bookshelf` /
  `chiseled_bookshelf`, `candle` on a `half=top` trapdoor shelf
- **storeroom** — `barrel` and `chest` in a grid two or three high, `hay_block`
  sacks, `composter`
- **workshop** — `loom`, `stonecutter`, `grindstone`, `smithing_table`, barrels
  underneath, `lantern` overhead
- **bedroom** — bed against the wall, `chest` at its foot, `carpet` beside it,
  one shelf, one hanging lantern
- **hearth room / shrine** — `campfire` on a stone plinth, wall banners, paired
  lanterns, `decorated_pot`

Furniture goes **along the walls**; the middle of a room is circulation, and a
block in the way of a two-block-tall player is an obstacle, not a feature.

## Height bands

Work a room in bands so it is not all furniture-on-the-floor:

- **floor** — `carpet` to zone, `campfire` for the hearth, `half=bottom`
  trapdoor ledges, `decorated_pot`
- **1-2 above floor** — the furniture band; most of your cells go here
- **2-3 above floor** — the wall band: wall signs, wall banners, `half=top`
  trapdoor shelves, a `flower_pot` on a ledge
- **ceiling** — exposed beams (`stripped_*_log`, `*_wood`), hanging lanterns
  (`hanging=true`), trapdoor panels between the beams

## Ceilings and the frame

A flat plane of planks overhead reads as a lid. Run beams across it — stripped
logs every 2-4 cells, the same rhythm as the roof — and hang the lanterns from
them. On a build whose style shows its frame outside, the frame must continue
inside: the reference compound carries 30-42 timber cells on every storey.

## Lighting

130 light sources across a 40x45 compound: enough that nothing spawns, few enough
that the rooms still have shadows.

- hang lanterns (`hanging=true`) from beams rather than sticking torches on walls
  at head height — that is the clearest single sign of an unfinished build
- a campfire lights a room and adds smoke for free
- `candle` (1-4 per cell) for a small, warm, low light
- `soul_lantern` / `soul_campfire` for anything cold, eerie or undead
- outside: light the doorway and the path, and let the rest go dark

## Exterior finishing

- **windows** — trapdoor shutters flanking the opening; a `flower_pot` or lantern
  on the sill; panes set back one cell into the wall so the opening has depth
- **doorstep** — one stone step, two lanterns flanking, and a `scatter` path of
  `dirt_path` / `gravel` / `coarse_dirt` leading away
- **garden** — `scatter` `short_grass` plus two or three flower types at density
  0.15-0.3; a single tree off-axis beats a symmetrical pair
- **chimney** — a 1x1 column past the ridge with a `campfire` on top for smoke
- **fence yard** — a `walls`-shaped fence rect with a gap or a `set` fence_gate
- **roof interest** — a `bell` or `lightning_rod` near the ridge; lanterns hung
  under the eave corners
- **rafter ends** — a full block poking out under the eave every 2 cells, which
  is the spacing the roof slope itself uses
