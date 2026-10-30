package com.dwinovo.numen.pathing.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.function.Predicate;

import com.dwinovo.numen.pathing.drive.Blockage;
import com.dwinovo.numen.pathing.drive.Course;
import com.dwinovo.numen.pathing.drive.Halt;
import com.dwinovo.numen.pathing.drive.Journal;
import com.dwinovo.numen.pathing.drive.Trip;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.PlannedView;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.search.SearchClock;
import com.dwinovo.numen.pathing.search.SearchResult;
import com.dwinovo.numen.pathing.search.SearchView;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;

/**
 * 一趟的预演:真实的 {@link Trip}(搜索的派发与收结论、分段、接续、总预算、一趟的记忆、收场的判断)加一具<b>虚拟的身体</b>——
 * 身体不走,只按交出的路线推进。路线上每一步要走多少刻是路线数据({@link Course#minimumAt},几何最短);接续的时限走的是真实的
 * {@link Trip#aheadTicks} → {@code Allowance#next},虚拟身体走到哪、接续等不等,都由 {@link Trip} 自己的 {@link Trip#settled}
 * 决定,这里不另算一份。
 *
 * <p><b>时间</b>:一次搜索想了多久由表({@link Metered}:默认按展开数、精确判断数折算,与测试服务器上的 {@link SearchClock#STEADY} 同一套系数)读出,
 * 不是墙钟,所以结果与机器快慢无关、逐字节可复现。从脚下搜时身体站着等;接续搜索期间身体接着走,走到要等接续的那一步前
 * 就站住等。时间以游戏刻计,虚拟身体走完路线上每步的最短刻数。
 *
 * <p><b>改动</b>:虚拟身体走过一步,那一步的挖与垫就算做完了,之后它读的世界(从脚下的搜索、站在哪)叠着这些改动({@link PlannedView});接续搜索读的世界
 * 叠上还没走的路线的改动,这是 {@link Trip} 自己做的。
 *
 * <p><b>不做的</b>:身体不真走,所以没有脚下打滑、被推出路线、到达后的视线判定({@code Arrival}),也不挖不垫真的世界。路线终点在目标里就算到。执行里"这一步做不成"的事只在测试要求时才有
 * ({@code stuck}:走到这样的一步就当它走不通,记给 {@link Trip#fail})。
 */
final class Rehearsal {

    /** 一刻的纳秒数:与 {@code drive.Allowance} 折算路线剩下刻数用的同一个。 */
    private static final double TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    /**
     * 一道要预演的题:读哪份世界(从某个节点出发的搜索拷的快照)、成本模型、规格、目标、起点。
     *
     * @param snapshots 为从某个节点出发的一次搜索拷的世界
     * @param budget    这一趟总共最多展开几个节点;0 是按起点到终点的估价定({@code drive.Allowance})
     */
    record Scene(Function<BlockPos, SearchView> snapshots, CostModel model, RouteSpec spec, Goal goal, BlockPos start, int budget) {}

    /** 一趟预演的结果。时间都是游戏刻,按表折算(不是墙钟)。 */
    record Result(boolean arrived, Halt halt, double firstMove, double maxThink, double waited, double ticks, int searches,
                  int expanded, Map<Journal.Replan, Integer> replans, double cost, List<Edit> edits, int steps) {

        /** 结局的三类({@link Row.Outcome}):把能走到的都搜完了没有目标才算证明到不了。 */
        Row.Outcome outcome() {
            if (arrived) {
                return Row.Outcome.ARRIVED;
            }
            return halt instanceof Halt.Searched s && s.stop() == SearchResult.Stop.EXHAUSTED ? Row.Outcome.UNREACHABLE : Row.Outcome.GAVE_UP;
        }

        int replansTotal() {
            return replans.values().stream().mapToInt(Integer::intValue).sum();
        }

        int replans(Journal.Replan why) {
            return replans.getOrDefault(why, 0);
        }

        int dug() {
            return (int) edits.stream().filter(e -> e instanceof Edit.Dig).count();
        }

        int placed() {
            return (int) edits.stream().filter(e -> e instanceof Edit.Place).count();
        }
    }

    /** 默认的表:按搜索表的同一套系数折算做了多少活({@link SearchClock#STEADY} 同一套),读出一次搜索想了多久。 */
    static final class Steady implements Metered {

        private volatile long precomputed;
        private volatile long nanos;

        @Override
        public Timer start(long limit) {
            precomputed = 0;
            nanos = 0;
            Timer inner = SearchClock.STEADY.start(limit);
            return new Timer() {
                @Override
                public void precomputed(int columns) {
                    inner.precomputed(columns);
                    precomputed = (long) columns * SearchClock.PER_COLUMN;
                }

                @Override
                public boolean up(int expanded, int checked) {
                    nanos = precomputed + (long) expanded * SearchClock.PER_EXPANSION + (long) checked * SearchClock.PER_CHECK;
                    return inner.up(expanded, checked);
                }
            };
        }

        @Override
        public long nanos() {
            return nanos;
        }
    }

    /** 虚拟身体向 {@link Trip} 交出的东西:它此刻在哪个节点(路线推进到哪)、读的世界、成本模型。 */
    private static final class Ground implements Trip.Ground {

        private final Scene scene;
        /** 虚拟身体一路走过做完的改动:它读的世界叠着它们(真身体做过的挖和垫就在世界里)。 */
        private final List<Edit> done;
        BlockPos at;

        Ground(Scene scene, List<Edit> done) {
            this.scene = scene;
            this.done = done;
            this.at = scene.start();
        }

        @Override
        public SearchView snapshot(BlockPos from) {
            SearchView base = scene.snapshots().apply(from);
            return PlannedView.of(base, List.copyOf(done));
        }

        @Override
        public CostModel model() {
            return scene.model();
        }

        @Override
        public Trip.Standing standing() {
            Stance stance = Stance.at(snapshot(at), scene.model().body().stats(), at);
            return stance == null ? null : new Trip.Standing(at, stance);
        }

        @Override
        public String body() {
            return "虚拟身体 " + at.toShortString();
        }
    }

    private final Scene scene;
    private final Trip trip;
    private final Journal journal = new Journal();
    private final Course course;
    private final Ground ground;
    private final Metered meter;
    private final Predicate<Maneuver> stuck;

    private double ticks;
    private double waited;
    private double maxThink;
    private double firstMove = Double.NaN;
    /** 走到一半的那一步已经走了几刻。 */
    private double carry;
    /** 最近一次 {@link #walk} 站住等接续时没用掉的刻数。 */
    private double idle;
    private double cost;
    private final List<Edit> edits = new ArrayList<>();
    private int steps;

    Rehearsal(Scene scene, String who) {
        this(scene, who, new Steady(), step -> false);
    }

    /**
     * @param meter 读一次搜索想了多久的表
     * @param stuck 虚拟身体做不成的步:走到这样的一步当它走不通
     */
    Rehearsal(Scene scene, String who, Metered meter, Predicate<Maneuver> stuck) {
        this.scene = scene;
        this.meter = meter;
        this.stuck = stuck;
        this.ground = new Ground(scene, edits);
        this.trip = new Trip(ground, who, journal, scene.goal(), scene.spec(), scene.budget(), meter);
        this.course = trip.course();
    }

    Result run() {
        Halt halt = null;
        while (true) {
            if (trip.stalled()) {
                if (!trip.searching()) {
                    if (trip.spent()) {
                        halt = trip.spentOut();
                        break;
                    }
                    trip.fromBody(ground.at);
                }
            } else {
                trip.continueAhead();
            }
            if (trip.searching()) {
                // 手上有路时身体边走边等(接续),没有就站着等(从脚下搜)
                boolean walking = !trip.stalled();
                while (!trip.ready()) {
                    LockSupport.parkNanos(20_000);
                }
                double think = meter.nanos() / TICK_NANOS;
                maxThink = Math.max(maxThink, think);
                if (Double.isNaN(firstMove)) {
                    firstMove = think;
                }
                ticks += think;
                if (walking) {
                    walk(carry + think);
                    waited += idle;
                } else {
                    waited += think;
                    carry = 0;
                }
                if (trip.poll() == Trip.Event.HALTED) {
                    halt = trip.halt();
                    break;
                }
            } else if (course.finished()) {
                break;
            } else {
                double before = carry;
                ticks += walk(Double.POSITIVE_INFINITY) - before;
            }
        }
        return new Result(halt == null, halt, Double.isNaN(firstMove) ? 0 : firstMove, maxThink, waited, ticks, journal.searches(),
                journal.expansions().stream().mapToInt(Integer::intValue).sum(), journal.replans(), cost, edits, steps);
    }

    /**
     * 身体按路线走 {@code budget} 刻:一步一步走,走完一步({@link Trip#advance})才算过了;走到要等接续的那一步前
     * ({@link Trip#settled} 为假)或路线尽头就站住,没用掉的刻数记在 {@link #idle};一步走了一半的刻数留给下一次({@link #carry})。
     * 走到做不成的一步({@code stuck})就当它走不通,路线扔掉。
     *
     * @return 这一次走掉的刻数(含上次留下的那半步)
     */
    private double walk(double budget) {
        double left = budget;
        double consumed = 0;
        while (!course.finished() && trip.settled() && left >= course.minimumAt(course.cur())) {
            int i = course.cur();
            Route.Leg leg = course.leg(i);
            if (stuck.test(leg.maneuver())) {
                Maneuver m = leg.maneuver();
                trip.fail(Journal.Replan.TIMEOUT, m, new Blockage(m.to(), ground.snapshot(ground.at).getBlockState(m.to()), m.kind(), null,
                        Blockage.Hitch.STUCK));
                carry = 0;
                idle = 0;
                return consumed;
            }
            left -= course.minimumAt(i);
            consumed += course.minimumAt(i);
            cost += course.costAt(i);
            steps++;
            edits.addAll(leg.maneuver().edits());
            ground.at = leg.maneuver().to();
            trip.advance(i + 1);
        }
        boolean stoppedByBudget = !course.finished() && trip.settled();
        carry = stoppedByBudget ? left : 0;
        idle = stoppedByBudget || !Double.isFinite(left) ? 0 : left;
        return consumed;
    }
}
