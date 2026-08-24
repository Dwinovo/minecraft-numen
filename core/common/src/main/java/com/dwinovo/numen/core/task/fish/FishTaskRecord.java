package com.dwinovo.numen.core.task.fish;

import com.dwinovo.numen.task.TaskRecord;

/** Typed descriptor and live progress for the {@code fish} background task. */
public final class FishTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "fish";

    /** 要钓几条;<b>0 = 一直钓</b>(常驻,直到主人换掉这件活)。 */
    public final int requested;

    private int caught;
    private int casts;

    public FishTaskRecord(String toolCallId, long deadlineGameTime, int requested) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.requested = requested;
    }

    public int caught() {
        return caught;
    }

    public int casts() {
        return casts;
    }

    public void caughtOne() {
        caught++;
    }

    public void castOnce() {
        casts++;
    }

    @Override
    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task_status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    public String describe() {
        return requested > 0
                ? "钓鱼 " + caught + "/" + requested
                : "钓鱼,已钓到 " + caught + " 条";
    }
}
