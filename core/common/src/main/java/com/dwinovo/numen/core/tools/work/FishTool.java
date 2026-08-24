package com.dwinovo.numen.core.tools.work;

import static com.dwinovo.numen.task.TaskDispatch.ctx;
import static com.dwinovo.numen.task.TaskDispatch.setTask;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.task.fish.FishTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.util.Mth;

/** Fish from a nearby water surface using the vanilla fishing-rod interaction. */
public final class FishTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private static final int MAX_COUNT = 64;
    private static final long TICKS_PER_CATCH = 90L * 20L;
    private static final long MIN_TIMEOUT_TICKS = 120L * 20L;

    private record Args(Integer count) {}

    @Override
    public String name() {
        return FishTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Fish repeatedly from nearby water. Requires a vanilla fishing rod in inventory. "
                + "If currently in water, moves up to 12 blocks onto a safe dry fishing stance; "
                + "it does not search long-distance for a biome or water body. `count` is successful bites reeled in; "
                + "vanilla fishing may produce fish, junk, or treasure. Uses native casting, loot, "
                + "rod durability, sounds, and stats. BACKGROUND task: returns task_id immediately; "
                + "the outcome arrives in task_finished.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalInteger("count",
                        "How many catches to reel in. OMIT to keep fishing indefinitely — "
                        + "she stays on it until you give her something else to do.",
                        1, MAX_COUNT)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        var context = ctx(toolCallId, companion);
        if (parsed == null || parsed.count() == null) {
            // 没给数量 = 常驻:一直钓,不设期限(期限是给"该多久干完"用的,而它没有干完)。
            setTask(companion, new FishTaskRecord(toolCallId, com.dwinovo.numen.task.TaskRecord.NO_DEADLINE, 0), args, reply);
            return;
        }
        int count = Mth.clamp(parsed.count(), 1, MAX_COUNT);
        long timeout = Math.max(MIN_TIMEOUT_TICKS, count * TICKS_PER_CATCH);
        setTask(companion, new FishTaskRecord(toolCallId, context.deadline(timeout), count), args, reply);
    }
}
