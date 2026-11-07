package com.dwinovo.numen.task.fish;

import com.dwinovo.numen.api.task.Activity;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.api.task.TaskRecord;

import java.util.List;

/** Typed descriptor for the {@code work fish} background task: one cast. */
public final class FishTaskRecord extends TaskRecord<List<String>> {

    /** 一竿的期限:等咬钩的时长,外加对准、抛出去、落水的那几秒。 */
    private static final long CAST_TICKS = FishCompanionTask.CAST_LIFETIME + 15 * 20;

    public FishTaskRecord(ServerCall source) {
        super(source, source.her().level().getGameTime() + CAST_TICKS);
    }

    /**
     * 一行人话 —— 这是<b>给模型看的</b>:{@code <current_task>} 与 task status 印的是它,主人看的是 {@link #activity()}。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    @Override
    public String describe() {
        return "钓鱼,抛了一竿";
    }

    /** 给主人看的一句(头顶气泡、档案页),客户端按当前语言翻;{@link #describe()} 是给模型的。 */
    @Override
    public Activity activity() {
        return Activity.of("numen.activity.fish");
    }
}
