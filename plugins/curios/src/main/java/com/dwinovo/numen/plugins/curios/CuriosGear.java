package com.dwinovo.numen.plugins.curios;

import com.dwinovo.numen.api.gear.GearSlot;
import com.dwinovo.numen.api.gear.GearSource;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.ISlotType;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 饰品栏这一处穿戴来源。槽名是 {@code curios:<槽类型>}({@code curios:ring}),同类多格同名。
 *
 * <p>假玩家按 {@code EntityType.PLAYER} 拿到和真玩家同一套槽;整合包没给玩家分槽时饰品栏能力不会挂上,
 * 这里就一格都没有。
 */
final class CuriosGear implements GearSource {

    static final String PREFIX = CuriosApi.MODID + ":";

    /** 界面里看得见的槽类型,按 Curios 自己的顺序({@link ISlotType#getOrder})逐格展开。 */
    @Override
    public List<GearSlot> slots(NumenPlayer body) {
        Optional<ICuriosItemHandler> found = CuriosApi.getCuriosInventory(body);
        if (found.isEmpty()) {
            return List.of();
        }
        ICuriosItemHandler inv = found.get();
        Map<String, ISlotType> types = CuriosApi.getSlots(body.level());
        List<ICurioStacksHandler> handlers = new ArrayList<>();
        for (ICurioStacksHandler h : inv.getCurios().values()) {
            if (h.isVisible()) handlers.add(h);
        }
        handlers.sort(Comparator.comparingInt(h -> types.get(h.getIdentifier()).getOrder()));
        List<GearSlot> out = new ArrayList<>();
        for (ICurioStacksHandler h : handlers) {
            for (int i = 0; i < h.getSlots(); i++) {
                out.add(new CurioGearSlot(body, inv, h, i));
            }
        }
        return out;
    }

    /**
     * 物品本身得是饰品(有饰品能力,或挂着 {@code curios:*} 标签)才认领,认领的是收它的那些槽类型。
     * 先问"是不是饰品",整合包用 {@code curios:all} 定义的"什么都收"的槽才不会把镐子也揽过来。
     */
    @Override
    public Set<String> kindsOf(NumenPlayer body, ItemStack stack) {
        boolean curio = CuriosApi.getCurio(stack).isPresent()
                || stack.getTags().anyMatch(tag -> tag.location().getNamespace().equals(CuriosApi.MODID));
        if (!curio) {
            return Set.of();
        }
        Set<String> kinds = new TreeSet<>();
        for (String id : CuriosApi.getItemStackSlots(stack, body.level()).keySet()) {
            kinds.add(PREFIX + id);
        }
        return kinds;
    }
}
