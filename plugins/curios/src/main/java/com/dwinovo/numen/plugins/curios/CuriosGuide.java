package com.dwinovo.numen.plugins.curios;

/**
 * {@code curios} 的说明(Markdown):Curios 联动,她经 {@code ApiSearch} 读到的第一份。这个插件没有自己的函数,只给穿戴多一类槽位。
 */
final class CuriosGuide {

    private CuriosGuide() {}

    static final String TEXT = """
            # curios

            Curios adds accessory slots (rings, charms, necklaces and other baubles) to the player. This plugin adds no functions of its own: it makes your accessory slots count as a place you can wear things.

            ## What changes
            - `numen.gear.wear(item)` and `numen.gear.remove` now know the accessory slots. Wearing something without naming a slot picks a fitting free slot by itself, armor slots and accessory slots alike; name a slot only when you want a particular one.
            - What you wear in accessory slots is listed with your armor in your worn list, so `numen.status.self()` shows it.

            ## Example
            ```lua
            numen.gear.wear("some_mod:lucky_ring")
            ```
            """;
}
