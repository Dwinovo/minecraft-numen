package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.blueprint.Blueprint;
import com.dwinovo.numen.core.blueprint.BlueprintStore;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Saves a loaded world region as a reusable, owner-scoped blueprint. */
public final class SaveBlueprintTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private record Args(String name, int x1, int y1, int z1, int x2, int y2, int z2) {}

    @Override public String name() { return "save_blueprint"; }

    @Override
    public String description() {
        return "Save a loaded rectangular world region as a reusable blueprint. The minimum corner becomes local 0,0,0. Only non-air block states are saved; chest/machine contents and all block-entity NBT are deliberately excluded, so this cannot duplicate items. Max axis 64, max volume 32768. The blueprint belongs to the current owner and can later be inspected with plan_blueprint or built with build_blueprint.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("name", "Blueprint name: 1-48 letters/digits, '_' or '-'.")
                .integer("x1", "First corner X.")
                .integer("y1", "First corner Y.")
                .integer("z1", "First corner Z.")
                .integer("x2", "Second corner X.")
                .integer("y2", "Second corner Y.")
                .integer("z2", "Second corner Z.")
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            if (!(companion.level instanceof ServerLevel level)) {
                reply.accept(TaskResult.fail("blueprint capture needs a server level").toJson());
                return;
            }
            Args a = GSON.fromJson(args, Args.class);
            Blueprint blueprint = BlueprintStore.capture(level, a.name(),
                    new BlockPos(a.x1(), a.y1(), a.z1()), new BlockPos(a.x2(), a.y2(), a.z2()));
            BlueprintStore.save(level.getServer(), companion.getOwnerUuid(), blueprint);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", blueprint.name());
            data.put("size_x", blueprint.sizeX());
            data.put("size_y", blueprint.sizeY());
            data.put("size_z", blueprint.sizeZ());
            data.put("volume", blueprint.volume());
            data.put("non_air_blocks", blueprint.blocks().size());
            data.put("source_dimension", blueprint.sourceDimension());
            reply.accept(TaskResult.ok("saved blueprint '" + blueprint.name() + "' ("
                    + blueprint.sizeX() + "x" + blueprint.sizeY() + "x" + blueprint.sizeZ()
                    + ", " + blueprint.blocks().size() + " non-air blocks)", data).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail(ex.getMessage()).toJson());
        }
    }
}
