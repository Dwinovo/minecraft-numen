package com.dwinovo.numen.combat;

import com.dwinovo.numen.api.combat.RangedWeapon;
import com.dwinovo.numen.api.combat.RangedWeapons;
import com.dwinovo.numen.api.entity.NumenPlayer;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 打这个目标,近战用哪把、远程用哪把。<b>两条路各挑一把,不排成一张总榜</b>——
 * 它们不可比:弓的攻击力是 0(伤害在箭上),放进同一张按攻击力排的榜里永远垫底,
 * 于是背着弓的她照样会去拿石剑贴脸。够不够得着由 {@link AttackPlan} 判,这里只负责
 * "每条路上最好的那把是哪把"。
 *
 * <p>远程那条路不认弓弩:登记表里的提供者({@link RangedWeapons})各自说背包里哪件是它的、此刻能不能射,弓和弩只是 Numen 自己的
 * 两个提供者。
 */
public final class Loadout {

    /** 一把武器和它所在的格子。{@code slot} 交给 {@link com.dwinovo.numen.api.entity.Hotbar#hold}。 */
    public record Pick(int slot, ItemStack stack, double score) {}

    /** 选中的远程武器:所在的格子、物品、认它的提供者、此刻是不是现成的。 */
    public record Ranged(int slot, ItemStack stack, RangedWeapon weapon, boolean ready) {}

    private final Pick melee;
    private final Ranged ranged;

    private Loadout(Pick melee, Ranged ranged) {
        this.melee = melee;
        this.ranged = ranged;
    }

    /** 近战最狠的那把;赤手空拳时 null。 */
    public Pick melee() {
        return melee;
    }

    /** 能立刻用的远程武器;没有、或有但没弹药时 null。 */
    public Ranged ranged() {
        return ranged;
    }

    public boolean hasMelee() {
        return melee != null;
    }

    public boolean hasRanged() {
        return ranged != null;
    }

    /** 选中的远程武器是现成的(已上弦的弩、上了膛的枪)——它不必再准备,可以立刻射。 */
    public boolean rangedCharged() {
        return ranged != null && ranged.ready();
    }

    /**
     * 扫一遍背包。近战按 {@link WeaponDamage#against} 对<b>这个目标</b>排序;远程问提供者:现成的 &gt; 要准备的,同一档里先登记的提供者
     * 那件排前面(弓在弩前,上弦的弩省掉整个拉弓周期、弓的蓄力比给弩上弦快),同一个提供者的几件先扫到的那件。
     */
    public static Loadout forTarget(NumenPlayer player, Entity target) {
        Inventory inventory = player.getInventory();
        List<RangedWeapon> providers = RangedWeapons.all();
        Pick bestMelee = null;
        Ranged bestRanged = null;
        int bestRank = Integer.MAX_VALUE;

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            RangedWeapon provider = RangedWeapons.of(stack).orElse(null);
            if (provider != null) {
                RangedWeapon.Readiness readiness = provider.readiness(player, stack);
                if (readiness != RangedWeapon.Readiness.EMPTY) {
                    boolean ready = readiness == RangedWeapon.Readiness.READY;
                    int rank = (ready ? 0 : providers.size()) + providers.indexOf(provider);
                    if (rank < bestRank) {
                        bestRank = rank;
                        bestRanged = new Ranged(slot, stack, provider, ready);
                    }
                }
                continue;
            }
            double score = WeaponDamage.against(player, target, stack);
            if (score > 0.0 && (bestMelee == null || score > bestMelee.score())) {
                bestMelee = new Pick(slot, stack, score);
            }
        }
        return new Loadout(bestMelee, bestRanged);
    }
}
