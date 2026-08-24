package com.dwinovo.numen.core.task.combat;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.act.Ballistics;
import com.dwinovo.numen.core.combat.Loadout;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;

/**
 * 射一箭的完整生命周期:拉弓/上弦 → 对准 → 松手 → 确认射出去了。
 *
 * <h2>为什么要一个状态机</h2>
 * 原版的远程武器不是"调一个方法就发射"。弓要按住蓄力,力度随按住的刻数长;弩要先花几十刻
 * 上弦,上完弦才能扣发。中途换手、被打断、箭用光,都会让这套动作停在半路。所以必须有个东西
 * 记着"我现在拉到哪一步了",而不是每 tick 从头判断。
 *
 * <h2>哑火要认出来</h2>
 * 松了手不等于箭飞出去了。{@link State#SETTLING} 是给原版几刻时间去真正生成弹射物;
 * 过了还没动静就记一次哑火。不认这一态的话,一把没箭的弩会让她原地拉一整天。
 */
final class RangedShot {

    /** 弓拉到这个刻数就够满力了(再拉不会更强)。 */
    private static final int BOW_RELEASE_TICKS = 15;
    /** 弓的蓄力上限;到了还没对准也放出去,免得无限拉着。 */
    private static final int BOW_MAX_DRAW_TICKS = 40;
    /** 上弦超过这个刻数就当它上不了(没箭、被换手)。 */
    private static final int CROSSBOW_LOAD_TIMEOUT = 80;
    /** 松手之后等几刻确认弹射物真的生成了。 */
    private static final int SETTLE_TICKS = 4;
    /** 视线与弹道夹角小于它才算对准。 */
    static final double AIM_THRESHOLD_DEGREES = 1.5;

    private enum State { USING, READY_TO_FIRE, SETTLING, DONE, MISFIRE }

    private final NumenPlayer player;
    private final boolean crossbow;
    private State state = State.USING;
    private int held;
    private int settle;
    private boolean fired;

    RangedShot(NumenPlayer player, boolean crossbow) {
        this.player = player;
        this.crossbow = crossbow;
        player.gameMode.useItem(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
    }

    /** 这一发的武器还是不是手上这把——换了就得作废重来。 */
    static boolean stillHolding(boolean crossbow, ItemStack stack) {
        return crossbow ? stack.getItem() instanceof CrossbowItem : stack.getItem() instanceof BowItem;
    }

    /** 拉弓的刻数换算成箭速倍率(原版公式)。 */
    static double bowPowerForTicks(int ticks) {
        double draw = ticks / 20.0;
        double power = (draw * draw + draw * 2.0) / 3.0;
        return Math.min(1.0, Math.max(0.0, power));
    }

    /** 对准且拉够了才松手。 */
    static boolean canRelease(double angleDegrees, int heldTicks, int releaseTicks) {
        return angleDegrees <= AIM_THRESHOLD_DEGREES && heldTicks >= releaseTicks;
    }

    /** 这一发用的箭速——弓按已拉的刻数算,弩恒定。 */
    double projectileVelocity(double bowFullSpeed, double crossbowSpeed) {
        if (crossbow) {
            return crossbowSpeed;
        }
        return bowFullSpeed * bowPowerForTicks(Math.max(BOW_RELEASE_TICKS, held + 1));
    }

    /** @return true 表示这一发已经结束(射出去了或哑了) */
    boolean tick(Ballistics.Aim aim, Entity target) {
        switch (state) {
            case USING -> tickUsing(aim, target);
            case READY_TO_FIRE -> tickReadyToFire(aim, target);
            case SETTLING -> tickSettling();
            default -> { return true; }
        }
        return state == State.DONE || state == State.MISFIRE;
    }

    boolean fired() {
        return fired;
    }

    /**
     * 这一刻该不该转向瞄准。
     *
     * <p><b>拉弓的过程中不用瞄</b> —— 原版的箭朝哪飞只看<b>松手那一刻</b>的视线。而每刻转向
     * 会把脚也带偏(移动是按朝向投影的),于是她一路走进目标脸上。只在快松手时转过去,
     * 中间十几刻的走位就干净了 —— 挥刀早就是这么做的。
     */
    boolean aboutToRelease() {
        return crossbow ? state == State.READY_TO_FIRE : held >= BOW_RELEASE_TICKS - 1;
    }

    void abort() {
        if (player.isUsingItem()) {
            player.stopUsingItem();
        }
    }

    private void tickUsing(Ballistics.Aim aim, Entity target) {
        ItemStack weapon = player.getMainHandItem();
        if (crossbow) {
            if (CrossbowItem.isCharged(weapon)) {
                state = State.READY_TO_FIRE;
                tickReadyToFire(aim, target);
            } else if (!player.isUsingItem()) {
                state = State.SETTLING;
            } else if (++held >= CrossbowItem.getChargeDuration(weapon)) {   // 1.20.2:时长只看弩(快速装填在里面)
                player.releaseUsingItem();
                state = State.READY_TO_FIRE;
                settle = 0;
            } else if (held >= CROSSBOW_LOAD_TIMEOUT) {
                player.stopUsingItem();
                state = State.SETTLING;
            }
            return;
        }
        if (!player.isUsingItem()) {
            state = State.SETTLING;
            return;
        }
        held++;
        double angle = Ballistics.angleDegrees(player.getViewVector(1.0f), aim.direction());
        if (canRelease(angle, held, BOW_RELEASE_TICKS) || held >= BOW_MAX_DRAW_TICKS) {
            player.releaseUsingItem();
            markFired(aim, target);
        }
    }

    private void tickReadyToFire(Ballistics.Aim aim, Entity target) {
        ItemStack weapon = player.getMainHandItem();
        if (!CrossbowItem.isCharged(weapon)) {
            if (++settle >= SETTLE_TICKS) {
                state = State.MISFIRE;
            }
            return;
        }
        if (Ballistics.angleDegrees(player.getViewVector(1.0f), aim.direction()) <= AIM_THRESHOLD_DEGREES) {
            player.gameMode.useItem(player, player.level(), weapon, InteractionHand.MAIN_HAND);
            markFired(aim, target);
        }
    }

    private void tickSettling() {
        if (++settle >= SETTLE_TICKS) {
            state = State.MISFIRE;
        }
    }

    private void markFired(Ballistics.Aim aim, Entity target) {
        fired = true;
        if (target != null) {
            Constants.LOG.info("[numen-attack] 射出 target={} weapon={} held={} dist={} eta={}",
                    target.getId(), crossbow ? "crossbow" : "bow", held,
                    String.format("%.1f", player.distanceTo(target)), Math.ceil(aim.travelTicks()));
        }
        state = State.DONE;
    }

    /** 这一发用的是不是弩(决定箭速与哑火判定)。 */
    static boolean isCrossbow(Loadout.Pick pick) {
        return pick.stack().getItem() instanceof CrossbowItem;
    }
}
