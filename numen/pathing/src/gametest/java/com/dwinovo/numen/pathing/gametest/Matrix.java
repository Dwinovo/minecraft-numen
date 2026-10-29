package com.dwinovo.numen.pathing.gametest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.dwinovo.numen.pathing.api.NavRequest;
import com.dwinovo.numen.pathing.api.NavStatus;
import com.dwinovo.numen.pathing.api.Navigation;
import com.dwinovo.numen.pathing.api.Navigator;
import com.dwinovo.numen.pathing.api.Ports;
import com.dwinovo.numen.pathing.drive.Journal;
import com.dwinovo.numen.pathing.drive.LiveWorld;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Heading;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.Move;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.plan.Moves;
import com.dwinovo.numen.pathing.plan.Premise;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.search.SearchClock;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.BodyStats;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "精确判过的步 ⇒ 执行走得通"的矩阵:一批场景({@link MatrixScene})铺在场地里的若干格(每格 9×9)上并行跑,每一格自成一台小状态机——
 * 搭场景、等水流稳定、对每种走法每个问法用精确的前提判这一步成不成立({@link Move#premise},搜索与执行往前核用的是同一个函数),
 * 成立的就让一具新的身体在真实世界里只做这一步(把这一步当作路线交给导航,目标就是它的落点),逐个断言:
 * <ul>
 *   <li>在预计刻数的合理倍数内到了落点,导航以到达收场;</li>
 *   <li>一路上不掉血;</li>
 *   <li>没有重规划:往前核成立、没有走不下去、没被看护判成卡住、没有离开路线(重规划意味着照这一步走不通)。</li>
 * </ul>
 * 规划判不成立的(保守是允许的)只记进覆盖统计,不算失败。收场时统计表打进日志,有失败的一并列出。
 */
final class Matrix {

    private static final Logger LOG = LoggerFactory.getLogger("numen-matrix");
    /**
     * 排障:环境变量 NUMEN_MATRIX 用分号隔开几项——{@code reasons} 打出判不成立的原因,{@code only=甲|乙} 只跑名字里含甲或乙的场景,
     * {@code trace=甲|乙} 让名字里含甲或乙的那些步每刻记一行身体的位置与速度。
     */
    private static final String OPTIONS = System.getenv("NUMEN_MATRIX") == null ? "" : System.getenv("NUMEN_MATRIX");
    private static final List<String> TRACE = option("trace=");
    static final List<String> ONLY = option("only=");

    private static List<String> option(String name) {
        for (String part : OPTIONS.split(";")) {
            if (part.startsWith(name)) {
                return List.of(part.substring(name.length()).split("\\|"));
            }
        }
        return List.of();
    }

    /** 场地里并行的格数(每边几格)。 */
    private static final int GRID = 4;
    /** 水里的场景等这么多刻让水流稳定。 */
    private static final int SETTLE = 40;
    /** 一步实际用的刻数超过预计的这么多倍加这么多刻,就算慢得不合理(看护的期限是两倍加六十刻)。 */
    private static final double SLOW_FACTOR = 2.0;
    private static final int SLOW_TICKS = 40;
    /** 一步做了这么多倍预计刻数加这么多刻还没收场,就算没走完。 */
    private static final double LIMIT_FACTOR = 3.0;
    private static final int LIMIT_TICKS = 160;

    private final GameTestHelper helper;
    private final ServerLevel level;
    private final BlockPos origin;
    private final RouteSpec spec;
    private final java.util.function.Function<TestBody, com.dwinovo.numen.pathing.plan.Materials> materials;
    private final java.util.function.Consumer<TestBody> kit;
    private final Deque<MatrixScene> queue;
    private final Cell[] cells = new Cell[GRID * GRID];
    private final TestBody planner;
    private final int total;
    private final int timeout;
    private int age;
    private int named;
    private boolean done;

    private final Map<String, Counts> counts = new LinkedHashMap<>();
    private final Map<String, Integer> reasons = new TreeMap<>();
    private final List<String> failures = new ArrayList<>();
    private int scenes;
    private int noStart;
    private int invalid;

    /** 一种方块样子 × 一种走法的计数。 */
    private static final class Counts {
        int queried;
        int held;
        int passed;
    }

    Matrix(GameTestHelper helper, List<MatrixScene> scenes, RouteSpec spec, int timeout,
           java.util.function.Function<TestBody, com.dwinovo.numen.pathing.plan.Materials> materials,
           java.util.function.Consumer<TestBody> kit) {
        this.helper = helper;
        this.level = helper.getLevel();
        this.origin = helper.absolutePos(BlockPos.ZERO).above();
        this.spec = spec;
        this.materials = materials;
        this.kit = kit;
        this.queue = new ArrayDeque<>(scenes.stream().filter(s -> ONLY.isEmpty() || ONLY.stream().anyMatch(s.key()::contains)).toList());
        this.total = queue.size();
        this.timeout = timeout;
        for (int i = 0; i < cells.length; i++) {
            cells[i] = new Cell(origin.offset(i % GRID * MatrixScene.SIZE, 0, i / GRID * MatrixScene.SIZE));
        }
        // 规划问的是一具身体的快照,身体不在场景里:站在场地的角落
        BlockPos corner = origin.offset(GRID * MatrixScene.SIZE + 1, 0, GRID * MatrixScene.SIZE + 1);
        level.setBlock(corner, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
        this.planner = TestBody.spawn(level, "mx_plan", corner.getX() + 0.5, corner.getY() + 1, corner.getZ() + 0.5);
        kit.accept(planner);
        helper.onEachTick(this::tick);
    }

    // ==================== 每刻 ====================

    private void tick() {
        if (done) {
            return;
        }
        age++;
        boolean busy = false;
        for (Cell cell : cells) {
            busy |= cell.tick();
        }
        if (!busy && queue.isEmpty()) {
            done = true;
            planner.leave();
            conclude();
        } else if (age > timeout) {
            done = true;
            planner.leave();
            for (Cell cell : cells) {
                cell.abort();
            }
            report();
            throw new GameTestAssertException("矩阵没在 " + timeout + " 刻内跑完:还剩 " + queue.size() + " 个场景没开始");
        }
    }

    private void conclude() {
        report();
        if (!failures.isEmpty()) {
            StringBuilder sb = new StringBuilder("规划说能走、执行没走通 ").append(failures.size()).append(" 处:");
            for (int i = 0; i < Math.min(40, failures.size()); i++) {
                sb.append("\n  ").append(failures.get(i));
            }
            throw new GameTestAssertException(sb.toString());
        }
        helper.succeed();
    }

    // ==================== 统计 ====================

    private void report() {
        int queried = 0;
        int held = 0;
        int passed = 0;
        Map<String, int[]> byKind = new TreeMap<>();
        StringBuilder table = new StringBuilder();
        Map<String, Map<MoveKind, Counts>> byVariant = new LinkedHashMap<>();
        for (Map.Entry<String, Counts> e : counts.entrySet()) {
            String[] key = e.getKey().split("\\|");
            byVariant.computeIfAbsent(key[0], k -> new TreeMap<>()).put(MoveKind.valueOf(key[1]), e.getValue());
            Counts c = e.getValue();
            queried += c.queried;
            held += c.held;
            passed += c.passed;
            int[] k = byKind.computeIfAbsent(key[1], x -> new int[3]);
            k[0] += c.queried;
            k[1] += c.held;
            k[2] += c.passed;
        }
        byVariant.forEach((variant, kinds) -> {
            table.append("\n    ").append(String.format("%-28s", variant));
            kinds.forEach((kind, c) -> table.append(' ').append(kind).append(' ').append(c.held).append('/').append(c.queried)
                    .append(c.passed == c.held ? "" : " (走通 " + c.passed + ")"));
        });
        StringBuilder kinds = new StringBuilder();
        byKind.forEach((k, v) -> kinds.append(' ').append(k).append(" 成立 ").append(v[1]).append('/').append(v[0])
                .append(" 走通 ").append(v[2]).append(';'));
        LOG.info("[matrix] {} 个场景(方块立不住的 {} 个没算,起点站不住 {}):问了 {} 种走法×问法,规划成立 {},执行走通 {},判不成立 {},失败 {}。按走法:{}{}",
                scenes, invalid, noStart, queried, held, passed, queried - held, failures.size(), kinds, table);
        if (OPTIONS.contains("reasons")) {
            StringBuilder sb = new StringBuilder();
            reasons.forEach((k, v) -> sb.append("\n    ").append(k).append(" = ").append(v));
            LOG.info("[matrix] 判不成立的原因:{}", sb);
        }
        for (String failure : failures) {
            LOG.warn("[matrix] 失败 {}", failure);
        }
    }

    private Counts counts(String variant, MoveKind kind) {
        return counts.computeIfAbsent(variant + "|" + kind, k -> new Counts());
    }

    // ==================== 一格 ====================

    private enum Phase {
        IDLE, SETTLING, EXECUTING
    }

    private final class Cell {

        final BlockPos origin;
        Phase phase = Phase.IDLE;
        MatrixScene scene;
        int wait;
        BlockState[] snapshot;
        List<Hold> holds = new ArrayList<>();
        int next;
        Exec exec;

        Cell(BlockPos origin) {
            this.origin = origin;
        }

        /** 这一刻这格还有事在做。 */
        boolean tick() {
            switch (phase) {
                case IDLE -> {
                    scene = queue.poll();
                    if (scene == null) {
                        return false;
                    }
                    if (!scene.build(level, origin)) {
                        invalid++;
                        return true;
                    }
                    scenes++;
                    wait = scene.wet() ? SETTLE : 0;
                    phase = Phase.SETTLING;
                    return true;
                }
                case SETTLING -> {
                    if (wait-- > 0) {
                        return true;
                    }
                    snapshot = capture();
                    holds = plan();
                    next = 0;
                    phase = holds.isEmpty() ? Phase.IDLE : Phase.EXECUTING;
                    return true;
                }
                case EXECUTING -> {
                    if (exec == null) {
                        if (next > 0) {
                            restore();
                        }
                        exec = new Exec(this, holds.get(next++));
                    }
                    if (exec.tick()) {
                        exec = null;
                        if (next >= holds.size()) {
                            phase = Phase.IDLE;
                        }
                    }
                    return true;
                }
            }
            return false;
        }

        void abort() {
            if (exec != null) {
                exec.abort();
                exec = null;
            }
        }

        BlockState[] capture() {
            BlockState[] out = new BlockState[MatrixScene.SIZE * (MatrixScene.ROOF + 1) * MatrixScene.SIZE];
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int i = 0;
            for (int x = 0; x < MatrixScene.SIZE; x++) {
                for (int y = 0; y <= MatrixScene.ROOF; y++) {
                    for (int z = 0; z < MatrixScene.SIZE; z++) {
                        out[i++] = level.getBlockState(pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z));
                    }
                }
            }
            return out;
        }

        /** 把场景还原成刚稳定时的样子(上一次走动开过的门、关过的门)。 */
        void restore() {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int i = 0;
            for (int x = 0; x < MatrixScene.SIZE; x++) {
                for (int y = 0; y <= MatrixScene.ROOF; y++) {
                    for (int z = 0; z < MatrixScene.SIZE; z++) {
                        pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                        BlockState want = snapshot[i++];
                        if (level.getBlockState(pos) != want) {
                            level.setBlock(pos, want, 18);
                        }
                    }
                }
            }
        }

        /** 对这个场景问规划:每种走法、每个问法;成立的列成要走的一步。 */
        List<Hold> plan() {
            LiveWorld world = new LiveWorld(level);
            CostModel model = CostModel.of(spec, planner.snapshot(), Trial.ALLOW_ALL, materials.apply(planner), Threats.NONE);
            BodyStats stats = model.body().stats();
            BlockPos nominal = scene.at(origin, 0, scene.base().startY, 0);
            BlockPos node = Stance.settle(world, stats, nominal);
            Stance start = Stance.at(world, stats, node);
            if (start == null || Math.abs(node.getY() - nominal.getY()) > 1) {
                noStart++;
                return List.of();
            }
            List<Hold> out = new ArrayList<>();
            for (Move move : Moves.ALL) {
                if (!scene.base().moves.contains(move.kind())) {
                    continue;
                }
                for (Heading heading : headings(move.kind())) {
                    Counts c = counts(scene.variant().name(), move.kind());
                    c.queried++;
                    Premise premise = move.premise(model, world, node, start, heading);
                    if (premise instanceof Premise.Holds holds) {
                        c.held++;
                        out.add(new Hold(scene, move, heading, node, start, holds.maneuver(), model));
                    } else if (premise instanceof Premise.Fails fails) {
                        reasons.merge(scene.variant().name() + "|" + scene.where() + "/" + scene.level() + "|" + scene.base() + "|"
                                + move.kind() + "|" + fails.reason(), 1, Integer::sum);
                    }
                }
            }
            return out;
        }

        /** 这种走法在这个场景里问哪些方向。 */
        List<Heading> headings(MoveKind kind) {
            List<Heading> out = new ArrayList<>();
            switch (kind) {
                case CLIMB -> {
                    out.add(Heading.UP);
                    out.add(Heading.DOWN);
                }
                case PILLAR -> out.add(Heading.UP);
                case DOWNWARD -> out.add(Heading.DOWN);
                case SWIM -> {
                    for (Heading h : scene.base().headings) {
                        out.add(scene.turned(h));
                    }
                    out.add(Heading.UP);
                    out.add(Heading.DOWN);
                }
                default -> {
                    for (Heading h : scene.base().headings) {
                        out.add(scene.turned(h));
                    }
                }
            }
            return out;
        }
    }

    /** 规划说成立的一步。 */
    private record Hold(MatrixScene scene, Move move, Heading heading, BlockPos from, Stance start, Maneuver maneuver,
                        CostModel model) {

        String describe() {
            return scene.key() + " " + move.kind() + " " + heading.dx() + "," + heading.dy() + "," + heading.dz();
        }
    }

    // ==================== 一步的执行 ====================

    private final class Exec {

        final Cell cell;
        final Hold hold;
        final TestBody body;
        final Navigation navigation;
        final double expected;
        int ticks;

        Exec(Cell cell, Hold hold) {
            this.cell = cell;
            this.hold = hold;
            Stance start = hold.start();
            BlockPos from = hold.from();
            this.body = TestBody.spawn(level, "mx" + (named++ % 100000), from.getX() + 0.5, start.feetY(), from.getZ() + 0.5);
            body.getFoodData().setFoodLevel(20);
            kit.accept(body);
            this.expected = hold.move().ticks(hold.model(), hold.maneuver());
            Route.Leg leg = new Route.Leg(hold.maneuver(), hold.move().cost(hold.model(), hold.maneuver()), expected,
                    hold.model().body().breath().now());
            Route route = new Route(from, start, List.of(leg));
            Navigator navigator = Navigator.of(body, new Ports(Trial.ALLOW_ALL, materials.apply(body), Threats.NONE, SearchClock.STEADY));
            this.navigation = navigator.drive(NavRequest.to(Goals.at(hold.maneuver().to()), spec).following(route));
        }

        /** 做一刻;这一步收场了返回 true。 */
        boolean tick() {
            ticks++;
            NavStatus status = navigation.tick();
            if (ticks == 1 && TRACE.stream().anyMatch(hold.describe()::contains)) {
                LOG.info("[trace] {} 规划 {} 改动 {} 跳 {} 起点 {} 落点 {}", hold.describe(), com.dwinovo.numen.pathing.drive.PathLog.step(hold.maneuver()),
                        hold.maneuver().edits(), hold.maneuver().jump(), hold.maneuver().start(), hold.maneuver().landing());
            }
            if (TRACE.stream().anyMatch(hold.describe()::contains)) {
                net.minecraft.world.phys.Vec3 v = body.getDeltaMovement();
                LOG.info("[trace] {} t{} pos {} vel {} ground {} water {} yaw {} keys {}", hold.describe(), ticks,
                        String.format("%.3f,%.3f,%.3f", body.getX() - cell.origin.getX(), body.getY() - cell.origin.getY(),
                                body.getZ() - cell.origin.getZ()),
                        String.format("%.3f,%.3f,%.3f", v.x, v.y, v.z), body.onGround(), body.isInWater(), (int) body.getYRot(),
                        keys(body));
            }
            while (navigation.waiting()) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new GameTestAssertException("等搜索的结论时被打断");
                }
                status = navigation.tick();
            }
            double limit = expected * LIMIT_FACTOR + LIMIT_TICKS;
            Journal journal = navigation.report().journal();
            // 第一次重规划(往前核不成立、走不下去、卡住、离开路线……)就记下收场:往后的重搜是另一回事,不是这一步的事
            boolean faulty = !journal.replans().isEmpty();
            if (status.running() && ticks <= limit && !faulty) {
                return false;
            }
            String why = verdict(status, journal);
            Counts c = counts(hold.scene().variant().name(), hold.move().kind());
            if (why == null) {
                c.passed++;
            } else {
                failures.add(hold.describe() + " → " + why + " [" + detail(status, journal) + "]");
            }
            abort();
            return true;
        }

        private String verdict(NavStatus status, Journal journal) {
            if (body.incoming() > 0) {
                return "掉血 " + body.incoming();
            }
            if (!journal.replans().isEmpty()) {
                return "重规划(" + com.dwinovo.numen.pathing.drive.PathLog.replans(journal) + ")";
            }
            if (status.running()) {
                return "没走完(" + ticks + " 刻,预计 " + fmt(expected) + ")";
            }
            if (status.state() != NavStatus.State.ARRIVED) {
                return "收场是 " + status.state() + " " + String.valueOf(status.outcome()).replaceAll("\s+", " ");
            }
            if (ticks > expected * SLOW_FACTOR + SLOW_TICKS) {
                return "太慢(" + ticks + " 刻,预计 " + fmt(expected) + ")";
            }
            return null;
        }

        private String detail(NavStatus status, Journal journal) {
            BlockPos at = body.blockPosition();
            return com.dwinovo.numen.pathing.drive.PathLog.who(body) + " " + com.dwinovo.numen.pathing.drive.PathLog.step(hold.maneuver())
                    + " 用了 " + ticks + " 刻 预计 " + fmt(expected) + " 身体 " + at.subtract(cell.origin) + " 落点 "
                    + hold.maneuver().to().subtract(cell.origin) + " 起点 " + hold.from().subtract(cell.origin) + " 改动 "
                    + hold.maneuver().edits().size() + " 站姿 " + hold.start().kind() + "→" + hold.maneuver().landing().kind();
        }

        void abort() {
            navigation.stop();
            body.leave();
        }
    }

    private static String keys(TestBody body) {
        StringBuilder sb = new StringBuilder();
        for (com.dwinovo.numen.api.entity.Controls.Key key : com.dwinovo.numen.api.entity.Controls.Key.values()) {
            if (body.controls().held(key)) {
                sb.append(key.name().charAt(0));
            }
        }
        return sb.toString();
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }
}
