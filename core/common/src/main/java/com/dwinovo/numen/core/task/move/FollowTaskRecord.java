package com.dwinovo.numen.core.task.move;

import com.dwinovo.numen.task.TaskRecord;

/**
 * 「跟着我」——她当前在做的事就是跟着主人。
 *
 * <p>没有"干完"这回事,所以 {@link com.dwinovo.numen.task.TaskRecord#NO_DEADLINE}:
 * 期限回答的是"该多久干完",而这件活的终点只有主人换掉它。
 *
 * <p>{@code keepWithin} 是跟到多近就算到位。到位之后任务<b>休眠</b>(而不是结束):
 * 身体让给别人,主人一走远它自己就醒过来。这跟原版 {@code Goal.canUse()} 是同一
 * 个道理——休眠不是失败。
 */
public final class FollowTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "follow";

    /** 跟到这么近就算到位(米)。 */
    public final double keepWithin;

    public FollowTaskRecord(String toolCallId, double keepWithin) {
        super(TOOL_NAME, toolCallId, NO_DEADLINE);
        this.keepWithin = keepWithin;
    }

    @Override
    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task_status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    public String describe() {
        return "跟着你,保持 " + (int) keepWithin + " 米";
    }
}
