package com.dwinovo.numen.plugins.kaleidoscope;

/**
 * {@code kaleidoscope} 的说明(Markdown):森罗厨房(Kaleidoscope Cookery)联动,她经 {@code ApiSearch} 读到的第一份。函数各自的签名与说明
 * 由登记生成,这里讲它们之外的:这个插件干什么、怎么配合。
 */
final class KaleidoscopeGuide {

    private KaleidoscopeGuide() {}

    static final String TEXT = """
            # kaleidoscope

            Kaleidoscope Cookery adds cooking in a stir-fry pot and a stockpot. This plugin lets you read the menu, look at a pot, and cook a dish.

            ## How it fits together
            - `kaleidoscope.pot.recipes(cookware)` is the menu: recipe id, ingredients with portions, container, utensil and time. A pot knows a few hundred recipes, so narrow it with `name` or `have_only`. Some recipes are flexible and use this world's own golden ratio.
            - `kaleidoscope.pot.inspect(at)` reads one pot from any distance: stage, contents, heat, ticks left. Check that it is free before you cook on it.
            - `kaleidoscope.pot.cook(recipe, at)` makes a whole dish from start to finish, in the order the cookware wants. It is a module function built from the single steps, which you can call yourself: `oil`, `base` (a stockpot's soup base), `fill` (the ingredients), `lid`, `stir` (stir-fry until done) and `plate` (take the dish out with the carrier the recipe wants). A stir-fry pot burns its dish if it is not plated within about 40 seconds of being done; a stockpot never burns.
            - Every step stands where you are and works the pot in reach; each asks your owner when the rules say so.
            - Read the `kaleidoscope_cooking` skill for heat sources, containers, utensils, crops and the golden ratio.

            ## Example
            ```lua
            local menu = kaleidoscope.pot.recipes("pot", {name = "rice", have_only = true})
            kaleidoscope.pot.cook(menu.recipes[1].recipe, {x = 120, y = 64, z = -35})
            ```
            """;
}
