package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.function.Consumer;

public final class FillTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private static final int MAX_VOLUME = 20000;

    private record Args(String block_id, int x1, int y1, int z1, int x2, int y2, int z2, boolean hollow) {}

    @Override
    public String name() {
        return "fill";
    }

    @Override
    public String description() {
        return "Fill a 3D rectangular box with blocks in one operation. "
                + "Two corner coordinates (x1,y1,z1) and (x2,y2,z2) define the box. "
                + "If hollow=true, only the shell (walls, floor, ceiling) is filled; the interior stays empty. "
                + "If hollow=false, every block in the box is filled solid. "
                + "Max " + MAX_VOLUME + " blocks per call. Creative mode only.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("block_id", "Minecraft block id, e.g. minecraft:stone_bricks")
                .integer("x1", "First corner X coordinate")
                .integer("y1", "First corner Y coordinate")
                .integer("z1", "First corner Z coordinate")
                .integer("x2", "Second corner X coordinate")
                .integer("y2", "Second corner Y coordinate")
                .integer("z2", "Second corner Z coordinate")
                .bool("hollow", "true = only shell, false = solid fill")
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);

        if (companion.gameMode.getGameModeForPlayer() != GameType.CREATIVE) {
            reply.accept(TaskResult.fail("fill only works in creative mode").toJson());
            return;
        }

        ResourceLocation rl = ResourceLocation.tryParse(a.block_id());
        if (rl == null) {
            reply.accept(TaskResult.fail("Invalid block id: " + a.block_id()).toJson());
            return;
        }

        Block block = BuiltInRegistries.BLOCK.get(rl);
        BlockState state = block.defaultBlockState();
        net.minecraft.world.level.Level level = companion.level;

        int minX = Math.min(a.x1(), a.x2());
        int maxX = Math.max(a.x1(), a.x2());
        int minY = Math.min(a.y1(), a.y2());
        int maxY = Math.max(a.y1(), a.y2());
        int minZ = Math.min(a.z1(), a.z2());
        int maxZ = Math.max(a.z1(), a.z2());

        int volume = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > MAX_VOLUME) {
            reply.accept(TaskResult.fail("Volume " + volume + " exceeds max " + MAX_VOLUME + ". Use smaller box or multiple fill calls.").toJson());
            return;
        }

        int placed = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (a.hollow()) {
                        boolean onSurface = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                        if (!onSurface) continue;
                    }
                    level.setBlock(new BlockPos(x, y, z), state, 3);
                    placed++;
                }
            }
        }

        reply.accept(TaskResult.ok("Filled " + placed + " blocks of " + a.block_id()
                + " from (" + minX + "," + minY + "," + minZ + ") to (" + maxX + "," + maxY + "," + maxZ + ")"
                + (a.hollow() ? " [hollow]" : " [solid]")).toJson());
    }
}
