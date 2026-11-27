---
name: litematica_projection
description: What Litematica's placements and layer display are, what the litematica.placement functions return (the list of placements, the cells of chosen layers, turning a design into a placement for your owner to look at), and how those cells go straight into minecraft.build.place and minecraft.build.diff.
---

# Litematica placements

Your owner has Litematica installed. It is a schematic mod that runs only on the player's client: your owner loads a schematic file (`.litematic`), puts it somewhere in the world, and a translucent ghost appears there to build by. The ghost is only shown on your owner's client; it is not blocks in the world. The functions are in the `litematica.placement` group; look up how to call them with ApiSearch (select:litematica.placement.cells) and the like. They all run on your owner's client and read your owner's Litematica as it is at that moment.

## Placements

A schematic file put somewhere in the world is a placement:

- **Origin**: the cell the schematic's own origin lands on.
- **Subregions**: a schematic can be made of several subregions, each with its own position and its own on/off switch; a subregion that is off is not part of the placement.
- **Rotation and mirror**: the whole placement can be rotated 0, 90, 180 or 270 degrees clockwise around its origin, and mirrored; `left_right` flips north–south (z), `front_back` flips east–west (x). Each subregion has its own rotation and mirror as well. Block facings change along with them.
- **Enabled**: your owner can turn a whole placement off.
- **Selected**: your owner can have several placements loaded at once, and one of them is the selected one.

Placements with the same name cannot be told apart, so naming one of them fails; with no name given, the selected one is read.

## Layer display (layer range)

Your owner can have Litematica show only some layers of a placement so the ghost does not block the view. It is your owner's display setting and does not limit what anyone can read: `mode` is `all`, `single_layer`, `layer_range`, `all_below` or `all_above` (Litematica's own names), `axis` is the axis the layers run along (usually `y`), `from` and `to` are the lowest and highest layer shown, and an end that has no bound is left out. The layer display shows which layer your owner cares about right now, but `cells` can read any layers it wants.

## What the functions return

`litematica.placement.list()` returns `layer` (the layer display above) and `placements`. Each placement gives: its name, which file it comes from, its origin, the world range it covers (the lowest and highest corners and the size along each axis), rotation, mirror, whether it is enabled, whether it is selected, and how many of its cells hold a block (air and switched-off subregions do not count).

`litematica.placement.cells(64, 66)` returns the cells of one placement in the three layers from world y 64 to 66 (a single number is one layer; with no placement named the selected one is read, and one is named with the option `{placement = "name"}`). Each cell is `{name, pos}`: `pos` is the world position and `name` is the block with its full state, written the way `/setblock` takes it, for example `minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]`. Litematica's own positioning is already applied: origin, subregions, rotation and mirror, with block facings turned to match. Air is not returned; layers the placement does not reach give an empty list.

One call returns at most 16384 cells. If the layers asked for do not fit, the call fails (`bad_argument`), and the hint gives the layers that do fit, counted from the lowest one.

## Handing them to building

The cells returned are Cells and go unchanged into any function that takes a list of cells:

```lua
local cells = litematica.placement.cells(64)
print(minecraft.build.diff(cells))
minecraft.build.place(cells)
```

`minecraft.build.diff` reports what is still missing, which materials the remaining cells need (`materials`) and which of them you do not have (`short_of`) — Litematica's own material list is counted against your owner's inventory, not yours.

## Showing your owner a design

`litematica.placement.preview(cells, name)` turns a list of cells into a placement in your owner's client, put at the cells' own world positions:

```lua
litematica.placement.preview({{name = "cobblestone", pos = {x = 100, y = 64, z = -20}}}, "my_house")
```

It only changes what your owner sees; the world does not change. The cells are saved as `numen/<name>.litematic` under the schematics folder, so your owner can load it again in Litematica later, edit it, and share it. Calling it again with the same name replaces the old placement, so calling it again after changing a design is an update. The name is also the file name and may only use letters, digits, spaces and `_ - . ( )`. Air cells are not shown. What comes back is the new placement, in the same form as an item of `list`.
