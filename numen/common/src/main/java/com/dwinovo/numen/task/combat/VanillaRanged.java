package com.dwinovo.numen.task.combat;

import com.dwinovo.numen.api.combat.Flight;
import com.dwinovo.numen.api.combat.Handling;
import com.dwinovo.numen.api.combat.RangedWeapon;
import com.dwinovo.numen.api.entity.NumenPlayer;

import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;

/**
 * 原版的弓与弩:Numen 自己的两个远程武器提供者,和模组的枪走同一张登记表、同一扇门,原版没有任何捷径。弓比弩先登记,所以同档里弓排前面。
 *
 * <p>弹道常数、站位的环与射程、拉弓上弦的状态机({@link RangedShot})都在这里与它旁边;攻击任务只看 {@link RangedWeapon} 说的。
 */
public final class VanillaRanged implements RangedWeapon {

    // 弹道常数:箭的物理与两种发射器的初速。
    private static final double ARROW_GRAVITY = 0.05;
    private static final double ARROW_DRAG = 0.99;
    private static final double ARROW_HITBOX_RADIUS = 0.5;
    static final double BOW_FULL_SPEED = 3.0;
    static final double CROSSBOW_SPEED = 3.15;

    /**
     * 站位的环。内沿八格,不是五格:<b>拉满一张弓要二十刻</b>,这二十刻里僵尸能走四格半。五格的话她刚拉到一半人就贴脸了,只能中断重来——
     * 实测她在 0.6~2.9 格里挣扎,最后被爬行者炸死。内沿要装得下"拉一次弓的工夫对方能走多远"。
     * 外沿十二格<b>不是射程上限</b>:三十二格的话她能站在天边,而箭有下坠、目标会走,那么远基本射不中;十二格是"稳稳能中、又够得开"的量级。
     * 射程三十二格是算得出弹道的上限。
     */
    public static final Handling HANDLING = new Handling(8.0, 12.0, 32.0);

    public static final VanillaRanged BOW = new VanillaRanged(false);
    public static final VanillaRanged CROSSBOW = new VanillaRanged(true);

    private final boolean crossbow;

    private VanillaRanged(boolean crossbow) {
        this.crossbow = crossbow;
    }

    /** 箭按这个初速飞的弹道;弓的出膛速度叠上射手的,弩不叠。 */
    static Flight.Arc arc(double velocity, boolean crossbow) {
        return new Flight.Arc(velocity, ARROW_GRAVITY, ARROW_DRAG, ARROW_HITBOX_RADIUS, !crossbow);
    }

    @Override
    public String name() {
        return crossbow ? "crossbow" : "bow";
    }

    @Override
    public boolean handles(ItemStack stack) {
        return crossbow ? stack.getItem() instanceof CrossbowItem : stack.getItem() instanceof BowItem;
    }

    @Override
    public Readiness readiness(NumenPlayer body, ItemStack stack) {
        if (crossbow && CrossbowItem.isCharged(stack)) {
            return Readiness.READY;
        }
        return body.getProjectile(stack).isEmpty() ? Readiness.EMPTY : Readiness.LOADABLE;
    }

    @Override
    public Flight flight(NumenPlayer body, ItemStack stack) {
        return arc(crossbow ? CROSSBOW_SPEED : BOW_FULL_SPEED * RangedShot.bowPowerForTicks(15), crossbow);
    }

    @Override
    public Handling handling(NumenPlayer body, ItemStack stack) {
        return HANDLING;
    }

    @Override
    public Shot begin(NumenPlayer body, ItemStack stack) {
        return new RangedShot(body, crossbow);
    }

    @Override
    public String misfireText() {
        return "the bow or crossbow did not launch an arrow";
    }
}
