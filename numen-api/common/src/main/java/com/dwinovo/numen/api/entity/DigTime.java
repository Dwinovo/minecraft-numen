package com.dwinovo.numen.api.entity;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 挖掉一格要几刻,照原版 1.21.1 的公式:每刻的进度是 {@code BlockBehaviour.getDestroyProgress},即
 * {@code Player.getDestroySpeed ÷ 硬度 ÷ (对的工具 30,否则 100)};进度累到 1 那一刻方块碎掉。{@code Player.getDestroySpeed}
 * 从手上那件的挖掘速度起算,速度大于 1 时加上挖掘效率属性,再乘急迫、挖掘疲劳、方块破坏速度属性,眼睛泡在水里乘水下挖掘
 * 速度,脚不着地除以 5。
 *
 * <p>挖碎之后,原版客户端的手要缓几刻才挖下一格({@link #cooldown})。
 *
 * <p>身体上的事实只有 {@link Mining} 那几项与游戏模式,所以不碰真实的身体也能算:寻路给挖一格定价、鼠标({@code Mouse})
 * 等多久缓手、第三方插件估一格的价钱,用的都是这一份公式。用哪件工具是使用者的事。
 */
public final class DigTime {

    /**
     * 挖掘速度用到的身体属性与效果,照原版 {@code Player.getDestroySpeed} 取值。
     *
     * @param efficiency     属性 {@code mining_efficiency} 里不来自手上那件的部分(原版 0);手上那件的效率附魔由挑工具时按
     *                       那件自己的修饰符加上({@link #efficiency})
     * @param breakSpeed     属性 {@code block_break_speed}(原版 1)
     * @param submergedSpeed 属性 {@code submerged_mining_speed}(原版 0.2,水下速掘把它提到 1)
     * @param haste          急迫与潮涌能量里较高的等级(amplifier),没有是 -1
     * @param fatigue        挖掘疲劳的等级(amplifier),没有是 -1
     */
    public record Mining(double efficiency, double breakSpeed, double submergedSpeed, int haste, int fatigue) {

        /** 原版玩家不带任何效果时的取值。 */
        public static final Mining VANILLA = new Mining(0, 1, 0.2, -1, -1);

        /**
         * 从一具真实的身体上读此刻的取值。挖掘效率属性里手上那件自己带的修饰符(效率附魔)要扣掉:挑工具时每件按它自己的修饰符
         * 加回去,不扣就会把手上那件的附魔算到每一件头上。
         */
        public static Mining of(ServerPlayer body) {
            return new Mining(efficiencyBesidesHand(body),
                    body.getAttributeValue(Attributes.BLOCK_BREAK_SPEED),
                    body.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED),
                    MobEffectUtil.hasDigSpeed(body) ? MobEffectUtil.getDigSpeedAmplification(body) : -1,
                    body.hasEffect(MobEffects.DIG_SLOWDOWN) ? body.getEffect(MobEffects.DIG_SLOWDOWN).getAmplifier() : -1);
        }

        /** 挖掘效率属性去掉手上那件自己的修饰符之后的值。 */
        private static double efficiencyBesidesHand(ServerPlayer body) {
            AttributeInstance live = body.getAttribute(Attributes.MINING_EFFICIENCY);
            Set<ResourceLocation> ofHand = new HashSet<>();
            body.getMainHandItem().forEachModifier(EquipmentSlot.MAINHAND, (holder, modifier) -> {
                if (holder.is(Attributes.MINING_EFFICIENCY.unwrapKey().orElseThrow())) {
                    ofHand.add(modifier.id());
                }
            });
            AttributeInstance rest = new AttributeInstance(Attributes.MINING_EFFICIENCY, changed -> {});
            rest.setBaseValue(live.getBaseValue());
            for (AttributeModifier modifier : live.getModifiers()) {
                if (!ofHand.contains(modifier.id())) {
                    rest.addTransientModifier(modifier);
                }
            }
            return rest.getValue();
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
     * 手上是 {@code tool} 时挖掘效率属性的值:身体别处给的那一份,加上这件工具自己在主手上的修饰符(效率附魔就在这里)。
     * 照原版属性的算法叠加,不是简单相加。
     */
    public static double efficiency(Mining mining, ItemStack tool) {
        AttributeInstance attribute = new AttributeInstance(Attributes.MINING_EFFICIENCY, changed -> {});
        attribute.setBaseValue(mining.efficiency());
        tool.forEachModifier(EquipmentSlot.MAINHAND, (holder, modifier) -> {
            if (holder.is(Attributes.MINING_EFFICIENCY.unwrapKey().orElseThrow())) {
                attribute.addTransientModifier(modifier);
            }
        });
        return attribute.getValue();
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
