package com.dwinovo.numen.api.entity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;

import java.util.ArrayList;
import java.util.List;

/**
 * 一件活从开始到现在,她身上用坏了哪些装备。装备用坏任何活里都可能发生(挖、打、走路垫块),所以每件活的回执都带这一句:活开始时
 * {@link #open} 记下,收尾时问 {@link #broken}。
 *
 * <p>身体别的变化不在这里:挖、捡、合成有自己的账;点一下之后身上变了什么,她自己看。
 */
public final class BodyDelta {

    private final long brokenMark;

    private BodyDelta(NumenPlayer her) {
        this.brokenMark = her.brokenGearMark();
    }

    /** 此刻记下。 */
    public static BodyDelta open(NumenPlayer her) {
        return new BodyDelta(her);
    }

    /**
     * 从记下到现在用坏的装备,写成一段圆括号里的话,接在任何回执后面都读得通;没有是空串。
     */
    public String broken(NumenPlayer her) {
        List<NumenPlayer.BrokenGear> gone = her.brokenGearSince(brokenMark);
        if (gone.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (NumenPlayer.BrokenGear g : gone) {
            parts.add("your " + BuiltInRegistries.ITEM.getKey(g.item()) + " broke while it was " + where(g.slot()));
        }
        return "(" + String.join("; ", parts) + "; check your inventory for what you still carry)";
    }

    private static String where(EquipmentSlot slot) {
        return switch (slot) {
            case MAINHAND -> "in your main hand";
            case OFFHAND -> "in your off hand";
            default -> "on your " + slot.getName();
        };
    }
}
