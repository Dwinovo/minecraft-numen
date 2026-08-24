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
import net.minecraft.util.Mth;

/**
 * 跟着走——第一个纯<b>常驻</b>的工具:它没有"干完"这回事,只有被主人换掉。
 *
 * <p>所以它没有 count、没有期限。派下去之后她就一直跟着,直到主人让她做别的
 * ({@code mine} / {@code fish} / ……都会顶掉它)。
 *
 * <p>不给 {@code entity_id} 就是跟主人。给了就跟那一只——村民、狼、别的玩家都行。
 * 两者<b>目标消失时的含义不同</b>,见 {@code FollowTaskRecord#entityId}。
 */
public final class FollowTool implements NumenTool {

    private static final Gson GSON = new Gson();

    /** 默认跟到几米内。3 米大致是"就在旁边"又不至于挤到主人身上。 */
    private static final double DEFAULT_DISTANCE = 3.0;
    private static final double MIN_DISTANCE = 2.0;
    private static final double MAX_DISTANCE = 16.0;

    private record Args(Integer distance, Integer entity_id, String entity_uuid, Boolean may_alter_terrain) {}

    @Override
    public String name() {
        return FollowTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Tag along with someone, following them wherever they go — your owner by "
                + "default, or any entity you name. This is a STANDING job: there is nothing "
                + "to finish, so you will keep at it until you are given something else to do. "
                + "Use it when the owner asks you to come along or stick close, or to shadow a "
                + "particular mob or player. You go quiet while you are already beside them. "
                + "Following a named entity ends by itself if that entity dies or leaves the "
                + "loaded area; following your owner just waits when they log off. TERRAIN: by "
                + "default she never breaks or places a block to keep up. When the only way to "
                + "them would need digging, bridging or pillaring, following ENDS with a failure "
                + "that lists exactly which blocks; if altering them is acceptable, re-send follow "
                + "with may_alter_terrain=true (ask the owner when it is not obviously natural "
                + "terrain).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalInteger("distance",
                        "How close to stay, in blocks. Defaults to " + (int) DEFAULT_DISTANCE + ".",
                        (int) MIN_DISTANCE, (int) MAX_DISTANCE)
                .optionalInteger("entity_id",
                        "Who to follow, by runtime entity id from scan_nearby_entities. "
                                + "Leave it out to follow your owner.",
                        1, Integer.MAX_VALUE)
                .optionalBool("may_alter_terrain", "Consent to dig through, bridge or pillar to "
                        + "keep up. Omit/false = leave every block untouched (default). Set true "
                        + "after a failed follow listed the blocks in the way and you judge that "
                        + "acceptable, or when the owner said so.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        double distance = parsed == null || parsed.distance() == null
                ? DEFAULT_DISTANCE
                : Mth.clamp(parsed.distance(), MIN_DISTANCE, MAX_DISTANCE);
        Integer entityId = parsed == null ? null : parsed.entity_id();
        java.util.UUID targetUuid = null;
        if (entityId != null) {
            var target = ((net.minecraft.server.level.ServerLevel) companion.level())
                    .getEntity(entityId);
            if (target == null || target.isRemoved() || target == companion) {
                reply.accept("no entity with id " + entityId
                        + " is here — scan_nearby_entities first, ids do not survive restarts");
                return;
            }
            // 把身份钉进 args:常驻任务跨重启是<b>重放这次调用</b>,而 id 每次开服重发。
            // 不钉的话重放之后她可能一声不吭地跟上另一只。
            targetUuid = target.getUUID();
            args.addProperty("entity_uuid", targetUuid.toString());
        } else if (parsed != null && parsed.entity_uuid() != null) {
            targetUuid = java.util.UUID.fromString(parsed.entity_uuid());
        }
        boolean mayAlterTerrain = parsed != null && Boolean.TRUE.equals(parsed.may_alter_terrain());
        setTask(companion, new FollowTaskRecord(toolCallId, distance, entityId, targetUuid, mayAlterTerrain),
                args, reply);
    }
}
