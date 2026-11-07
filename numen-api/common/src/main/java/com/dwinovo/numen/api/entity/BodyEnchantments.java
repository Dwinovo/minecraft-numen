package com.dwinovo.numen.api.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * 身体上几项由附魔直接起作用的数值,读法只在这里。1.21 起它们是同名属性({@code sneaking_speed}、
 * {@code water_movement_efficiency}、{@code oxygen_bonus}、{@code submerged_mining_speed}),本代没有这些属性,原版在用到的地方
 * 直接读附魔等级;这里照本代原版的读法换算成与那些属性同一条曲线的值,调用方按属性的意思用。
 */
public final class BodyEnchantments {

    private BodyEnchantments() {}

    /** 潜行时移动输入乘的倍数:原版 {@code LocalPlayer.aiStep} 的 {@code 0.3 + 迅捷潜行加成},夹在 0 到 1。 */
    public static double sneakingSpeed(LivingEntity body) {
        return Mth.clamp(0.3F + EnchantmentHelper.getSneakingSpeedBonus(body), 0.0F, 1.0F);
    }

    /** 水下移动效率:原版 {@code LivingEntity.travel} 读深海探索者等级,最多算 3 级,每级三分之一。 */
    public static double waterMovementEfficiency(LivingEntity body) {
        return Math.min(3, EnchantmentHelper.getDepthStrider(body)) / 3.0;
    }

    /** 氧气加成:水下呼吸等级,每级让每刻扣氧的概率变成 {@code 1/(等级+1)}(原版 {@code LivingEntity.decreaseAirSupply})。 */
    public static double oxygenBonus(LivingEntity body) {
        return EnchantmentHelper.getRespiration(body);
    }

    /** 眼睛泡在水里时挖掘速度乘的倍数:原版 {@code Player.getDestroySpeed} 没有水下速掘就除以 5。 */
    public static double submergedMiningSpeed(LivingEntity body) {
        return EnchantmentHelper.hasAquaAffinity(body) ? 1.0 : 0.2;
    }
}
