package com.dwinovo.numen.core.task.move;

import com.dwinovo.numen.core.nav.Terrain;
import com.dwinovo.numen.core.nav.Trip;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.core.FailureType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * 跟着走——第一个<b>常驻</b>任务。默认跟主人,点名了就跟那一只。
 *
 * <h2>它跟一次性任务差在哪</h2>
 * 只差一行:{@link #onTick} <b>永远不返终态</b>。同一个槽、同一套派发、同一个接口,
 * 「挖 64 块」干完腾位,而它一直占着,直到主人给她别的事做。
 *
 * <h2>跟到了就休眠,不是结束</h2>
 * 跟到了(这一趟的目标自己说到了,{@link Goals#near} 落脚点附近 {@code keepWithin} 格)之后
 * {@link #canRun} 返 false:身体让给别人(她可以站着看你、可以被反射拿去吃东西),主人一走远
 * 它自己就醒过来。这跟原版 {@code Goal.canUse()} 是同一个道理——<b>休眠不是失败</b>,不发结果、
 * 不腾槽、不惊动模型。到没到只看目标,这里不另拿距离判"差不多到了";离主人多远只决定要不要起步。
 *
 * <h2>够不着就报出去</h2>
 * 跟着走从不动世界(路线规格 alter=NONE,没有开关),于是"没有路"多半不是暂时的:隔着断崖、
 * 在屋里、差几格高——退避多少次都一样。那就以失败收场,把原因连同候选路线清单交给
 * 模型,它决定先 goto 一条开路、换个办法、或者告诉主人。一个明确的失败原因不能攥在手里
 * 站着空算。主人飞在半空时跟的是他脚下能站的地方({@link #anchor}),一般够得着;真够不着
 * 也照样报。
 *
 * <h2>目标没了,主人和别人不一样</h2>
 * <b>主人下线是暂时的</b>——他会回来,所以休眠等着,这也是常驻该有的样子。而点名跟的
 * 那只羊死了、或者走出加载范围被卸载了,再等也不会回来:那时收尾报给模型,让它决定下
 * 一步。一套逻辑通吃的话,要么她对着一只死羊站到天荒地老,要么主人一下线任务就没了。
 *
 * <p><b>{@code nav.tick()} 的返回值一个都不能丢</b>:{@link Trip} 收场之后就稳定地是那个结局,
 * 不接住就是她永久定在原地而 {@code task status} 照说"执行中"——主人完全看不出她卡住了。
 * 这里接住的方式就是把它变成任务的结果。
 *
 * <p><b>目标跟着挪</b>:他走出上次定下的落脚点两格外,就把新目标交给在走的这一趟({@link Trip#retarget}),
 * 在走的路还算数就照走,不算数才重搜——挪多远才值得换在这里定。
 */
public final class FollowCompanionTask extends AbstractCompanionTask<FollowTaskRecord> {

    /** 跟到之后,他比 {@code keepWithin} 多走出这么远才重新起步,免得在临界距离上抖着走走停停。 */
    private static final double RESUME_MARGIN = 2.0;

    /** 上一刻是不是在走——用来只在真正起步/到位时重建导航。 */
    private boolean moving;
    /** 在走的这一趟朝着的落脚点;他挪出它 {@link #RETARGET_DISTANCE} 格外才换目标。 */
    private BlockPos heading;

    /** 他挪出上次的落脚点这么远(格)才把新目标交给在走的这一趟。 */
    private static final double RETARGET_DISTANCE = 2.0;

    public FollowCompanionTask(NumenPlayer player, FollowTaskRecord record) {
        super(player, record);
    }

    @Override
    public boolean canRun(NumenPlayer companion) {
        Entity target = target(companion);
        if (target == null) {
            // 点名的目标没了:要放它跑一刻才收得了尾(canRun 返 false 的任务不会 tick,
            // 也就永远报不出去)。跟的是主人就单纯睡着等他回来。
            return r.target != null;
        }
        if (moving) {
            // 在走的这一趟到没到,只看它的目标
            return true;
        }
        // 迟滞:走出 keepWithin + margin 才起步——一跟到就起步会让她在临界距离上一步一停地抖
        return companion.position().distanceTo(target.position()) > r.keepWithin + RESUME_MARGIN;
    }

    @Override
    protected void onStart() {
        moving = false;
    }

    @Override
    protected TaskState onTick() {
        Entity target = target(player);
        if (target == null) {
            if (r.target == null) {
                return TaskState.RUNNING;   // 主人下线:canRun 已经挡住了,这里只是防御
            }
            stopNav();
            fail("the entity you were following is gone (killed, or it left the loaded area)",
                    FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        BlockPos anchor = anchor(target);
        if (nav == null) {
            // 只走不改;跟不上的时候回执里要有候选路线的清单
            heading = anchor;
            nav = Trip.to(player, goal(anchor), TERRAIN, anchor).probing();
        } else if (anchor.distSqr(heading) > RETARGET_DISTANCE * RETARGET_DISTANCE) {
            heading = anchor;
            nav.retarget(goal(anchor), anchor);
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
        // 不返终态就是"常驻"的全部含义;只有够不着和目标没了才收场。
        return TaskState.RUNNING;
    }

    /**
     * 跟着谁。没点名就是主人;点名了就按 UUID 现查——每次都查,因为它随时可能死掉或者
     * 走出加载范围,而那两件事对我们是同一个答案:不在了。
     *
     * <p>不同维度天然落进 null:{@code ServerLevel.getEntity} 只认自己这一层。
     */
    private Entity target(NumenPlayer companion) {
        if (r.target == null) {
            var owner = companion.resolveOwnerPlayer();
            return owner == null || owner.level() != companion.level() ? null : owner;
        }
        Entity e = ((ServerLevel) companion.level()).getEntity(r.target);
        return e == null || e.isRemoved() ? null : e;
    }

    /** 跟到落脚点 {@code anchor} 附近 {@code keepWithin} 格内。 */
    private Goal goal(BlockPos anchor) {
        return Goals.near(anchor, r.keepWithin);
    }

    /** 跟随的路线规格:只走不改,没有开关。 */
    private static final RouteSpec TERRAIN = RouteSpec.defaults();

    /**
     * 跟到哪一格:他所在那一列里身体待得住的地方({@link Terrain#settle})——他站着就是他脚下,悬空(飞行、跳跃、本来就会飞)
     * 时是他下方的地面。那一列都待不住(悬在虚空上)就还是他那一格:那里够不着,于是照实报,而不是假装找到了。
     */
    private BlockPos anchor(Entity target) {
        return Terrain.of(player).settle(target.blockPosition());
    }

    @Override
    protected String successMessage() {
        // 常驻任务走不到 SUCCESS;真被换掉时走的是 cancelledMessage。
        return "跟随结束";
    }
}
