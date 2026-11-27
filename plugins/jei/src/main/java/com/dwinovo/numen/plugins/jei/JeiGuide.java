package com.dwinovo.numen.plugins.jei;

/**
 * {@code jei} 的说明(Markdown):JEI 联动,她经 {@code ApiSearch} 读到的第一份。三个查询函数,答复来自主人客户端上的 JEI。
 */
final class JeiGuide {

    private JeiGuide() {}

    static final String TEXT = """
            # jei

            Just Enough Items (JEI) knows the recipes of every mod that is installed, and the description pages that mods and modpacks wrote for items. JEI lives on your owner's client, so these answers come from there; when it does not answer, the call fails and says why (your owner's client is offline, JEI is not installed on it, it timed out).

            ## Functions
            - `jei.lookup.recipes(item)` and `jei.lookup.uses(item)` return JEI's recipes: which machine does it, the category's name in your owner's language, and fluids with their amounts. They have the same shape as `minecraft.inv.recipes` and `minecraft.inv.uses`, which answer from the game's own recipe list; ask JEI when you need to know which machine does a recipe.
            - `jei.lookup.info(item)` returns the description pages the mod or the modpack wrote for the item.
            - Chance, time, heat and energy are not read from JEI, so the results never guess them.

            ## Example
            ```lua
            local made = jei.lookup.recipes("create:shaft")
            print(#made.recipes)
            ```
            """;
}
