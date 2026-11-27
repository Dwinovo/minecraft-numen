package com.dwinovo.numen;

/**
 * {@code numen} 这个插件的说明(Markdown):她经 {@code ApiSearch} 读 {@code select:numen} 时拿到的第一份。讲这个插件是干什么的、各组怎么配合、
 * 动手之前先看什么、合成熔炼从哪个函数起手;每个函数的签名与说明由登记生成,这里不重复。引擎的系统提示不讲这些:它们是内容插件的事。
 */
final class NumenGuide {

    private NumenGuide() {}

    static final String TEXT = """
            # numen

            The game itself: your body, the world around you, what you carry, and the machines of vanilla Minecraft. Whatever a player can do by hand, you do through these functions; other plugins add what one particular mod does on top of them.

            ## Look before you act
            - `numen.status.self()` is your whole self in one call: health, hunger, position, equipment and the full inventory. `numen.status.owner()` is your owner, `numen.status.world()` the dimension, time and weather. Check here instead of assuming, and claim an item or a finished job only when a result confirmed it.
            - The world comes from `numen.scan.*`. `numen.scan.blocks` finds blocks as clusters of touching blocks, nearest first. `numen.scan.entities` lists the entities near you with the ids (`kind@code`) every other function takes; `numen.scan.entity`, `numen.scan.block`, `numen.scan.sight` and `numen.scan.map` read one thing, or the ground around you, in more detail. `numen.locate.structure` and `numen.locate.biome` find what is far away.

            ## How the groups fit
            - `numen.move`: get somewhere. `walk` goes on foot and swims, digging and building when that is cheaper than going around; `fly` is creative flight and works only when you may fly; `glide` crosses a long way on an elytra; `away` and `follow` keep a distance or tag along. The module function `numen.move.explore(dir)` heads out in a direction until something turns up.
            - `numen.work` and `numen.fight`: `numen.work.dig` digs given blocks, `numen.work.mine(cluster)` digs out a cluster a scan found, `numen.work.collect()` picks up what lies around, `numen.work.fish` fishes; `numen.fight.attack` fights one entity and `numen.fight.clear(radius)` clears the hostiles around you.
            - `numen.build` and `numen.shape`: you draw a design as cells with `numen.shape` (regions, patterns and brushes named after WorldEdit's) and make the world look like it with `numen.build.place`, one pass from where you stand; `numen.build.diff` says what a spot still lacks.
            - `numen.use`: click like a player does: `block`, `entity`, `item`, `hit`, plus `sign`, `sleep`, `wake`, `glide` and `dismount`.
            - `numen.inv`, `numen.gear`, `numen.creative`: what you carry (`items`, `count`, `drop`, `eat`, books), what you hold and wear (`numen.gear.hold`, `wear`, `remove`), and, in creative, `numen.creative.give`.
            - The stations of the game each have a group: `numen.container`, `numen.furnace`, `numen.anvil`, `numen.enchant`, `numen.grindstone`, `numen.stonecutter`, `numen.smithing`, `numen.loom`, `numen.cartography`, `numen.brewing`, `numen.crafter`, `numen.beacon`, `numen.lectern` and `numen.trade`.
            - `numen.task` shows and stops what your body is doing and sets timers, `numen.time` waits, `numen.permission` shows and tightens what your owner lets you do, and `numen.mc.run` runs one Minecraft command as a player would type it.

            ## Making things
            Start from `numen.inv.recipes(item)`: it returns every recipe for the item, with its id and category, from every source that is installed. `numen.inv.uses(item)` and `numen.inv.info(item)` answer the opposite questions. Then:
            - Crafting is `numen.inv.make(item, count)`, which picks the recipe, finds or places a crafting table when the grid needs one, and crafts until there are enough; `numen.inv.craft` crafts one recipe once.
            - Smelting, blasting and smoking is `numen.inv.smelt(furnace, item, count)`, which fuels the furnace, waits and takes out what is cooked.
            - Don't use `numen.use.block` to "make" something: it clicks, it does not craft.

            ## Examples
            ```lua
            local veins = numen.scan.blocks("iron_ore", {radius = 24})
            if #veins > 0 then numen.work.mine(veins[1]) end
            ```
            ```lua
            numen.inv.make("minecraft:iron_pickaxe")
            numen.inv.smelt(numen.scan.blocks("minecraft:furnace")[1].nearest, "minecraft:raw_iron")
            ```
            """;
}
