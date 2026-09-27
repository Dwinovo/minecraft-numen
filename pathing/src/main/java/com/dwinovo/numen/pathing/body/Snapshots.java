package com.dwinovo.numen.pathing.body;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.dwinovo.numen.pathing.plan.BodySnapshot;
import com.dwinovo.numen.pathing.world.BodyStats;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.PowderSnowBlock;

/**
 * 从一具真实的身体上抄下规划要的身体快照,原版口径只此一处:尺寸取原版的姿势尺寸,迈步、起跳、重力、交互距离、
 * 摔落、水下移动、挖掘速度取同名属性,细雪托不托得住照原版看脚上的皮靴,冰霜行者看靴子的附魔,游戏模式、血量、饱食度、
 * 主背包照抄。
 *
 * <p>挖掘效率属性里手上那件自己带的修饰符(效率附魔)要扣掉:挑工具时每件按它自己的修饰符加回去,不扣就会把手上那件的
 * 附魔算到每一件头上。
 *
 * <p>只在世界所在的线程上调用;抄出来的快照可以交给搜索线程。
 */
public final class Snapshots {

    private Snapshots() {}

    public static BodySnapshot of(ServerPlayer body) {
        BodyStats stats = stats(body);
        BodySnapshot.Mining mining = new BodySnapshot.Mining(efficiencyBesidesHand(body),
                body.getAttributeValue(Attributes.BLOCK_BREAK_SPEED),
                body.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED),
                MobEffectUtil.hasDigSpeed(body) ? MobEffectUtil.getDigSpeedAmplification(body) : -1,
                body.hasEffect(MobEffects.DIG_SLOWDOWN) ? body.getEffect(MobEffects.DIG_SLOWDOWN).getAmplifier() : -1);
        List<ItemStack> inventory = List.copyOf(body.getInventory().items);
        return new BodySnapshot(stats, body.gameMode.getGameModeForPlayer(), body.getHealth(),
                body.getAttributeValue(Attributes.SAFE_FALL_DISTANCE),
                body.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER), body.getFoodData().getFoodLevel(),
                body.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY), inventory, mining);
    }

    /** 第 0 层要的那几项物理量:尺寸、迈步、起跳、重力、交互距离、细雪与冰霜行者。 */
    public static BodyStats stats(ServerPlayer body) {
        return new BodyStats(body.getDimensions(Pose.STANDING), body.getDimensions(Pose.CROUCHING),
                body.maxUpStep(), body.getAttributeValue(Attributes.JUMP_STRENGTH), body.getGravity(),
                body.blockInteractionRange(), PowderSnowBlock.canEntityWalkOnPowderSnow(body), frostWalker(body));
    }

    /** 脚上的靴子带冰霜行者。 */
    private static boolean frostWalker(ServerPlayer body) {
        Holder<Enchantment> frost = body.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                .getHolderOrThrow(Enchantments.FROST_WALKER);
        return EnchantmentHelper.getItemEnchantmentLevel(frost, body.getItemBySlot(EquipmentSlot.FEET)) > 0;
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
