package com.dwinovo.numen.core.combat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * 这把武器<b>打这个目标</b>能打多少——用来在自己的几把武器之间排序。
 *
 * <h2>为什么不能只读物品的攻击力</h2>
 * 攻击力是个与目标无关的常数,于是一把亡灵杀手 V 的铁剑打僵尸会输给一把光板钻石剑,
 * 而实际正相反。这一代(1.20.2)的锋利/亡灵/节肢加成走
 * {@link EnchantmentHelper#getDamageBonus} 按目标 {@link MobType} 取值——原版近战
 * 结算用的就是这条,这里不自己抄加成表,模组附魔只要挂进这套机制同样被认得。
 *
 * <h2>算了什么,没算什么</h2>
 * 算:武器自带的攻击力 + 附魔对该目标类别的加成。
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
        if (weapon.isEmpty()) {
            return 0.0;
        }
        double base = flatAttackDamage(weapon);
        if (base <= 0.0) {
            return 0.0;   // 不是近战武器:方块、食物、以及伤害在箭上的弓弩
        }
        // 1.20.2:附魔加成按目标的 MobType 档位取(亡灵/节肢/水生/无),非生物按无档。
        MobType type = target instanceof LivingEntity living ? living.getMobType() : MobType.UNDEFINED;
        return base + EnchantmentHelper.getDamageBonus(weapon, type);
    }

    /**
     * 物品给主手加的那一档攻击力。只认 {@code ADDITION}:乘法档改的是"已经加完的总额",
     * 脱离基础值单看没有意义,而这里比较的正是各把武器各自的那一档。
     */
    public static double flatAttackDamage(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0;
        }
        // 1.20.2:属性修饰符是 (Attribute → Modifier) 的 multimap,不是组件。
        double damage = 0.0;
        for (AttributeModifier modifier
                : stack.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE)) {
            if (modifier.getOperation() == AttributeModifier.Operation.ADDITION) {
                damage += modifier.getAmount();
            }
        }
        return damage;
    }
}
