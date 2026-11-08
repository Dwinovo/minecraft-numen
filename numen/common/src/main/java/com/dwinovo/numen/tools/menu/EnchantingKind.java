package com.dwinovo.numen.tools.menu;

import com.dwinovo.numen.PlayerInv;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 附魔台:三个选项各自的等级要求与原版露出的那一个提示;选一项就是按钮 0 到 2(选项编号减一)。等级、青金石够不够、物品能不能附魔,原版在
 * {@code clickMenuButton} 里判,不够什么也不做;读回来这一格里的东西没变就是没附上,把要的和有的说出来。
 */
final class EnchantingKind implements MenuKind.Chooses<EnchantmentMenu> {

    @Override
    public Class<EnchantmentMenu> menu() {
        return EnchantmentMenu.class;
    }

    @Override
    public String name() {
        return "an enchanting table";
    }

    @Override
    public Screen read(EnchantmentMenu menu, NumenPlayer her) {
        var ids = her.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT).asHolderIdMap();
        List<Screen.EnchantOption> options = new ArrayList<>();
        for (int i = 0; i < menu.costs.length; i++) {
            Holder<Enchantment> clue = menu.enchantClue[i] < 0 ? null : ids.byId(menu.enchantClue[i]);
            Optional<Screen.EnchantHint> hint = clue == null ? Optional.empty()
                    : Optional.of(new Screen.EnchantHint(clue.unwrapKey().map(key -> key.location().toString()).orElse("?"),
                            menu.levelClue[i]));
            options.add(new Screen.EnchantOption(i + 1, menu.costs[i], menu.costs[i] > 0, hint));
        }
        return new Screen.Enchanting(options, menu.getGoldCount(), her.experienceLevel);
    }

    @Override
    public String choose(EnchantmentMenu menu, NumenPlayer her, Pick first, Pick second) {
        if (second != null) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "an enchanting table takes one option: 1, 2 or 3",
                    Call.of("numen.gui.choose", 1));
        }
        int n = first.numberIn("option", menu.costs.length);
        String before = Receipt.stack(menu.getSlot(0).getItem());
        her.packets().menuButton(n - 1);
        if (Receipt.stack(menu.getSlot(0).getItem()).equals(before)) {
            throw refused(menu, her, n);
        }
        return "pressed enchanting option " + n;
    }

    /** 没附上:读到的事实——这一项要什么、她和台子里有什么。 */
    private static ApiError refused(EnchantmentMenu menu, NumenPlayer her, int n) {
        ItemStack item = menu.getSlot(0).getItem();
        if (item.isEmpty()) {
            return new ApiError(ErrorKind.FAILED, "nothing was enchanted: the table's item slot is empty — put the item "
                    + "in first (numen.gui.put)", Call.of("numen.gui.view"));
        }
        int cost = menu.costs[n - 1];
        if (cost <= 0) {
            return new ApiError(ErrorKind.FAILED, "nothing was enchanted: the table offers nothing at option " + n
                    + " for " + Receipt.stack(item) + " — numen.gui.view shows which options are offered",
                    Call.of("numen.gui.view"));
        }
        int lapis = menu.getGoldCount();
        String hint = lapis < n && PlayerInv.carriedCount(her.getInventory(), Items.LAPIS_LAZULI) > 0
                ? Call.of("numen.gui.put", "minecraft:lapis_lazuli") : null;
        return new ApiError(ErrorKind.FAILED, "nothing was enchanted: option " + n + " needs " + Math.max(cost, n)
                + " experience levels and " + n + " lapis lazuli; you have " + her.experienceLevel + " levels and the table "
                + "holds " + lapis + " lapis lazuli", hint);
    }
}
