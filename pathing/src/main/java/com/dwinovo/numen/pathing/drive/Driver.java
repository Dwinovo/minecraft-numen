package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dwinovo.numen.pathing.body.Aim;
import com.dwinovo.numen.pathing.body.Body;
import com.dwinovo.numen.pathing.body.BodyAction;
import com.dwinovo.numen.pathing.body.Controls.Key;
import com.dwinovo.numen.pathing.body.Crosshair;
import com.dwinovo.numen.pathing.body.Effector;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.search.Favoring;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Origin;
import com.dwinovo.numen.pathing.search.Pending;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.search.Search;
import com.dwinovo.numen.pathing.search.SearchResult;
import com.dwinovo.numen.pathing.search.Searches;
import com.dwinovo.numen.pathing.search.WorldSnapshot;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.BodyStats;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 段状态机:一次导航从头到尾怎么走。首段从身体脚下搜;路线走到只剩几秒、又没到目标时,提前从路线终点搜下一段,
 * 接在后面,段与段之间不停顿;一步走不下去就从身体此刻的位置重新搜(旧路打折,不无谓换道),同一步三次走不下去就收场。
 *
 * <h2>人在哪一步</h2>
 * 身体此刻在哪个节点,照搜索定起点的同一条规则({@link Origin})在活世界上定;它稳稳落在(站着、攀着、浮着,与那一步的
 * 落点一致)后面某一步的起点上,就认到那一步——冲劲把她带过了一两步也不回头;落在前面某一步的起点上(被推回去了),
 * 就退回那一步;落在路线之外一阵子,就从那里重新搜。
 *
 * <h2>到达</h2>
 * 路线走完、身体在终点上停稳,只看目标自己的判定;目标要求看得见某一格时,在活世界上复核视线。模块里没有"差不多到了"。
 *
 * <h2>规划的结论</h2>
 * 搜索没有交出路线时,段状态机只交出那次搜索的停因与输入({@link Halt.Searched}),为什么没路由门面在同一份快照上诊断。
 */
public final class Driver {

    /** 路线还剩这么多刻就提前搜下一段。 */
    private static final double LOOKAHEAD_TICKS = 100;
    /** 认步时往前往后看几步。 */
    private static final int WINDOW = 4;
    /** 同一步走不下去几次就收场。 */
    private static final int STRIKES = 3;
    /** 落在路线之外这么多刻就重新搜。 */
    private static final int OFF_ROUTE_TICKS = 15;
    /** 半程路线连续几段没让离目标更近就收场。 */
    private static final int STALE_PARTIALS = 3;
    /** 起点身体待不住(卡在方块里、悬在半空)最多等多少刻。 */
    private static final int STRANDED_TICKS = 60;
    /**
     * 每一段搜索展开到这么多个节点还没到目标、又已经有够远的半程路线,就先交出它({@link Search#handOver}):身体先走这一段,
     * 快走完时从它的终点接着搜。依据:最费的是许放块时搜索在空中铺开(搭桥一格约 35、垫柱约 25,估价每格只减 3.56),测试服务器里
     * 许改地形的搜索每个节点几十微秒(实测见 docs/pathing.md 第十三节),一万个节点在一秒以内,与 Baritone 先交路线的
     * primaryTimeoutMS(500 毫秒)同一量级;它是默认预算 {@link com.dwinovo.numen.pathing.api.NavRequest#DEFAULT_BUDGET}
     * 的四分之一,Baritone 的 primary 与 failure 两个时限也是一比四。起伏地形上一百格的路展开两三千到四千个节点,路线不变。
     * 按节点数计,结论不随机器快慢变。
     */
    static final int HAND_OVER = 10_000;

    /** 导航的状态。 */
    public enum State {
        RUNNING, ARRIVED, HALTED
    }

    /** 为什么派这次搜索。 */
    private enum Purpose {
        /** 从身体脚下搜(首段,或走不下去之后重搜)。 */
        FROM_BODY("从脚下"),
        /** 从当前路线的终点搜下一段。 */
        NEXT("接续");

        /** 日志里的叫法。 */
        final String label;

        Purpose(String label) {
            this.label = label;
        }
    }

    private final Rig rig;
    private final RouteSpec spec;
    private final int budget;
    private final Watchdog watchdog = new Watchdog();
    private Goal goal;

    private final List<Route.Leg> legs = new ArrayList<>();
    /** 正在走的那一步的下标;等于 {@code legs.size()} 是路线走完了。 */
    private int cur;
    /** 路线的终点在目标里。 */
    private boolean complete;
    /** 从这条路线的终点接着搜过,没搜出能接上的一段:走到终点再从脚下搜,不再提前搜。 */
    private boolean nextFailed;
    private BlockPos start;
    private Stance startStance;
    private Step step;

    private Pending<SearchResult> pending;
    private Purpose purpose;
    private Search pendingSearch;
    private Favoring favoring = Favoring.NONE;

    private final Map<List<Object>, Integer> strikes = new HashMap<>();
    private Blockage lastBlockage;
    private double bestEstimate = Double.POSITIVE_INFINITY;
    private int stalePartials;
    private int offRoute;
    private int stranded;
    private boolean paused;

    private State state = State.RUNNING;
    private Halt halt;
    /** 上一刻对外交出的"在推进":变了才记日志。 */
    private boolean moving = true;

    /**
     * @param budget 每次搜索最多展开几个节点
     * @param seed   先照这条路走(调用方从候选里挑的);没有为 null。它只是第一段,走不下去照样按目标与规格重搜
     */
    public Driver(Body body, Effector hands, TerrainPolicy terrain, Materials materials, Threats threats, Goal goal,
                  RouteSpec spec, int budget, Route seed) {
        this(new Rig(body, hands, terrain, materials, threats), goal, spec, budget, seed);
    }

    Driver(Rig rig, Goal goal, RouteSpec spec, int budget, Route seed) {
        this.rig = rig;
        this.goal = goal;
        this.spec = spec;
        this.budget = budget;
        if (seed != null) {
            install(seed, goalHas(seed.end(), seed.endStance()));
        }
    }

    // ==================== 对外 ====================

    public State state() {
        return state;
    }

    /** 收场的原因;没收场为 null。 */
    public Halt halt() {
        return halt;
    }

    public EditLedger ledger() {
        return rig.ledger;
    }

    public List<BodyAction> actions() {
        return rig.actions();
    }

    /** 在推进:{@link Watchdog} 对外交出的信号。 */
    public boolean progressing() {
        return watchdog.progressing();
    }

    /** 身体此刻是计划内的坠落:宿主的摔落反射只接管计划外的。 */
    public boolean plannedFall() {
        return step != null && step.falls() && !rig.entity.onGround();
    }

    /** 身体此刻站着等一次搜索的结论:没有路可走(或路走完了还没到),派出去的搜索还没回来。 */
    public boolean waiting() {
        return state == State.RUNNING && pending != null && cur >= legs.size();
    }

    /** 此刻在走的路线(不含已经走过的步);没有为空。 */
    public List<Route.Leg> remaining() {
        return legs.isEmpty() ? List.of() : List.copyOf(legs.subList(cur, legs.size()));
    }

    /**
     * 换目标(跟着的东西挪了):在走的这条路原本到得了目标,而它的终点在新目标里还算数、停在那儿没变贵
     * ({@link Goal#keepsStop}),就照走;否则扔掉它,从身体脚下按新目标重搜,旧路打折。在飞的搜索是按旧目标派的,作废。
     * 交进来的还是同一个目标(目标是值,每刻重编一次也相等),什么都不变:在走的路与在飞的搜索照旧。
     */
    public void retarget(Goal next) {
        if (next.equals(goal)) {
            return;
        }
        Goal before = goal;
        goal = next;
        bestEstimate = Double.POSITIVE_INFINITY;
        stalePartials = 0;
        BlockPos end = legs.isEmpty() ? start : legs.get(legs.size() - 1).maneuver().to();
        Stance endStance = legs.isEmpty() ? startStance : legs.get(legs.size() - 1).maneuver().landing();
        if (complete && end != null && endStance != null && Goal.keepsStop(before, next, end, endStance)) {
            PathLog.debug("{} 换目标 {} -> {},在走的路终点还算数,照走", rig.who, before, next);
            return;
        }
        // 跟随、战斗走位的目标随对方挪动一再换,记 DEBUG;换了之后的搜索照常记 INFO
        PathLog.debug("{} 换目标 {} -> {},从脚下重搜", rig.who, before, next);
        reset();
    }

    /** 暂停:松开所有键,手上正在挖的放下,路线留着。 */
    public void pause() {
        PathLog.debug("{} 暂停 {}", rig.who, PathLog.body(rig.entity));
        paused = true;
        rig.keys.releaseAll();
        rig.hands.release();
    }

    /** 接着走:照留着的路线走下去,不重新搜。 */
    public void resume() {
        PathLog.debug("{} 接着走", rig.who);
        paused = false;
        step = null;
    }

    /** 叫停:在飞的搜索作废,松开所有键(包括潜行),手上正在挖的放下。 */
    public void stop() {
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
        rig.keys.releaseAll();
        rig.hands.release();
        if (state == State.RUNNING) {
            state = State.HALTED;
        }
    }

    // ==================== 每刻 ====================

    public State tick() {
        if (state != State.RUNNING) {
            return state;
        }
        rig.tally.begin();
        try {
            advance();
            watchProgress();
        } finally {
            rig.tally.end(rig.who);
        }
        return state;
    }

    /** 推一刻。 */
    private State advance() {
        if (paused) {
            watchdog.waiting(rig.entity.position());
            return state;
        }
        if (rig.entity.isPassenger()) {
            // 原版按潜行就从载具上下来
            rig.keys.releaseAll();
            rig.keys.press(Key.SNEAK);
            BodyAction.Dismounted dismount = new BodyAction.Dismounted(rig.entity.getVehicle().getType());
            pendingDismount = dismount;
            return state;
        }
        if (pendingDismount != null) {
            rig.act(pendingDismount);
            pendingDismount = null;
        }
        poll();
        if (state != State.RUNNING) {
            return state;
        }
        drive();
        return state;
    }

    /** "在推进"变了就记一笔:一秒没挪也没干活时宿主的脱困反射会接手,日志里要看得出是从哪一刻起、停在哪一步。 */
    private void watchProgress() {
        boolean now = watchdog.progressing();
        if (now == moving || state != State.RUNNING) {
            return;
        }
        moving = now;
        if (now) {
            PathLog.debug("{} 恢复推进 {}", rig.who, PathLog.at(rig.entity.position()));
        } else {
            PathLog.info("{} 不在推进(一秒没挪也没干活) {} {}", rig.who,
                    step != null ? PathLog.step(step.planned()) : "没有在走的步", PathLog.body(rig.entity));
        }
    }

    /** 按了潜行、还没看到身体下来的那次下载具。 */
    private BodyAction.Dismounted pendingDismount;

    private void drive() {
        ServerPlayer body = rig.entity;
        BlockPos node = node();
        if (legs.isEmpty() && !complete || cur >= legs.size() && !complete) {
            // 没有路,或路走完了还没到:等在飞的搜索,没有就从脚下搜
            hold();
            if (pending == null) {
                if (node == null) {
                    // 脚下待不住:朝旁边待得住的节点挪,挪了一阵还不行就收场
                    if (++stranded > STRANDED_TICKS) {
                        halt(new Halt.Stranded(body.blockPosition(), rig.world().getBlockState(body.blockPosition())));
                    } else {
                        stepOut();
                    }
                    return;
                }
                stranded = 0;
                dispatch(Purpose.FROM_BODY, node, null);
            }
            watchdog.waiting(body.position());
            return;
        }
        if (cur >= legs.size()) {
            arrive(node);
            return;
        }
        track(node);
        if (cur >= legs.size()) {
            arrive(node);
            return;
        }
        if (step == null) {
            Maneuver planned = legs.get(cur).maneuver();
            Maneuver after = cur + 1 < legs.size() ? legs.get(cur + 1).maneuver() : null;
            step = new Step(rig, planned, after, goal, spec, watchdog);
        }
        Beat beat = step.tick();
        switch (beat) {
            case Beat.Going going -> lookahead();
            case Beat.Blocked blocked -> fail(blocked.blockage());
            case Beat.Denied denied -> {
                rig.hands.release();
                halt(new Halt.Denied(denied.cell(), denied.reason()));
            }
        }
    }

    // ==================== 人在哪一步 ====================

    /** 身体此刻所在的节点(与搜索定起点同一条规则);悬在半空、卡在方块里为 null。 */
    private BlockPos node() {
        ServerPlayer body = rig.entity;
        BodyStats stats = rig.snapshot().stats();
        return Origin.of(rig.world(), stats, body.getX(), body.getY(), body.getZ()).orElse(null);
    }

    /**
     * 身体脚下不是一个待得住的节点(头顶压着方块、原版让它趴着;卡在方块里),却踩在地上:朝旁边最近的一个待得住的节点
     * 挪过去,挪到了就能从那里搜。悬在半空、旁边都待不住时什么也不做。
     */
    private void stepOut() {
        ServerPlayer body = rig.entity;
        if (!body.onGround()) {
            return;
        }
        BodyStats stats = rig.snapshot().stats();
        BlockPos here = BlockPos.containing(body.getX(), com.dwinovo.numen.pathing.world.Footing.cellOf(body.getY()),
                body.getZ());
        Vec3 best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos next = here.offset(dx, 0, dz);
                if ((dx == 0 && dz == 0) || Stance.at(rig.world(), stats, next) == null) {
                    continue;
                }
                Vec3 c = Control.center(next);
                double d = distanceSqr(c);
                if (d < bestDistance) {
                    best = c;
                    bestDistance = d;
                }
            }
        }
        if (best != null) {
            Steering.stop(body, rig.keys, best.x, best.z, body.getY());
        }
    }

    /** 身体此刻稳稳地以 {@code kind} 的方式待着。 */
    private boolean settled(Stance.Kind kind) {
        ServerPlayer body = rig.entity;
        return switch (kind) {
            case GROUND -> body.onGround();
            case CLIMBING -> body.onClimbable();
            case SWIMMING -> body.isInWater();
        };
    }

    /** 按身体落在了哪个节点认步:往后认、往回退,或者认出落在了路线之外。 */
    private void track(BlockPos node) {
        if (node == null || step != null && step.holds()) {
            return;
        }
        int last = Math.min(cur + WINDOW, legs.size());
        for (int j = last; j > cur; j--) {
            Maneuver done = legs.get(j - 1).maneuver();
            if (done.to().equals(node) && settled(done.landing().kind())) {
                if (step != null) {
                    step.finish(node);
                }
                for (int k = cur; k < j; k++) {
                    rig.ledger.stepped();
                }
                strikes.clear();
                lastBlockage = null;
                cur = j;
                step = null;
                offRoute = 0;
                return;
            }
        }
        Maneuver current = legs.get(cur).maneuver();
        if (node.equals(current.from()) || node.equals(current.to())) {
            offRoute = 0;
            return;
        }
        for (int j = Math.max(0, cur - WINDOW); j < cur; j++) {
            Maneuver earlier = legs.get(j).maneuver();
            if (earlier.from().equals(node) && settled(earlier.start().kind())) {
                cur = j;
                step = null;
                offRoute = 0;
                return;
            }
        }
        if (settled(Stance.Kind.GROUND) || settled(Stance.Kind.CLIMBING) || settled(Stance.Kind.SWIMMING)) {
            if (++offRoute > OFF_ROUTE_TICKS) {
                // 落在路线之外:从这里重新搜,不算这一步走不下去
                offRoute = 0;
                replan("离开路线,落在 " + PathLog.pos(node) + ",该在 " + PathLog.step(current));
            }
        }
    }

    // ==================== 到达 ====================

    /** 路线走完了:在终点上停稳,再看目标自己的判定与视线。 */
    private void arrive(BlockPos node) {
        ServerPlayer body = rig.entity;
        BlockPos end = legs.isEmpty() ? start : legs.get(legs.size() - 1).maneuver().to();
        Stance endStance = legs.isEmpty() ? startStance : legs.get(legs.size() - 1).maneuver().landing();
        step = null;
        rig.keys.release(Key.SPRINT);
        rig.keys.release(Key.JUMP);
        rig.keys.set(Key.SNEAK, endStance.kind() == Stance.Kind.CLIMBING);
        if (endStance.kind() == Stance.Kind.SWIMMING && body.getY() < end.getY() + 0.3) {
            rig.keys.press(Key.JUMP);
        }
        Vec3 c = Control.center(end);
        boolean still = Steering.stop(body, rig.keys, c.x, c.z, endStance.feetY())
                || Math.sqrt(horizontalSpeedSqr()) < Control.STILL && distanceSqr(c) < 0.3 * 0.3;
        watchdog.observe(body.position(), false);
        if (!still || node == null || !settled(endStance.kind())) {
            if (node != null && !node.equals(end) && settled(Stance.Kind.GROUND) && ++offRoute > OFF_ROUTE_TICKS) {
                offRoute = 0;
                replan("没停在终点 " + PathLog.pos(end) + ",落在 " + PathLog.pos(node));
            }
            return;
        }
        Stance here = Stance.at(rig.world(), rig.snapshot().stats(), node);
        if (here == null || !goal.contains(node.getX(), node.getY(), node.getZ(), here)) {
            replan("停在终点 " + PathLog.pos(node) + " 却不在目标 " + goal + " 里");
            return;
        }
        rig.keys.releaseAll();
        BlockPos sight = goal.sight(node.getX(), node.getY(), node.getZ(), here);
        if (sight != null && !sees(sight)) {
            halt(new Halt.NoSight(sight));
            return;
        }
        state = State.ARRIVED;
    }

    /** 眼睛看得见 {@code target} 这一格:转过去看它身上的瞄点,准星落在它上面。 */
    private boolean sees(BlockPos target) {
        Vec3 point = Aim.point(rig.entity, target);
        if (point == null) {
            return false;
        }
        Aim.look(rig.entity, point);
        HitResult hit = Crosshair.pick(rig.entity);
        return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK
                && block.getBlockPos().equals(target);
    }

    private boolean goalHas(BlockPos node, Stance stance) {
        return stance != null && goal.contains(node.getX(), node.getY(), node.getZ(), stance);
    }

    // ==================== 搜索 ====================

    private CostModel model() {
        return CostModel.of(spec, rig.snapshot(), rig.terrain, rig.materials, rig.threats);
    }

    /** 排障用:此刻在哪一步。走过的经过见日志({@link PathLog})。 */
    @Override
    public String toString() {
        String at = cur < legs.size() ? cur + "/" + legs.size() + " " + PathLog.step(legs.get(cur).maneuver())
                : cur + "/" + legs.size();
        return "Driver[" + state + " step " + at + " complete=" + complete + " pending=" + (pending == null ? "-" : purpose)
                + "]";
    }

    /**
     * 从 {@code from} 派一次搜索:在世界所在的线程上拷下以它为中心的快照,成本模型按此刻的身体与端口现组;展开到
     * {@link #HAND_OVER} 个节点就先交出半程。接着路线往下搜时起点接在 {@code arrival} 那一步后面,从身体脚下搜为 null。
     */
    private void dispatch(Purpose why, BlockPos from, Maneuver arrival) {
        long t0 = System.nanoTime();
        WorldSnapshot view = WorldSnapshot.around(rig.entity.serverLevel(), from);
        long t1 = System.nanoTime();
        CostModel model = model();
        long t2 = System.nanoTime();
        rig.tally.dispatched(t1 - t0, t2 - t1);
        PathLog.debug("{} 派搜索 {} 从 {} 去 {} 拷快照 {} 组成本模型 {}", rig.who, why.label, PathLog.pos(from), goal,
                PathLog.ms(t1 - t0), PathLog.ms(t2 - t1));
        Search search = new Search(view, model, from, goal, budget, favoring).handingOverAt(HAND_OVER).after(arrival);
        pendingSearch = search;
        purpose = why;
        pending = Searches.submit(search);
    }

    private void poll() {
        if (pending == null) {
            return;
        }
        SearchResult result = pending.poll();
        if (result == null) {
            return;
        }
        Search search = pendingSearch;
        logSearch(search, result, pending);
        pending = null;
        if (purpose == Purpose.NEXT) {
            BlockPos end = legs.get(legs.size() - 1).maneuver().to();
            if (result.route() != null && result.route().start().equals(end)) {
                legs.addAll(result.route().legs());
                complete = result.arrived();
                partial(result);
            } else {
                // 接续段没搜出来:走到终点后从脚下再搜
                PathLog.info("{} 接续段接不上,走到终点 {} 再从脚下搜", rig.who, PathLog.pos(end));
                nextFailed = true;
            }
            return;
        }
        if (result.route() == null) {
            BlockPos node = node();
            Stance here = node == null ? null : Stance.at(rig.world(), rig.snapshot().stats(), node);
            if (here != null && goalHas(node, here) && settled(here.kind())) {
                // 最后一步进了目标的同一刻,这次搜索没交出路:她已经到了
                PathLog.debug("{} 搜索没交出路,可她已经站在目标里 {}", rig.who, PathLog.pos(node));
                start = node;
                startStance = here;
                legs.clear();
                cur = 0;
                complete = true;
                return;
            }
            halt(lastBlockage != null && result.stop() == SearchResult.Stop.EXHAUSTED
                    ? new Halt.Blocked(lastBlockage)
                    : new Halt.Searched(result.stop(), search));
            return;
        }
        install(result.route(), result.arrived());
        partial(result);
    }

    /**
     * 一次搜索的结论记一行:谁、为什么搜、从哪到哪、规格要点、展开几个节点、在工作线程上跑了多久与排队多久、为什么停、
     * 交出的路线;DEBUG 再记一行路线上的每一步。
     */
    private void logSearch(Search search, SearchResult result, Pending<SearchResult> done) {
        Route route = result.route();
        PathLog.info("{} 搜索 {} {} 去 {} {} 展开 {} 用时 {} 排队 {} 停因 {} {}", rig.who, purpose.label,
                PathLog.pos(search.start()), search.goal(), PathLog.spec(search.model().spec()), result.expanded(),
                PathLog.ms(done.ranNanos()), PathLog.ms(done.queuedNanos()), result.stop(),
                route == null ? "没交出路线" : (result.arrived() ? "到目标 " : "半程 ") + PathLog.route(route));
        if (route != null && PathLog.debugging()) {
            PathLog.debug("{} 路线 {}", rig.who, PathLog.legs(route));
        }
    }

    /** 半程路线:连续几段都没让离目标更近,就按那次搜索的停因收场。 */
    private void partial(SearchResult result) {
        if (result.arrived()) {
            return;
        }
        BlockPos end = result.route().end();
        double estimate = goal.estimate(end.getX(), end.getY(), end.getZ());
        if (estimate < bestEstimate - 1) {
            bestEstimate = estimate;
            stalePartials = 0;
        } else if (++stalePartials >= STALE_PARTIALS) {
            PathLog.info("{} 半程路线连续 {} 段没离目标更近,收场", rig.who, STALE_PARTIALS);
            halt(new Halt.Searched(result.stop(), pendingSearch));
        }
    }

    private void install(Route route, boolean arrived) {
        legs.clear();
        legs.addAll(route.legs());
        cur = 0;
        start = route.start();
        startStance = route.startStance();
        complete = arrived;
        nextFailed = false;
        step = null;
        offRoute = 0;
    }

    /** 路线快走完、又没到目标:从终点提前搜下一段。 */
    private void lookahead() {
        if (complete || nextFailed || pending != null || legs.isEmpty()) {
            return;
        }
        double left = 0;
        for (int i = cur; i < legs.size(); i++) {
            left += legs.get(i).cost();
        }
        if (left < LOOKAHEAD_TICKS) {
            Maneuver last = legs.get(legs.size() - 1).maneuver();
            dispatch(Purpose.NEXT, last.to(), last);
        }
    }

    /** 扔掉当前路线,从身体脚下重新搜,旧路打折;{@code why} 记进日志。 */
    private void replan(String why) {
        PathLog.info("{} 重搜:{} 身体 {}", rig.who, why, PathLog.at(rig.entity.position()));
        reset();
    }

    /** 扔掉当前路线,下一刻从身体脚下重新搜,旧路打折。 */
    private void reset() {
        if (!legs.isEmpty()) {
            favoring = Favoring.along(new Route(start, startStance, legs));
        }
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
        rig.hands.release();
        legs.clear();
        cur = 0;
        complete = false;
        nextFailed = false;
        step = null;
    }

    /** 一步走不下去:记一次;同一步第三次,就以它收场,否则重新搜。 */
    private void fail(Blockage blockage) {
        lastBlockage = blockage;
        Maneuver m = step.planned();
        List<Object> key = List.of(m.kind(), m.from(), m.to());
        int count = strikes.merge(key, 1, Integer::sum);
        PathLog.info("{} 走不下去 {} 这一步 {} 第 {}/{} 次 -> {} {}", rig.who, PathLog.blockage(blockage), PathLog.step(m),
                count, STRIKES, count >= STRIKES ? "收场" : "重搜", PathLog.body(rig.entity));
        if (count >= STRIKES) {
            rig.hands.release();
            halt(new Halt.Blocked(blockage));
            return;
        }
        reset();
    }

    private void hold() {
        rig.keys.release(Key.FORWARD);
        rig.keys.release(Key.BACK);
        rig.keys.release(Key.SPRINT);
        rig.keys.release(Key.JUMP);
        rig.keys.release(Key.LEFT);
        rig.keys.release(Key.RIGHT);
    }

    private void halt(Halt why) {
        halt = why;
        state = State.HALTED;
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
        rig.keys.releaseAll();
    }

    private double horizontalSpeedSqr() {
        Vec3 v = rig.entity.getDeltaMovement();
        return v.x * v.x + v.z * v.z;
    }

    private double distanceSqr(Vec3 c) {
        double dx = c.x - rig.entity.getX();
        double dz = c.z - rig.entity.getZ();
        return dx * dx + dz * dz;
    }

}
