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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;

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
    /**
     * 一次调用展开后的总格数上限。
     *
     * <p>此前是 4096,而一栋正常房子 5000~8000 格——等于逼着整栋建筑拆成好几次
     * 调用。生存模式的盘料只盘当前这一批,拆开就意味着墙已经砌好了才发现屋顶的
     * 料不够,留下半成品空壳。<b>"整栋一次规划"和这个上限是绑死的</b>,要求前者
     * 就必须给够后者。
     */
    private static final int MAX_TOTAL_CELLS = 16384;
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
        long build = com.dwinovo.numen.core.task.BuildCompanionTask
                .estimatedTicks(cellCount, consumeMaterials);
        return Math.max(MIN_TIMEOUT_TICKS,
                TRAVEL_ALLOWANCE_TICKS + (long) (build * TIMEOUT_SLACK));
    }

    private record Args(List<OpSpec> ops, Boolean replace_existing) {}
    private record OpSpec(String op, String block_id, Boolean hollow,
                          Integer x, Integer y, Integer z,
                          Integer x1, Integer y1, Integer z1,
                          Integer x2, Integer y2, Integer z2,
                          Integer radius, Integer height, Double density,
                          String facing, String axis, String half,
                          Integer overhang, String gable_block, String ridge_block,
                          String eave_block, String soffit_block,
                          String roof_shape, String roof_curve, Integer corner_lift,
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
                + "`roof` over base rect x1,y1,z1..x2,z2 — give a SLAB block as block_id (stone_brick_slab, "
                + "deepslate_tile_slab, spruce_slab...) and it lays a real tiled slope: the surface climbs HALF "
                + "a block per cell so there are no full-block steps, shallow at the eaves and steepening toward "
                + "the ridge. Slabs, not stairs, are what a good roof is made of. Then `roof_shape` (xuanshan / "
                + "wudian / xieshan / zuanjian / shed), `overhang` 1-4, `ridge_block` for the ridges that stand "
                + "proud of the tiles, `eave_block` for the drip band around the edge, `gable_block` for the end "
                + "walls, `soffit_block` for the underside, `corner_lift` for upturned corners, `hollow` "
                + "(default true, leaves attic space); `set_door` a full door at x,y,z (lower "
                + "half) with `facing`; `scatter` sprinkles the block over plane y1 within x1,z1..x2,z2 at "
                + "optional `density` 0-1 (default 0.25) — flowers, grass, mushrooms. "
                + "PALETTES: any block_id may name a weighted MIX instead of one block — "
                + "\"stone_bricks*8, mossy_stone_bricks*2, cracked_stone_bricks\" — and each cell picks one "
                + "deterministically. A flat single-colour surface is the number one thing that makes a build "
                + "look fake, so mix 10-20% of a weathered variant into every large wall, floor and roof. "
                + "block_id `air` CLEARS the cell (drops harvest normally). Liquids are NOT handled: leave "
                + "water and lava out of the ops — dig the basin and let the player pour it. "
                + "Compose whole buildings like stacking toy bricks in ONE call, "
                + "up to 16384 cells — enough for a whole house, so use it. She walks to the site once, then "
                + "works inside it, placing cells in batches "
                + "from the ground up. MATERIALS: in creative she builds freely. In survival every cell consumes "
                + "1 matching item and the whole job is refused up front, PLACING NOTHING, if anything is "
                + "short — so put the ENTIRE building (foundation, walls, roof, openings, details) in ONE call. "
                + "Split it across several calls and the walls go up before anyone discovers the roof material "
                + "is missing, leaving a half-built shell. Treat a shortfall as an invitation to gather "
                + "together, not an error, and never promise the player a survival build costs nothing. "
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
                "description", "Block for this op, e.g. minecraft:stone_bricks; minecraft:air clears. "
                        + "May be a weighted mix — \"stone_bricks*8, mossy_stone_bricks*2, "
                        + "cracked_stone_bricks\" — each cell picks one deterministically. Mix a weathered "
                        + "variant into every large surface; flat single-colour walls read as fake."));
        props.put("hollow", Map.of("type", "boolean",
                "description", "box/cylinder/sphere: keep only the outer shell. "
                        + "roof: leave the space under the slopes open (default true)."));
        props.put("overhang", Map.of("type", "integer",
                "description", "roof: eaves extending past the walls, 0-4. 1-2 reads far better than 0; "
                        + "East Asian roofs want 2-4."));
        props.put("roof_shape", enumSchema(
                "roof: xuanshan (alias gable) = two slopes with a ridge along the longer axis, the two ends "
                        + "closed by a bargeboard; the everyday roof, Chinese and Western alike. "
                        + "wudian (alias hip) = four slopes with four diagonal hip ridges and no gable ends — "
                        + "the highest rank, save it for the grandest hall on a site. "
                        + "zuanjian (alias pyramid) = the four slopes meet at a point, for a square footprint: "
                        + "towers, gazebos, pavilions. "
                        + "xieshan (alias half_hip) = four slopes below, a gable with two decorated ends above; "
                        + "second in rank and the most ornate silhouette. "
                        + "shed = a single slope one way, for lean-tos, porches and anything added on. "
                        + "Default xuanshan.",
                "xuanshan", "wudian", "xieshan", "zuanjian", "shed",
                "gable", "hip", "half_hip", "pyramid"));
        props.put("roof_curve", enumSchema(
                "roof: concave (default) = shallow at the eaves and steepening toward the ridge. This is the "
                        + "real profile of a tiled roof, East Asian or otherwise, and it is what stops a roof "
                        + "reading as a stepped pyramid. straight = constant 1:1 pitch; only ask for it when you "
                        + "specifically want a steep, hard, Gothic or Alpine silhouette.",
                "concave", "straight"));
        props.put("corner_lift", Map.of("type", "integer",
                "description", "roof: flick the four eave corners upward by this many blocks, 0-3. The "
                        + "upturned corner is the single most recognisable feature of East Asian roofs; "
                        + "leave it 0 for Western ones."));
        props.put("gable_block", Map.of("type", "string",
                "description", "roof: block (or mix) filling the end walls — the triangle under a xuanshan "
                        + "slope, or the decorated panel of a xieshan. Omit and they stay open."));
        props.put("ridge_block", Map.of("type", "string",
                "description", "roof: block (or mix) for the RIDGES, which stand proud of the tiles — the "
                        + "crest along the top, the four diagonal hip ridges of a wudian/zanjian, and the "
                        + "bargeboards of a xuanshan. Pick something that contrasts with the roof: this one "
                        + "line is most of what makes a roof read as designed rather than extruded."));
        props.put("eave_block", Map.of("type", "string",
                "description", "roof: block (or mix) for the outermost course only — the drip band running "
                        + "right around the edge. A single contrasting ring (copper, dark prismarine, a "
                        + "different wood) does an enormous amount of work for one block of width."));
        props.put("soffit_block", Map.of("type", "string",
                "description", "roof: block (or mix) for a second skin one block under the tiles, following "
                        + "the same slope. This is what the roof looks like FROM BELOW and from an open gable; "
                        + "without it the underside is bare half-slabs and the roof reads as a shell. Costs "
                        + "roughly as many cells again, so skip it on roofs nobody will stand under."));
        props.put("x", Map.of("type", "integer", "description", "set/set_door: cell x."));
        props.put("y", Map.of("type", "integer", "description", "set/set_door: cell y (door lower half)."));
        props.put("z", Map.of("type", "integer", "description", "set/set_door: cell z."));
        // 哪个 op 要哪几个坐标,此前一个字都没写,模型只能从散文里猜——实测就撞出
        // 过 "y2 is required"。每一条都点名用它的 op。
        props.put("x1", Map.of("type", "integer",
                "description", "First corner X. box/walls/line/scatter: the rect. cylinder: bottom "
                        + "centre. sphere: centre. roof: base rect."));
        props.put("y1", Map.of("type", "integer",
                "description", "First corner Y. cylinder: bottom level. scatter: the single plane it "
                        + "sprinkles on. roof: the level the eaves sit at."));
        props.put("z1", Map.of("type", "integer", "description", "First corner Z — see x1."));
        props.put("x2", Map.of("type", "integer",
                "description", "Opposite corner X. Needed by box/walls/line/scatter/roof."));
        props.put("y2", Map.of("type", "integer",
                "description", "Opposite corner Y — REQUIRED by box, walls and line, which are "
                        + "volumes. NOT used by scatter (one plane, y1) or roof (height is derived "
                        + "from the span)."));
        props.put("z2", Map.of("type", "integer",
                "description", "Opposite corner Z. Needed by box/walls/line/scatter/roof."));
        props.put("radius", Map.of("type", "integer",
                "description", "cylinder/sphere only: radius in blocks."));
        props.put("height", Map.of("type", "integer",
                "description", "cylinder only: height in blocks, upward from y1."));
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
        // 材料记账随能力画像:免耗材(创造)想建就建;否则消耗背包,开工前
        // 由任务预检并逐项报缺(见 BuildCompanionTask 的 checkMaterials)。
        boolean consume = !com.dwinovo.numen.core.task.WorkProfile.of(companion).freeMaterials();
        long timeout = timeoutTicksFor(targets.size(), consume);
        dispatchAsync(companion, new BuildTaskRecord(toolCallId,
                ctx(toolCallId, companion).deadline(timeout), targets, replaceExisting,
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
        BuildPalette palette = BuildPalette.parse(spec.block_id());
        boolean hollow = spec.hollow() != null && spec.hollow();
        if (spec.x1() == null || spec.y1() == null || spec.z1() == null) {
            throw new IllegalArgumentException("op " + spec.op() + " needs x1, y1, z1");
        }
        if ("roof".equals(spec.op())) {
            return expandRoof(spec, palette);
        }
        List<BlockPos> cells = "scatter".equals(spec.op())
                ? scatterCells(spec.x1(), spec.y1(), spec.z1(),
                        req(spec.x2(), "x2"), req(spec.z2(), "z2"),
                        spec.density() == null ? 0.25 : spec.density())
                : shapeCells(spec.op(), hollow, spec.x1(), spec.y1(), spec.z1(),
                        spec.x2(), spec.y2(), spec.z2(), spec.radius(), spec.height());
        List<BuildTaskRecord.Target> out = new ArrayList<>(cells.size());
        for (BlockPos pos : cells) {
            BuildPalette.Entry e = palette.pick(pos);
            out.add(new BuildTaskRecord.Target(e.block(), e.item(), pos, e.label(), null, null, null));
        }
        return out;
    }

    /**
     * 屋顶:半砖三态砌出的连续坡面 + 垂脊 + 正脊 + 檐口。
     *
     * <p>做法是从四栋手工中式建筑(悬山、歇山、庑殿、攒尖)里逐格量出来的,不是推的。
     * 三条量出来的事实决定了整个引擎:
     *
     * <ol>
     *   <li><b>坡面主料是半砖,不是楼梯。</b>四栋里半砖比楼梯多 6~43 倍,而且
     *       bottom/top/double 三态的占比四栋几乎一致(37/27/35)。砌法是同一个
     *       高度上<b>下半砖当踏面、双层砖当立面</b>交替,顶面每格升半格,连一级
     *       整块的台阶都没有。此前这里是"下半砖 + 上半砖",顶面轮廓一样,但上半砖
     *       底下那半格是空的——从底下看是一排悬空的砖。</li>
     *   <li><b>举架量的是每格的抬升,不是每层的收分。</b>量出来的顶面高度序列是
     *       每格抬一个半砖(五举),接近脊时抬两个(十举),平均 1.2,即屋顶高
     *       ≈ 0.6 × 半跨。所以这里<b>按每格到檐口的距离直接定高度</b>,不再逐层
     *       收分——四条垂脊(到两边檐口等距的那条对角线)也就自然落出来了,而逐层
     *       收分的写法必须另外拼角,拼一次错一次。</li>
     *   <li><b>脊高出屋面,不齐平。</b>四栋的脊都是异色实心块压在瓦面之上:庑殿、
     *       攒尖的四条垂脊是一格宽的正 45° 对角线,悬山两端是外探的博风板,正脊
     *       再高出两格。此前脊是嵌进最后一层里的,所以四坡顶怎么调都不像中式。</li>
     * </ol>
     *
     * <p>结构定死,换料换风格:石砖 + 铜是中式,深板岩 + 深色橡木是哥特,陶瓦是
     * 地中海。所以每一个结构件都单独收一个 palette 参数。
     */
    private static List<BuildTaskRecord.Target> expandRoof(OpSpec spec, BuildPalette palette) {
        return roofCells(spec.x1(), spec.y1(), spec.z1(), req(spec.x2(), "x2"), req(spec.z2(), "z2"),
                spec.block_id(), spec.roof_shape(), spec.roof_curve(),
                spec.overhang(), spec.corner_lift(),
                spec.gable_block(), spec.ridge_block(), spec.eave_block(), spec.soffit_block(),
                spec.hollow());
    }

    /** 屋顶展开(公开静态,测试直接验几何)。 */
    public static List<BuildTaskRecord.Target> roofCells(
            int x1, int y1, int z1, int x2, int z2,
            String material, String shapeArg, String curveArg,
            Integer overhangArg, Integer cornerLiftArg,
            String gableSpec, String ridgeSpec, String eaveSpec, String soffitSpec,
            Boolean hollowArg) {
        BuildPalette palette = BuildPalette.parse(material);
        int ax = Math.min(x1, x2);
        int bx = Math.max(x1, x2);
        int az = Math.min(z1, z2);
        int bz = Math.max(z1, z2);
        int y0 = y1;
        int overhang = overhangArg == null ? 0 : Math.max(0, Math.min(4, overhangArg));
        ax -= overhang;
        bx += overhang;
        az -= overhang;
        bz += overhang;

        String shape = shapeArg == null ? "xuanshan" : shapeArg;
        // 举架曲线是默认。直坡要明确要求——量过的四栋没有一栋是直的,而直坡正是
        // "看着像金字塔"的那个样子。
        boolean concave = !"straight".equals(curveArg);
        int cornerLift = cornerLiftArg == null ? 0 : Math.max(0, Math.min(3, cornerLiftArg));
        BuildPalette gable = gableSpec == null ? null : BuildPalette.parse(gableSpec);
        BuildPalette ridge = ridgeSpec == null ? null : BuildPalette.parse(ridgeSpec);
        BuildPalette eave = eaveSpec == null ? null : BuildPalette.parse(eaveSpec);
        BuildPalette soffit = soffitSpec == null ? null : BuildPalette.parse(soffitSpec);
        boolean hollow = hollowArg == null || hollowArg;

        boolean hip = switch (shape) {
            case "wudian", "hip", "zuanjian", "zanjian", "pyramid" -> true;
            default -> false;
        };
        boolean xieshan = "xieshan".equals(shape) || "half_hip".equals(shape);
        boolean shed = "shed".equals(shape);
        if (!hip && !xieshan && !shed && !"xuanshan".equals(shape) && !"gable".equals(shape)) {
            throw new IllegalArgumentException("roof shape must be xuanshan/gable, wudian/hip, "
                    + "xieshan/half_hip, zuanjian/pyramid or shed");
        }

        boolean ridgeAlongX = (bx - ax) >= (bz - az);
        int slopeSpan = ridgeAlongX ? (bz - az) : (bx - ax);
        // 单坡一整片倒向一侧,走全跨;其余是两坡对开,各走半跨
        int reach = shed ? slopeSpan : slopeSpan / 2;
        int[] h = surfaceHalves(reach, concave);
        // 歇山下段四坡约占四成,上段转双坡带山花
        int brk = xieshan ? Math.max(1, reach * 2 / 5) : 0;

        Map<Long, BuildTaskRecord.Target> out = new LinkedHashMap<>();
        for (int x = ax; x <= bx; x++) {
            for (int z = az; z <= bz; z++) {
                int dSlope = ridgeAlongX ? Math.min(z - az, bz - z) : Math.min(x - ax, bx - x);
                int dEnd = ridgeAlongX ? Math.min(x - ax, bx - x) : Math.min(z - az, bz - z);
                int d;
                if (shed) {
                    d = ridgeAlongX ? (z - az) : (x - ax);
                } else if (hip) {
                    d = Math.min(dSlope, dEnd);
                } else if (xieshan) {
                    d = dEnd < brk ? Math.min(dSlope, dEnd) : dSlope;
                } else {
                    d = dSlope;
                }
                d = Math.min(d, h.length - 1);
                int halves = h[d];

                boolean lip = d == 0;
                skinCell(out, lip && eave != null ? eave : palette, x, z, y0, halves, lip);
                if (soffit != null && !lip) {
                    soffitCell(out, soffit, x, z, y0, halves);
                }
                if (!hollow) {
                    fillUnder(out, palette, x, z, y0, halves);
                }

                // 山花:悬山两端的三角墙;歇山在腰线那一列,坡面在那里有个竖直落差。
                // 必须在压脊之前填,否则脊会被这里的实心块盖掉。
                if (gable != null && !shed && !hip) {
                    int faceTop = (halves - 1) / 2;
                    int faceBottom = xieshan
                            ? (dEnd == brk ? h[Math.min(dSlope, brk - 1)] / 2 : faceTop)
                            : (dEnd == 0 ? 0 : faceTop);
                    for (int gy = faceBottom; gy < faceTop; gy++) {
                        putSolidAt(out, gable, new BlockPos(x, y0 + gy, z), false);
                    }
                }

                BuildPalette spine = ridge != null ? ridge : palette;
                // 垂脊:四坡到两边檐口等距的那条正 45° 对角线。悬山没有垂脊,两端
                // 改作博风板——同样是异色一条,只是走在山面的边缘。
                boolean hipSpine = (hip || (xieshan && dEnd < brk)) && dSlope == dEnd && d < reach;
                boolean barge = !hip && !shed && dEnd == 0;
                if (hipSpine || barge) {
                    putProud(out, spine, x, z, y0, halves, 1);
                }
                if (!shed && d >= reach) {
                    putProud(out, spine, x, z, y0, halves, 2);
                }
            }
        }
        if (cornerLift > 0) {
            liftEaveCorners(out, ridge != null ? ridge : palette, ax, bx, az, bz, y0, cornerLift);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("roof resolved to zero cells");
        }
        return new ArrayList<>(out.values());
    }

    /**
     * 举架:从檐口往脊,每格屋面的顶面到多高(以<b>半砖</b>计)。
     *
     * <p>清式《工程做法》的举架把东亚屋面那条凹曲线量化成逐步加陡的举高比:檐口起于
     * "五举"(0.5),脊步收到"十举"(1.0)。翻成方块就是<b>每格抬升几个半砖</b>:
     * 五举抬一个,十举抬两个。
     *
     * <p>存档里量出来的顶面序列(悬山,半跨 13)是
     * {@code 4,4,5,6,7,8,9,10,11,12,14,15,17}——前段每格抬一,近脊抬二,平均 1.2,
     * 也就是屋顶高 ≈ 0.6 × 半跨。下面这条 {@code 1 + 0.6f²} 的抬升量累出来正是这个
     * 数,而且中段会自然出现一二相间,和量到的一样。
     *
     * <p>第一格单独低一档:那是檐口的薄唇({@code skinCell} 的 thin 分支)。存档里
     * 四栋都用上半砖收边,檐口因此是薄的、利的,不是一刀切齐的墩子。
     */
    private static int[] surfaceHalves(int reach, boolean concave) {
        int n = Math.max(1, reach) + 1;
        int[] h = new int[n];
        double acc = 1.0;
        int cur = 1;
        for (int k = 0; k < n; k++) {
            double f = reach <= 0 ? 1.0 : (double) k / reach;
            acc += concave ? 1.0 + 0.6 * f * f : 2.0;
            cur = Math.max(cur + 1, (int) Math.round(acc));
            h[k] = cur;
        }
        return h;
    }

    /**
     * 屋面一格。顶面高度以半砖计:<b>偶数用双层砖</b>(占满整格,当立面),
     * <b>奇数用下半砖</b>(占下半格,当踏面)。两者交替,顶面每格升半格,底下不留空。
     *
     * <p>{@code thin} 是檐口那一格,改用上半砖:顶面同高但只有半格厚,檐口收成一道
     * 薄边。存档里四栋的檐口都是这么收的。
     */
    private static void skinCell(Map<Long, BuildTaskRecord.Target> out, BuildPalette pal,
                                 int x, int z, int y0, int halves, boolean thin) {
        BlockPos pos = new BlockPos(x, y0 + (halves - 1) / 2, z);
        BuildPalette.Entry e = pal.pick(pos);
        Block slab = slabFor(e.block());
        if (slab == null) {
            // 给的料推不出半砖(原木、玻璃之类),照整块砌:糙一点,但不漏
            out.put(pos.asLong(), new BuildTaskRecord.Target(e.block(), e.item(), pos, e.label(),
                    null, null, null));
            return;
        }
        putSlabAt(out, pos, slab,
                thin ? SlabType.TOP : (halves % 2 == 1 ? SlabType.BOTTOM : SlabType.DOUBLE));
    }

    /**
     * 望板:瓦面底下的第二层皮,顶面恒比瓦面低一格,而且<b>实心一格厚</b>——半格处用
     * "上半砖 + 下半砖"两块拼齐。
     *
     * <p>存档里四栋都有这一层(瓦面石砖、望板木)。没有它,从屋里往上看就是一排半砖
     * 的背面和一格格的空档,屋顶是个壳;有了它,屋里屋外都是一道光滑斜面。
     */
    private static void soffitCell(Map<Long, BuildTaskRecord.Target> out, BuildPalette pal,
                                   int x, int z, int y0, int halves) {
        int s = halves - 2;
        if (s < 1) {
            return;
        }
        int y = y0 + (s - 1) / 2;
        BuildPalette.Entry e = pal.pick(new BlockPos(x, y, z));
        Block slab = slabFor(e.block());
        if (slab == null) {
            putSolidAt(out, pal, new BlockPos(x, y, z), false);
            return;
        }
        if (s % 2 == 0) {
            putSlabAt(out, new BlockPos(x, y, z), slab, SlabType.DOUBLE);
        } else {
            putSlabAt(out, new BlockPos(x, y, z), slab, SlabType.BOTTOM);
            putSlabAt(out, new BlockPos(x, y - 1, z), slab, SlabType.TOP);
        }
    }

    /**
     * 脊:压在屋面之上的异色实心块,{@code n} 格高。
     *
     * <p>{@code y0 + halves / 2} 这一格正好骑在顶面上:顶面落在整格时(halves 偶)
     * 它整格露在外面,落在半格时(halves 奇)它盖掉那块半砖、把顶面抬到整格。所以
     * 坡面走到哪一档,脊都是明确高出来的一条,不会时隐时现。
     */
    private static void putProud(Map<Long, BuildTaskRecord.Target> out, BuildPalette pal,
                                 int x, int z, int y0, int halves, int n) {
        for (int k = 0; k < n; k++) {
            BlockPos pos = new BlockPos(x, y0 + halves / 2 + k, z);
            BuildPalette.Entry e = pal.pick(pos);
            if (e.block() instanceof SlabBlock) {
                // 脊料常常也是半砖(没给 ridge_block 时就直接是屋面主料)。半砖的
                // 默认状态是下半砖,照放就是一条悬空的半砖;脊必须实心,所以铺双层。
                putSlabAt(out, pos, e.block(), SlabType.DOUBLE);
            } else {
                putSolidAt(out, pal, pos, true);
            }
        }
    }

    /** 不留阁楼时把屋面底下填实。 */
    private static void fillUnder(Map<Long, BuildTaskRecord.Target> out, BuildPalette pal,
                                  int x, int z, int y0, int halves) {
        for (int y = y0; y < y0 + (halves - 1) / 2; y++) {
            putSolidAt(out, pal, new BlockPos(x, y, z), false);
        }
    }

    /**
     * 檐角起翘(飞檐):四个檐角往上挑起来。
     *
     * <p>这是东亚屋顶最认得出的一笔——尖角一挑,整片屋面就"活"了。做法是在檐口那一层
     * 的四角往上叠几格,并把紧挨着角的两格也抬一格,让翘起来的是一条弧,不是四根柱子。
     * 用脊料,因为翘角本来就是垂脊的末端。
     */
    private static void liftEaveCorners(Map<Long, BuildTaskRecord.Target> out, BuildPalette palette,
                                        int ax, int bx, int az, int bz, int y0, int lift) {
        int[][] corners = {{ax, az}, {bx, az}, {ax, bz}, {bx, bz}};
        for (int[] c : corners) {
            int cx = c[0];
            int cz = c[1];
            for (int k = 1; k <= lift; k++) {
                putSolidAt(out, palette, new BlockPos(cx, y0 + k, cz), true);
            }
            if (lift >= 2) {
                int inx = cx == ax ? 1 : -1;
                int inz = cz == az ? 1 : -1;
                putSolidAt(out, palette, new BlockPos(cx + inx, y0 + 1, cz), false);
                putSolidAt(out, palette, new BlockPos(cx, y0 + 1, cz + inz), false);
            }
        }
    }

    /**
     * 从给的料推它的半砖——屋面主料是半砖,而模型给过来的可能是整块、楼梯或木板。
     *
     * <p>{@code stone_bricks → stone_brick_slab} 这类要去掉复数才对得上,所以按几条
     * 命名规律依次试,而不是硬拼一个后缀。推不出就退回整块砌。
     */
    private static Block slabFor(Block block) {
        if (block instanceof SlabBlock) {
            return block;
        }
        var id = BuiltInRegistries.BLOCK.getKey(block);
        if (id == null) {
            return null;
        }
        String p = id.getPath();
        List<String> tries = new ArrayList<>();
        for (String suf : new String[]{"_stairs", "_planks", "_wall"}) {
            if (p.endsWith(suf)) {
                tries.add(p.substring(0, p.length() - suf.length()) + "_slab");
            }
        }
        if (p.endsWith("s")) {
            tries.add(p.substring(0, p.length() - 1) + "_slab");
        }
        tries.add(p + "_slab");
        for (String t : tries) {
            Block b = BuiltInRegistries.BLOCK.getValue(
                    net.minecraft.resources.Identifier.fromNamespaceAndPath(id.getNamespace(), t));
            if (b instanceof SlabBlock) {
                return b;
            }
        }
        return null;
    }

    private static void putSlabAt(Map<Long, BuildTaskRecord.Target> out, BlockPos pos,
                                  Block slab, SlabType type) {
        BlockState state = slab.defaultBlockState().setValue(SlabBlock.TYPE, type);
        // topHalf 是状态的镜像,不是自由字段:双层砖没有上下之分,那里必须是 null,
        // 否则 Target 自己的一致性校验当场拒收(它按同一条规则反推)。
        Boolean topHalf = type == SlabType.DOUBLE ? null : type == SlabType.TOP;
        out.put(pos.asLong(), new BuildTaskRecord.Target(state, slab.asItem(), pos,
                BuiltInRegistries.BLOCK.getKey(slab).getPath(), null, null, topHalf));
    }

    private static void putStairAt(Map<Long, BuildTaskRecord.Target> out, BuildPalette palette,
                                   BlockPos pos, Direction facing) {
        BuildPalette.Entry e = palette.pick(pos);
        if (e.block() instanceof net.minecraft.world.level.block.StairBlock) {
            BlockState state = e.block().defaultBlockState()
                    .setValue(net.minecraft.world.level.block.StairBlock.FACING, facing);
            out.put(pos.asLong(), new BuildTaskRecord.Target(state, e.item(), pos, e.label(),
                    facing, null, null));
        } else {
            out.put(pos.asLong(),
                    new BuildTaskRecord.Target(e.block(), e.item(), pos, e.label(), null, null, null));
        }
    }

    private static void putSolidAt(Map<Long, BuildTaskRecord.Target> out, BuildPalette palette,
                                   BlockPos pos, boolean overwrite) {
        BuildPalette.Entry e = palette.pick(pos);
        BuildTaskRecord.Target t =
                new BuildTaskRecord.Target(e.block(), e.item(), pos, e.label(), null, null, null);
        if (overwrite) {
            out.put(pos.asLong(), t);
        } else {
            out.putIfAbsent(pos.asLong(), t);
        }
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
            // 与调色板同一处解析:按方块查、air 与水合法、岩浆挡掉。此前这里另抄
            // 了一遍 ToolArgs.parseItem + if(AIR) 的写法,而那个 AIR 分支永远到不了。
            BuildPalette.Entry resolved = BuildPalette.resolve(spec.block_id(), 1);
            Item item = resolved.item();
            net.minecraft.world.level.block.Block block = resolved.block();
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
            // 注:roof 不在此处——屋顶要逐格决定楼梯朝向,产出的是方块状态而不只是
            // 位置,走 roofCells。
            default -> throw new IllegalArgumentException(
                    "shape must be box, walls, line, cylinder or sphere");
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
