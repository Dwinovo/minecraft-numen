package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.pathing.body.Body;
import com.dwinovo.numen.api.entity.BodyAction;
import com.dwinovo.numen.api.entity.Controls.Key;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Origin;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.search.SearchClock;
import com.dwinovo.numen.pathing.search.WorldSnapshot;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.api.entity.Sight;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * 段状态机:一次导航从头到尾怎么走。每次搜索的目标都是真终点,时限由表定({@link Allowance}):从身体脚下搜的首段想几十毫秒,
 * 到了就交整条,没到就交半程让身体先动;半程一交出,马上从它的终点在后台接着搜,接在后面,前后两截之间不停顿。
 *
 * <p>走不通有两种,共用这一趟的记忆({@link TripMemory}):
 * <ul>
 *   <li>搜索没到目标:展开过的格子的估价抬高、记进记忆,身体前进到边界上总价最小的节点(截到回得了头的地方),从那里接着搜
 *       (LSS-LRTA* [Koenig &amp; Sun 2009]);半程也截不出来就再搜(放不放弃只由总预算定);</li>
 *   <li>执行里一步走不通:这一步记为不通,只扔掉在走的路、从身体此刻的位置重搜(旧路打折,不无谓换道)。</li>
 * </ul>
 *
 * <h2>重规划</h2>
 * 什么情况下扔掉在走的路、或从路线终点接着搜,原因只有 {@link Journal.Replan} 这几种,全在 {@link #replan} 与 {@link #next}
 * 记账与记日志:每一步开始前往前核接下来几步({@link Lookahead}),有一步在活世界上不成立;一步走着走不下去({@link Blockage});
 * 一步超时({@link Watchdog});半程路线快走完({@link Continuation});另有走着离开了路线、到了终点不算到、接续段证明终点是死路。
 * 世界变化靠往前核每一步开始时读一遍活世界发现,不订阅事件。
 * 收场只有一个判据:这一趟的总预算(按展开的节点数,{@link Allowance})用完还没到;另外是搜索证明了到不了(能走到的都搜过了),
 * 或根本动不了。时钟只决定每次想多久,不决定放不放弃。身体不传送,不做模型不知道的事:重搜与原因都进日志与行程报告({@link Journal})。
 *
 * <p>这里是执行那一半:驱动正在走的那一步({@link Step})、认步、停稳、对外交出状态。规划那一半是 {@link Trip}(不带身体:搜索的派发与收结论、
 * 路线与接续、总预算、一趟的记忆与收场的判断),身体与世界经 {@link Trip.Ground} 交给它。其余各归一个部件——
 * <ul>
 *   <li>路线与进度:{@link Course}(归 {@link Trip});</li>
 *   <li>认步,即身体此刻在路线上的哪一步:{@link Tracker}(身体此刻在哪个节点,照搜索定起点的同一条规则({@link Origin})在活世界上定);</li>
 *   <li>接续,何时搜下一截、怎么拼、末尾几步何时能开始走:{@link Continuation}(归 {@link Trip});</li>
 *   <li>每次想多久、展开几个节点、一趟的总预算:{@link Allowance}(归 {@link Trip});</li>
 *   <li>到达,停稳之后到没到:{@link Arrival}(路过的导航不停稳,走进目标就算到了)。</li>
 * </ul>
 *
 * <h2>规划的结论</h2>
 * 搜索没有交出路线时,段状态机只交出那次搜索的停因与输入({@link Halt.Searched}),为什么没路由门面在同一份快照上诊断。
 */
public final class Driver {

    /** 起点身体待不住(卡在方块里、悬在半空)最多等多少刻。 */
    private static final int STRANDED_TICKS = 60;
    /**
     * 路线走完、身体停住了却不在终点那一格(刚从高处落进水里,冲劲把身子压到水面以下再浮上来;冲劲带出了终点那一格),最多等多少刻让它
     * 自己回到终点。三秒:从三格多高的地方落进水里,沉下去再浮起来要一两秒。
     */
    private static final int SETTLE_TICKS = 60;

    /** 导航的状态。 */
    public enum State {
        RUNNING, ARRIVED, HALTED
    }

    private final Rig rig;
    private final RouteSpec spec;
    /** 路过这个目标,不停下。 */
    private final boolean passing;
    private final Watchdog watchdog = new Watchdog();
    /** 规划那一半:搜索、路线、接续、总预算、记忆、收场的判断。 */
    private final Trip trip;
    private final Course course;
    private final Tracker tracker = new Tracker();
    private Step step;

    private int stranded;
    /** 路线走完、身体停住了却不在终点那一格,已经等了几刻({@link #SETTLE_TICKS})。 */
    private int unsettled;

    private State state = State.RUNNING;
    private Halt halt;
    /** 上一刻对外交出的"在推进":变了才记日志。 */
    private boolean moving = true;

    /**
     * @param budget 这一趟总共最多展开几个节点;0 是按起点到终点的估价定({@link Allowance})
     * @param seed    先照这条路走(调用方给的);没有为 null。它只是第一段,走不下去照样按目标与规格重搜
     * @param passing 路过这个目标:走进去就算到了,不停稳
     * @param clock   搜索看的表(宿主的端口):只定每次想多久
     */
    public Driver(Body body, TerrainPolicy terrain, Materials materials, Threats threats, Goal goal,
                  RouteSpec spec, int budget, Route seed, boolean passing, SearchClock clock) {
        this.rig = new Rig(body, terrain, materials, threats);
        this.spec = spec;
        this.trip = new Trip(new Eyes(), rig.who, rig.journal, goal, spec, budget, clock);
        this.course = trip.course();
        this.passing = passing;
        if (seed != null) {
            install(seed, trip.goalHas(seed.end(), seed.endStance()));
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

    public Journal journal() {
        return rig.journal;
    }

    /** 到此刻为止身体真在水下憋过的每一段。 */
    public List<DiveLog.Dive> dives() {
        return rig.dives.dives(rig.entity);
    }

    /** 这一趟里最近一次"离目标最近处被拒"的那一步;没有为 null。 */
    public Blockage nearestRefusal() {
        return trip.nearestRefusal();
    }

    /** 在推进:{@link Watchdog} 对外交出的信号。 */
    public boolean progressing() {
        return watchdog.progressing();
    }

    /** 身体此刻是计划内的坠落:宿主的摔落反射只接管计划外的。 */
    public boolean plannedFall() {
        return step != null && step.falls() && !rig.entity.onGround();
    }

    /**
     * 身体此刻在计划内的一段水下:在走的这一步憋着气,而此刻的氧气撑得到这一段水下走完——规划时算过,这一步开始前与执行中的
     * 每一刻再按真实氧气算({@link Step#holdsBreath})。撑不到的那一刻起就不是计划内的了:这一步以憋不住气走不下去、从脚下重搜。
     * 宿主的换气本能只接管计划外的。
     */
    public boolean plannedDive() {
        return state == State.RUNNING && !course.finished() && course.current().submerged()
                && (step == null || step.holdsBreath());
    }

    /**
     * 身体此刻站着等一次搜索的结论:没有路可走(或路走完了还没到),或路线末尾的几步要等接续段到了才开始({@link Continuation#settled}),
     * 派出去的搜索还没回来。
     */
    public boolean waiting() {
        return state == State.RUNNING && trip.waiting();
    }

    /** 此刻在走的路线(不含已经走过的步);没有为空。 */
    public List<Route.Leg> remaining() {
        return course.remaining();
    }

    /**
     * 换目标(跟着的东西挪了):在走的这条路原本到得了目标,而它的终点在新目标里还算数、停在那儿没变贵
     * ({@link Goal#keepsStop}),就照走;否则扔掉它,从身体脚下按新目标重搜,旧路打折,在飞的搜索作废。手上还没有路、从脚下的
     * 搜索在飞时,让它搜完,交回来的路走到终点再按那时的目标判。
     * 交进来的还是同一个目标(目标是值,每刻重编一次也相等),什么都不变:在走的路与在飞的搜索照旧。
     */
    public void retarget(Goal next) {
        if (!trip.retarget(next, rig.world())) {
            reset();
        }
    }

    /** 叫停:在飞的搜索作废,松开所有键(包括潜行),手上正在挖的放下。 */
    public void stop() {
        trip.abandon();
        rig.keys.releaseAll();
        rig.mouse.release();
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
        DiveLog.Dive surfaced = rig.dives.observe(rig.entity);
        if (surfaced != null) {
            PathLog.info("{} 出水 从 {} 到 {} 憋了 {} 刻 氧气最低 {}/{}", rig.who, PathLog.pos(surfaced.from()),
                    PathLog.pos(surfaced.to()), surfaced.ticks(), surfaced.lowestAir(), surfaced.maxAir());
        }
        collect();
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
            PathLog.info("{} 不在推进(一秒没挪也没干活) {} {} {}", rig.who,
                    step != null ? PathLog.step(step.planned()) : "没有在走的步", PathLog.body(rig.entity),
                    PathLog.hands(rig.entity, rig.keys, rig.mouse, step == null ? null : step.working()));
        }
    }

    private void drive() {
        ServerPlayer body = rig.entity;
        BlockPos node = node();
        if (trip.stalled()) {
            // 没有路,或路走完了还没到:等在飞的搜索,没有就从脚下搜
            hold();
            if (!trip.searching()) {
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
                if (trip.spent()) {
                    halt(trip.spentOut());
                    return;
                }
                trip.fromBody(node);
            }
            watchdog.waiting(body.position());
            return;
        }
        if (course.finished()) {
            arrive(node);
            return;
        }
        if (track(node)) {
            return;
        }
        if (course.finished()) {
            arrive(node);
            return;
        }
        if (step == null) {
            if (!trip.settled()) {
                // 后面接什么还没定下来:不开始走末尾这几步,等接续段到了(或确认接不上)。末尾的步怎么走只由路线数据定,不由线程快慢定
                hold();
                trip.continueAhead();
                watchdog.waiting(body.position());
                return;
            }
            switch (Lookahead.verify(rig, trip.goal(), spec, course)) {
                case Lookahead.Broken broken -> {
                    fail(Journal.Replan.AHEAD, broken.step(), broken.blockage());
                    return;
                }
                case Lookahead.Clear clear -> {
                    Maneuver planned = course.current();
                    List<Maneuver> diving = new ArrayList<>();
                    for (int i = course.cur() + 1; planned.submerged() && i < course.size() && course.at(i).submerged(); i++) {
                        diving.add(course.at(i));
                    }
                    step = new Step(rig, course.cur() > 0 ? course.at(course.cur() - 1) : null, clear.fresh(), clear.model(),
                            course.ahead(Continuation.TAIL - 1), diving, watchdog);
                }
            }
        }
        Beat beat = step.tick();
        switch (beat) {
            case Beat.Going going -> trip.continueAhead();
            case Beat.Blocked blocked -> fail(blocked.blockage().hitch() == Blockage.Hitch.STUCK ? Journal.Replan.TIMEOUT
                    : Journal.Replan.FAILED, course.current(), blocked.blockage());
            case Beat.Denied denied -> {
                rig.mouse.release();
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
            Steering.stop(rig, best.x, best.z, body.getY());
        }
    }

    /** 按身体落在了哪个节点认步({@link Tracker}),认出来的结果在这里落实:走完的步记账、退回的步当走不下去、丢了路线就重搜。路线被扔掉(或收场)了返回 true。 */
    private boolean track(BlockPos node) {
        if (node == null || step != null && step.holds()) {
            return false;
        }
        switch (tracker.locate(course, node, this::settled)) {
            case Tracker.Fix.Advanced advanced -> {
                if (step != null) {
                    step.finish(node);
                }
                // 冲劲可能把身体带过了几步:这一路的刻数按各步几何最短耗时的比例分给走完的每一步
                double shortest = 0;
                for (int k = course.cur(); k < advanced.to(); k++) {
                    shortest += course.minimumAt(k);
                }
                for (int k = course.cur(); k < advanced.to(); k++) {
                    Maneuver walked = course.at(k);
                    rig.ledger.stepped(walked.kind());
                    rig.journal.walked(course.costAt(k));
                    if (step != null) {
                        rig.journal.timed(walked.kind(), watchdog.stepTicks() * course.minimumAt(k) / shortest, course.minimumAt(k));
                    }
                }
                trip.advance(advanced.to());
                step = null;
                return false;
            }
            case Tracker.Fix.FellBack fellBack -> {
                // 落回了前面一步的起点:正在走的这一步没走成,和走不下去一样记一次、从这里重搜。悄悄退回去重走的话,同一处一圈圈地退,
                // 每一步的期限都从头算,永远等不到"卡住"
                Maneuver current = course.current();
                fail(Journal.Replan.FAILED, current, new Blockage(current.to(), rig.world().getBlockState(current.to()), current.kind(),
                        null, Blockage.Hitch.FELL_BACK));
                return true;
            }
            case Tracker.Fix.Lost lost -> {
                if (step == null) {
                    // 还没开始走这一步(上一步的冲劲把身子带出了路线):不怪它,从这里重新搜
                    replan(Journal.Replan.OFF_ROUTE, "落在 " + PathLog.pos(node) + ",该在 " + PathLog.step(course.current()));
                    return true;
                }
                // 走着这一步,身体落在了路线之外:这一步没把身体带到它的落点,和走不下去一样记一次。只重搜不记的话,规划看不见的墙前面
                // 斜走一步滑到旁边一行,重搜又斜走回来,每一次都是一条新路线的第一步,期限从头算,永远等不到"卡住",永远走不出去
                Maneuver current = course.current();
                fail(Journal.Replan.FAILED, current, new Blockage(current.to(), rig.world().getBlockState(current.to()), current.kind(),
                        null, Blockage.Hitch.LOST));
                return true;
            }
            case Tracker.Fix.Here here -> {
                return false;
            }
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

    // ==================== 到达 ====================

    /** 路线走完了:在终点上停稳({@link Steering}),停稳了问 {@link Arrival};路过的导航走进目标就算到了。 */
    private void arrive(BlockPos node) {
        ServerPlayer body = rig.entity;
        BlockPos end = course.end();
        Stance endStance = course.endStance();
        step = null;
        if (passing && node != null && Arrival.passes(trip.goal(), rig.world(), rig.snapshot().stats(), node)) {
            PathLog.debug("{} 路过 {},不停", rig.who, PathLog.pos(node));
            state = State.ARRIVED;
            return;
        }
        rig.keys.release(Key.SPRINT);
        rig.keys.release(Key.JUMP);
        rig.keys.set(Key.SNEAK, endStance.kind() == Stance.Kind.CLIMBING);
        if (endStance.kind() == Stance.Kind.SWIMMING && body.getY() < end.getY() + Control.FLOAT) {
            rig.keys.press(Key.JUMP);
        }
        Vec3 c = Control.center(end);
        boolean still = Steering.stop(rig, c.x, c.z, endStance.feetY())
                || Math.sqrt(horizontalSpeedSqr()) < Control.STILL && distanceSqr(c) < 0.3 * 0.3;
        watchdog.observe(body.position(), false);
        if (!still || node == null || !settled(endStance.kind())) {
            if (node != null && !node.equals(end) && settled(Stance.Kind.GROUND) && tracker.strays()) {
                // 最后一步没把身体停在它的落点上:记为不通。只重搜不记的话,同一处终点一圈圈地冲过头、重搜、再冲过头
                missedEnd(rig.who + " 没停在终点 " + PathLog.pos(end) + ",落在 " + PathLog.pos(node));
            }
            return;
        }
        switch (Arrival.judge(trip.goal(), rig.world(), rig.snapshot().stats(), node, body.getEyePosition(), body.blockInteractionRange())) {
            case Arrival.Verdict.Replan replan -> {
                if (!node.equals(end)) {
                    if (++unsettled <= SETTLE_TICKS) {
                        // 停住了却不在终点那一格:刚落进水里还在往下沉、冲劲刚把身子带出终点那一格,给它一阵子自己回到终点
                        return;
                    }
                    // 回不去:最后一步没把身体带到它的落点,记为不通再重搜(同一处终点一圈圈冲过头、重搜、再冲过头,永远收不了场)
                    missedEnd(rig.who + " " + replan.why());
                    return;
                }
                unsettled = 0;
                replan(Journal.Replan.ARRIVAL, replan.why());
            }
            case Arrival.Verdict.Blind blind -> {
                rig.keys.releaseAll();
                halt(new Halt.NoSight(blind.target()));
            }
            case Arrival.Verdict.Arrived arrived -> {
                rig.keys.releaseAll();
                if (arrived.aim() != null) {
                    rig.look.at(arrived.aim());
                }
                state = State.ARRIVED;
            }
        }
    }

    // ==================== 搜索 ====================

    /** 排障用:此刻在哪一步。走过的经过见日志({@link PathLog})。 */
    @Override
    public String toString() {
        String at = !course.finished() ? course.cur() + "/" + course.size() + " " + PathLog.step(course.current())
                : course.cur() + "/" + course.size();
        return "Driver[" + state + " step " + at + " complete=" + course.complete() + " pending=" + trip.pending()
                + "]";
    }

    /** 收下在飞的搜索的结论(路线怎么变、收没收场由 {@link Trip#poll} 定),执行这边按路线的变化接着做。 */
    private void collect() {
        switch (trip.poll()) {
            case NONE, EXTENDED -> {
            }
            case FRESH -> afterInstall();
            case DISCARDED -> afterDiscard();
            case HALTED -> halt(trip.halt());
        }
    }

    private void install(Route route, boolean arrived) {
        trip.install(route, arrived);
        afterInstall();
    }

    /** 换了新路线:执行从头开始。 */
    private void afterInstall() {
        unsettled = 0;
        step = null;
        tracker.reset();
    }

    /** 路线扔掉了:手上正在挖的放下,认步与在走的那一步从头。 */
    private void afterDiscard() {
        unsettled = 0;
        rig.mouse.release();
        tracker.reset();
        step = null;
    }

    /** 按 {@code why} 重规划:记账、记日志({@code detail} 是这一次的经过),扔掉当前路线,下一刻从身体脚下重新搜,旧路打折。 */
    private void replan(Journal.Replan why, String detail) {
        trip.replan(why, detail);
        afterDiscard();
    }

    /**
     * 路线走完了,身体却没停在终点上:最后一步没把它带到落点,这一步记为不通再重搜(只重搜不记的话,同一处终点一圈圈冲过头、
     * 重搜、再冲过头)。路线一步都没有(起点就是站位)时没有哪一步可怪,身体只是被带离了站位——从这里重搜。
     */
    private void missedEnd(String detail) {
        if (course.isEmpty()) {
            replan(Journal.Replan.OFF_ROUTE, detail);
            return;
        }
        Maneuver last = course.lastLeg().maneuver();
        PathLog.info("{}", detail);
        fail(Journal.Replan.FAILED, last, new Blockage(last.to(), rig.world().getBlockState(last.to()), last.kind(), null, Blockage.Hitch.LOST));
    }

    /** 扔掉当前路线,下一刻从身体脚下重新搜,旧路打折。 */
    private void reset() {
        trip.discard();
        afterDiscard();
    }

    /** 第 {@code m} 步走不通:{@link Trip#fail} 记账、扔掉路线,执行这边跟着从头。 */
    private void fail(Journal.Replan why, Maneuver m, Blockage blockage) {
        trip.fail(why, m, blockage);
        afterDiscard();
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
        trip.abandon();
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

    /** 规划向身体与世界要的东西,取自活世界与身体此刻的样子。 */
    private final class Eyes implements Trip.Ground {

        @Override
        public WorldSnapshot snapshot(BlockPos from) {
            return WorldSnapshot.around(rig.entity.serverLevel(), from, WorldSnapshot.SEARCH_RADIUS);
        }

        @Override
        public CostModel model() {
            return CostModel.of(spec, rig.snapshot(), rig.terrain, rig.materials, rig.threats);
        }

        @Override
        public Trip.Standing standing() {
            BlockPos node = node();
            Stance here = node == null ? null : Stance.at(rig.world(), rig.snapshot().stats(), node);
            return here != null && settled(here.kind()) ? new Trip.Standing(node, here) : null;
        }

        @Override
        public String body() {
            return PathLog.body(rig.entity);
        }

        @Override
        public void dispatched(long snapshotNanos, long modelNanos) {
            rig.tally.dispatched(snapshotNanos, modelNanos);
        }
    }

}
