package com.dwinovo.numen.api.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 挖掉一格要几刻,照原版 1.20.1 的公式:每刻的进度是 {@code BlockBehaviour.getDestroyProgress},即
 * {@code Player.getDestroySpeed ÷ 硬度 ÷ (对的工具 30,否则 100)};进度累到 1 那一刻方块碎掉。{@code Player.getDestroySpeed}
 * 从手上那件的挖掘速度起算,速度大于 1 时加上效率附魔(等级的平方加一),再乘急迫、挖掘疲劳,眼睛泡在水里(没有水下速掘)除以 5,
 * 脚不着地除以 5。这一代没有挖掘效率、方块破坏速度、水下挖掘速度这几个属性,{@link Mining} 里它们取后来那几个属性的默认值。
 *
 * <p>挖碎之后,原版客户端的手要缓几刻才挖下一格({@link #cooldown})。
 *
 * <p>身体上的事实只有 {@link Mining} 那几项与游戏模式,所以不碰真实的身体也能算:寻路给挖一格定价、鼠标({@code Mouse})
 * 等多久缓手、第三方插件估一格的价钱,用的都是这一份公式。用哪件工具是使用者的事。
 */
public final class DigTime {

    /**
     * 挖掘速度用到的身体事实,照原版 {@code Player.getDestroySpeed} 取值。
     *
     * @param efficiency     不来自手上那件的效率(这一代恒为 0);手上那件的效率附魔由挑工具时按那件自己的附魔加上({@link #efficiency})
     * @param breakSpeed     方块破坏速度的乘数(这一代恒为 1)
     * @param submergedSpeed 眼睛泡在水里时的乘数(0.2,水下速掘把它提到 1)
     * @param haste          急迫与潮涌能量里较高的等级(amplifier),没有是 -1
     * @param fatigue        挖掘疲劳的等级(amplifier),没有是 -1
     */
    public record Mining(double efficiency, double breakSpeed, double submergedSpeed, int haste, int fatigue) {

        /**
         * 从一具真实的身体上读此刻的取值。这一代效率只来自工具自己的效率附魔(挑工具时每件按它自己的附魔加上),
         * 破坏速度恒为 1,水下的乘数看水下速掘附魔。
         */
        public static Mining of(ServerPlayer body) {
            return new Mining(0, 1.0, EnchantmentHelper.hasAquaAffinity(body) ? 1.0 : 0.2,
                    MobEffectUtil.hasDigSpeed(body) ? MobEffectUtil.getDigSpeedAmplification(body) : -1,
                    body.hasEffect(MobEffects.DIG_SLOWDOWN) ? body.getEffect(MobEffects.DIG_SLOWDOWN).getAmplifier() : -1);
        }
    }

    /** 原版客户端挖碎一格之后缓手的刻数({@code MultiPlayerGameMode.destroyDelay})。 */
    private static final int DESTROY_DELAY = 5;

    private DigTime() {}

    /**
     * 挖碎一格之后要缓几刻才能挖下一格,照原版客户端:累着进度挖碎的、创造模式挖掉的都缓 5 刻,生存模式一下就碎的不缓。
     * 寻路给挖一格定价与鼠标({@code Mouse})缓手按的都是它。
     *
     * @param instant 生存模式里第一下就碎(每刻的进度不小于 1)
     */
    public static int cooldown(boolean creative, boolean instant) {
        return creative || !instant ? DESTROY_DELAY : 0;
    }

    /**
     * 拿 {@code tool} 挖 {@code state} 要几刻。创造模式一下就碎;挖不动的方块(硬度为负)答 {@link Integer#MAX_VALUE}。
     *
     * @param creative   创造模式
     * @param eyeInWater 挖的时候眼睛泡在水里
     * @param grounded   挖的时候脚踏实地(挂在梯子上、浮在水里都不算)
     */
    public static int ticks(Mining mining, boolean creative, ItemStack tool, BlockState state, boolean eyeInWater,
                            boolean grounded) {
        return ticks(mining, creative, tool, efficiency(mining, tool), state, eyeInWater, grounded);
    }

    /** 同上,手上是这件工具时的挖掘效率属性值已经算好({@link #efficiency};调用方对一批工具重复算时按槽位缓存)。 */
    public static int ticks(Mining mining, boolean creative, ItemStack tool, double efficiency, BlockState state,
                            boolean eyeInWater, boolean grounded) {
        if (creative) {
            return 1;
        }
        float progress = progress(mining, tool, efficiency, state, eyeInWater, grounded);
        if (progress <= 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, (int) Math.ceil(1.0F / progress));
    }

    /**
     * 手上是 {@code tool} 时挖掘速度加上的效率:身体别处给的那一份,加上这件工具自己的效率附魔(等级的平方加一)。
     */
    public static double efficiency(Mining mining, ItemStack tool) {
        int level = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_EFFICIENCY, tool);
        return mining.efficiency() + (level > 0 ? level * level + 1 : 0);
    }

    /** 每刻的进度,原版 {@code getDestroyProgress};{@code efficiency} 是手上是这件工具时的挖掘效率属性值。 */
    private static float progress(Mining mining, ItemStack tool, double efficiency, BlockState state,
                                  boolean eyeInWater, boolean grounded) {
        float hardness = state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (hardness == -1.0F) {
            return 0;
        }
        float speed = tool.getDestroySpeed(state);
        if (speed > 1.0F) {
            speed += (float) efficiency;
        }
        if (mining.haste() >= 0) {
            speed *= 1.0F + (mining.haste() + 1) * 0.2F;
        }
        if (mining.fatigue() >= 0) {
            speed *= switch (mining.fatigue()) {
                case 0 -> 0.3F;
                case 1 -> 0.09F;
                case 2 -> 0.0027F;
                default -> 8.1E-4F;
            };
        }
        speed *= (float) mining.breakSpeed();
        if (eyeInWater) {
            speed *= (float) mining.submergedSpeed();
        }
        if (!grounded) {
            speed /= 5.0F;
        }
        boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        return speed / hardness / (correct ? 30 : 100);
    }
}
