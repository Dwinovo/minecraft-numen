package com.dwinovo.numen.task.wait;

import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.api.task.TaskRecord;

/**
 * {@code time wait}:站着等这么多刻。期限比等的时长多一点,只防卡死。
 */
public final class WaitTaskRecord extends TaskRecord<Double> {

    /** 等完的那一刻(主世界游戏刻,与身体所在维度同一个钟)。 */
    public final long until;
    /** 要等的秒数,回执里照写。 */
    public final double seconds;

    public WaitTaskRecord(ServerCall call, double seconds) {
        super(call, call.her().level().getGameTime() + ticks(seconds) + 20);
        this.seconds = seconds;
        this.until = call.her().level().getGameTime() + ticks(seconds);
    }

    /** 秒折成刻,至少一刻。 */
    static long ticks(double seconds) {
        return Math.max(1, Math.round(seconds * 20));
    }

    @Override
    public String describe() {
        return getToolName() + " " + seconds + "s";
    }
}
