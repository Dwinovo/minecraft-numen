package com.dwinovo.numen.plugins.minecraft;

/**
 * {@code minecraft} 这个插件的说明(Markdown):她经 {@code ApiSearch} 读 {@code select:minecraft} 时拿到的第一份。讲这个插件是干什么的、各组怎么配合、
 * 动手之前先看什么、合成熔炼从哪个函数起手;每个函数的签名与说明由登记生成,这里不重复。引擎的系统提示不讲这些:它们是这个插件的事。
 */
final class NumenGuide {

    private NumenGuide() {}

    static final String TEXT = """
            # minecraft

            The game itself: your body, the world around you, what you carry, and the machines of vanilla Minecraft. Whatever a player can do by hand, you do through these functions; other plugins add what one particular mod does on top of them.

            ## Look before you act
            - `minecraft.status.self()` is your whole self in one call: health, hunger, position, equipment and the full inventory. `minecraft.status.owner()` is your owner, `minecraft.status.world()` the dimension, time and weather. Check here instead of assuming, and claim an item or a finished job only when a result confirmed it.
            - The world comes from `minecraft.scan.*`. `minecraft.scan.blocks` finds blocks as clusters of touching blocks, nearest first. `minecraft.scan.entities` lists the entities near you with the ids (`kind@code`) every other function takes; `minecraft.scan.entity`, `minecraft.scan.block`, `minecraft.scan.sight` and `minecraft.scan.map` read one thing, or the ground around you, in more detail. `minecraft.locate.structure` and `minecraft.locate.biome` find what is far away.

            ## How the groups fit
            - `minecraft.move`: get somewhere. `walk` goes on foot and swims, digging and building when that is cheaper than going around; `fly` is creative flight and works only when you may fly; `glide` crosses a long way on an elytra; `away` and `follow` keep a distance or tag along. The module function `minecraft.move.explore(dir)` heads out in a direction until something turns up.
            - `minecraft.work` and `minecraft.fight`: `minecraft.work.dig` digs given blocks, `minecraft.work.mine(cluster)` digs out a cluster a scan found, `minecraft.work.collect()` picks up what lies around, `minecraft.work.fish` fishes; `minecraft.fight.attack` fights one entity and `minecraft.fight.clear(radius)` clears the hostiles around you.
            - `minecraft.build` and `minecraft.shape`: you draw a design as cells with `minecraft.shape` (regions, patterns and brushes named after WorldEdit's) and make the world look like it with `minecraft.build.place`, one pass from where you stand; `minecraft.shape.pos(x, y, z)` makes a Pos and `minecraft.shape.cells(list)` makes Cells of a list of cells; `minecraft.build.diff` says what a spot still lacks.
            - `minecraft.use`: click like a player does: `block`, `entity`, `item`, `hit`, plus `sign`, `sleep`, `wake`, `glide` and `dismount`. While you ride something, `minecraft.move.walk` sails a boat over water, and any other walk needs `minecraft.use.dismount()` to step off first.
            - `minecraft.inv`, `minecraft.gear`, `minecraft.creative`: what you carry (`items` lists every stack, and an entry of it names that exact stack for the functions that take an item; `count`, `drop`, `eat`, books), what you hold and wear (`minecraft.gear.hold`, `wear`, `remove`), and, in creative, `minecraft.creative.give`.
            - The stations of the game each have a group: `minecraft.container`, `minecraft.furnace`, `minecraft.anvil`, `minecraft.enchant`, `minecraft.grindstone`, `minecraft.stonecutter`, `minecraft.smithing`, `minecraft.loom`, `minecraft.cartography`, `minecraft.brewing`, `minecraft.crafter`, `minecraft.beacon`, `minecraft.lectern` and `minecraft.trade`.
            - `numen.task` shows and stops what your body is doing and sets timers, `minecraft.time` waits, `numen.permission` shows and tightens what your owner lets you do, and `minecraft.command.run` runs one Minecraft command as a player would type it.

            ## Making things
            Start from `minecraft.inv.recipes(item)`: it returns every recipe for the item in the game's recipe list, with its id and category. `minecraft.inv.uses(item)` and `minecraft.inv.info(item)` answer the opposite questions. Then:
            - Crafting is `minecraft.inv.make(item, count)`, which picks the recipe, finds or places a crafting table when the grid needs one, and crafts until there are enough; `minecraft.inv.craft` crafts one recipe once.
            - Smelting, blasting and smoking is `minecraft.inv.smelt(furnace, item, count)`, which fuels the furnace, waits and takes out what is cooked.
            - Don't use `minecraft.use.block` to "make" something: it clicks, it does not craft.

            ## Examples
            ```lua
            local veins = minecraft.scan.blocks("iron_ore", {radius = 24})
            if #veins > 0 then minecraft.work.mine(veins[1]) end
            ```
            ```lua
            minecraft.inv.make("minecraft:iron_pickaxe")
            minecraft.inv.smelt(minecraft.scan.blocks("minecraft:furnace")[1].nearest, "minecraft:raw_iron")
            ```
            """;
}
