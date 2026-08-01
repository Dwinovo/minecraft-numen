# chinese_classical — 中式官式

Official Chinese architecture makes hierarchy visible: a strict north–south
axis, symmetric courtyards, and a roof whose weight and colour announce the rank
of what stands beneath it. The bracket cluster under the eave is the signature —
a deliberately complex transition between column and roof. Colour is coded, not
chosen for taste: red columns, grey walls, and a glazed roof whose colour you do
not get to pick freely.

## Materials
- **columns**: red_concrete, red_terracotta, crimson_planks — evenly spaced and
  clearly structural
- **walls**: gray_concrete, stone_bricks, smooth_stone; the wall is infill, the
  columns carry the eye
- **roof**: this is where most attempts go wrong. The historic glazed tile was
  imperial yellow, but **gold_block reads as treasure, not as a roof** — it is
  too bright, too flat and too obviously metal. What actually works is a
  weathered, textured surface. One combination that holds up on a hand-built
  hall, band by band:
  - tiles `stone_brick_slab` — grey, textured, and it takes the light well
  - ridges `dark_prismarine` — deep blue-green, clearly darker than the tile, so
    the crest and the four diagonals read from across the courtyard
  - eave band `waxed_oxidized_cut_copper_slab` — one course of verdigris right
    around the edge
  - soffit `oak_slab*5, jungle_slab*4, spruce_slab*2` — three woods mixed, seen
    from under the deep eave
  Substitutes that work in the same slots: deepslate_tiles or gray_concrete for
  grey tile, warped_planks for an unusual teal. Reserve any yellow for a finial
  one block big, never a whole roof plane.
- **brackets and beams**: dark_oak_log, spruce_planks
- **paving**: stone, andesite, polished_andesite in a regular grid
- **light**: paired lanterns flanking every doorway

## Proportions
- an odd number of bays across the front (3, 5, 7); the centre bay is the door
- roof rise 0.6–0.75 of half-span — measured off hand-built halls, and the
  generator's `concave` curve already lands there
- eaves overhang 2–3, deeper at the corners
- rafter ends every 2 cells along the eave, which is the spacing the slope itself
  uses
- platform base 1–2 above the courtyard, with a central stair or ramp

## Signature moves
- **Axis** — everything mirrors about one line, and the visitor walks it.
- **Courtyard sequence** — gate, court, hall, court, hall. The space between
  buildings matters as much as the buildings.
- **Bracket band** — a visually busy horizontal layer between column top and
  eave. Stacked stairs and slabs read as brackets from any distance.
- **Upturned corners** — the eave corner lifts more sharply than Japanese work.

## Variants
- **palace hall** — yellow roof, wide platform, ceremonial scale
- **garden pavilion** — small, open on every side, beside water
- **temple** — grey roof, incense court, drum and bell towers flanking
- **siheyuan** — four wings enclosing one private courtyard

## Avoid
- breaking symmetry on the main axis
- yellow roofs on ordinary buildings; it outranks them
- exposed rough stone — surfaces here are finished
