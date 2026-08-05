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
    public String describe() {
        return "跟着主人(保持 " + (int) keepWithin + " 米内)";
    }
}
