package com.dwinovo.numen.plugins.kaleidoscope;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.function.Consumer;

/** 看一格炒锅/汤锅现在什么样:第几阶段、锅里有什么、底下有没有火、还缺什么、会不会糊。 */
public final class KcInspectTool implements NumenTool {

    private static final Gson GSON = new Gson();

    private record Args(int x, int y, int z) {}

    @Override
    public String name() {
        return "kc_inspect";
    }

    @Override
    public String description() {
        return "Kaleidoscope Cookery: read one pot or stockpot block — which stage it is in, what is inside,"
                + " whether the block under it is lit, how many ticks until it is done / burns / clears, and"
                + " what it is waiting for. Reads at any distance. Use it before kc_cook to check the"
                + " cookware is free, and while somebody else's dish is in there to see when it frees up.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("x", "Block X of the cookware.")
                .integer("y", "Block Y of the cookware.")
                .integer("z", "Block Z of the cookware.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        BlockPos pos = new BlockPos(a.x(), a.y(), a.z());
        Cooker cooker = Cooker.at(companion.serverLevel(), pos);
        if (cooker == null) {
            reply.accept(TaskResult.fail("nothing at " + Cooker.where(pos) + " is a pot or a stockpot"
                    + " (steamers, chopping boards, millstones and spits are not wired up yet)").toJson());
            return;
        }
        reply.accept(TaskResult.ok(cooker.kind().id() + " at " + Cooker.where(pos), cooker.report()).toJson());
    }
}
