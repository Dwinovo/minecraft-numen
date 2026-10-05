package com.dwinovo.numen.plugins.curios;

import com.dwinovo.numen.api.gear.GearSlot;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.util.Optional;

/**
 * 饰品栏里的一格。判据和玩家在饰品界面里拖动是同一条路径:{@code CurioSlot} 就是包着同一个
 * {@link IDynamicStackHandler} 的 {@code SlotItemHandler}——放入问 {@code isItemValid}(槽的标签断言、
 * {@code ICurio.canEquip}、{@code CurioCanEquipEvent}),取出问 {@code extractItem}({@code CurioCanUnequipEvent}、
 * 绑定诅咒、{@code ICurio.canUnequip})。
 */
final class CurioGearSlot implements GearSlot {

    private final NumenPlayer body;
    private final ICurioStacksHandler handler;
    private final int index;

    CurioGearSlot(NumenPlayer body, ICurioStacksHandler handler, int index) {
        this.body = body;
        this.handler = handler;
        this.index = index;
    }

    @Override
    public String name() {
        return CuriosGear.PREFIX + handler.getIdentifier();
    }

    @Override
    public ItemStack worn() {
        return handler.getStacks().getStackInSlot(index);
    }

    /** Curios 8(本代)的槽没有「停用」这回事,格子在就收;收不收只看槽的断言与饰品自己。 */
    @Override
    public Optional<String> refuseWear(ItemStack one) {
        return handler.getStacks().isItemValid(index, one) ? Optional.empty()
                : Optional.of(id(one) + " is not accepted in " + name());
    }

    /** 模拟取一次:取不出来就是摘不下(绑定诅咒,或饰品自己、别的模组不让摘)。 */
    @Override
    public Optional<String> refuseRemove() {
        ItemStack worn = worn();
        if (worn.isEmpty() || !handler.getStacks().extractItem(index, worn.getCount(), true).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(id(worn) + " can't be taken off " + name()
                + " (curse of binding, or the accessory itself refuses)");
    }

    /**
     * 写进那一格;放进去的非空时,补调 {@code ICurio.onEquipFromUse}——和 {@code CurioSlot.set}
     * 一致(穿戴音效等由饰品自己决定)。
     */
    @Override
    public ItemStack swap(ItemStack in) {
        IDynamicStackHandler stacks = handler.getStacks();
        ItemStack old = stacks.getStackInSlot(index);
        stacks.setStackInSlot(index, in);
        if (!in.isEmpty()) {
            SlotContext context = new SlotContext(handler.getIdentifier(), body, index, false,
                    handler.getRenders().get(index));
            CuriosApi.getCurio(in).ifPresent(curio -> curio.onEquipFromUse(context));
        }
        return old;
    }

    private static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }
}
