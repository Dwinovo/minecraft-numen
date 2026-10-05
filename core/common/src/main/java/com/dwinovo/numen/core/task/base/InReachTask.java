package com.dwinovo.numen.core.task.base;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.nav.Terrain;
import com.dwinovo.numen.core.task.move.GotoReminders;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskState;

import net.minecraft.core.BlockPos;

/**
 * "Act on something within reach of where she stands" — the shape every press task shares ({@code numen.use.block},
 * {@code numen.use.entity}). It never travels: each tick, if {@link #reached()} it {@link #act() acts}; otherwise, once the body
 * has settled, the call fails with how far the target is and the {@code numen.move.to} to copy. Getting there is the
 * script's step, not this task's.
 *
 * @param <R> the concrete {@link TaskRecord} subtype for this task.
 */
public abstract class InReachTask<R extends TaskRecord> extends AbstractCompanionTask<R> {

    protected InReachTask(NumenPlayer player, R record) {
        super(player, record);
    }

    /** 够不着时要点名的目标格(算距离、给能照抄的去处)。 */
    protected abstract BlockPos target();

    /** Are we within reach to {@link #act()} this tick? */
    protected abstract boolean reached();

    /**
     * 身体稳没稳——动作前置里"站得住"那一半的唯一判据。不等于"站在地上":
     * 游在水面(舀水、放船正是这个姿势)和坐在载具里都没有 onGround,原版对
     * 交互也从不要求脚踏实地;这道门挡的只是坠落中途的按键。
     */
    protected final boolean bodySettled() {
        return player.onGround() || player.isInWater() || player.isPassenger();
    }

    /** Do the bounded thing at the target; return {@link TaskState#RUNNING} or a terminal state. */
    protected abstract TaskState act();

    @Override
    protected final TaskState onTick() {
        if (reached()) return act();
        // 身体还没站稳(刚落地、跳在半空)时判不了够不够得着:等它站稳,不拿半空里的一刻下"够不着"的结论
        if (!bodySettled()) {
            return TaskState.RUNNING;
        }
        BlockPos t = target();
        double dist = Math.sqrt(player.distanceToSqr(t.getX() + 0.5, t.getY() + 0.5, t.getZ() + 0.5));
        // 下一步照抄:有可点轮廓的方块走到看得见它一面的地方,空气、流体与实体所在的格走到附近
        String next = GotoReminders.call(t, Terrain.of(player).clickable(t) ? "arrive = \"use\"" : "arrive = \"near\"");
        fail("target " + t.getX() + "," + t.getY() + "," + t.getZ() + " is " + String.format("%.1f", dist)
                + " blocks away or out of sight — out of working reach, and this action does not travel. " + next
                + " first, then call this again.", FailureType.OUT_OF_REACH);
        return TaskState.FAILED;
    }

    @Override
    protected abstract String successMessage();
}
