package com.dwinovo.numen.api.task.chain;

import com.dwinovo.numen.api.entity.Controls;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.task.Task;
import com.dwinovo.numen.api.task.TaskState;
import com.dwinovo.numen.api.task.reflex.Reflex;
import net.minecraft.tags.FluidTags;

/**
 * 闲着时浮在水面上——原版生物的漂浮行为({@code FloatGoal}/{@code Swim}),身体的基本面。假玩家没有客户端替它按跳,
 * 闲着在水里只会慢慢沉下去。
 *
 * <p>条件照原版 {@code Swim}:在水里、水没过身体的高度超过跳跃阈值({@code getFluidJumpThreshold})时按住跳。另加
 * 车万女仆 {@code MaidSwimJumpTask} 的一条:整个人已经在水下({@code isUnderWater},眼睛没进水里才算浮着)
 * 时不被动上浮——刚潜到水底某一格、下一个工具调用还没来,不能自己浮上去。浮不浮上来是换气本能的事,那只管憋不住气。
 *
 * <p>它是空闲姿态({@link com.dwinovo.numen.api.task.Poses}):有任务在做、有反射接管时轮不到它;寻路由任务驱动,不会和它
 * 抢键。不发任何事件:浮着是身体的常态,不是值得说的事。
 */
public final class FloatChain implements Task, Reflex {

    @Override
    public boolean canRun(NumenPlayer companion) {
        return companion.isInWater() && !companion.isUnderWater()
                && companion.getFluidHeight(FluidTags.WATER) > companion.getFluidJumpThreshold();
    }

    @Override
    public TaskState tick(NumenPlayer companion) {
        companion.controls().press(Controls.Key.JUMP);
        return TaskState.RUNNING;
    }

    @Override
    public void stop(NumenPlayer companion, StopReason why) {}

    @Override
    public String name() {
        return "float";
    }

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "闲着时浮在水面上不往下沉;整个人已经在水下时不会自己浮上来";
    }
}
