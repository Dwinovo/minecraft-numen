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
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Build a bounded set of explicit block cells as one background task. */
public final class BuildTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private static final int MAX_BLOCKS = 512;
    /** blocks + shapes 展开后的总格数上限。 */
    private static final int MAX_TOTAL_CELLS = 4096;
    private static final long MIN_TIMEOUT_TICKS = 60 * 20;
    private static final long TICKS_PER_BLOCK = 20 * 20;

    private record Args(List<BlockSpec> blocks, List<ShapeSpec> shapes,
                        Boolean replace_existing, Integer layer_height) {}
    private record ShapeSpec(String shape, String block_id, Boolean hollow,
                             Integer x1, Integer y1, Integer z1,
                             Integer x2, Integer y2, Integer z2,
                             Integer radius, Integer height) {}
    private record BlockSpec(String block_id, int x, int y, int z,
                             String facing, String axis, String half,
                             Map<String, String> properties) {}

    @Override
    public String name() {
        return "build";
    }

    @Override
    public String description() {
        return "Construct or clear blocks as ONE background task. Consult build_guide BEFORE designing. Two ways to express the work, freely mixed in "
                + "a single call: `blocks` = explicit cells (each with block_id, x, y, z and optional state fields "
                + "facing/axis/half/properties — use for precise details like stairs, doors, torches); `shapes` = "
                + "parametric volumes, each {shape: box|line|cylinder|sphere, block_id, hollow?, coords} — box/line "
                + "take corners x1,y1,z1 to x2,y2,z2; cylinder takes bottom-center x1,y1,z1 + radius + height; "
                + "sphere takes center x1,y1,z1 + radius; hollow keeps only the outer shell. block_id "
                + "minecraft:air CLEARS/carves (drops harvest normally; liquids are always left untouched). "
                + "Compose a whole building like stacking toy bricks: floor box, wall boxes, roof, then detail "
                + "cells — many shapes and cells in ONE call, up to 4096 total cells. The task walks, climbs and "
                + "bridges to each cell bottom-up layer by layer. Materials are NOT required or consumed for now (free build mode) — never refuse a build for lack of blocks. For prebuilt structure files use the blueprint "
                + "tool instead. BACKGROUND: after acceptance wait for task_finished; never resend while running "
                + "or after status=done.";
    }


    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("block_id", Map.of(
                "type", "string",
                "description", "Namespaced id of the block item to place, e.g. minecraft:obsidian. "
                        + "Use minecraft:air to clear/break whatever occupies the cell."));
        props.put("x", Map.of("type", "integer", "description", "Target x."));
        props.put("y", Map.of("type", "integer", "description", "Target y."));
        props.put("z", Map.of("type", "integer", "description", "Target z."));
        props.put("facing", enumSchema("Optional facing: north/south/east/west/up/down.",
                "north", "south", "east", "west", "up", "down"));
        props.put("axis", enumSchema("Optional pillar/log axis.", "x", "y", "z"));
        props.put("half", enumSchema("Optional slab/stair half.", "top", "bottom"));
        props.put("properties", Map.of(
                "type", List.of("object", "null"),
                "description", "Optional block-state properties by name, e.g. {\"open\":\"false\"}.",
                "additionalProperties", Map.of("type", "string")));

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", props);
        item.put("required", List.of("block_id", "x", "y", "z"));
        item.put("additionalProperties", false);

        Map<String, Object> blocks = new LinkedHashMap<>();
        blocks.put("type", "array");
        blocks.put("description", "Explicit block cells in absolute world coordinates (precise details).");
        blocks.put("items", item);
        blocks.put("maxItems", MAX_BLOCKS);

        Map<String, Object> shapeProps = new LinkedHashMap<>();
        shapeProps.put("shape", Map.of("type", "string",
                "enum", List.of("box", "line", "cylinder", "sphere")));
        shapeProps.put("block_id", Map.of("type", "string",
                "description", "Block for this shape; minecraft:air carves."));
        shapeProps.put("hollow", Map.of("type", "boolean",
                "description", "Optional, default false. Outer shell only."));
        shapeProps.put("x1", Map.of("type", "integer"));
        shapeProps.put("y1", Map.of("type", "integer"));
        shapeProps.put("z1", Map.of("type", "integer"));
        shapeProps.put("x2", Map.of("type", "integer"));
        shapeProps.put("y2", Map.of("type", "integer"));
        shapeProps.put("z2", Map.of("type", "integer"));
        shapeProps.put("radius", Map.of("type", "integer"));
        shapeProps.put("height", Map.of("type", "integer"));
        Map<String, Object> shapeItem = new LinkedHashMap<>();
        shapeItem.put("type", "object");
        shapeItem.put("properties", shapeProps);
        shapeItem.put("required", List.of("shape", "block_id", "x1", "y1", "z1"));
        shapeItem.put("additionalProperties", false);
        Map<String, Object> shapes = new LinkedHashMap<>();
        shapes.put("type", "array");
        shapes.put("description", "Parametric volumes, applied in order before/with blocks.");
        shapes.put("items", shapeItem);

        Map<String, Object> rootProps = new LinkedHashMap<>();
        rootProps.put("blocks", blocks);
        rootProps.put("shapes", shapes);
        rootProps.put("replace_existing", Map.of(
                "type", "boolean",
                "description", "Optional, default true. Clear wrong non-protected blocks at requested cells."));
        rootProps.put("layer_height", Map.of(
                "type", "integer",
                "description", "Optional. Explicit vertical layer step (1-16) builds bottom-up in slices of"
                        + " that height. Default 0 = auto: structures taller than 2 blocks build in 1-block"
                        + " layers (the body always works from atop the finished part); flat or low"
                        + " structures place freely."));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", rootProps);
        root.put("required", List.of());
        root.put("additionalProperties", false);
        return root;
    }

    private static Map<String, Object> enumSchema(String description, String... values) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", List.of("string", "null"));
        schema.put("description", description);
        schema.put("enum", valuesWithNull(values));
        return schema;
    }

    private static List<Object> valuesWithNull(String... values) {
        List<Object> out = new ArrayList<>();
        for (String value : values) {
            out.add(value);
        }
        out.add(null);
        return out;
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        List<BuildTaskRecord.Target> targets = new ArrayList<>();
        if (parsed.shapes() != null) {
            for (ShapeSpec shape : parsed.shapes()) {
                targets.addAll(expandShape(shape));
            }
        }
        if (parsed.blocks() != null && !parsed.blocks().isEmpty()) {
            targets.addAll(parseTargets(parsed.blocks()));
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("give at least one of blocks / shapes");
        }
        // 后写覆盖先写(细节格修饰形状),去重保留最后一笔
        Map<Long, BuildTaskRecord.Target> byPos = new LinkedHashMap<>();
        for (BuildTaskRecord.Target target : targets) {
            byPos.put(target.pos().asLong(), target);
        }
        targets = new ArrayList<>(byPos.values());
        if (targets.size() > MAX_TOTAL_CELLS) {
            throw new IllegalArgumentException("this call resolves to " + targets.size()
                    + " cells, exceeding " + MAX_TOTAL_CELLS + "; split it into multiple calls");
        }
        boolean replaceExisting = parsed.replace_existing() == null || parsed.replace_existing();
        int layerHeight = parsed.layer_height() == null ? 0 : parsed.layer_height();
        if (layerHeight < 0 || layerHeight > 16) {
            throw new IllegalArgumentException("layer_height must be between 0 and 16");
        }
        long timeout = Math.max(MIN_TIMEOUT_TICKS, (long) targets.size() * TICKS_PER_BLOCK);
        // 材料校验暂全关(想建就建):不查背包、不扣数量;寻路搭脚手架仍用背包真实方块
        dispatchAsync(companion, new BuildTaskRecord(toolCallId,
                ctx(toolCallId, companion).deadline(timeout), targets, replaceExisting, layerHeight,
                false), reply);
    }

    /** 一个形状展开为目标格集(几何生成 + 单一方块类型)。 */
    private static List<BuildTaskRecord.Target> expandShape(ShapeSpec spec) {
        if (spec == null || spec.shape() == null || spec.block_id() == null) {
            throw new IllegalArgumentException("each shape needs shape and block_id");
        }
        Item item = ToolArgs.parseItem(spec.block_id());
        net.minecraft.world.level.block.Block block;
        if (item == Items.AIR) {
            block = Blocks.AIR;
        } else if (item instanceof BlockItem blockItem) {
            block = blockItem.getBlock();
        } else {
            throw new IllegalArgumentException(spec.block_id() + " is not a placeable block");
        }
        String label = spec.block_id().contains(":")
                ? spec.block_id().split(":", 2)[1] : spec.block_id();
        boolean hollow = spec.hollow() != null && spec.hollow();
        if (spec.x1() == null || spec.y1() == null || spec.z1() == null) {
            throw new IllegalArgumentException("shape " + spec.shape() + " needs x1, y1, z1");
        }
        List<BlockPos> cells = shapeCells(spec.shape(), hollow,
                spec.x1(), spec.y1(), spec.z1(),
                spec.x2(), spec.y2(), spec.z2(), spec.radius(), spec.height());
        List<BuildTaskRecord.Target> out = new ArrayList<>(cells.size());
        for (BlockPos pos : cells) {
            out.add(new BuildTaskRecord.Target(block, item, pos, label, null, null, null));
        }
        return out;
    }

    static List<BuildTaskRecord.Target> parseTargets(List<BlockSpec> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            throw new IllegalArgumentException("blocks must contain at least one cell");
        }
        if (blocks.size() > MAX_BLOCKS) {
            throw new IllegalArgumentException("build accepts at most " + MAX_BLOCKS + " cells");
        }
        List<BuildTaskRecord.Target> targets = new ArrayList<>(blocks.size());
        Set<BlockPos> seen = new LinkedHashSet<>();
        for (BlockSpec spec : blocks) {
            if (spec == null || spec.block_id() == null || spec.block_id().isBlank()) {
                throw new IllegalArgumentException("each block needs block_id, x, y and z");
            }
            Item item = ToolArgs.parseItem(spec.block_id());
            net.minecraft.world.level.block.Block block;
            if (item == Items.AIR) {
                block = Blocks.AIR;
            } else if (item instanceof BlockItem blockItem) {
                block = blockItem.getBlock();
            } else {
                throw new IllegalArgumentException(spec.block_id() + " is not a placeable block");
            }
            BlockPos pos = new BlockPos(spec.x(), spec.y(), spec.z());
            if (!seen.add(pos)) {
                throw new IllegalArgumentException("duplicate build cell " + pos.toShortString());
            }
            Direction facing = opt(spec.facing()) == null ? null : Direction.byName(opt(spec.facing()));
            if (opt(spec.facing()) != null && facing == null) {
                throw new IllegalArgumentException("invalid facing: " + spec.facing());
            }
            Direction.Axis axis = opt(spec.axis()) == null ? null : Direction.Axis.byName(opt(spec.axis()));
            if (opt(spec.axis()) != null && axis == null) {
                throw new IllegalArgumentException("invalid axis: " + spec.axis());
            }
            String half = opt(spec.half());
            if (half != null && !half.equals("top") && !half.equals("bottom")) {
                throw new IllegalArgumentException("invalid half: " + spec.half());
            }
            String label = BuiltInRegistries.BLOCK.getKey(block).getPath();
            Boolean topHalf = half == null ? null : half.equals("top");
            BlockState desiredState = applyProperties(
                    new BuildTaskRecord.Target(block, item, pos, label,
                            facing, axis, topHalf).desiredState(), spec.properties());
            targets.add(new BuildTaskRecord.Target(desiredState, item, pos, label,
                    facing, axis, topHalf));
        }
        return List.copyOf(targets);
    }


    private static BlockState applyProperties(BlockState state, Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return state;
        }
        BlockState out = state;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String name = opt(entry.getKey());
            String value = opt(entry.getValue());
            if (name == null || value == null) {
                throw new IllegalArgumentException("block-state properties need non-empty names and values");
            }
            Property<?> property = out.getBlock().getStateDefinition().getProperty(name);
            if (property == null) {
                throw new IllegalArgumentException(out.getBlock().getName().getString()
                        + " has no property " + name);
            }
            out = setProperty(out, property, value);
        }
        return out;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState setProperty(BlockState state, Property property, String value) {
        java.util.Optional parsed = property.getValue(value);
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("invalid value " + value
                    + " for property " + property.getName());
        }
        return state.setValue(property, (Comparable) parsed.get());
    }

    private static String opt(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toLowerCase();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 形状展开为格集(去重、上限封顶)。公开静态,测试直接验几何。 */
    public static List<BlockPos> shapeCells(String shape, boolean hollow,
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
        if (out.size() > MAX_TOTAL_CELLS) {
            throw new IllegalArgumentException("shape has " + out.size() + " cells, exceeding "
                    + MAX_TOTAL_CELLS + "; split it into smaller calls");
        }
        return new ArrayList<>(out);
    }

    private static void add(Set<BlockPos> out, int x, int y, int z) {
        out.add(new BlockPos(x, y, z));
    }

    private static int req(Integer v, String name) {
        if (v == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return v;
    }
}
