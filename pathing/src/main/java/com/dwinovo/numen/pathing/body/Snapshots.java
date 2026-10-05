package com.dwinovo.numen.pathing.body;

import java.util.List;

import com.dwinovo.numen.pathing.plan.BodySnapshot;
import com.dwinovo.numen.pathing.plan.Breath;
import com.dwinovo.numen.pathing.world.BodyStats;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.PowderSnowBlock;

/**
 * 从一具真实的身体上抄下规划要的身体快照,原版口径只此一处:尺寸取原版的姿势尺寸,迈步、起跳、重力、交互距离、
 * 摔落、挖掘速度取同名属性,细雪托不托得住照原版看脚上的皮靴,冰霜行者看靴子的附魔,游戏模式、血量、饱食度、
 * 主背包照抄;氧气、水下呼吸附魔、水下呼吸类效果还剩几刻、头上是不是海龟壳照抄,原版永远不扣氧
 * 的(能在水下呼吸、无敌)记成效果无穷。
 *
 * <p>本代(1.20.6)没有挖掘效率、水下挖掘速度、水下移动效率、氧气加成这几条属性,它们是附魔直接起的作用:深海探索者、
 * 水下速掘、水下呼吸按附魔等级折成同一条曲线填进快照(水下移动 = min(1, 深海探索者级数 / 3),水下挖掘 = 水下速掘 1 否则
 * 0.2,氧气加成 = 水下呼吸级数)。挖掘效率只来自手上那件的效率附魔,由挑工具时按那件自己的等级加上,身体别处没有这一份,
 * 记 0。
 *
 * <p>只在世界所在的线程上调用;抄出来的快照可以交给搜索线程。
 */
public final class Snapshots {

    private Snapshots() {}

    public static BodySnapshot of(ServerPlayer body) {
        BodyStats stats = stats(body);
        BodySnapshot.Mining mining = new BodySnapshot.Mining(0,
                body.getAttributeValue(Attributes.BLOCK_BREAK_SPEED),
                EnchantmentHelper.hasAquaAffinity(body) ? 1.0 : 0.2,
                MobEffectUtil.hasDigSpeed(body) ? MobEffectUtil.getDigSpeedAmplification(body) : -1,
                body.hasEffect(MobEffects.DIG_SLOWDOWN) ? body.getEffect(MobEffects.DIG_SLOWDOWN).getAmplifier() : -1);
        List<ItemStack> inventory = List.copyOf(body.getInventory().items);
        return new BodySnapshot(stats, body.gameMode.getGameModeForPlayer(), body.getHealth(),
                body.getAttributeValue(Attributes.SAFE_FALL_DISTANCE),
                body.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER), body.getFoodData().getFoodLevel(),
                Math.min(1.0, EnchantmentHelper.getDepthStrider(body) / 3.0), inventory, mining, breath(body));
    }

    /**
     * 憋气的本钱。水下呼吸类效果照原版 {@code MobEffectUtil.hasWaterBreathing} 认水下呼吸与潮涌能量两种,取剩得久的;
     * 能在水下呼吸的实体({@code canBreatheUnderwater})与无敌的玩家原版不扣氧({@code LivingEntity.baseTick}),记成无穷。
     */
    private static Breath breath(ServerPlayer body) {
        double shield = body.canBreatheUnderwater() || body.getAbilities().invulnerable ? Double.POSITIVE_INFINITY
                : Math.max(remaining(body, MobEffects.WATER_BREATHING), remaining(body, MobEffects.CONDUIT_POWER));
        return new Breath(body.getAirSupply(), body.getMaxAirSupply(), EnchantmentHelper.getRespiration(body),
                shield, body.getItemBySlot(EquipmentSlot.HEAD).is(Items.TURTLE_HELMET));
    }

    /** 这个效果还剩几刻;没有为 0,不限时为无穷。 */
    private static double remaining(ServerPlayer body, Holder<MobEffect> effect) {
        MobEffectInstance instance = body.getEffect(effect);
        if (instance == null) {
            return 0;
        }
        return instance.isInfiniteDuration() ? Double.POSITIVE_INFINITY : instance.getDuration();
    }

    /** 第 0 层要的那几项物理量:尺寸、迈步、起跳、重力、交互距离、细雪与冰霜行者。 */
    public static BodyStats stats(ServerPlayer body) {
        return new BodyStats(body.getDimensions(Pose.STANDING), body.getDimensions(Pose.CROUCHING),
                body.maxUpStep(), body.getAttributeValue(Attributes.JUMP_STRENGTH), body.getGravity(),
                body.blockInteractionRange(), PowderSnowBlock.canEntityWalkOnPowderSnow(body), frostWalker(body));
    }

    /** 脚上的靴子带冰霜行者。 */
    private static boolean frostWalker(ServerPlayer body) {
        return EnchantmentHelper.getItemEnchantmentLevel(Enchantments.FROST_WALKER, body.getItemBySlot(EquipmentSlot.FEET)) > 0;
    }
}
