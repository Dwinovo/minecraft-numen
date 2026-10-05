package com.dwinovo.numen.pathing.plan;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 挖掉一格要几刻,照这一代原版的公式:每刻的进度是 {@code BlockBehaviour.getDestroyProgress},即
 * {@code Player.getDestroySpeed ÷ 硬度 ÷ (对的工具 30,否则 100)};进度累到 1 那一刻方块碎掉。{@code Player.getDestroySpeed}
 * 从手上那件的挖掘速度起算,速度大于 1 时加上效率附魔(等级的平方加一),再乘急迫、挖掘疲劳,眼睛泡在水里乘水下挖掘
 * 速度,脚不着地除以 5。
 *
 * <p>挖碎之后,原版客户端的手要缓几刻才挖下一格({@link #cooldown})。
 *
 * <p>规划定价与执行等多久用的都是这里;所需的身体状态全部来自 {@link BodySnapshot},用哪件工具由 {@link ToolChoice} 定。
 */
public final class DigTime {

    /** 原版客户端挖碎一格之后缓手的刻数({@code MultiPlayerGameMode.destroyDelay})。 */
    private static final int DESTROY_DELAY = 5;

    private DigTime() {}

    /**
     * 挖碎一格之后要缓几刻才能挖下一格,照原版客户端:累着进度挖碎的、创造模式挖掉的都缓 5 刻,生存模式一下就碎的不缓。
     * 规划给挖一格定价({@link ToolChoice#handTicks})与身体的手({@code PlayerHands})按的都是它。
     *
     * @param instant 生存模式里第一下就碎(每刻的进度不小于 1)
     */
    public static int cooldown(boolean creative, boolean instant) {
        return creative || !instant ? DESTROY_DELAY : 0;
    }

    /**
     * 拿 {@code tool} 挖 {@code state} 要几刻。创造模式一下就碎;挖不动的方块(硬度为负)由 {@link DigRules} 先挡下,这里
     * 答 {@link Integer#MAX_VALUE}。
     *
     * @param eyeInWater 挖的时候眼睛泡在水里
     * @param grounded   挖的时候脚踏实地(挂在梯子上、浮在水里都不算)
     */
    public static int ticks(BodySnapshot body, ItemStack tool, BlockState state, boolean eyeInWater, boolean grounded) {
        return ticks(body, tool, efficiency(body, tool), state, eyeInWater, grounded);
    }

    /** 同上,手上是这件工具时的挖掘效率已经算好({@link ToolChoice} 按槽位缓存着)。 */
    static int ticks(BodySnapshot body, ItemStack tool, double efficiency, BlockState state,
                     boolean eyeInWater, boolean grounded) {
        if (body.creative()) {
            return 1;
        }
        float progress = progress(body, tool, efficiency, state, eyeInWater, grounded);
        if (progress <= 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, (int) Math.ceil(1.0F / progress));
    }

    /** 手上是 {@code tool} 时挖掘速度加上的效率:身体别处给的那一份,加上这件工具自己的效率附魔(等级的平方加一)。 */
    static double efficiency(BodySnapshot body, ItemStack tool) {
        int level = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_EFFICIENCY, tool);
        return body.mining().efficiency() + (level > 0 ? level * level + 1 : 0);
    }

    /** 每刻的进度,原版 {@code getDestroyProgress};{@code efficiency} 是手上是这件工具时加上的效率。 */
    private static float progress(BodySnapshot body, ItemStack tool, double efficiency, BlockState state,
                          boolean eyeInWater, boolean grounded) {
        float hardness = state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (hardness == -1.0F) {
            return 0;
        }
        BodySnapshot.Mining mining = body.mining();
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
