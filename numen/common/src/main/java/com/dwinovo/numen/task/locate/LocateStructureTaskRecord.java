package com.dwinovo.numen.task.locate;

import com.dwinovo.numen.api.task.Activity;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.api.task.TaskRecord;

/**
 * Typed task descriptor for {@code locate structure}: "where is the nearest
 * X?" — X being a structure id ({@code minecraft:fortress}) or a structure
 * tag ({@code #minecraft:village}). Resolution happens server-side in the
 * goal, where the registry lives.
 */
public final class LocateStructureTaskRecord extends TaskRecord<Located> {

    /** Raw structure argument as the LLM gave it: an id, or a {@code #}-prefixed tag. */
    public final String structure;

    public LocateStructureTaskRecord(ServerCall source, long deadlineGameTime, String structure) {
        super(source, deadlineGameTime);
        this.structure = structure;
    }

    @Override
    public String describe() {
        return getToolName() + " " + structure;
    }

    /** 给主人看的一句(头顶气泡、档案页),客户端按当前语言翻;{@link #describe()} 是给模型的。 */
    @Override
    public Activity activity() {
        return Activity.of("numen.activity.locate_structure", structure);
    }
}
