package com.dwinovo.numen.core.task.move;

import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

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
 * <h2>够不着也是休眠</h2>
 * 常驻任务没有"失败"这个终点,所以「够不着」只能表达成休眠 + 退避重试:主人飞起来、
 * 隔着断崖、换了维度,她就先放手,等条件回来自己醒。
 *
 * <p>这一条以前是缺的,后果很重:{@code nav.tick()} 的返回值被整个丢掉,而
 * {@link PlayerNav} 的 FAILED 是<b>终局闩</b>(一经裁定即稳定持续)。于是主人一飞起来
 * 导航判 NO_PATH,此后每一刻都返 FAILED、没人重建,她就<b>永久定在原地</b>——主人落回
 * 地面也不会自愈。而 {@code task_status} 照样说"执行中"(常驻任务按定义永远 RUNNING),
 * 主人完全看不出她卡住了。
 */
public final class FollowCompanionTask extends AbstractCompanionTask<FollowTaskRecord> {

    private static final double WALK_SPEED = 1.0;
    /** 比 {@code keepWithin} 多出这么远才重新起步,免得在临界距离上抖着走走停停。 */
    private static final double RESUME_MARGIN = 2.0;

    /** 够不着之后第一次重试等多久(刻)。 */
    private static final int RETRY_BASE_TICKS = 20;
    /** 连续够不着时退避上限(刻)。一次失败的搜索会烧光整个预算,主人飞五分钟不该重算三百次。 */
    private static final int RETRY_MAX_TICKS = 200;
    /** 主人悬空时,往下找地面最多找几格。 */
    private static final int GROUND_SCAN = 64;

    /** 上一刻是不是在走——用来只在真正起步/到位时重建导航。 */
    private boolean moving;

    /** 够不着时休眠到这个游戏刻;0 = 没在退避。 */
    private long retryAtGameTime;
    /** 连续够不着的次数,只用来算退避时长。 */
    private int unreachableStreak;

    public FollowCompanionTask(NumenPlayer player, FollowTaskRecord record) {
        super(player, record);
    }

    @Override
    public boolean canRun(NumenPlayer companion) {
        ServerPlayer owner = companion.resolveOwnerPlayer();
        if (owner == null || owner.level() != companion.level()) {
            return false;   // 不在线 / 不同维度:够不着,睡着等
        }
        if (companion.level().getGameTime() < retryAtGameTime) {
            return false;   // 刚判过够不着,退避中——身体让给别人,别空转烧搜索预算
        }
        double gap = companion.position().distanceTo(owner.position());
        // 迟滞:走出 keepWithin + margin 才起步,回到 keepWithin 之内才停——
        // 单阈值会让她在临界距离上一步一停地抖。
        return moving ? gap > r.keepWithin : gap > r.keepWithin + RESUME_MARGIN;
    }

    @Override
    protected void onStart() {
        moving = false;
        retryAtGameTime = 0;
        unreachableStreak = 0;
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
        switch (nav.tick()) {
            case RUNNING -> { }
            case ARRIVED -> {
                stopNav();
                moving = false;
                unreachableStreak = 0;
            }
            case FAILED -> backOff();
        }
        if (closeEnough()) {
            stopNav();
            moving = false;
            unreachableStreak = 0;
        }
        // 永远不返终态 —— 这一行就是"常驻"的全部含义。
        return TaskState.RUNNING;
    }

    /**
     * 现在够不着:拆掉导航(FAILED 是终局闩,不拆就永远失败)、放手、退避一会儿再试。
     * 退避按连续失败次数翻倍到上限——主人在天上飞的那几分钟里,每秒重算一次全预算
     * 搜索是纯浪费,而她真落地时最多晚 10 秒就跟上。
     */
    private void backOff() {
        stopNav();
        moving = false;
        unreachableStreak++;
        int wait = Math.min(RETRY_MAX_TICKS, RETRY_BASE_TICKS << Math.min(unreachableStreak - 1, 4));
        retryAtGameTime = player.level().getGameTime() + wait;
    }

    private NavGoal goal() {
        ServerPlayer owner = player.resolveOwnerPlayer();
        BlockPos at = owner == null ? player.blockPosition() : anchor(owner);
        return NavGoal.nearGround(at, r.keepWithin);
    }

    /**
     * 主人悬空(飞行/跳跃/坐船)时跟到他<b>脚下的地面</b>。
     *
     * <p>{@link NavGoal#nearGround} 只认 ±1 格高差,直接追主人所在的那一格,人在半空就
     * 永远够不着——这正是「飞起来她就不跟了」的来源。往下找到第一块能站的地面,
     * 「就近跟随」在他头顶下方成立。
     *
     * <p>找不到(悬在虚空/海面上)就返回扫到的最低点:那一格同样够不着,于是走退避,
     * 而不是假装找到了。
     */
    private BlockPos anchor(ServerPlayer owner) {
        BlockPos at = owner.blockPosition();
        if (owner.onGround()) {
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
        ServerPlayer owner = player.resolveOwnerPlayer();
        return owner != null && player.position().distanceTo(owner.position()) <= r.keepWithin;
    }

    @Override
    protected String successMessage() {
        // 常驻任务走不到 SUCCESS;真被换掉时走的是 cancelledMessage。
        return "跟随结束";
    }
}
