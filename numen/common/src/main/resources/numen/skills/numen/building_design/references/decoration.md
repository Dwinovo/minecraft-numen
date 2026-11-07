# Decoration — 让房子像有人住

Finishing touches that work in any style. A building wants the few that suit it; all of them at once make a showroom,
not a home. The measured interior numbers, what furniture is made of, the height bands and the room props are in the
Interiors section of SKILL.md.

## Ceilings and the frame

A flat plane of planks overhead reads as a lid. Beams across it — stripped logs every 2–4 cells, the same rhythm as the
roof — give the lanterns something to hang from. On a build whose style shows its frame outside, the frame continues
inside: the reference compound carries 30–42 timber cells on every storey. Beams (`stripped_*_log`, `*_wood`) and
upside-down stairs along the top of a wall also hide the underside of the roof blocks from inside.

## Lighting

130 light sources across a 40x45 compound: enough that nothing spawns, few enough that the rooms still have shadows.
Hostile mobs spawn only where block light is 0, and block light falls by 1 per cell of distance (x + y + z, around
solid blocks), so one source covers far more floor than it seems to.

| source | light |
| --- | --- |
| `lantern`, `campfire`, `sea_lantern`, `glowstone`, `shroomlight`, lit `redstone_lamp`, `jack_o_lantern`, froglights | 15 |
| `torch`, `end_rod` | 14 |
| `soul_lantern`, `soul_torch`, `soul_campfire` | 10 |
| `candle` — 1 / 2 / 3 / 4 in one cell | 3 / 6 / 9 / 12 |
| `glow_lichen` | 7 |

- lanterns hung (`hanging=true`) from beams read as fittings; torches stuck on walls at head height are the clearest
  single sign of an unfinished build
- a campfire lights a room and adds smoke
- candles give a small, warm, low light
- soul light (`soul_lantern`, `soul_campfire`) is cold and blue: anything eerie, cold or undead
- light hidden behind a slab lip, leaves or glass makes a surface seem to glow without a visible fixture
- outside, a lit doorway and path with the rest left dark reads as inhabited

## Exterior finishing

- **windows** — trapdoor shutters flanking the opening; a `flower_pot` or lantern on the sill; panes set back one cell
  into the wall so the opening has depth; a bay window pushed one cell out is its own small room
- **doorstep** — one stone step, two lanterns flanking, and a path of `dirt_path` / `gravel` / `coarse_dirt` leading
  away, wandering rather than ruler-straight, edged with slabs or stairs where it is formal
- **garden** — `short_grass` with two or three flower types mixed in, roughly one cell in four; shrubs as one or two
  leaf blocks on a log; a single tree off-axis beats a symmetrical pair; a large garden has one focal point (a tree,
  a pool, a fountain). Grass and dirt belong outdoors, not as an indoor floor.
- **chimney** — a 1x1 column past the ridge with a `campfire` on top for smoke
- **fence yard** — a ring of `oak_fence` with a gap, or an `oak_fence_gate` in it
- **roof interest** — a `bell` or `lightning_rod` near the ridge; lanterns hung under the eave corners
- **rafter ends** — a full block poking out under the eave every 2 cells, the spacing the slab slope itself uses
- **floors outside and in** — cracked and mossy variants, cobblestone and gravel where feet wear a floor most

## Water

Water reads as still only where every cell holds a source block; a single source spreads as flowing water, with a
visible current, up to 7 cells across flat ground. A flowing cell with two or more source blocks beside it, on a solid
floor or another source, becomes a source itself — two sources set apart by one cell make the cell between them a
third. A pond, moat, canal or fountain is a basin of source water: lined in stone or clay it reads as built; a natural
pond's edge is ragged and planted.
