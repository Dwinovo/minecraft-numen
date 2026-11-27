package com.dwinovo.numen.plugins.jei;

/**
 * {@code jei} 的说明(Markdown):JEI 联动,她经 {@code ApiSearch} 读到的第一份。这个插件没有自己的函数,只给知识动词多一个来源。
 */
final class JeiGuide {

    private JeiGuide() {}

    static final String TEXT = """
            # jei

            Just Enough Items (JEI) knows the recipes of every mod that is installed, and the description pages that mods and modpacks wrote for items. This plugin adds no functions of its own: it adds JEI as a source behind three functions that already exist.

            ## What changes
            - `numen.inv.recipes(item)` and `numen.inv.uses(item)` also return JEI's recipes: which machine does it, the category's name in your owner's language, and fluids with their amounts. A recipe that JEI and the game both list is one entry naming both.
            - `numen.inv.info(item)` also returns the description pages the mod or the modpack wrote for the item.
            - Chance, time, heat and energy are given by no source, so the results never guess them. `numen.api.sources("recipes")` lists the sources and what each can and cannot say.
            - JEI lives on your owner's client, so these answers come from there; when it does not answer, the result says why.

            ## Example
            ```lua
            local made = numen.inv.recipes("create:shaft")
            print(#made.recipes)
            ```
            """;
}
