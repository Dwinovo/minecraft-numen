package com.dwinovo.numen.task.locate;

import com.dwinovo.numen.api.task.Activity;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.api.task.TaskRecord;

/**
 * Typed task descriptor for {@code locate biome}: "where is the nearest X?" —
 * X being a biome id ({@code minecraft:warped_forest}) or a biome tag
 * ({@code #minecraft:is_forest}). Resolution happens server-side in the goal,
 * where the registry lives.
 */
public final class LocateBiomeTaskRecord extends TaskRecord<Located> {

    /** Raw biome argument as the LLM gave it: an id, or a {@code #}-prefixed tag. */
    public final String biome;

    public LocateBiomeTaskRecord(ServerCall source, long deadlineGameTime, String biome) {
        super(source, deadlineGameTime);
        this.biome = biome;
    }

    @Override
    public String describe() {
        return getToolName() + " " + biome;
    }

    /** 给主人看的一句(头顶气泡、档案页),客户端按当前语言翻;{@link #describe()} 是给模型的。 */
    @Override
    public Activity activity() {
        return Activity.of("numen.activity.locate_biome", biome);
    }
}
