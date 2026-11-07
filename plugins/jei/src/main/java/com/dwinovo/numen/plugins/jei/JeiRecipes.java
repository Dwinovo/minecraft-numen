package com.dwinovo.numen.plugins.jei;

import com.dwinovo.numen.api.knowledge.RecipePage;
import com.dwinovo.numen.api.sdk.ClientCall;
import com.dwinovo.numen.api.sdk.Fn;

/** {@code recipes} 的来源:主人客户端上的 JEI 里,以这样东西为产出的配方。 */
public final class JeiRecipes {

    private JeiRecipes() {}

    @Fn("The recipes that make an item, from your owner's JEI.")
    public static RecipePage read(ClientCall call, JeiQuery args) {
        return JeiLookups.recipes(args.item());
    }
}
