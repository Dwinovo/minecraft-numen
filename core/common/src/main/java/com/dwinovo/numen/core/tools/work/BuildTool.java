package com.dwinovo.numen.core.tools.work;

import static com.dwinovo.numen.task.TaskDispatch.ctx;
import static com.dwinovo.numen.task.TaskDispatch.setTask;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.build.BuildCopy;
import com.dwinovo.numen.core.build.BuildPalette;
import com.dwinovo.numen.core.build.BuildShapes;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.dwinovo.numen.core.task.build.ReplaceMode;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 建造:一串指令展开成目标格集,交给后台任务去砌。
 *
 * <p>原语只管几何,<b>不管风格</b>。屋顶怎么举架、脊用什么料、墙面怎么做凹凸,是建筑
 * 知识,住在 {@code building_design} 技能里;这里只提供"把格子放到哪儿、放成什么状态"。
 * 此前这里还住着一个 {@code roof} 算子,内置五种中式形制——那是把内容写进了机制:
 * 换一种风格就得改代码,而模型想要的第六种永远没有。
 */
public final class BuildTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private static final long MIN_TIMEOUT_TICKS = 60 * 20;
    private static final long TRAVEL_ALLOWANCE_TICKS = 40 * 20;
    /** 施工预计时长之上再留的余量(挪窝、翻层停顿、零进展重试都吃这笔)。 */
    private static final double TIMEOUT_SLACK = 1.6;

    /**
     * 施工时限:赴工地的行程 + 施工预计时长再留一截余量。
     *
     * <p>预计时长必须问施工层要,不能在这里另估一套。此前这里按"每格固定几刻"
     * 拍了个数,而生存最慢档实际是每格十刻——差二十倍,五百格的房子会在盖到一半
     * 时被判超时。两处各拍各的迟早再犯,所以公式只有一处真源。
     */
    public static long timeoutTicksFor(int cellCount, boolean consumeMaterials) {
        long build = com.dwinovo.numen.core.task.build.BuildOrder
                .estimatedTicks(cellCount, consumeMaterials);
        return Math.max(MIN_TIMEOUT_TICKS,
                TRAVEL_ALLOWANCE_TICKS + (long) (build * TIMEOUT_SLACK));
    }

    private record Args(List<OpSpec> ops, String mask) {}

    private record OpSpec(String op, String block_id, Boolean hollow,
                          Integer x, Integer y, Integer z,
                          Integer x1, Integer y1, Integer z1,
                          Integer x2, Integer y2, Integer z2,
                          Integer radius, Integer height,
                          List<String> rows, Map<String, String> legend,
                          Integer rotation, String mirror, Boolean include_air,
                          String mask) {}

    @Override
    public String name() {
        return "build";
    }

    @Override
    public String description() {
        return "Place blocks as ONE background task: an ordered `ops` stream, later ops overwrite "
                + "earlier cells, up to 16384 cells in one call. Load the building_design skill "
                + "(load_skill) BEFORE designing anything non-trivial — this tool is geometry only, "
                + "the skill is how to build well.\n"
                + "OPS: `layer` stamps a character grid — `rows` (first row at z1, each row runs +x "
                + "from x1) with a `legend` mapping each character to a block; ' ' and '.' mean "
                + "\"leave this cell alone\"; give y2 to repeat the same grid on every level y1..y2. "
                + "One grid IS a floor, a wall ring, an L-shaped footprint, a window pattern, a course "
                + "of roof tiles, scattered flowers — whatever you can draw. `set` one cell; `line` "
                + "two points (diagonals included); `cylinder` bottom-centre + radius + height; "
                + "`sphere` centre + radius (`hollow` for shells); `copy` an existing region "
                + "(x1,y1,z1..x2,y2,z2) to x,y,z with optional `rotation` 0/90/180/270 and `mirror` — "
                + "build one wing, mirror it, done.\n"
                + "BLOCKS: `block_id` takes the SAME syntax as /setblock, block state included: "
                + "\"oak_stairs[facing=east,half=top]\", \"oak_slab[type=top]\", \"oak_log[axis=x]\". "
                + "Doors, beds and tall flowers are placed from their LOWER half alone — the other "
                + "half appears with it. It may also be a weighted MIX — "
                + "\"stone_bricks*8, mossy_stone_bricks*2, cracked_stone_bricks\" — each cell picks one "
                + "deterministically; a flat single-colour surface is the number one thing that makes a "
                + "build look fake, so weather every large surface. `air` clears a cell.\n"
                + "MASK (per op, or once for the whole call): `carve` (default) builds through anything "
                + "and an `air` cell digs that cell out; `overwrite` builds through anything but leaves "
                + "air cells alone; `solid` only overwrites when the new block is a full block; `keep` "
                + "only builds into air, grass and other replaceable cells — use it to add to an "
                + "existing structure without touching it.\n"
                + "MATERIALS: in creative she builds freely. In survival every cell consumes 1 matching "
                + "item and the whole job is refused up front, PLACING NOTHING, if anything is short — "
                + "so put the ENTIRE building in ONE call; split it and the walls go up before anyone "
                + "discovers the roof material is missing. Treat a shortfall as an invitation to gather "
                + "together, never promise a survival build costs nothing. For prebuilt structure files "
                + "use the command build blueprint. BACKGROUND: after acceptance wait for task_finished; never "
                + "resend while running or after status=done.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("op", Map.of("type", "string",
                "enum", List.of("layer", "set", "line", "cylinder", "sphere", "copy")));
        props.put("block_id", Map.of("type", List.of("string", "null"),
                "description", "Block for this op, /setblock syntax with state — "
                        + "minecraft:stone_bricks, oak_stairs[facing=north], oak_slab[type=double]. "
                        + "May be a weighted mix (\"stone_bricks*8, mossy_stone_bricks\"). "
                        + "minecraft:air clears. layer: the fallback for characters the legend "
                        + "doesn't name. copy: not used."));
        props.put("rows", Map.of("type", List.of("array", "null"),
                "description", "layer: the grid, top row first. Row n sits at z1+n; character m in "
                        + "a row sits at x1+m. ' ' and '.' leave a cell alone.",
                "items", Map.of("type", "string")));
        props.put("legend", Map.of(
                "type", List.of("object", "null"),
                "description", "layer: character -> block (same syntax as block_id, mixes allowed).",
                "additionalProperties", Map.of("type", "string")));
        props.put("hollow", Map.of("type", List.of("boolean", "null"),
                "description", "cylinder/sphere: keep only the outer shell."));
        props.put("x", Map.of("type", List.of("integer", "null"),
                "description", "set: the cell. copy: destination corner (the lowest x,y,z of the result)."));
        props.put("y", Map.of("type", List.of("integer", "null"), "description", "set/copy — see x."));
        props.put("z", Map.of("type", List.of("integer", "null"), "description", "set/copy — see x."));
        props.put("x1", Map.of("type", List.of("integer", "null"),
                "description", "layer: the grid's x origin. line: first point. cylinder: bottom centre. "
                        + "sphere: centre. copy: one corner of the source region."));
        props.put("y1", Map.of("type", List.of("integer", "null"),
                "description", "The level this op works at — see x1."));
        props.put("z1", Map.of("type", List.of("integer", "null"), "description", "See x1."));
        props.put("x2", Map.of("type", List.of("integer", "null"),
                "description", "line: second point. copy: opposite corner of the source region."));
        props.put("y2", Map.of("type", List.of("integer", "null"),
                "description", "layer: repeat the grid on every level from y1 up to y2 (a 4-tall wall "
                        + "ring is ONE op). line/copy: see x2."));
        props.put("z2", Map.of("type", List.of("integer", "null"), "description", "See x2."));
        props.put("radius", Map.of("type", List.of("integer", "null"),
                "description", "cylinder/sphere only: radius in blocks."));
        props.put("height", Map.of("type", List.of("integer", "null"),
                "description", "cylinder only: height in blocks, upward from y1."));
        props.put("rotation", Map.of("type", List.of("integer", "null"),
                "description", "copy: turn the copy by 0, 90, 180 or 270 degrees clockwise.",
                "enum", List.of(0, 90, 180, 270)));
        props.put("mirror", enumSchema("copy: mirror the copy across an axis. Stair corners, door "
                + "hinges and bed heads flip correctly.", "none", "left_right", "front_back"));
        props.put("include_air", Map.of("type", List.of("boolean", "null"),
                "description", "copy: also copy the empty cells (so the destination gets hollowed "
                        + "out to match). Default false — only the blocks come across."));
        props.put("mask", maskSchema("This op only. Overrides the call-wide mask."));

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", props);
        item.put("required", List.of("op"));
        item.put("additionalProperties", false);

        Map<String, Object> ops = new LinkedHashMap<>();
        ops.put("type", "array");
        ops.put("description", "Ordered build instructions; later ops overwrite earlier cells.");
        ops.put("items", item);
        ops.put("minItems", 1);

        Map<String, Object> rootProps = new LinkedHashMap<>();
        rootProps.put("ops", ops);
        rootProps.put("mask", maskSchema("Default for every op in this call."));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", rootProps);
        root.put("required", List.of("ops"));
        root.put("additionalProperties", false);
        return root;
    }

    private static Map<String, Object> maskSchema(String who) {
        return enumSchema(who + " carve (default) = build through anything, and an air cell digs "
                + "that cell out. overwrite = build through anything, air cells left alone. "
                + "solid = only overwrite when the new block is a full block. "
                + "keep = only build into air and replaceable cells.",
                "carve", "overwrite", "solid", "keep");
    }

    private static Map<String, Object> enumSchema(String description, String... values) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", List.of("string", "null"));
        schema.put("description", description);
        List<Object> allowed = new ArrayList<>(List.of((Object[]) values));
        allowed.add(null);
        schema.put("enum", allowed);
        return schema;
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        if (parsed.ops() == null || parsed.ops().isEmpty()) {
            throw new IllegalArgumentException("ops must contain at least one instruction");
        }
        ServerLevel level = companion.serverLevel();
        int dropped = 0;
        List<BuildTaskRecord.Target> targets = new ArrayList<>();
        for (OpSpec op : parsed.ops()) {
            List<BuildTaskRecord.Target> cells;
            if ("copy".equals(op.op())) {
                BuildCopy.Result copied = expandCopy(op, level);
                cells = copied.targets();
                dropped += copied.dropped();
            } else {
                cells = expandOp(op);
            }
            ReplaceMode mask = readMask(op.mask());
            for (BuildTaskRecord.Target cell : cells) {
                targets.add(mask == null ? cell : cell.withMask(mask));
            }
        }
        // 单指令流:顺序即语义,后写覆盖先写,去重保留最后一笔
        Map<Long, BuildTaskRecord.Target> byPos = new LinkedHashMap<>();
        for (BuildTaskRecord.Target target : targets) {
            byPos.put(target.pos().asLong(), target);
        }
        targets = new ArrayList<>(byPos.values());
        if (targets.size() > BuildShapes.MAX_TOTAL_CELLS) {
            throw new IllegalArgumentException("this call resolves to " + targets.size()
                    + " cells, exceeding " + BuildShapes.MAX_TOTAL_CELLS + "; split it into multiple calls");
        }
        ReplaceMode callMask = readMask(parsed.mask());
        // 材料记账随能力画像:免耗材(创造)想建就建;否则消耗背包,开工前
        // 由任务预检并逐项报缺(见 BuildCompanionTask 的 checkMaterials)。
        boolean consume = !com.dwinovo.numen.core.WorkProfile.of(companion).freeMaterials();
        long timeout = timeoutTicksFor(targets.size(), consume);
        BuildTaskRecord record = new BuildTaskRecord(toolCallId,
                ctx(toolCallId, companion).deadline(timeout), targets,
                callMask == null ? ReplaceMode.REPLACE_EMPTY : callMask, consume);
        record.droppedAtLoad(dropped);
        setTask(companion, record, args, reply);
    }

    /** 模型写的档位名 → 让路档位;没写是 null(跟上一层的默认)。 */
    private static ReplaceMode readMask(String mask) {
        if (mask == null || mask.isBlank()) {
            return null;
        }
        return switch (mask.trim().toLowerCase()) {
            case "carve" -> ReplaceMode.REPLACE_EMPTY;
            case "overwrite" -> ReplaceMode.REPLACE_ANY;
            case "solid" -> ReplaceMode.REPLACE_SOLID;
            case "keep" -> ReplaceMode.DONT_REPLACE;
            default -> throw new IllegalArgumentException(
                    "unknown mask: " + mask + " (carve, overwrite, solid or keep)");
        };
    }

    /** 一条指令展开为目标格集。方块状态由 block_id 自己带(与 /setblock 同一套语法)。 */
    private static List<BuildTaskRecord.Target> expandOp(OpSpec spec) {
        if (spec == null || spec.op() == null) {
            throw new IllegalArgumentException("each op needs an op name");
        }
        if ("layer".equals(spec.op())) {
            return expandLayer(spec);
        }
        if (spec.block_id() == null) {
            throw new IllegalArgumentException("op " + spec.op() + " needs block_id");
        }
        BuildPalette palette = BuildPalette.parse(spec.block_id());
        if ("set".equals(spec.op())) {
            if (spec.x() == null || spec.y() == null || spec.z() == null) {
                throw new IllegalArgumentException("set needs x, y, z");
            }
            BlockPos pos = new BlockPos(spec.x(), spec.y(), spec.z());
            BuildTaskRecord.Target target = cell(palette, pos);
            // 分岔线:点名了方块状态就是图纸语义(照图直写);只写了方块名就是玩家动作
            // (物品原生落位,朝向随她视线,模组钩在物品放置上的转换照常跑)
            return List.of(spec.block_id().contains("[") ? target : target.asItemPlace());
        }
        if (spec.x1() == null || spec.y1() == null || spec.z1() == null) {
            throw new IllegalArgumentException("op " + spec.op() + " needs x1, y1, z1");
        }
        boolean hollow = spec.hollow() != null && spec.hollow();
        List<BlockPos> cells = BuildShapes.shapeCells(spec.op(), hollow,
                spec.x1(), spec.y1(), spec.z1(), spec.x2(), spec.y2(), spec.z2(),
                spec.radius(), spec.height());
        List<BuildTaskRecord.Target> out = new ArrayList<>(cells.size());
        for (BlockPos pos : cells) {
            out.add(cell(palette, pos));
        }
        return out;
    }

    /**
     * 一张字符网格。图例里每个字符自己一份调色板(可以是混合料),没写进图例的字符
     * 落到 {@code block_id} 上;两者都没有就是模型漏了图例,当场说清楚是哪个字符。
     */
    private static List<BuildTaskRecord.Target> expandLayer(OpSpec spec) {
        if (spec.x1() == null || spec.y1() == null || spec.z1() == null) {
            throw new IllegalArgumentException("layer needs x1, y1, z1");
        }
        if (spec.rows() == null || spec.rows().isEmpty()) {
            throw new IllegalArgumentException("layer needs rows");
        }
        Map<Character, BuildPalette> legend = new HashMap<>();
        if (spec.legend() != null) {
            for (Map.Entry<String, String> e : spec.legend().entrySet()) {
                if (e.getKey() == null || e.getKey().length() != 1) {
                    throw new IllegalArgumentException(
                            "legend keys are single characters, got \"" + e.getKey() + "\"");
                }
                legend.put(e.getKey().charAt(0), BuildPalette.parse(e.getValue()));
            }
        }
        BuildPalette fallback = spec.block_id() == null ? null : BuildPalette.parse(spec.block_id());
        int y2 = spec.y2() == null ? spec.y1() : spec.y2();
        List<BuildShapes.CharCell> cells = BuildShapes.layerCells(
                spec.x1(), spec.y1(), y2, spec.z1(), spec.rows());
        List<BuildTaskRecord.Target> out = new ArrayList<>(cells.size());
        for (BuildShapes.CharCell c : cells) {
            BuildPalette palette = legend.get(c.key());
            if (palette == null) {
                palette = fallback;
            }
            if (palette == null) {
                throw new IllegalArgumentException("layer: character '" + c.key()
                        + "' is not in the legend and there is no block_id to fall back on");
            }
            out.add(cell(palette, c.pos()));
        }
        return out;
    }

    private static BuildCopy.Result expandCopy(OpSpec spec, ServerLevel level) {
        if (spec.x1() == null || spec.y1() == null || spec.z1() == null
                || spec.x2() == null || spec.y2() == null || spec.z2() == null) {
            throw new IllegalArgumentException("copy needs the source corners x1,y1,z1 and x2,y2,z2");
        }
        if (spec.x() == null || spec.y() == null || spec.z() == null) {
            throw new IllegalArgumentException("copy needs the destination corner x, y, z");
        }
        Rotation rotation = switch (spec.rotation() == null ? 0 : spec.rotation()) {
            case 0 -> Rotation.NONE;
            case 90 -> Rotation.CLOCKWISE_90;
            case 180 -> Rotation.CLOCKWISE_180;
            case 270 -> Rotation.COUNTERCLOCKWISE_90;
            default -> throw new IllegalArgumentException(
                    "rotation must be 0, 90, 180 or 270, got " + spec.rotation());
        };
        Mirror mirror = switch (spec.mirror() == null ? "none" : spec.mirror().trim().toLowerCase()) {
            case "none" -> Mirror.NONE;
            case "left_right" -> Mirror.LEFT_RIGHT;
            case "front_back" -> Mirror.FRONT_BACK;
            default -> throw new IllegalArgumentException(
                    "mirror must be none, left_right or front_back, got " + spec.mirror());
        };
        return BuildCopy.copy(level,
                new BlockPos(spec.x1(), spec.y1(), spec.z1()),
                new BlockPos(spec.x2(), spec.y2(), spec.z2()),
                new BlockPos(spec.x(), spec.y(), spec.z()),
                rotation, mirror, spec.include_air() != null && spec.include_air());
    }

    /** 一格:调色板按位置取料,方块状态就是它自己带的那份。 */
    private static BuildTaskRecord.Target cell(BuildPalette palette, BlockPos pos) {
        BuildPalette.Entry e = palette.pick(pos);
        return new BuildTaskRecord.Target(e.state(), e.item(), pos, e.label());
    }
}
