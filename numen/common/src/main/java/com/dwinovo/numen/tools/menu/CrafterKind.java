package com.dwinovo.numen.tools.menu;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.permission.Action;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import net.minecraft.world.inventory.CrafterMenu;

import java.util.ArrayList;
import java.util.List;

/**
 * 合成器:3x3 的九格各开没开;开关一格是 {@code ContainerSlotStateChanged}(格号,是 {@code gui.view} 列的界面格号 0 到 8)。动的是这台
 * 合成器,交权限层裁决"用这一格";旁观模式原版不认,读回来格子没变就如实说。
 */
final class CrafterKind implements MenuKind.Toggles<CrafterMenu> {

    private static final int GRID = 9;

    @Override
    public Class<CrafterMenu> menu() {
        return CrafterMenu.class;
    }

    @Override
    public String name() {
        return "a crafter";
    }

    @Override
    public Screen read(CrafterMenu menu, NumenPlayer her) {
        List<Screen.CrafterSlot> slots = new ArrayList<>();
        for (int i = 0; i < GRID; i++) {
            slots.add(new Screen.CrafterSlot(i, !menu.isSlotDisabled(i)));
        }
        return new Screen.Crafter(slots, menu.isPowered());
    }

    @Override
    public Action gate(CrafterMenu menu, NumenPlayer her) {
        return MenuKinds.useOpenedBlock(her);
    }

    @Override
    public String toggle(CrafterMenu menu, NumenPlayer her, int slot) {
        if (slot < 0 || slot >= GRID) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "the crafter's grid slots are 0 to " + (GRID - 1), Call.of("numen.gui.view"));
        }
        boolean wasOpen = !menu.isSlotDisabled(slot);
        her.packets().slotState(slot, !wasOpen);
        if (menu.isSlotDisabled(slot) != wasOpen) {
            throw new ApiError(ErrorKind.FAILED, "crafter slot " + slot + " stayed " + (wasOpen ? "open" : "closed"),
                    Call.of("numen.gui.view"));
        }
        return (wasOpen ? "closed" : "opened") + " crafter slot " + slot;
    }
}
