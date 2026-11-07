package com.dwinovo.numen.pathing.api;

import java.util.List;

import com.dwinovo.numen.api.entity.DigTime;
import com.dwinovo.numen.pathing.plan.BodySnapshot;
import com.dwinovo.numen.pathing.plan.Breath;
import com.dwinovo.numen.pathing.plan.Mobility;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Kinematics;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.PowderSnowBlock;

/**
 * 从一具真实的身体上抄下规划要的身体快照,原版口径只此一处:尺寸取原版的姿势尺寸,迈步、起跳、重力、交互距离、
 * 摔落、水下移动、挖掘速度取同名属性,细雪托不托得住照原版看脚上的皮靴,冰霜行者看靴子的附魔,游戏模式、血量、饱食度、
 * 主背包照抄;氧气、水下呼吸附魔(属性 {@code oxygen_bonus})、水下呼吸类效果还剩几刻、头上是不是海龟壳照抄,原版永远不扣氧
 * 的(能在水下呼吸、无敌)记成效果无穷。
 *
 * <p>只在世界所在的线程上调用;抄出来的快照可以交给搜索线程。
 */
public final class Snapshots {

    private Snapshots() {}

    public static BodySnapshot of(ServerPlayer body) {
        BodyStats stats = stats(body);
        DigTime.Mining mining = DigTime.Mining.of(body);
        List<ItemStack> inventory = List.copyOf(body.getInventory().getNonEquipmentItems());
        return new BodySnapshot(stats, body.gameMode.getGameModeForPlayer(), body.getHealth(),
                body.getAttributeValue(Attributes.SAFE_FALL_DISTANCE),
                body.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER), body.getFoodData().getFoodLevel(),
                body.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY), inventory, mining, breath(body), mobility(body));
    }

    /**
     * 憋气的本钱。水下呼吸类效果照原版 {@code MobEffectUtil.hasWaterBreathing} 认水下呼吸与潮涌能量两种,取剩得久的;
     * 能在水下呼吸的实体({@code canBreatheUnderwater})与无敌的玩家原版不扣氧({@code LivingEntity.baseTick}),记成无穷。
     * 规划之外要问"此刻憋得住吗"的(Numen 的换气本能)也读这一份。
     */
    public static Breath breath(ServerPlayer body) {
        double shield = body.canBreatheUnderwater() || body.getAbilities().invulnerable ? Double.POSITIVE_INFINITY
                : Math.max(remaining(body, MobEffects.WATER_BREATHING), remaining(body, MobEffects.CONDUIT_POWER));
        return new Breath(body.getAirSupply(), body.getMaxAirSupply(), body.getAttributeValue(Attributes.OXYGEN_BONUS),
                shield, body.getItemBySlot(EquipmentSlot.HEAD).is(Items.TURTLE_HELMET));
    }

    /**
     * 身上已有的移动方式:许不许飞、在不在飞、飞行速度照能力({@link Abilities});滑翔物看胸甲位——没坏的鞘翅
     * (再掉一点耐久不会坏,{@code nextDamageWillBreak} 为假),还能用多少点耐久是离坏掉前剩的;烟花数是背包与副手里的火箭总数。谁能飞、谁能滑翔只在这里判。
     */
    public static Mobility mobility(ServerPlayer body) {
        Abilities abilities = body.getAbilities();
        ItemStack chest = body.getItemBySlot(EquipmentSlot.CHEST);
        int gliderLife = chest.is(Items.ELYTRA) && !chest.nextDamageWillBreak()
                ? chest.getMaxDamage() - chest.getDamageValue() - 1 : 0;
        int fireworks = 0;
        int burn = Integer.MAX_VALUE;
        for (ItemStack stack : rockets(body)) {
            fireworks += stack.getCount();
            burn = Math.min(burn, ROCKET_BURN * flightDuration(stack));
        }
        return new Mobility(abilities.mayfly, flying(body), abilities.getFlyingSpeed(), gliderLife, fireworks,
                fireworks == 0 ? 0 : burn);
    }

    /** 背包与副手里的烟花火箭,各一叠。 */
    private static List<ItemStack> rockets(ServerPlayer body) {
        List<ItemStack> out = new java.util.ArrayList<>();
        for (ItemStack stack : body.getInventory().getNonEquipmentItems()) {
            if (stack.is(Items.FIREWORK_ROCKET)) {
                out.add(stack);
            }
        }
        if (body.getOffhandItem().is(Items.FIREWORK_ROCKET)) {
            out.add(body.getOffhandItem());
        }
        return out;
    }

    /** 烟花飞行时长({@code Fireworks.flightDuration}):每多一级多推十刻。 */
    private static int flightDuration(ItemStack stack) {
        Fireworks fireworks = stack.get(DataComponents.FIREWORKS);
        return fireworks == null ? 1 : Math.max(1, fireworks.flightDuration());
    }

    /** 一级飞行时长的烟花保底推多少刻({@code FireworkRocketEntity} 的寿命:{@code 10 × 时长 + 最多 11 的随机})。 */
    private static final int ROCKET_BURN = 10;

    /** 身体此刻在不在飞(创造飞行):快照里的 {@link Mobility#flying} 与执行时每刻问的是这一处。 */
    public static boolean flying(ServerPlayer body) {
        return body.getAbilities().flying;
    }

    /** 在水里游一格要几刻:规划给水里一步定价用的同一个数({@link Kinematics#wadeTicksPerBlock})。 */
    public static double swimTicksPerBlock(ServerPlayer body) {
        return Kinematics.wadeTicksPerBlock(stats(body), body.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY));
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
        return new BodyStats(body.getDimensions(Pose.STANDING),
                body.maxUpStep(), body.getAttributeValue(Attributes.JUMP_STRENGTH), body.getGravity(), walkingSpeed(body),
                body.getAttributeValue(Attributes.SNEAKING_SPEED),
                body.blockInteractionRange(), PowderSnowBlock.canEntityWalkOnPowderSnow(body), frostWalker(body));
    }

    /** 平走的移动速度:属性值去掉此刻疾跑加上的那一份(疾跑的倍数在 {@code Kinematics} 里按疾跑算)。 */
    private static double walkingSpeed(ServerPlayer body) {
        double value = body.getAttributeValue(Attributes.MOVEMENT_SPEED);
        return body.isSprinting() ? value / Kinematics.SPRINT_MULTIPLIER : value;
    }

    /** 脚上的靴子带冰霜行者。 */
    private static boolean frostWalker(ServerPlayer body) {
        Holder<Enchantment> frost = body.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.FROST_WALKER);
        return EnchantmentHelper.getItemEnchantmentLevel(frost, body.getItemBySlot(EquipmentSlot.FEET)) > 0;
    }
}
