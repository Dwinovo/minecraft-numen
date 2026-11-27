---
name: litematica_projection
description: What Litematica is and how its placements work — schematic files, the ghost your owner sees, origin, subregions, rotation and mirror, enabled and selected placements, and the layer display.
---

# Litematica placements

Litematica is a schematic mod that runs only on the player's client: a player loads a schematic file (`.litematic`), puts it somewhere in the world, and a translucent ghost appears there to build by. The ghost is only shown on that player's client; it is not blocks in the world.

## Placements

A schematic file put somewhere in the world is a placement:

- **Origin**: the cell the schematic's own origin lands on.
- **Subregions**: a schematic can be made of several subregions, each with its own position and its own on/off switch; a subregion that is off is not part of the placement.
- **Rotation and mirror**: the whole placement can be rotated 0, 90, 180 or 270 degrees clockwise around its origin, and mirrored; `left_right` flips north–south (z), `front_back` flips east–west (x). Each subregion has its own rotation and mirror as well. Block facings change along with them.
- **Enabled**: a whole placement can be turned off.
- **Selected**: several placements can be loaded at once, and one of them is the selected one.

## Layer display (layer range)

A player can have Litematica show only some layers of a placement so the ghost does not block the view. It is a display setting only: the schematic itself still holds every layer. The modes are `all`, `single_layer`, `layer_range`, `all_below` and `all_above`; the layers run along one axis (usually `y`) between a lowest and a highest layer, and an end can be left unbounded. The layer display shows which layer the player cares about right now.
