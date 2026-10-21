package com.dwinovo.numen.tools.menu;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.tools.inventory.InvApi.Stack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * 铁砧:这次要花的经验和结果格(名字);改名是 {@code RenameItem}。名字多长、输入格有没有东西,原版在 {@code setItemName} 里管,不合就什么也
 * 不做;读回来结果格上的名字不是要的就是没成。取结果(扣经验)是 {@code numen.gui.take} 或 {@code numen.gui.quick}(结果格那一格)。
 */
final class AnvilKind implements MenuKind.Renames<AnvilMenu> {

    @Override
    public Class<AnvilMenu> menu() {
        return AnvilMenu.class;
    }

    @Override
    public String name() {
        return "an anvil";
    }

    @Override
    public Screen read(AnvilMenu menu, NumenPlayer her) {
        ItemStack result = menu.getSlot(AnvilMenu.RESULT_SLOT).getItem();
        return new Screen.Anvil(menu.getCost(),
                result.isEmpty() ? Optional.empty()
                        : Optional.of(new Stack(BuiltInRegistries.ITEM.getKey(result.getItem()).toString(), result.getCount())),
                result.isEmpty() ? Optional.empty() : Optional.of(result.getHoverName().getString()));
    }

    @Override
    public String rename(AnvilMenu menu, NumenPlayer her, String name) {
        if (name.isBlank() || name.length() > AnvilMenu.MAX_NAME_LENGTH) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "a name is 1 to " + AnvilMenu.MAX_NAME_LENGTH + " characters",
                    Call.of("numen.gui.rename", "Name"));
        }
        her.packets().renameItem(name);
        ItemStack result = menu.getSlot(AnvilMenu.RESULT_SLOT).getItem();
        if (result.isEmpty() || !result.getHoverName().getString().equals(name)) {
            throw new ApiError(ErrorKind.FAILED, menu.getSlot(AnvilMenu.INPUT_SLOT).getItem().isEmpty()
                    ? "nothing to rename: the anvil's input slot is empty — put the item in first (numen.gui.put)"
                    : "no result named \"" + name + "\" appeared (it may already have that name)",
                    Call.of("numen.gui.view"));
        }
        return "renamed the result to \"" + name + "\"; taking it (window slot " + AnvilMenu.RESULT_SLOT + ") costs "
                + menu.getCost() + " levels";
    }
}
