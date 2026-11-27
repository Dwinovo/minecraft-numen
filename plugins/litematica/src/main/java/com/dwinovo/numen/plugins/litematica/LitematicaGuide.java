package com.dwinovo.numen.plugins.litematica;

/**
 * {@code litematica} 的说明(Markdown):Litematica 联动,她经 {@code ApiSearch} 读到的第一份。函数各自的签名与说明由登记生成,这里讲它们之外的:
 * 这个插件干什么、怎么配合。
 */
final class LitematicaGuide {

    private LitematicaGuide() {}

    static final String TEXT = """
            # litematica

            Litematica is a schematic mod that runs on the player's client: your owner loads a schematic and a ghost of it shows where it should be built. The ghost is not blocks in the world. This plugin reads the schematics your owner has loaded so that you can build them, and shows your own designs back to them as projections.

            ## How it fits together
            - `litematica.placement.list()` shows the projections your owner has loaded, which one is selected and the layer display they set.
            - `litematica.placement.cells(layers)` returns the cells of one projection on the layers you name, in world coordinates, as cells you can pass straight to `minecraft.build.place` or compare with `minecraft.build.diff`. A call returns a limited number of cells, so for a large build ask a few layers at a time.
            - `litematica.placement.preview(cells, name)` shows a design of yours to your owner as a projection, at the cells' own coordinates, without touching the world.
            - Everything here runs on your owner's client and reads their Litematica as it is at that moment, so it exists only for an owner whose client has the mod.
            - Read the `litematica_projection` skill for placements, subregions, rotation and mirror.

            ## Example
            ```lua
            local cells = litematica.placement.cells(64, 66)
            minecraft.build.place(cells)
            ```
            """;
}
