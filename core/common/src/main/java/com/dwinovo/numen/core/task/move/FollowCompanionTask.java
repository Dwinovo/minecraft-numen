package com.dwinovo.numen.core.task.move;

import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.core.FailureType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * 跟着走——第一个<b>常驻</b>任务。默认跟主人,点名了就跟那一只。
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
 * <h2>够不着就报出去</h2>
 * 跟着走默认不动世界(见 {@code TerrainPermit}),于是"没有路"多半不是暂时的:隔着断崖、
 * 在屋里、差几格高——退避多少次都一样。那就以失败收场,把原因连同要动的方块清单交给
 * 模型,它决定带 {@code may_alter_terrain} 重发、换个办法、或者告诉主人。一个明确的失败
 * 原因不能攥在手里站着空算。主人飞在半空时跟的是他脚下的地面({@link #anchor}),
 * 一般够得着;真够不着也照样报。
 *
 * <h2>目标没了,主人和别人不一样</h2>
 * <b>主人下线是暂时的</b>——他会回来,所以休眠等着,这也是常驻该有的样子。而点名跟的
 * 那只羊死了、或者走出加载范围被卸载了,再等也不会回来:那时收尾报给模型,让它决定下
 * 一步。一套逻辑通吃的话,要么她对着一只死羊站到天荒地老,要么主人一下线任务就没了。
 *
 * <p><b>{@code nav.tick()} 的返回值一个都不能丢</b>:{@link PlayerNav} 的 FAILED 是
 * <b>终局闩</b>(一经裁定即稳定持续),不接住就是她永久定在原地而 {@code task_status}
 * 照说"执行中"——主人完全看不出她卡住了。这里接住的方式就是把它变成任务的结果。
 */
public final class FollowCompanionTask extends AbstractCompanionTask<FollowTaskRecord> {

    private static final double WALK_SPEED = 1.0;
    /** 比 {@code keepWithin} 多出这么远才重新起步,免得在临界距离上抖着走走停停。 */
    private static final double RESUME_MARGIN = 2.0;

    /** 主人悬空时,往下找地面最多找几格。 */
    private static final int GROUND_SCAN = 64;

    /** 上一刻是不是在走——用来只在真正起步/到位时重建导航。 */
    private boolean moving;

    public FollowCompanionTask(NumenPlayer player, FollowTaskRecord record) {
        super(player, record);
    }

    @Override
    public boolean canRun(NumenPlayer companion) {
        Entity target = target(companion);
        if (target == null) {
            // 点名的目标没了:要放它跑一刻才收得了尾(canRun 返 false 的任务不会 tick,
            // 也就永远报不出去)。跟的是主人就单纯睡着等他回来。
            return r.entityId != null;
        }
        double gap = companion.position().distanceTo(target.position());
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
        Entity target = target(player);
        if (target == null) {
            if (r.entityId == null) {
                return TaskState.RUNNING;   // 主人下线:canRun 已经挡住了,这里只是防御
            }
            stopNav();
            fail("the entity you were following is gone (killed, or it left the loaded area)",
                    FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        if (nav == null) {
            // 目标每次重规划时现取,所以主人边走她也跟得上。地形许可按记录来,默认只走不改;
            // 探针开着——跟不上的时候回执里要有"会动哪些方块"的清单
            nav = PlayerNav.toGoal(player, this::goal, WALK_SPEED, this::closeEnough,
                    r.mayAlterTerrain ? PlayerNav.ContextProvider.TERRAFORM
                            : PlayerNav.ContextProvider.DEFAULT).withTerrainProbe();
        }
        moving = true;
        switch (nav.tick()) {
            case RUNNING -> { }
            case ARRIVED -> {
                stopNav();
                moving = false;
            }
            case FAILED -> {
                // 够不着就是这件活的结果:原因与清单交给模型,别攥着站在原地空算
                String why = nav.failReason();
                FailureType type = nav.failType();
                stopNav();
                fail("can't keep up: " + why, type);
                return TaskState.FAILED;
            }
        }
        if (closeEnough()) {
            stopNav();
            moving = false;
        }
        // 不返终态就是"常驻"的全部含义;只有够不着和目标没了才收场。
        return TaskState.RUNNING;
    }

    /**
     * 跟着谁。没点名就是主人;点名了就按 id 现查——每次都查,因为它随时可能死掉或者
     * 走出加载范围,而那两件事对我们是同一个答案:不在了。
     *
     * <p>不同维度天然落进 null:{@code ServerLevel.getEntity} 只认自己这一层。
     */
    private Entity target(NumenPlayer companion) {
        if (r.entityId == null) {
            var owner = companion.resolveOwnerPlayer();
            return owner == null || owner.level() != companion.level() ? null : owner;
        }
        Entity e = ((ServerLevel) companion.level()).getEntity(r.entityId);
        if (e == null || e.isRemoved() || e == companion) {
            return null;
        }
        // id 对上还不够:重启之后同一个号可能发给了别的东西。
        return r.targetUuid != null && !r.targetUuid.equals(e.getUUID()) ? null : e;
    }

    private NavGoal goal() {
        Entity target = target(player);
        BlockPos at = target == null ? player.blockPosition() : anchor(target);
        return NavGoal.nearGround(at, r.keepWithin);
    }

    /**
     * 目标悬空(飞行/跳跃/坐船/本来就会飞)时跟到它<b>脚下的地面</b>。
     *
     * <p>{@link NavGoal#nearGround} 只认 ±1 格高差,直接追主人所在的那一格,人在半空就
     * 永远够不着——这正是「飞起来她就不跟了」的来源。往下找到第一块能站的地面,
     * 「就近跟随」在他头顶下方成立。
     *
     * <p>找不到(悬在虚空/海面上)就返回扫到的最低点:那一格同样够不着,于是照实报,
     * 而不是假装找到了。
     */
    private BlockPos anchor(Entity target) {
        BlockPos at = target.blockPosition();
        if (target.onGround()) {
            return at;
        }
        Level level = player.level();
        BlockPos p = at;
        for (int i = 0; i < GROUND_SCAN; i++) {
            if (MovementHelper.canWalkOn(level, p.below())) {
                return p;                        // 站得住,就是这儿
            }
            if (!MovementHelper.canWalkThrough(level, p.below())) {
                return p;                        // 下面是穿不过又站不住的东西,不再往下
            }
            p = p.below();
        }
        return p;
    }

    private boolean closeEnough() {
        Entity target = target(player);
        return target != null && player.position().distanceTo(target.position()) <= r.keepWithin;
    }

    @Override
    protected String successMessage() {
        // 常驻任务走不到 SUCCESS;真被换掉时走的是 cancelledMessage。
        return "跟随结束";
    }
}
