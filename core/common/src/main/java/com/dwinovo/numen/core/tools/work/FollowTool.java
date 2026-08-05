package com.dwinovo.numen.core.tools.work;

import static com.dwinovo.numen.task.TaskDispatch.setTask;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.task.move.FollowTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 跟着主人——第一个纯<b>常驻</b>的工具:它没有"干完"这回事,只有被主人换掉。
 *
 * <p>所以它没有 count、没有期限。派下去之后她就一直跟着,直到主人让她做别的
 * ({@code mine} / {@code fish} / ……都会顶掉它)。
 */
public final class FollowTool implements NumenTool {

    private static final Gson GSON = new Gson();

    /** 默认跟到几米内。3 米大致是"就在旁边"又不至于挤到主人身上。 */
    private static final double DEFAULT_DISTANCE = 3.0;
    private static final double MIN_DISTANCE = 2.0;
    private static final double MAX_DISTANCE = 16.0;

    private record Args(Integer distance) {}

    @Override
    public String name() {
        return FollowTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Stay with your owner, following them wherever they go. This is a STANDING job: "
                + "there is nothing to finish, so you will keep at it until you are given "
                + "something else to do. Use it when the owner asks you to come along or "
                + "stick close. You go quiet while you are already beside them.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalInteger("distance",
                        "How close to stay, in blocks. Defaults to " + (int) DEFAULT_DISTANCE + ".",
                        (int) MIN_DISTANCE, (int) MAX_DISTANCE)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        double distance = parsed == null || parsed.distance() == null
                ? DEFAULT_DISTANCE
                : Math.clamp(parsed.distance(), MIN_DISTANCE, MAX_DISTANCE);
        setTask(companion, new FollowTaskRecord(toolCallId, distance), args, reply);
    }
}
