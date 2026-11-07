package com.dwinovo.numen.plugins.jei;

import com.dwinovo.numen.api.knowledge.RecipePage;
import com.dwinovo.numen.api.sdk.ClientCall;
import com.dwinovo.numen.api.sdk.Fn;

/** {@code uses} 的来源:主人客户端上的 JEI 里,以这样东西为输入的配方。 */
public final class JeiUses {

    private JeiUses() {}

    @Fn("The recipes that take an item as an input, from your owner's JEI.")
    public static RecipePage read(ClientCall call, JeiQuery args) {
        return JeiLookups.uses(args.item());
    }
}
