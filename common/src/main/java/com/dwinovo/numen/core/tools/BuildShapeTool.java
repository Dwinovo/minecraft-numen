package com.dwinovo.numen.core.tools;

import static com.dwinovo.numen.task.TaskDispatch.ctx;
import static com.dwinovo.numen.task.TaskDispatch.dispatchAsync;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.core.task.BuildTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 参数化形状建造:把常用几何(盒/线/柱/球,实心或空心)展开成格集,交给同一个
 * 建造任务。LLM 像搭积木一样逐步组合——地板、墙、塔身、穹顶各来一笔,复杂建筑
 * 由多次调用叠出来;方块用 {@code minecraft:air} 则整个形状是"挖空"。
 */
public final class BuildShapeTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private static final int MAX_CELLS = 4096;
    private static final long MIN_TIMEOUT_TICKS = 60 * 20;
    private static final long TICKS_PER_BLOCK = 20 * 20;

    private record Args(String shape, String block_id, Boolean hollow,
                        Integer x1, Integer y1, Integer z1,
                        Integer x2, Integer y2, Integer z2,
                        Integer radius, Integer height) {}

    @Override
    public String name() {
        return "build_shape";
    }

    @Override
    public String description() {
        return "Build one parametric shape of a single block type as a background task — compose complex builds "
                + "from several calls like stacking toy bricks (floor, then walls, then roof). Shapes: `box` "
                + "(corners x1,y1,z1 to x2,y2,z2; hollow=true keeps only the outer shell — walls+floor+ceiling), "
                + "`line` (straight run of blocks from point 1 to point 2, any direction), `cylinder` (vertical; "
                + "x1,y1,z1 = center of the BOTTOM disc, plus radius and height; hollow=true builds just the tube "
                + "wall), `sphere` (x1,y1,z1 = center, plus radius; hollow=true builds just the shell — good for "
                + "domes when combined with an air box to cut the lower half). block_id `minecraft:air` makes the "
                + "shape CARVE instead of build. Cells cap at 4096 per call — split bigger work into multiple "
                + "calls. BACKGROUND: after acceptance wait for task_finished; never resend while running.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("shape", Map.of("type", "string",
                "enum", List.of("box", "line", "cylinder", "sphere"),
                "description", "Which shape to build."));
        props.put("block_id", Map.of("type", "string",
                "description", "Block to build with, e.g. minecraft:stone_bricks; minecraft:air carves."));
        props.put("hollow", Map.of("type", "boolean",
                "description", "Optional, default false. Keep only the outer shell of box/cylinder/sphere."));
        props.put("x1", Map.of("type", "integer", "description", "First corner / center X."));
        props.put("y1", Map.of("type", "integer", "description", "First corner / center-or-bottom Y."));
        props.put("z1", Map.of("type", "integer", "description", "First corner / center Z."));
        props.put("x2", Map.of("type", "integer", "description", "Second corner X (box/line only)."));
        props.put("y2", Map.of("type", "integer", "description", "Second corner Y (box/line only)."));
        props.put("z2", Map.of("type", "integer", "description", "Second corner Z (box/line only)."));
        props.put("radius", Map.of("type", "integer", "description", "Radius in blocks (cylinder/sphere)."));
        props.put("height", Map.of("type", "integer", "description", "Height in blocks (cylinder only)."));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of("shape", "block_id", "x1", "y1", "z1"));
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        boolean hollow = a.hollow() != null && a.hollow();
        List<BlockPos> cells = cells(a.shape(), hollow,
                req(a.x1(), "x1"), req(a.y1(), "y1"), req(a.z1(), "z1"),
                a.x2(), a.y2(), a.z2(), a.radius(), a.height());

        Item item = ToolArgs.parseItem(a.block_id());
        Block block;
        if (item == Items.AIR) {
            block = Blocks.AIR;
        } else if (item instanceof BlockItem blockItem) {
            block = blockItem.getBlock();
        } else {
            throw new IllegalArgumentException(a.block_id() + " is not a placeable block");
        }
        String label = a.block_id().contains(":") ? a.block_id().split(":", 2)[1] : a.block_id();
        List<BuildTaskRecord.Target> targets = new ArrayList<>(cells.size());
        for (BlockPos pos : cells) {
            targets.add(new BuildTaskRecord.Target(block, item, pos, label, null, null, null));
        }
        long timeout = Math.max(MIN_TIMEOUT_TICKS, (long) targets.size() * TICKS_PER_BLOCK);
        dispatchAsync(companion, new BuildTaskRecord(toolCallId,
                ctx(toolCallId, companion).deadline(timeout), targets, true, 0), reply);
    }

    private static int req(Integer v, String name) {
        if (v == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return v;
    }

    /** 形状展开为格集(去重、上限封顶)。公开静态,测试直接验几何。 */
    public static List<BlockPos> cells(String shape, boolean hollow,
                                       int x1, int y1, int z1,
                                       Integer x2, Integer y2, Integer z2,
                                       Integer radius, Integer height) {
        Set<BlockPos> out = new LinkedHashSet<>();
        switch (shape == null ? "" : shape) {
            case "box" -> {
                int ax = Math.min(x1, req(x2, "x2")), bx = Math.max(x1, x2);
                int ay = Math.min(y1, req(y2, "y2")), by = Math.max(y1, y2);
                int az = Math.min(z1, req(z2, "z2")), bz = Math.max(z1, z2);
                for (int y = ay; y <= by; y++) {
                    for (int x = ax; x <= bx; x++) {
                        for (int z = az; z <= bz; z++) {
                            if (hollow && x != ax && x != bx && y != ay && y != by
                                    && z != az && z != bz) {
                                continue;
                            }
                            add(out, x, y, z);
                        }
                    }
                }
            }
            case "line" -> {
                int bx = req(x2, "x2"), by = req(y2, "y2"), bz = req(z2, "z2");
                int steps = Math.max(1, Math.max(Math.abs(bx - x1),
                        Math.max(Math.abs(by - y1), Math.abs(bz - z1))));
                for (int i = 0; i <= steps; i++) {
                    add(out, Math.round(x1 + (bx - x1) * (float) i / steps),
                            Math.round(y1 + (by - y1) * (float) i / steps),
                            Math.round(z1 + (bz - z1) * (float) i / steps));
                }
            }
            case "cylinder" -> {
                int r = req(radius, "radius");
                int h = Math.max(1, height == null ? 1 : height);
                double outer = (r + 0.5) * (r + 0.5);
                double inner = (r - 0.5) * (r - 0.5);
                for (int y = y1; y < y1 + h; y++) {
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            double d = dx * dx + dz * dz;
                            if (d > outer || (hollow && d < inner)) {
                                continue;
                            }
                            add(out, x1 + dx, y, z1 + dz);
                        }
                    }
                }
            }
            case "sphere" -> {
                int r = req(radius, "radius");
                double outer = (r + 0.5) * (r + 0.5);
                double inner = (r - 0.5) * (r - 0.5);
                for (int dy = -r; dy <= r; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            double d = dx * dx + dy * dy + dz * dz;
                            if (d > outer || (hollow && d < inner)) {
                                continue;
                            }
                            add(out, x1 + dx, y1 + dy, z1 + dz);
                        }
                    }
                }
            }
            default -> throw new IllegalArgumentException(
                    "shape must be box, line, cylinder or sphere");
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("shape resolved to zero cells");
        }
        if (out.size() > MAX_CELLS) {
            throw new IllegalArgumentException("shape has " + out.size() + " cells, exceeding "
                    + MAX_CELLS + "; split it into smaller calls");
        }
        return new ArrayList<>(out);
    }

    private static void add(Set<BlockPos> out, int x, int y, int z) {
        out.add(new BlockPos(x, y, z));
    }
}
