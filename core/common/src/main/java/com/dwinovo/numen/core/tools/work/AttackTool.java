package com.dwinovo.numen.core.tools.work;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.tools.CombatOps;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.dwinovo.numen.task.TaskDispatch.ctx;
import static com.dwinovo.numen.task.TaskDispatch.setTask;

/**
 * 打掉指定的实体。<b>不问模型用什么武器</b>——那要看走到跟前时还有多远、有没有视线、
 * 还剩几支箭,全是模型在派发那一刻看不到的东西。
 */
public final class AttackTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final CombatOps impl = new CombatOps();

    private record Args(List<Integer> entity_ids) {}

    @Override
    public String name() {
        return "attack";
    }

    @Override
    public String description() {
        return "Attack one or more SPECIFIC entities by runtime id from scan_nearby_entities. "
                + "The body picks how: it closes in and swings when it can reach the target, shoots "
                + "with a bow or crossbow when it cannot get there, and keeps its distance from things "
                + "that explode. It also picks the strongest weapon you own AGAINST THAT TARGET "
                + "(Smite matters against undead), and walks over the drops afterwards. Do not ask for "
                + "a weapon — you cannot see the distance, the line of sight or the arrow count it "
                + "decides from. OMIT entity_ids to fight off EVERY hostile near you instead of named "
                + "targets: that is the only way to handle things that split (slimes, magma cubes), "
                + "because splitting replaces them with brand-new ids. It ends when nothing is coming "
                + "after you any more. BACKGROUND: acceptance means combat is already running; do not "
                + "resend ids, poll, or launch another body action until task_finished reports the outcome.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalIntArray("entity_ids",
                        "Runtime entity ids from scan_nearby_entities (1-20 distinct targets). "
                                + "Omit entirely to fight off every hostile near you.",
                        1, 20)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        setTask(companion, impl.attack(a.entity_ids(), ctx(toolCallId, companion)), args, reply);
    }
}
