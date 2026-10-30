package com.dwinovo.numen.api.knowledge;

import com.dwinovo.numen.api.sdk.Doc;

import java.util.List;
import java.util.Optional;

/** 一个来源对 {@code recipes}、{@code uses} 的答复:配方,以及它因为封顶没列的还有几条。 */
@Doc("The recipes that match, and how many more matched beyond what is listed.")
public record RecipePage(@Doc("The recipes.") List<Recipe> recipes,
                         @Doc("How many more recipes matched but are not listed; absent when all are listed.")
                         Optional<Integer> omitted) {

    /** 全部列出的一页。 */
    public static RecipePage of(List<Recipe> recipes) {
        return new RecipePage(recipes, Optional.empty());
    }
}
