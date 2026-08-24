package com.dwinovo.numen.core.combat;

import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;

/**
 * 打这个目标,近战用哪把、远程用哪把。<b>两条路各挑一把,不排成一张总榜</b>——
 * 它们不可比:弓的攻击力是 0(伤害在箭上),放进同一张按攻击力排的榜里永远垫底,
 * 于是背着弓的她照样会去拿石剑贴脸。够不够得着由 {@link AttackPlan} 判,这里只负责
 * "每条路上最好的那把是哪把"。
 */
public final class Loadout {

    /** 一把武器和它所在的格子。{@code slot} 交给 {@code holdInHand}。 */
    public record Pick(int slot, ItemStack stack, double score) {}

    private final Pick melee;
    private final Pick ranged;
    private final boolean rangedCharged;

    private Loadout(Pick melee, Pick ranged, boolean rangedCharged) {
        this.melee = melee;
        this.ranged = ranged;
        this.rangedCharged = rangedCharged;
    }

    /** 近战最狠的那把;赤手空拳时 null。 */
    public Pick melee() {
        return melee;
    }

    /** 能立刻用的远程武器;没有弓弩、或有弓弩没箭时 null。 */
    public Pick ranged() {
        return ranged;
    }

    public boolean hasMelee() {
        return melee != null;
    }

    public boolean hasRanged() {
        return ranged != null;
    }

    /** 选中的远程武器是把已上弦的弩——它不必再拉一次,可以立刻射。 */
    public boolean rangedCharged() {
        return rangedCharged;
    }

    /**
     * 扫一遍背包。近战按 {@link WeaponDamage#against} 对<b>这个目标</b>排序;远程按
     * "已上弦的弩 &gt; 弓 &gt; 未上弦的弩"挑——上弦的弩省掉整个拉弓周期,
     * 而弓的蓄力比给弩上弦快。
     */
    public static Loadout forTarget(NumenPlayer player, Entity target) {
        Inventory inventory = player.getInventory();
        Pick bestMelee = null;
        Pick chargedCrossbow = null;
        Pick bow = null;
        Pick loadableCrossbow = null;

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.getItem() instanceof CrossbowItem) {
                if (CrossbowItem.isCharged(stack)) {
                    if (chargedCrossbow == null) {
                        chargedCrossbow = new Pick(slot, stack, 0.0);
                    }
                } else if (loadableCrossbow == null && !player.getProjectile(stack).isEmpty()) {
                    loadableCrossbow = new Pick(slot, stack, 0.0);
                }
                continue;
            }
            if (stack.getItem() instanceof BowItem) {
                if (bow == null && !player.getProjectile(stack).isEmpty()) {
                    bow = new Pick(slot, stack, 0.0);
                }
                continue;
            }
            double score = WeaponDamage.against(player, target, stack);
            if (score > 0.0 && (bestMelee == null || score > bestMelee.score())) {
                bestMelee = new Pick(slot, stack, score);
            }
        }

        Pick ranged = chargedCrossbow != null ? chargedCrossbow
                : bow != null ? bow
                : loadableCrossbow;
        return new Loadout(bestMelee, ranged, ranged != null && ranged == chargedCrossbow);
    }
}
