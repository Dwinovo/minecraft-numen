package com.dwinovo.numen.entity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一次动作前后她的身体变了什么:背包与经验、血量、骑乘,以及装备有没有用坏。"身体做的一切都要如实报告给模型"在动作这一层的
 * 唯一出处——点一下、挖、打这些动作的回执都拿它说,各自不再写一份判据。
 *
 * <p>只报事实(多了什么、少了什么、血从多少到多少、骑上了什么),不下结论。动作开始时 {@link #open} 拍一张,收尾时问
 * {@link #changes}(背包、血量、骑乘)与 {@link #broken}(用坏的装备)。两样分开问:装备用坏任何动作里都可能发生,每件活的
 * 回执都要带;背包的增减是点一下这类动作的结果,挖、捡、合成有自己的账。
 */
public final class BodyDelta {

    /** 血量差小于它不算变:浮点误差。 */
    private static final float HEALTH_EPSILON = 1.0E-3f;

    private final Belongings carried;
    private final float health;
    private final Entity vehicle;
    private final long brokenMark;

    private BodyDelta(NumenPlayer her) {
        this.carried = Belongings.of(her);
        this.health = her.getHealth();
        this.vehicle = her.getVehicle();
        this.brokenMark = her.brokenGearMark();
    }

    /** 此刻拍一张。 */
    public static BodyDelta open(NumenPlayer her) {
        return new BodyDelta(her);
    }

    /** 从拍照到现在,背包与经验、血量、骑乘各自的变化,每一条是一件真发生的事;都没变是空表。 */
    public List<String> changes(NumenPlayer her) {
        List<String> facts = new ArrayList<>();
        String items = carried.itemChange(her);
        if (!items.isEmpty()) {
            facts.add("inventory: " + items);
        }
        String xp = carried.experienceChange(her);
        if (!xp.isEmpty()) {
            facts.add(xp);
        }
        if (Math.abs(her.getHealth() - health) >= HEALTH_EPSILON) {
            facts.add("health: " + number(health) + " -> " + number(her.getHealth()));
        }
        Entity now = her.getVehicle();
        if (now != vehicle) {
            if (vehicle != null) {
                facts.add("no longer riding " + describe(vehicle));
            }
            if (now != null) {
                facts.add("now riding " + describe(now));
            }
        }
        return facts;
    }

    /**
     * 从拍照到现在用坏的装备,写成一段圆括号里的话,接在任何回执后面都读得通;没有是空串。
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
        return "(" + String.join("; ", parts) + "; numen.inv.items() lists what you still carry)";
    }

    private static String where(EquipmentSlot slot) {
        return switch (slot) {
            case MAINHAND -> "in your main hand";
            case OFFHAND -> "in your off hand";
            default -> "on your " + slot.getName();
        };
    }

    private static String describe(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + " (id " + entity.getId() + ")";
    }

    /** 血量按原版的点数写,整数不带小数点。 */
    private static String number(float value) {
        return value == Math.rint(value) ? String.valueOf((int) value) : String.format(Locale.ROOT, "%.1f", value);
    }
}
