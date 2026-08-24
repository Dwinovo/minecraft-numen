package com.dwinovo.numen.core.combat;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * 这把武器<b>打这个目标</b>能打多少——用来在自己的几把武器之间排序。
 *
 * <h2>为什么不能只读物品的攻击力</h2>
 * 攻击力是个与目标无关的常数,于是一把亡灵杀手 V 的铁剑打僵尸会输给一把光板钻石剑,
 * 而实际正相反。附魔从 1.21 起是数据驱动的,所以这里不自己写锋利/亡灵/节肢的加成表
 * ——那种表既要跟着原版数值走,又对模组附魔一律失明。判据交给
 * {@link EnchantmentHelper#modifyDamage},它认得所有已注册的附魔。
 *
 * <h2>算了什么,没算什么</h2>
 * 算:武器自带的攻击力 + 附魔对该目标的加成 + 物品自身的额外伤害
 * ({@link Item#getAttackDamageBonus},重锤的下坠加伤走这里)。
 *
 * <p>没算:力量效果、暴击、目标的护甲与抗性。<b>不是漏了,是它们不改变排序</b>——
 * 力量是加同一个常数,护甲与抗性是乘同一个系数,暴击是乘 1.5,三者对每把候选武器
 * 施加的都是同一个单调变换。要的既然只是"哪把最狠",算它们纯属白算。
 */
public final class WeaponDamage {

    private WeaponDamage() {}

    /**
     * 排序分。<b>只用于比较</b>,不是这一击的真实伤害(见类注释)。
     *
     * @return 该武器打该目标的相对强弱;弓弩这类攻击力为零的返回 0,它们不走近战这条路
     */
    public static double against(Player attacker, Entity target, ItemStack weapon) {
        if (weapon.isEmpty() || !(attacker.level() instanceof ServerLevel level)) {
            return 0.0;
        }
        double base = flatAttackDamage(weapon);
        if (base <= 0.0) {
            return 0.0;   // 不是近战武器:方块、食物、以及伤害在箭上的弓弩
        }
        DamageSource source = level.damageSources().playerAttack(attacker);
        double enchanted = EnchantmentHelper.modifyDamage(level, weapon, target, source, (float) base);
        return enchanted + weapon.getItem().getAttackDamageBonus(target, (float) enchanted, source);
    }

    /**
     * 物品给主手加的那一档攻击力。只认 {@code ADD_VALUE}:乘法档改的是"已经加完的总额",
     * 脱离基础值单看没有意义,而这里比较的正是各把武器各自的那一档。
     */
    public static double flatAttackDamage(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0;
        }
        ItemAttributeModifiers modifiers =
                stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        double damage = 0.0;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (entry.slot().test(EquipmentSlot.MAINHAND)
                    && entry.attribute().is(Attributes.ATTACK_DAMAGE)
                    && entry.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) {
                damage += entry.modifier().amount();
            }
        }
        return damage;
    }
}
