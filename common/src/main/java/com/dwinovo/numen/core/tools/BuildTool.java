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

    private record Args(List<OpSpec> ops, Boolean replace_existing, Integer layer_height) {}
    private record OpSpec(String op, String block_id, Boolean hollow,
                          Integer x, Integer y, Integer z,
                          Integer x1, Integer y1, Integer z1,
                          Integer x2, Integer y2, Integer z2,
                          Integer radius, Integer height, Double density,
                          String facing, String axis, String half,
                          Map<String, String> properties) {}
    private record BlockSpec(String block_id, int x, int y, int z,
                             String facing, String axis, String half,
                             Map<String, String> properties) {}

    @Override
    public String name() {
        return "build";
    }

    @Override
    public String description() {
        return "Construct or clear blocks as ONE background task, expressed as a single ordered `ops` stream — "
                + "write ops in build order, later ops overwrite earlier cells. Load the building_design skill "
                + "(load_skill) BEFORE designing anything non-trivial. Ops: `set` one cell (block_id, x,y,z, "
                + "optional facing/axis/half/properties — precise details like stairs and torches); `box` "
                + "corners x1,y1,z1..x2,y2,z2 (hollow = outer shell); `walls` vertical perimeter ring only, NO "
                + "top/bottom face — the right primitive for wall rings; `line` two points; `cylinder` bottom-"
                + "center x1,y1,z1 + radius + height (hollow = tube); `sphere` center + radius (hollow = shell); "
                + "`roof` gable roof over base rect x1,y1,z1..x2,z2 — solid shrinking layers, gable ends filled, "
                + "doubles as the ceiling; `set_door` a full door at x,y,z (lower half) with `facing`; `scatter` "
                + "sprinkles the block over plane y1 within x1,z1..x2,z2 at optional `density` 0-1 (default "
                + "0.25) — flowers, grass, mushrooms. block_id minecraft:air CLEARS (drops harvest normally; "
                + "liquids always left untouched). Compose whole buildings like stacking toy bricks in ONE call, "
                + "up to 4096 cells. The task walks, climbs and bridges bottom-up layer by layer. Materials are "
                + "NOT required or consumed for now (free build mode) — never refuse a build for lack of blocks. "
                + "For prebuilt structure files use the blueprint tool. BACKGROUND: after acceptance wait for "
                + "task_finished; never resend while running or after status=done.";
    }


    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("op", Map.of("type", "string",
                "enum", List.of("set", "box", "walls", "line", "cylinder", "sphere",
                        "roof", "set_door", "scatter")));
        props.put("block_id", Map.of("type", "string",
                "description", "Block for this op, e.g. minecraft:stone_bricks; minecraft:air clears."));
        props.put("hollow", Map.of("type", "boolean",
                "description", "box/cylinder/sphere: keep only the outer shell."));
        props.put("x", Map.of("type", "integer", "description", "set/set_door: cell x."));
        props.put("y", Map.of("type", "integer", "description", "set/set_door: cell y (door lower half)."));
        props.put("z", Map.of("type", "integer", "description", "set/set_door: cell z."));
        props.put("x1", Map.of("type", "integer"));
        props.put("y1", Map.of("type", "integer"));
        props.put("z1", Map.of("type", "integer"));
        props.put("x2", Map.of("type", "integer"));
        props.put("y2", Map.of("type", "integer"));
        props.put("z2", Map.of("type", "integer"));
        props.put("radius", Map.of("type", "integer"));
        props.put("height", Map.of("type", "integer"));
        props.put("density", Map.of("type", "number",
                "description", "scatter: fraction of cells to fill, 0-1, default 0.25."));
        props.put("facing", enumSchema("set: block facing; set_door: which way the door faces.",
                "north", "south", "east", "west", "up", "down"));
        props.put("axis", enumSchema("set: pillar/log axis.", "x", "y", "z"));
        props.put("half", enumSchema("set: slab/stair half.", "top", "bottom"));
        props.put("properties", Map.of(
                "type", List.of("object", "null"),
                "description", "set: extra block-state properties by name.",
                "additionalProperties", Map.of("type", "string")));

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", props);
        item.put("required", List.of("op", "block_id"));
        item.put("additionalProperties", false);

        Map<String, Object> ops = new LinkedHashMap<>();
        ops.put("type", "array");
        ops.put("description", "Ordered build instructions; later ops overwrite earlier cells.");
        ops.put("items", item);
        ops.put("minItems", 1);

        Map<String, Object> rootProps = new LinkedHashMap<>();
        rootProps.put("ops", ops);
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
        root.put("required", List.of("ops"));
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
        if (parsed.ops() == null || parsed.ops().isEmpty()) {
            throw new IllegalArgumentException("ops must contain at least one instruction");
        }
        List<BuildTaskRecord.Target> targets = new ArrayList<>();
        for (OpSpec op : parsed.ops()) {
            targets.addAll(expandOp(op));
        }
        // 单指令流:顺序即语义,后写覆盖先写,去重保留最后一笔
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
        // 材料记账随能力画像:免耗材(创造)想建就建;否则消耗背包,开工前
        // 由任务预检并逐项报缺(见 BuildCompanionTask 的 preflight)。
        boolean consume = !com.dwinovo.numen.core.task.WorkProfile.of(companion).freeMaterials();
        dispatchAsync(companion, new BuildTaskRecord(toolCallId,
                ctx(toolCallId, companion).deadline(timeout), targets, replaceExisting, layerHeight,
                consume), reply);
    }

    /** 一条指令展开为目标格集。set/set_door 携带状态;体积算子单一方块类型。 */
    private static List<BuildTaskRecord.Target> expandOp(OpSpec spec) {
        if (spec == null || spec.op() == null || spec.block_id() == null) {
            throw new IllegalArgumentException("each op needs op and block_id");
        }
        if ("set".equals(spec.op())) {
            if (spec.x() == null || spec.y() == null || spec.z() == null) {
                throw new IllegalArgumentException("set needs x, y, z");
            }
            return parseTargets(List.of(new BlockSpec(spec.block_id(), spec.x(), spec.y(), spec.z(),
                    spec.facing(), spec.axis(), spec.half(), spec.properties())));
        }
        if ("set_door".equals(spec.op())) {
            return expandDoor(spec);
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
            throw new IllegalArgumentException("op " + spec.op() + " needs x1, y1, z1");
        }
        List<BlockPos> cells = "scatter".equals(spec.op())
                ? scatterCells(spec.x1(), spec.y1(), spec.z1(),
                        req(spec.x2(), "x2"), req(spec.z2(), "z2"),
                        spec.density() == null ? 0.25 : spec.density())
                : shapeCells(spec.op(), hollow, spec.x1(), spec.y1(), spec.z1(),
                        spec.x2(), spec.y2(), spec.z2(), spec.radius(), spec.height());
        List<BuildTaskRecord.Target> out = new ArrayList<>(cells.size());
        for (BlockPos pos : cells) {
            out.add(new BuildTaskRecord.Target(block, item, pos, label, null, null, null));
        }
        return out;
    }

    /** 整扇门:下半 + 上半两格,朝向正确,一笔成型——半格错位的卡门病从原语层消灭。 */
    private static List<BuildTaskRecord.Target> expandDoor(OpSpec spec) {
        if (spec.x() == null || spec.y() == null || spec.z() == null) {
            throw new IllegalArgumentException("set_door needs x, y, z (lower half)");
        }
        Item item = ToolArgs.parseItem(spec.block_id());
        if (!(item instanceof BlockItem blockItem)
                || !(blockItem.getBlock() instanceof net.minecraft.world.level.block.DoorBlock door)) {
            throw new IllegalArgumentException(spec.block_id() + " is not a door");
        }
        Direction facing = spec.facing() == null ? Direction.NORTH : Direction.byName(spec.facing());
        if (facing == null || facing.getAxis().isVertical()) {
            throw new IllegalArgumentException("set_door facing must be north/south/east/west");
        }
        BlockPos lower = new BlockPos(spec.x(), spec.y(), spec.z());
        String label = spec.block_id().contains(":")
                ? spec.block_id().split(":", 2)[1] : spec.block_id();
        BlockState base = door.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DoorBlock.FACING, facing);
        List<BuildTaskRecord.Target> out = new ArrayList<>(2);
        out.add(new BuildTaskRecord.Target(base.setValue(
                net.minecraft.world.level.block.DoorBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER),
                item, lower, label, null, null, null));
        out.add(new BuildTaskRecord.Target(base.setValue(
                net.minecraft.world.level.block.DoorBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER),
                item, lower.above(), label, null, null, null));
        return out;
    }

    /** 平面撒点:y1 平面、x1,z1..x2,z2 矩形内按密度取格。位置哈希决定取舍——
     *  确定性(同参数同结果),测试与断点续建都friendly。 */
    private static List<BlockPos> scatterCells(int x1, int y, int z1, int x2, int z2, double density) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2);
        int az = Math.min(z1, z2), bz = Math.max(z1, z2);
        double d = Math.max(0.0, Math.min(1.0, density));
        List<BlockPos> out = new ArrayList<>();
        for (int x = ax; x <= bx; x++) {
            for (int z = az; z <= bz; z++) {
                long h = x * 341873128712L + z * 132897987541L;
                h ^= h >>> 27;
                if (Math.floorMod(h, 1000) < (long) (d * 1000)) {
                    out.add(new BlockPos(x, y, z));
                }
            }
        }
        if (out.isEmpty()) {
            out.add(new BlockPos(ax, y, az));   // 密度过低也至少给一格,别空手而归
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
            case "walls" -> {
                // 周界竖墙:只有四面墙柱,不含顶底面——盖房的正确原语(空心盒的
                // 顶底面会在地基上叠出第二层地板,把门洞下半埋进屋里)。
                int ax = Math.min(x1, req(x2, "x2")), bx = Math.max(x1, x2);
                int ay = Math.min(y1, req(y2, "y2")), by = Math.max(y1, y2);
                int az = Math.min(z1, req(z2, "z2")), bz = Math.max(z1, z2);
                for (int y = ay; y <= by; y++) {
                    for (int x = ax; x <= bx; x++) {
                        for (int z = az; z <= bz; z++) {
                            if (x != ax && x != bx && z != az && z != bz) {
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
            case "roof" -> {
                // 人字顶:底面矩形上逐层收分的实心层——长轴为脊,两侧各收 1/层,
                // 山墙天然填实,整片顶兼作天花板。
                int ax = Math.min(x1, req(x2, "x2")), bx = Math.max(x1, x2);
                int az = Math.min(z1, req(z2, "z2")), bz = Math.max(z1, z2);
                boolean shrinkX = (bx - ax) >= (bz - az);
                int layers = (shrinkX ? (bx - ax) : (bz - az)) / 2 + 1;
                for (int i = 0; i < layers; i++) {
                    int lx0 = shrinkX ? ax + i : ax, lx1 = shrinkX ? bx - i : bx;
                    int lz0 = shrinkX ? az : az + i, lz1 = shrinkX ? bz : bz - i;
                    if (lx0 > lx1 || lz0 > lz1) {
                        break;
                    }
                    for (int x = lx0; x <= lx1; x++) {
                        for (int z = lz0; z <= lz1; z++) {
                            add(out, x, y1 + i, z);
                        }
                    }
                }
            }
            default -> throw new IllegalArgumentException(
                    "shape must be box, walls, line, cylinder, sphere or roof");
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
