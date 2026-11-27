package com.dwinovo.numen.plugins.jei;

import com.dwinovo.numen.api.knowledge.InfoPage;
import com.dwinovo.numen.api.knowledge.RecipePage;
import com.dwinovo.numen.api.sdk.ClientCall;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Note;

/** {@code jei.lookup}:主人客户端上的 JEI 对一样物品的回答;全是客户端函数,在客户端主线程上执行。 */
public final class JeiApi {

    private JeiApi() {}

    @Fn("The recipes that make an item, from your owner's JEI.")
    @Note("Adds what the game's own recipe list lacks: which machine does it, the category's name in your owner's language, "
            + "fluids with their amounts. Chance, time, heat and energy are not read from JEI, so they are never given.")
    public static RecipePage recipes(ClientCall call, JeiQuery args) {
        return JeiLookups.recipes(args.item());
    }

    @Fn("The recipes that take an item as an input, from your owner's JEI.")
    @Note("Same shape and limits as jei.lookup.recipes.")
    public static RecipePage uses(ClientCall call, JeiQuery args) {
        return JeiLookups.uses(args.item());
    }

    @Fn("The description pages written for an item, from your owner's JEI.")
    @Note("Only notes: the game's own facts are minecraft.inv.info.")
    public static InfoPage info(ClientCall call, JeiQuery args) {
        return JeiLookups.info(args.item());
    }
}
