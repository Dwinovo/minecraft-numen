package com.dwinovo.numen.task.base;

import com.dwinovo.numen.FailureType;
import com.dwinovo.numen.nav.Feet;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.task.TaskRecord;
import com.dwinovo.numen.api.task.TaskResult;
import com.dwinovo.numen.api.task.TaskState;
import com.dwinovo.numen.task.move.Arrival;

import net.minecraft.core.BlockPos;

/**
 * "Act on something within reach" — the shape every press task shares ({@code numen.use.block}, {@code numen.use.entity},
 * {@code numen.use.hit}). Each tick, if {@link #reached()} it {@link #act() acts}; otherwise it first walks to where the
 * action can be done ({@link #approach}: the arrival target of that action, the one place that decides what within reach
 * means) and then acts. The walk is a child task ({@link #walkTo}): its account (what it dug, placed, asked) is this
 * task's, and a walk that can't get there ends this task with the walk's own reason.
 *
 * @param <R> the concrete {@link TaskRecord} subtype for this task.
 * @param <V> the value it hands the script on success (what {@code R} declares).
 */
public abstract class InReachTask<R extends TaskRecord<V>, V> extends AbstractCompanionTask<R, V> {

    /** 这一次走路开始时她站的那一格;没在走是 null。走完了还在原地、还是够不着,就不再走了。 */
    private BlockPos walkStart;

    protected InReachTask(NumenPlayer player, R record) {
        super(player, record);
    }

    /** 够不着时走到哪儿去:能动手的地方,就是动作自己的到达目标({@link Arrival#use}、{@link Arrival#to} 靠近一只实体)。 */
    protected abstract Arrival approach();

    /** Are we within reach to {@link #act()} this tick? */
    protected abstract boolean reached();

    /** Do the bounded thing at the target; return {@link TaskState#RUNNING} or a terminal state. */
    protected abstract TaskState act();

    @Override
    protected final TaskState onTick() {
        if (walkStart == null) {
            if (reached()) {
                return act();
            }
            // 身体还没站稳(刚落地、跳在半空)时判不了够不够得着:等它站稳,不拿半空里的一刻下"够不着"的结论
            if (!bodySettled()) {
                return TaskState.RUNNING;
            }
            walkStart = Feet.cell(player);
        }
        TaskResult walked = walkTo(approach());
        if (walked == null) {
            return TaskState.RUNNING;
        }
        BlockPos from = walkStart;
        walkStart = null;
        if (!walked.success()) {
            fail(walked.message(), lastFailure(), walked.hint());
            return TaskState.FAILED;
        }
        // 走到了:下一刻再判够不够得着。走完了还在原地、还是够不着(目标挪了、挡着视线),再走也是原地,如实收场
        if (Feet.cell(player).equals(from) && !reached()) {
            fail("I walked to where " + approach().words() + " is and it is still out of my reach from here",
                    FailureType.OUT_OF_REACH);
            return TaskState.FAILED;
        }
        return TaskState.RUNNING;
    }

    @Override
    protected abstract String successMessage();
}
