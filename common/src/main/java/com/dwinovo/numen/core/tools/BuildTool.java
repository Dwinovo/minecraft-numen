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
                          String roof_shape, String roof_curve, Integer corner_lift,
                          Integer ridge_offset,
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
                + "`roof` over base rect x1,y1,z1..x2,z2 — give a STAIRS block as block_id and it lays real "
                + "sloped courses (ridge along the longer axis, stair facing handled for you), plus optional "
                + "`overhang` 0-3 eaves, `gable_block` to fill the triangular ends, `ridge_block` to cap the "
                + "peak, `hollow` (default true, leaves attic space); `set_door` a full door at x,y,z (lower "
                + "half) with `facing`; `scatter` sprinkles the block over plane y1 within x1,z1..x2,z2 at "
                + "optional `density` 0-1 (default 0.25) — flowers, grass, mushrooms. "
                + "PALETTES: any block_id may name a weighted MIX instead of one block — "
                + "\"stone_bricks*8, mossy_stone_bricks*2, cracked_stone_bricks\" — and each cell picks one "
                + "deterministically. A flat single-colour surface is the number one thing that makes a build "
                + "look fake, so mix 10-20% of a weathered variant into every large wall, floor and roof. "
                + "block_id minecraft:air CLEARS (drops harvest normally; "
                + "liquids always left untouched). Compose whole buildings like stacking toy bricks in ONE call, "
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
                "roof: gable = two slopes with a ridge along the longer axis (Western gable, Chinese "
                        + "xuanshan/yingshan). hip = four slopes, no gable ends (Western hip, Chinese wudian, "
                        + "the highest rank — save it for the grandest hall on a site). pyramid = hip on a "
                        + "square footprint meeting at a point (towers, gazebos, Chinese zanjian). "
                        + "half_hip = four slopes below, a gable with two gable-ends above (Chinese xieshan, "
                        + "Western Dutch gable) — second in rank and the most ornate silhouette. "
                        + "shed = a single slope one way (lean-tos, porches, factory wings, anything added on). "
                        + "saltbox = asymmetric gable, ridge off-centre, one short steep slope and one long "
                        + "shallow one — reads as a house that was extended. Default gable.",
                "gable", "hip", "pyramid", "half_hip", "shed", "saltbox"));
        props.put("ridge_offset", Map.of("type", "integer",
                "description", "saltbox only: how far the ridge sits from the low edge, in blocks. "
                        + "Default is a third of the span; smaller makes the front slope steeper."));
        props.put("roof_curve", enumSchema(
                "roof: straight = constant pitch, the Western default. concave = shallow at the eaves and "
                        + "steepening toward the ridge, which is what makes East Asian roofs read as curved "
                        + "rather than as a stepped pyramid. Use concave for any Chinese, Japanese or Korean "
                        + "building. Default straight.",
                "straight", "concave"));
        props.put("corner_lift", Map.of("type", "integer",
                "description", "roof: flick the four eave corners upward by this many blocks, 0-3. The "
                        + "upturned corner is the single most recognisable feature of East Asian roofs; "
                        + "leave it 0 for Western ones."));
        props.put("gable_block", Map.of("type", "string",
                "description", "roof: block (or mix) filling the triangular ends. Omit and they stay open."));
        props.put("ridge_block", Map.of("type", "string",
                "description", "roof: block (or mix) capping the peak line, e.g. a slab."));
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
     * 屋顶:楼梯砌的斜面 + 出檐 + 山墙 + 屋脊。
     *
     * <p>此前是"底面矩形逐层收分的实心层"——那不是屋顶,是阶梯状的实心土堆,
     * 而且收分收在长轴上,屋脊横着架在短边方向,越长的房子越离谱。生成的建筑
     * "一眼假",一多半是顶上这一坨。
     *
     * <p>现在按真实砌法:<b>屋脊沿长轴</b>,坡向短轴;斜面用楼梯方块,朝向指向
     * 上坡方向(即指向屋脊);两端三角山墙填实;脊线单独压一层;可出檐。
     *
     * <p>楼梯朝向这条是从真实手工建筑的图纸里量出来的,不是推的:同一片斜面
     * 每升高一格、坡向坐标加一格,而 facing 始终不变且指向屋脊。
     */
    private static List<BuildTaskRecord.Target> expandRoof(OpSpec spec, BuildPalette palette) {
        return roofCells(spec.x1(), spec.y1(), spec.z1(), req(spec.x2(), "x2"), req(spec.z2(), "z2"),
                spec.block_id(), spec.roof_shape(), spec.roof_curve(),
                spec.overhang(), spec.corner_lift(), spec.ridge_offset(),
                spec.gable_block(), spec.ridge_block(), spec.hollow());
    }

    /** 屋顶展开(公开静态,测试直接验几何与朝向)。 */
    public static List<BuildTaskRecord.Target> roofCells(
            int x1, int y1, int z1, int x2, int z2,
            String material, String shapeArg, String curveArg,
            Integer overhangArg, Integer cornerLiftArg, Integer ridgeOffsetArg,
            String gableSpec, String ridgeSpec, Boolean hollowArg) {
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

        String shape = shapeArg == null ? "gable" : shapeArg;
        boolean concave = "concave".equals(curveArg);
        int cornerLift = cornerLiftArg == null ? 0 : Math.max(0, Math.min(3, cornerLiftArg));
        BuildPalette gable = gableSpec == null ? null : BuildPalette.parse(gableSpec);
        BuildPalette ridge = ridgeSpec == null ? null : BuildPalette.parse(ridgeSpec);
        boolean hollow = hollowArg == null || hollowArg;   // 屋顶默认中空,给阁楼留地方

        Map<Long, BuildTaskRecord.Target> out = new LinkedHashMap<>();
        switch (shape) {
            case "hip", "pyramid" -> hipCourses(out, palette, ridge, ax, bx, az, bz, y0,
                    concave, hollow);
            case "gable" -> gableCourses(out, palette, gable, ridge, ax, bx, az, bz, y0,
                    concave, hollow);
            case "half_hip" -> halfHipCourses(out, palette, gable, ridge, ax, bx, az, bz, y0,
                    concave, hollow);
            case "shed" -> shedCourses(out, palette, gable, ax, bx, az, bz, y0, concave);
            case "saltbox" -> saltboxCourses(out, palette, gable, ridge, ax, bx, az, bz, y0,
                    ridgeOffsetArg, hollow);
            default -> throw new IllegalArgumentException(
                    "roof shape must be gable, hip, pyramid, half_hip, shed or saltbox");
        }
        if (cornerLift > 0) {
            liftEaveCorners(out, palette, ax, bx, az, bz, y0, cornerLift);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("roof resolved to zero cells");
        }
        return new ArrayList<>(out.values());
    }

    /**
     * 每一层坡面往里收多少格。
     *
     * <p>直线屋面每层收 1,坡度恒为 1:1——这就是"看起来又硬又像金字塔"的来源。
     * 东亚屋面不是直的:<b>檐口缓、越往脊越陡</b>,凹成一条曲线。清式《工程做法》
     * 的举架把这条曲线量化成了每步的举高比:一律从檐口的 0.5 起("五举拿头"),
     * 逐步加陡,脊步不超过 0.9~1.0。
     *
     * <p>翻成方块就是每层的收分不同:坡度 0.5 时一层收两格,接近 1.0 时一层收
     * 一格。收分序列一算,曲线就出来了。
     */
    private static int[] courseInsets(int halfSpan, boolean concave) {
        List<Integer> insets = new ArrayList<>();
        if (!concave) {
            for (int i = 0; i <= halfSpan; i++) {
                insets.add(i);
            }
        } else {
            double inset = 0.0;
            int last = -1;
            while (inset <= halfSpan + 0.001) {
                int step = (int) Math.round(inset);
                if (step > last) {
                    insets.add(step);
                    last = step;
                }
                double f = halfSpan <= 0 ? 1.0 : Math.min(1.0, inset / halfSpan);
                inset += 1.0 / (0.5 + 0.5 * f);   // 五举 → 十举,步长 2.0 → 1.0
            }
        }
        int[] out = new int[insets.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = insets.get(i);
        }
        return out;
    }

    /** 双坡(悬山/硬山/gable):脊沿长轴,坡向短轴,两端三角山墙可填。 */
    private static void gableCourses(Map<Long, BuildTaskRecord.Target> out, BuildPalette palette,
                                     BuildPalette gable, BuildPalette ridge,
                                     int ax, int bx, int az, int bz, int y0,
                                     boolean concave, boolean hollow) {
        boolean ridgeAlongX = (bx - ax) >= (bz - az);
        int spanLo = ridgeAlongX ? az : ax;
        int spanHi = ridgeAlongX ? bz : bx;
        int runLo = ridgeAlongX ? ax : az;
        int runHi = ridgeAlongX ? bx : bz;
        int[] insets = courseInsets((spanHi - spanLo) / 2, concave);
        for (int c = 0; c < insets.length; c++) {
            int lo = spanLo + insets[c];
            int hi = spanHi - insets[c];
            if (lo > hi) {
                break;
            }
            int y = y0 + c;
            boolean top = c == insets.length - 1;
            for (int run = runLo; run <= runHi; run++) {
                putStair(out, palette, ridgeAlongX, run, y, lo,
                        ridgeAlongX ? Direction.SOUTH : Direction.EAST);
                if (hi != lo) {
                    putStair(out, palette, ridgeAlongX, run, y, hi,
                            ridgeAlongX ? Direction.NORTH : Direction.WEST);
                }
                if (!hollow) {
                    for (int mid = lo + 1; mid < hi; mid++) {
                        putSolid(out, palette, ridgeAlongX, run, y, mid);
                    }
                }
                if (top && ridge != null) {
                    for (int mid = lo; mid <= hi; mid++) {
                        putSolid(out, ridge, ridgeAlongX, run, y + 1, mid);
                    }
                }
            }
            if (gable != null) {
                for (int mid = lo; mid <= hi; mid++) {
                    for (int gy = y0; gy < y; gy++) {
                        putSolid(out, gable, ridgeAlongX, runLo, gy, mid);
                        putSolid(out, gable, ridgeAlongX, runHi, gy, mid);
                    }
                }
            }
        }
    }

    /**
     * 四坡(庑殿/hip;方形底面即攒尖 pyramid):四面都收,没有山墙。
     *
     * <p>四条戗脊交汇的角上两个坡面撞在一起,楼梯朝哪边都不对——那里放实心块,
     * 正好读成垂脊。
     */
    private static void hipCourses(Map<Long, BuildTaskRecord.Target> out, BuildPalette palette,
                                   BuildPalette ridge, int ax, int bx, int az, int bz, int y0,
                                   boolean concave, boolean hollow) {
        int halfSpan = Math.min(bx - ax, bz - az) / 2;
        int[] insets = courseInsets(halfSpan, concave);
        for (int c = 0; c < insets.length; c++) {
            if (!hipCourse(out, palette, ridge, ax, bx, az, bz, y0, insets[c], c, hollow)) {
                break;
            }
            if (c == insets.length - 1 && ridge != null) {
                int i = insets[c];
                for (int x = ax + i; x <= bx - i; x++) {
                    for (int z = az + i; z <= bz - i; z++) {
                        putSolidAt(out, ridge, new BlockPos(x, y0 + c + 1, z), false);
                    }
                }
            }
        }
    }

    /** 四坡的一层。@return false = 已经收没了 */
    private static boolean hipCourse(Map<Long, BuildTaskRecord.Target> out, BuildPalette palette,
                                     BuildPalette ridge, int ax, int bx, int az, int bz,
                                     int y0, int inset, int course, boolean hollow) {
        int lx = ax + inset;
        int hx = bx - inset;
        int lz = az + inset;
        int hz = bz - inset;
        if (lx > hx || lz > hz) {
            return false;
        }
        int y = y0 + course;
        for (int x = lx; x <= hx; x++) {
            putStairAt(out, palette, new BlockPos(x, y, lz), Direction.SOUTH);
            putStairAt(out, palette, new BlockPos(x, y, hz), Direction.NORTH);
        }
        for (int z = lz; z <= hz; z++) {
            putStairAt(out, palette, new BlockPos(lx, y, z), Direction.EAST);
            putStairAt(out, palette, new BlockPos(hx, y, z), Direction.WEST);
        }
        // 四角:两个坡面在这里相撞,楼梯朝哪边都是错的(没有斜向楼梯)。给了
        // ridge_block 就用它当垂脊——那正是真实屋顶在这条棱上的做法;没给就退回
        // 主材,并让朝向跟着 z 向坡走,至少四个角一致,不会各朝各的。
        BuildPalette hipPal = ridge != null ? ridge : palette;
        putHipCorner(out, hipPal, new BlockPos(lx, y, lz), Direction.SOUTH);
        putHipCorner(out, hipPal, new BlockPos(hx, y, lz), Direction.SOUTH);
        putHipCorner(out, hipPal, new BlockPos(lx, y, hz), Direction.NORTH);
        putHipCorner(out, hipPal, new BlockPos(hx, y, hz), Direction.NORTH);
        if (!hollow) {
            for (int x = lx + 1; x < hx; x++) {
                for (int z = lz + 1; z < hz; z++) {
                    putSolidAt(out, palette, new BlockPos(x, y, z), false);
                }
            }
        }
        return true;
    }

    /**
     * 歇山(half_hip):下部四坡、上部双坡带两面山花。
     *
     * <p>中式等级仅次于庑殿,也是西方 Dutch gable 的东方本家。做法就是把两种坡
     * 面上下拼起来:先四面收几层,再在收窄后的矩形上起一个双坡。上段自然比下段
     * 陡——因为跨度小了曲线重新起算——这恰好就是歇山该有的样子。
     */
    private static void halfHipCourses(Map<Long, BuildTaskRecord.Target> out, BuildPalette palette,
                                       BuildPalette gable, BuildPalette ridge,
                                       int ax, int bx, int az, int bz, int y0,
                                       boolean concave, boolean hollow) {
        int halfSpan = Math.min(bx - ax, bz - az) / 2;
        int[] insets = courseInsets(halfSpan, concave);
        int hipCount = Math.max(1, insets.length * 2 / 5);   // 下部四坡约占四成
        hipCount = Math.min(hipCount, Math.max(1, insets.length - 2));
        for (int c = 0; c < hipCount; c++) {
            hipCourse(out, palette, ridge, ax, bx, az, bz, y0, insets[c], c, hollow);
        }
        int i = insets[hipCount - 1];
        int nax = ax + i;
        int nbx = bx - i;
        int naz = az + i;
        int nbz = bz - i;
        if (nax > nbx || naz > nbz) {
            return;
        }
        gableCourses(out, palette, gable, ridge, nax, nbx, naz, nbz, y0 + hipCount,
                concave, hollow);
    }

    /**
     * 单坡(shed / skillion):一整片斜面倒向一侧。
     *
     * <p>棚屋、披屋、厂房侧翼,以及贴着主体加建的那一坨。屋面就是一条斜线沿长轴
     * 扫出来的平面,两端的三角侧墙可以用 gable_block 填实。
     */
    private static void shedCourses(Map<Long, BuildTaskRecord.Target> out, BuildPalette palette,
                                    BuildPalette gable, int ax, int bx, int az, int bz, int y0,
                                    boolean concave) {
        boolean runAlongX = (bx - ax) >= (bz - az);
        int spanLo = runAlongX ? az : ax;
        int spanHi = runAlongX ? bz : bx;
        int runLo = runAlongX ? ax : az;
        int runHi = runAlongX ? bx : bz;
        int[] insets = courseInsets(spanHi - spanLo, concave);
        for (int c = 0; c < insets.length; c++) {
            int span = spanLo + insets[c];
            if (span > spanHi) {
                break;
            }
            int y = y0 + c;
            for (int run = runLo; run <= runHi; run++) {
                putStair(out, palette, runAlongX, run, y, span,
                        runAlongX ? Direction.SOUTH : Direction.EAST);
            }
            if (gable != null) {
                for (int gy = y0; gy < y; gy++) {
                    putSolid(out, gable, runAlongX, runLo, gy, span);
                    putSolid(out, gable, runAlongX, runHi, gy, span);
                }
            }
        }
    }

    /**
     * 不对称双坡(saltbox):脊线偏心,一坡短而陡、一坡长而缓。
     *
     * <p>形成的原因是后来加建——原本的双坡屋在背面接出一间,屋面顺势拉长。所以
     * 它天生带一种"这栋房子有历史"的味道,新英格兰殖民地民居的标志。
     */
    private static void saltboxCourses(Map<Long, BuildTaskRecord.Target> out, BuildPalette palette,
                                       BuildPalette gable, BuildPalette ridge,
                                       int ax, int bx, int az, int bz, int y0,
                                       Integer ridgeOffsetArg, boolean hollow) {
        boolean ridgeAlongX = (bx - ax) >= (bz - az);
        int spanLo = ridgeAlongX ? az : ax;
        int spanHi = ridgeAlongX ? bz : bx;
        int runLo = ridgeAlongX ? ax : az;
        int runHi = ridgeAlongX ? bx : bz;
        int span = spanHi - spanLo;
        int shortRun = ridgeOffsetArg != null
                ? Math.max(1, Math.min(span - 1, ridgeOffsetArg))
                : Math.max(1, span / 3);
        int longRun = span - shortRun;
        int height = shortRun;                       // 短坡走 1:1,长坡自然放缓
        for (int c = 0; c <= height; c++) {
            int y = y0 + c;
            int lo = spanLo + c;
            int hi = spanHi - (int) Math.round((double) longRun * c / Math.max(1, height));
            if (lo > hi) {
                break;
            }
            boolean top = c == height;
            for (int run = runLo; run <= runHi; run++) {
                putStair(out, palette, ridgeAlongX, run, y, lo,
                        ridgeAlongX ? Direction.SOUTH : Direction.EAST);
                if (hi != lo) {
                    putStair(out, palette, ridgeAlongX, run, y, hi,
                            ridgeAlongX ? Direction.NORTH : Direction.WEST);
                }
                if (!hollow) {
                    for (int mid = lo + 1; mid < hi; mid++) {
                        putSolid(out, palette, ridgeAlongX, run, y, mid);
                    }
                }
                if (top && ridge != null) {
                    for (int mid = lo; mid <= hi; mid++) {
                        putSolid(out, ridge, ridgeAlongX, run, y + 1, mid);
                    }
                }
            }
            if (gable != null) {
                for (int mid = lo; mid <= hi; mid++) {
                    for (int gy = y0; gy < y; gy++) {
                        putSolid(out, gable, ridgeAlongX, runLo, gy, mid);
                        putSolid(out, gable, ridgeAlongX, runHi, gy, mid);
                    }
                }
            }
        }
    }

    /**
     * 檐角起翘(飞檐):四个檐角往上挑起来。
     *
     * <p>这是东亚屋顶最认得出的一笔——尖角一挑,整片屋面就"活"了。做法是在檐口
     * 那一层的四角往上叠几格,并把紧挨着角的两格也抬一格,让翘起来的是一条弧,
     * 不是四根柱子。
     */
    private static void liftEaveCorners(Map<Long, BuildTaskRecord.Target> out, BuildPalette palette,
                                        int ax, int bx, int az, int bz, int y0, int lift) {
        int[][] corners = {{ax, az}, {bx, az}, {ax, bz}, {bx, bz}};
        for (int[] c : corners) {
            int cx = c[0];
            int cz = c[1];
            for (int h = 1; h <= lift; h++) {
                putSolidAt(out, palette, new BlockPos(cx, y0 + h, cz), false);
            }
            if (lift >= 2) {
                int inx = cx == ax ? 1 : -1;
                int inz = cz == az ? 1 : -1;
                putSolidAt(out, palette, new BlockPos(cx + inx, y0 + 1, cz), false);
                putSolidAt(out, palette, new BlockPos(cx, y0 + 1, cz + inz), false);
            }
        }
    }

    /** 垂脊上的一格:能实心就实心,只有楼梯可用时至少让四角朝向一致。 */
    private static void putHipCorner(Map<Long, BuildTaskRecord.Target> out, BuildPalette palette,
                                     BlockPos pos, Direction fallbackFacing) {
        BuildPalette.Entry e = palette.pick(pos);
        if (e.block() instanceof net.minecraft.world.level.block.StairBlock) {
            putStairAt(out, palette, pos, fallbackFacing);
        } else {
            putSolidAt(out, palette, pos, true);
        }
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

    private static void putStair(Map<Long, BuildTaskRecord.Target> out, BuildPalette palette,
                                 boolean ridgeAlongX, int run, int y, int span, Direction facing) {
        BlockPos pos = ridgeAlongX ? new BlockPos(run, y, span) : new BlockPos(span, y, run);
        BuildPalette.Entry e = palette.pick(pos);
        if (e.block() instanceof net.minecraft.world.level.block.StairBlock) {
            BlockState state = e.block().defaultBlockState()
                    .setValue(net.minecraft.world.level.block.StairBlock.FACING, facing);
            out.put(pos.asLong(), new BuildTaskRecord.Target(state, e.item(), pos, e.label(),
                    facing, null, null));
        } else {
            // 给的不是楼梯就照实心放——不替玩家改主意,只是没有斜面而已
            out.put(pos.asLong(),
                    new BuildTaskRecord.Target(e.block(), e.item(), pos, e.label(), null, null, null));
        }
    }

    private static void putSolid(Map<Long, BuildTaskRecord.Target> out, BuildPalette palette,
                                 boolean ridgeAlongX, int run, int y, int span) {
        BlockPos pos = ridgeAlongX ? new BlockPos(run, y, span) : new BlockPos(span, y, run);
        BuildPalette.Entry e = palette.pick(pos);
        out.putIfAbsent(pos.asLong(),
                new BuildTaskRecord.Target(e.block(), e.item(), pos, e.label(), null, null, null));
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
