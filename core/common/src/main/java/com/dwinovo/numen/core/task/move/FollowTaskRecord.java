package com.dwinovo.numen.core.task.move;

import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.task.TaskRecord;

import java.util.UUID;

/**
 * 「跟着」——她当前在做的事就是跟着某个东西走。默认是主人,给了 {@link #target} 就是跟着那一只。
 *
 * <p>没有"干完"这回事,所以 {@link com.dwinovo.numen.task.TaskRecord#NO_DEADLINE}:
 * 期限回答的是"该多久干完",而这件活的终点只有主人换掉它。
 *
 * <p>{@code keepWithin} 是跟到多近就算到位。到位之后任务<b>休眠</b>(而不是结束):
 * 身体让给别人,目标一走远它自己就醒过来。这跟原版 {@code Goal.canUse()} 是同一
 * 个道理——休眠不是失败。
 *
 * <p>跟着走<b>从不改地形</b>(路线规格 alter=NONE,没有开关):跟不上时任务以失败收场并列出
 * 候选路线,模型先 goto 一条开路再接着跟。
 */
public final class FollowTaskRecord extends TaskRecord {

    /** 跟到这么近就算到位(米)。 */
    public final double keepWithin;

    /**
     * 跟着谁,按 UUID 认。{@code null} = 主人。
     *
     * <p>UUID 跨重启不变:常驻任务重启后重放,重放的那一行写的也是它,认到的还是同一只,认不到就是它没了。
     *
     * <p>这两种目标<b>消失的含义不一样</b>,所以任务里分两支:主人下线是暂时的,他会
     * 回来,那时该休眠等着;点名的实体死了或者被卸载就是没了,再等也不会回来,该收尾
     * 报给模型。
     */
    public final UUID target;

    /** 点名的那只叫什么,给人看的;跟主人时为 null。 */
    private final String targetName;

    public FollowTaskRecord(ServerSource source, double keepWithin, UUID target, String targetName) {
        super(source, NO_DEADLINE);
        this.keepWithin = keepWithin;
        this.target = target;
        this.targetName = targetName;
    }

    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    @Override
    public String describe() {
        String who = target == null ? "你" : targetName;
        return "跟着" + who + ",保持 " + (int) keepWithin + " 米";
    }
}
