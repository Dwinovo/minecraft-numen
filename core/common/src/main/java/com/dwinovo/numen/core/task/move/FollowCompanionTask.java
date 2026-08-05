package com.dwinovo.numen.core.task.move;

import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * 跟着主人——第一个<b>常驻</b>任务。
 *
 * <h2>它跟一次性任务差在哪</h2>
 * 只差一行:{@link #onTick} <b>永远不返终态</b>。同一个槽、同一套派发、同一个接口,
 * 「挖 64 块」干完腾位,而它一直占着,直到主人给她别的事做。
 *
 * <h2>跟到了就休眠,不是结束</h2>
 * 主人就在旁边时 {@link #canRun} 返 false:身体让给别人(她可以站着看你、可以被
 * 反射拿去吃东西),主人一走远它自己就醒过来。这跟原版 {@code Goal.canUse()} 是
 * 同一个道理——<b>休眠不是失败</b>,不发结果、不腾槽、不惊动模型。
 *
 * <p>所以"跟着我"是一个安静的状态:她不会因为你走两步就刷一堆事件,也不会因为
 * 你停下就宣布任务完成。
 *
 * <p>主人不在线 / 不在同一个维度时同样休眠——那不是失败,是她够不着。
 */
public final class FollowCompanionTask extends AbstractCompanionTask<FollowTaskRecord> {

    private static final double WALK_SPEED = 1.0;
    /** 比 {@code keepWithin} 多出这么远才重新起步,免得在临界距离上抖着走走停停。 */
    private static final double RESUME_MARGIN = 2.0;

    /** 上一刻是不是在走——用来只在真正起步/到位时重建导航。 */
    private boolean moving;

    public FollowCompanionTask(NumenPlayer player, FollowTaskRecord record) {
        super(player, record);
    }

    @Override
    public boolean canRun(NumenPlayer companion) {
        ServerPlayer owner = companion.resolveOwnerPlayer();
        if (owner == null || owner.level() != companion.level()) {
            return false;   // 不在线 / 不同维度:够不着,睡着等
        }
        double gap = companion.position().distanceTo(owner.position());
        // 迟滞:走出 keepWithin + margin 才起步,回到 keepWithin 之内才停——
        // 单阈值会让她在临界距离上一步一停地抖。
        return moving ? gap > r.keepWithin : gap > r.keepWithin + RESUME_MARGIN;
    }

    @Override
    protected void onStart() {
        moving = false;
    }

    @Override
    protected TaskState onTick() {
        ServerPlayer owner = player.resolveOwnerPlayer();
        if (owner == null) {
            return TaskState.RUNNING;   // canRun 已经挡住了,这里只是防御
        }
        if (nav == null) {
            // 目标每次重规划时现取,所以主人边走她也跟得上。
            nav = PlayerNav.toGoal(player, this::goal, WALK_SPEED, this::closeEnough);
        }
        moving = true;
        nav.tick();
        if (closeEnough()) {
            stopNav();
            moving = false;
        }
        // 永远不返终态 —— 这一行就是"常驻"的全部含义。
        return TaskState.RUNNING;
    }

    private NavGoal goal() {
        ServerPlayer owner = player.resolveOwnerPlayer();
        BlockPos at = owner == null ? player.blockPosition() : owner.blockPosition();
        return NavGoal.nearGround(at, r.keepWithin);
    }

    private boolean closeEnough() {
        ServerPlayer owner = player.resolveOwnerPlayer();
        return owner != null && player.position().distanceTo(owner.position()) <= r.keepWithin;
    }

    @Override
    protected String successMessage() {
        // 常驻任务走不到 SUCCESS;真被换掉时走的是 cancelledMessage。
        return "跟随结束";
    }
}
