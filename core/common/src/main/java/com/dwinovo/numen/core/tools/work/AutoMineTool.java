package com.dwinovo.numen.core.tools.work;
import com.dwinovo.numen.core.tools.BlockActionOps;

import static com.dwinovo.numen.task.TaskDispatch.*;
import com.dwinovo.numen.core.tools.RouteSpecJson;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): gather blocks by type and quantity, or dig named groups from a scan. */
public final class AutoMineTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final BlockActionOps impl = new BlockActionOps();

    private record Args(List<String> block_ids, List<String> groups, Integer count, JsonObject spec) {}

    @Override
    public String name() {
        return "mine";
    }

    @Override
    public String description() {
        return "Gather blocks, in one of two ways. block_ids + count: she finds the nearest blocks of those "
                + "types herself and mines until `count` NEW items are gained or none remain nearby; include "
                + "all variants (iron_ore AND deepslate_iron_ore). groups: ids from your latest scan_blocks "
                + "(g1, g2, ...) — she digs exactly the cells of those groups that still hold the block the "
                + "scan saw, nothing beyond them; count is optional there and without it she digs the groups "
                + "out. An id not from the latest scan fails — scan again. Either way she travels with full "
                + "terrain-traversing navigation (digs to buried ores, pillars up cliffs, bridges gaps); no "
                + "coordinates or goto needed. count is items, not blocks (redstone_ore drops ~4). Before "
                + "breaking a block that needs the owner's consent she asks; if the owner or a rule refuses, "
                + "the task stops with the reason — decide what to do next, do not route around it. Only "
                + "mines what its tools actually harvest, and stops naming the needed tier if nothing "
                + "qualifies (to destroy a block regardless of drops, goto beside it and use interact_at "
                + "with button left). spec is goto's spec object laid over mine's own default, which may dig "
                + "anything (cells needing consent included) — pass it only to restrict her, e.g. "
                + "avoid_break for blocks or cells she must leave standing. BACKGROUND: a successful call is "
                + "already running; do not call mine/goto again while <current_task> exists and do not "
                + "poll. task_finished status=done means the job is complete; only timeout permits resending "
                + "the same arguments.";
    }


    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalStringArray("block_ids", "Namespaced block id(s) to gather; include all variants. "
                        + "Give this OR groups.")
                .optionalStringArray("groups", "Group ids from your latest scan_blocks (g1, g2, ...) to dig "
                        + "exactly those cells. Give this OR block_ids.")
                .optionalInteger("count", "How many ITEMS to gather (not blocks) — a block may drop several, "
                        + "and it counts only items gained on top of what you already hold. Required with "
                        + "block_ids; with groups, omit it to dig the groups out.", 1, 256)
                .optionalObject("spec", "How she may move and dig: goto's spec object, laid over mine's "
                        + "default (may dig anything, cells needing the owner's consent included). Fields "
                        + "you give override it; omit to keep it.", RouteSpecJson::schema)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        setTask(companion, impl.autoMine(companion, a.block_ids(), a.groups(), a.count(), a.spec(),
                ctx(toolCallId, companion)), args, reply);
    }
}
