package com.dwinovo.numen.tools.menu;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.tools.inventory.InvApi.Stack;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 切石机:放进输入格的东西能切出的配方(产物与每次的数量),选一个就是按钮 = 配方序号(编号减一);选好之后结果格才出东西,拿走它是
 * {@code numen.gui.take} 或 {@code numen.gui.quick}(结果格那一格)。
 */
final class StonecutterKind implements MenuKind.Chooses<StonecutterMenu> {

    @Override
    public Class<StonecutterMenu> menu() {
        return StonecutterMenu.class;
    }

    @Override
    public String name() {
        return "a stonecutter";
    }

    @Override
    public Screen read(StonecutterMenu menu, NumenPlayer her) {
        List<Screen.Cut> recipes = new ArrayList<>();
        List<RecipeHolder<StonecutterRecipe>> held = menu.getRecipes();
        for (int i = 0; i < held.size(); i++) {
            ItemStack makes = held.get(i).value().getResultItem(her.level().registryAccess());
            recipes.add(new Screen.Cut(i + 1, Stack.of(makes, makes.getCount())));
        }
        int selected = menu.getSelectedRecipeIndex();
        return new Screen.Stonecutting(recipes, selected < 0 ? Optional.empty() : Optional.of(selected + 1));
    }

    @Override
    public String choose(StonecutterMenu menu, NumenPlayer her, Pick first, Pick second) {
        if (second != null) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "a stonecutter takes one recipe number", Call.of("numen.gui.view"));
        }
        if (menu.getNumRecipes() == 0) {
            throw new ApiError(ErrorKind.FAILED, "the stonecutter offers no recipe: its input slot is empty or holds "
                    + "nothing it cuts — put the block in first (numen.gui.put)", Call.of("numen.gui.view"));
        }
        int n = first.numberIn("recipe", menu.getNumRecipes());
        her.packets().menuButton(n - 1);
        if (menu.getSelectedRecipeIndex() != n - 1 || menu.getSlot(StonecutterMenu.RESULT_SLOT).getItem().isEmpty()) {
            throw new ApiError(ErrorKind.FAILED, "recipe " + n + " was not selected: the result slot is still empty",
                    Call.of("numen.gui.view"));
        }
        return "selected stonecutter recipe " + n;
    }
}
