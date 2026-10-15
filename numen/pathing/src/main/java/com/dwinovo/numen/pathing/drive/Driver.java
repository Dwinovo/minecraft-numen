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
import com.dwinovo.numen.pathing.search.Favoring;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Origin;
import com.dwinovo.numen.pathing.search.Pending;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.search.Search;
import com.dwinovo.numen.pathing.search.SearchResult;
import com.dwinovo.numen.pathing.search.Searches;
import com.dwinovo.numen.pathing.search.TripMemory;
import com.dwinovo.numen.pathing.search.WorldSnapshot;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.api.entity.Sight;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * 段状态机:一次导航从头到尾怎么走,一段一段地走。每一段的目标是"到终点,或到一个估价比现在低一段的节点"({@link Search#advance}),
 * 首段从身体脚下搜;路线走到只剩几秒、又没到目标时,提前从路线终点搜下一段,接在后面,段与段之间不停顿。
 *
 * <p>走不通有两种,共用这一趟的记忆({@link TripMemory}):
 * <ul>
 *   <li>搜索没搜到这一段的终点:展开过的格子的估价抬高、记进记忆,身体前进到边界上总价最小的节点(截到回得了头的地方),从那里接着搜
 *       (LSS-LRTA* [Koenig &amp; Sun 2009]);</li>
 *   <li>执行里一步走不下去或卡住:这一步记为不通,只扔掉在走的路、从身体此刻的位置重搜这一段(旧路打折,不无谓换道)。</li>
 * </ul>
 * 收场只有一个判据:这一趟的总预算(按展开的节点数,{@link Allowance})用完还没到;另外是搜索证明了到不了(能走到的都搜过了),
 * 或根本动不了。身体不传送,不做模型不知道的事:重搜与原因都进日志与行程报告({@link Journal})。
 *
 * <p>这里只做组装:派搜索(拷快照、组成本模型、记日志)、驱动正在走的那一步({@link Step})、下载具、对外交出状态。其余各归一个部件——
 * <ul>
 *   <li>路线与进度:{@link Course};</li>
 *   <li>认步,即身体此刻在路线上的哪一步:{@link Tracker}(身体此刻在哪个节点,照搜索定起点的同一条规则({@link Origin})在活世界上定);</li>
 *   <li>接续,何时搜下一段、怎么拼、末尾几步何时能开始走:{@link Continuation};</li>
 *   <li>总预算与段的大小:{@link Allowance};</li>
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
    private final Allowance allowance;
    /** 路过这个目标,不停下。 */
    private final boolean passing;
    private final Watchdog watchdog = new Watchdog();
    private Goal goal;

    private final Course course = new Course();
    private final Tracker tracker = new Tracker();
    private final Continuation continuation = new Continuation();
    /** 这一趟的记忆:搜索与执行共用。 */
    private final TripMemory memory = new TripMemory();
    private Step step;

    private Pending<SearchResult> pending;
    private Purpose purpose;
    private Search pendingSearch;
    private Favoring favoring = Favoring.NONE;
    /** 最近一次搜索的输入与结论:总预算用完收场时诊断它。 */
    private Search lastSearch;
    private SearchResult lastResult;
    /** 最近一次没搜到这一段终点的搜索里,离目标最近处朝目标的那一步为什么被拒;还没有为 null。一趟走得久了要对人说"卡在哪",读它。 */
    private Blockage nearestRefusal;
    /** 最近一次没走成的一步的原因;之后走成了一步就清掉。从那里重搜把能走的都搜过了还是没路,没路的原因就是它。 */
    private Blockage lastBlockage;
    /** 这一趟里哪一次搜索因为憋不住气丢下过前提成立的步子:收场诊断"憋得住的话有没有路"看的是整趟,不只最后一次。 */
    private boolean breathless;

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
     */
    public Driver(Body body, TerrainPolicy terrain, Materials materials, Threats threats, Goal goal,
                  RouteSpec spec, int budget, Route seed, boolean passing) {
        this.rig = new Rig(body, terrain, materials, threats);
        this.goal = goal;
        this.spec = spec;
        this.allowance = new Allowance(budget);
        this.passing = passing;
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

    public Journal journal() {
        return rig.journal;
    }

    /** 到此刻为止身体真在水下憋过的每一段。 */
    public List<DiveLog.Dive> dives() {
        return rig.dives.dives(rig.entity);
    }

    /** 这一趟里最近一次"离目标最近处被拒"的那一步;没有为 null。 */
    public Blockage nearestRefusal() {
        return nearestRefusal;
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
        return state == State.RUNNING && pending != null && (course.finished() || !continuation.settled(course));
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
        if (next.equals(goal)) {
            return;
        }
        Goal before = goal;
        goal = next;
        // 抬高过的估价是对着旧目标算的;这一趟的总预算也按新目标的远近重定
        memory.forgetRaised();
        allowance.restart();
        breathless = false;
        if (course.isEmpty() && pending != null) {
            // 手上没有路、从脚下的搜索还在飞:让它搜完。跟着会动的东西时目标每刻都变,变一次就作废重派的话,在飞的那一次永远
            // 搜不完,她就一直站着;交回来的路照走,走到终点按那时的目标判,不在里面就从那儿重搜
            PathLog.debug("{} 换目标 {} -> {},在飞的搜索搜完再说", rig.who, before, next);
            return;
        }
        BlockPos end = course.end();
        Stance endStance = course.endStance();
        if (course.complete() && end != null && endStance != null && Goal.keepsStop(rig.world(), before, next, end, endStance)) {
            PathLog.debug("{} 换目标 {} -> {},在走的路终点还算数,照走", rig.who, before, next);
            return;
        }
        // 跟随、战斗走位的目标随对方挪动一再换,记 DEBUG;换了之后的搜索照常记 INFO
        PathLog.debug("{} 换目标 {} -> {},从脚下重搜", rig.who, before, next);
        reset();
    }

    /** 叫停:在飞的搜索作废,松开所有键(包括潜行),手上正在挖的放下。 */
    public void stop() {
        abandon();
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
            PathLog.info("{} 不在推进(一秒没挪也没干活) {} {} {}", rig.who,
                    step != null ? PathLog.step(step.planned()) : "没有在走的步", PathLog.body(rig.entity),
                    PathLog.hands(rig.entity, rig.keys, rig.mouse, step == null ? null : step.working()));
        }
    }

    private void drive() {
        ServerPlayer body = rig.entity;
        BlockPos node = node();
        if (course.isEmpty() && !course.complete() || course.finished() && !course.complete()) {
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
                if (allowance.spent()) {
                    spentOut();
                    return;
                }
                dispatch(Purpose.FROM_BODY, node, null);
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
            if (!continuation.settled(course)) {
                // 后面接什么还没定下来:不开始走末尾这几步,等接续段到了(或确认接不上)。末尾的步怎么走只由路线数据定,不由线程快慢定
                hold();
                if (pending == null) {
                    next();
                }
                watchdog.waiting(body.position());
                return;
            }
            Maneuver planned = course.current();
            List<Maneuver> diving = new ArrayList<>();
            for (int i = course.cur() + 1; planned.submerged() && i < course.size() && course.at(i).submerged(); i++) {
                diving.add(course.at(i));
            }
            step = new Step(rig, course.cur() > 0 ? course.at(course.cur() - 1) : null, planned, course.ahead(Continuation.TAIL - 1), diving,
                    goal, spec, watchdog);
        }
        Beat beat = step.tick();
        switch (beat) {
            case Beat.Going going -> lookahead();
            case Beat.Blocked blocked -> fail(blocked.blockage());
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
                    lastBlockage = null;
                    if (step != null) {
                        rig.journal.timed(walked.kind(), watchdog.stepTicks() * course.minimumAt(k) / shortest, course.minimumAt(k));
                    }
                }
                course.advanceTo(advanced.to());
                step = null;
                return false;
            }
            case Tracker.Fix.FellBack fellBack -> {
                // 落回了前面一步的起点:正在走的这一步没走成,和走不下去一样记一次、从这里重搜。悄悄退回去重走的话,同一处一圈圈地退,
                // 每一步的期限都从头算,永远等不到"卡住"
                Maneuver current = course.current();
                fail(current, new Blockage(current.to(), rig.world().getBlockState(current.to()), current.kind(), null,
                        Blockage.Hitch.FELL_BACK));
                return true;
            }
            case Tracker.Fix.Lost lost -> {
                if (step == null) {
                    // 还没开始走这一步(上一步的冲劲把身子带出了路线):不怪它,从这里重新搜
                    replan("离开路线,落在 " + PathLog.pos(node) + ",该在 " + PathLog.step(course.current()));
                    return true;
                }
                // 走着这一步,身体落在了路线之外:这一步没把身体带到它的落点,和走不下去一样记一次。只重搜不记的话,规划看不见的墙前面
                // 斜走一步滑到旁边一行,重搜又斜走回来,每一次都是一条新路线的第一步,期限从头算,永远等不到"卡住",永远走不出去
                Maneuver current = course.current();
                fail(current, new Blockage(current.to(), rig.world().getBlockState(current.to()), current.kind(), null,
                        Blockage.Hitch.LOST));
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
        if (passing && node != null && Arrival.passes(goal, rig.world(), rig.snapshot().stats(), node)) {
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
                Maneuver last = course.lastLeg().maneuver();
                PathLog.info("{} 没停在终点 {},落在 {}", rig.who, PathLog.pos(end), PathLog.pos(node));
                fail(last, new Blockage(last.to(), rig.world().getBlockState(last.to()), last.kind(), null, Blockage.Hitch.LOST));
            }
            return;
        }
        switch (Arrival.judge(goal, rig.world(), rig.snapshot().stats(), node, body.getEyePosition(), body.blockInteractionRange())) {
            case Arrival.Verdict.Replan replan -> {
                if (!node.equals(end)) {
                    if (++unsettled <= SETTLE_TICKS) {
                        // 停住了却不在终点那一格:刚落进水里还在往下沉、冲劲刚把身子带出终点那一格,给它一阵子自己回到终点
                        return;
                    }
                    // 回不去:最后一步没把身体带到它的落点,记为不通再重搜(同一处终点一圈圈冲过头、重搜、再冲过头,永远收不了场)
                    Maneuver last = course.lastLeg().maneuver();
                    PathLog.info("{} {}", rig.who, replan.why());
                    fail(last, new Blockage(last.to(), rig.world().getBlockState(last.to()), last.kind(), null, Blockage.Hitch.LOST));
                    return;
                }
                unsettled = 0;
                replan(replan.why());
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
        String at = !course.finished() ? course.cur() + "/" + course.size() + " " + PathLog.step(course.current())
                : course.cur() + "/" + course.size();
        return "Driver[" + state + " step " + at + " complete=" + course.complete() + " pending=" + (pending == null ? "-" : purpose)
                + "]";
    }

    /**
     * 从 {@code from} 派一次搜索:在世界所在的线程上拷下以它为中心的快照(只拷一段那么远),成本模型按此刻的身体与端口现组;
     * 目标是这一段的终点({@link Allowance#advance}),带着这一趟的记忆。接着路线往下搜时起点接在 {@code arrival} 那一步后面
     * (憋气从走完那一步时的样子起),从身体脚下搜为 null(憋气从身体此刻的样子起)。
     */
    private void dispatch(Purpose why, BlockPos from, Route.Leg arrival) {
        long t0 = System.nanoTime();
        WorldSnapshot view = WorldSnapshot.around(rig.entity.serverLevel(), from, Allowance.SNAPSHOT_RADIUS);
        long t1 = System.nanoTime();
        CostModel model = model();
        long t2 = System.nanoTime();
        rig.tally.dispatched(t1 - t0, t2 - t1);
        PathLog.debug("{} 派搜索 {} 从 {} 去 {} 拷快照 {} 组成本模型 {}", rig.who, why.label, PathLog.pos(from), goal,
                PathLog.ms(t1 - t0), PathLog.ms(t2 - t1));
        Search search = new Search(view, model, from, goal, allowance.segment(), favoring)
                .advancing(Allowance.advance(model.body().stats())).remembering(memory.frozen()).after(arrival);
        pendingSearch = search;
        purpose = why;
        pending = Searches.submit(search);
    }

    /** 从当前路线的终点搜下一段;这一趟的总预算用完了就不再接,路线走到头在那里收场。 */
    private void next() {
        if (allowance.spent()) {
            continuation.skip();
            return;
        }
        Route.Leg last = course.lastLeg();
        dispatch(Purpose.NEXT, last.maneuver().to(), last);
    }

    /** 在飞的搜索不要了:叫停,连它的输入(快照、成本模型)一起放手。 */
    private void abandon() {
        if (pending != null) {
            pending.cancel();
            pending = null;
            pendingSearch = null;
        }
    }

    private void poll() {
        if (pending == null) {
            return;
        }
        SearchResult result = pending.poll();
        if (result == null) {
            return;
        }
        // 搜索交了结论,它的输入(快照、成本模型)不再由段状态机握着;收场要诊断的,各自带着
        Search search = pendingSearch;
        pendingSearch = null;
        logSearch(search, result, pending);
        pending = null;
        lastSearch = search;
        lastResult = result;
        if (result.blocking() != null && !result.reached()) {
            nearestRefusal = Blockage.refused(search, result.blocking());
        }
        allowance.spend(result.expanded());
        if (Double.isFinite(result.startEstimate())) {
            allowance.begun(result.startEstimate(), search.advance());
        }
        breathless |= result.breathless();
        boolean learned = memory.raise(result.raised());
        if (purpose == Purpose.NEXT) {
            if (result.stop() == SearchResult.Stop.EXHAUSTED) {
                // 从路线的终点往外走得到的地方都搜过了,没有这一段的终点:那里是死路,记进记忆了(估价无穷大)。身体还没走进去,
                // 从此刻的位置重搜这一段,绕开它——走进去就出不来了(往下挖的竖井、封死的坑)
                replan("接续段证明路线的终点 " + PathLog.pos(course.end()) + " 是死路");
                return;
            }
            if (continuation.splice(course, result.route(), result.arrived())) {
                return;
            }
            if (result.route() == null) {
                PathLog.info("{} 接续段没搜出路线,走到终点 {} 再从脚下搜", rig.who, PathLog.pos(course.end()));
            } else {
                // 接续段的起点不是路线的终点:走到终点后从脚下再搜
                PathLog.info("{} 接续段接不上,走到终点 {} 再从脚下搜", rig.who, PathLog.pos(course.end()));
            }
            return;
        }
        if (result.route() == null) {
            BlockPos node = node();
            Stance here = node == null ? null : Stance.at(rig.world(), rig.snapshot().stats(), node);
            if (here != null && goalHas(node, here) && settled(here.kind())) {
                // 最后一步进了目标的同一刻,这次搜索没交出路:她已经到了
                PathLog.debug("{} 搜索没交出路,可她已经站在目标里 {}", rig.who, PathLog.pos(node));
                course.clear();
                course.install(new Route(node, here, List.of()), true);
                return;
            }
            unsolved(result, search, learned);
            return;
        }
        install(result.route(), result.arrived());
    }

    /**
     * 一次从脚下的搜索没交出路线。搜完了能走到的地方都没有这一段的终点、或根本动不了,这一趟就此收场;预算用完、有路伸进没加载的
     * 区块而身体没处可推进时,搜索让这一趟的记忆里估价又升高了({@code learned})就再搜(估价变了,不是同一次搜索),什么也没学到就是
     * 原地不动、再搜也一样,收场。总预算用完由下一次派搜索时判({@link #spentOut})。
     */
    private void unsolved(SearchResult result, Search search, boolean learned) {
        if (learned && result.stop() != SearchResult.Stop.EXHAUSTED && result.stop() != SearchResult.Stop.STRANDED) {
            return;
        }
        giveUp(result, search);
    }

    /**
     * 一次搜索没交出路线,这一趟就此收场。走不下去之后重搜把能走的都搜过了还是没路,而最后那步走不下去是执行里出的事(卡住、
     * 看不见、落点变了……)或几何上的缘故,没路的原因就是那一步的阻碍;其余是搜索自己的停因,缺料、不许挖、挖不动、权限这类
     * 说"缺什么"的原因由门面在同一份快照上诊断({@link com.dwinovo.numen.pathing.plan.Reason#informative})。
     */
    private void giveUp(SearchResult result, Search search) {
        boolean executionFailed = lastBlockage != null && (lastBlockage.hitch() != null || !lastBlockage.reason().informative());
        halt(executionFailed && result.stop() == SearchResult.Stop.EXHAUSTED
                ? new Halt.Blocked(lastBlockage)
                : new Halt.Searched(result.stop(), breathless, search, result.blocking()));
    }

    /** 这一趟的总预算用完了:以最后一次搜索的结论收场。 */
    private void spentOut() {
        PathLog.info("{} 这一趟的总预算 {} 个节点用完了,还没到,收场", rig.who, allowance.used());
        halt(new Halt.Searched(SearchResult.Stop.BUDGET, breathless, lastSearch, lastResult.blocking()));
    }

    /**
     * 一次搜索的结论记一行:谁、为什么搜、从哪到哪、规格要点、展开几个节点、在工作线程上跑了多久(连同 CPU、JIT、回收、每节点的读数,见 {@link PathLog#load})与排队多久、为什么停、
     * 交出的路线;DEBUG 再记一行路线上的每一步。
     */
    private void logSearch(Search search, SearchResult result, Pending<SearchResult> done) {
        Route route = result.route();
        rig.journal.searched(result.expanded(), done.ranNanos(), !result.raised().isEmpty(), result.gap());
        String why = result.blocking() == null || result.reached() ? ""
                : " 离目标最近处朝目标的步被拒:" + PathLog.blocking(result.blocking());
        String what = route == null ? "没交出路线"
                : (result.arrived() ? "到目标 " : result.reached() ? "一段 " : "往边界推进 ") + PathLog.route(route);
        PathLog.info("{} 搜索 {} {} 去 {} {} 展开 {} 界 {} 用时 {}({}) 排队 {} 停因 {} {}{}", rig.who, purpose.label,
                PathLog.pos(search.start()), search.goal(), PathLog.spec(search.model().spec()), result.expanded(),
                Double.isNaN(result.gap()) ? "-" : String.format("%.2f", result.gap()),
                PathLog.ms(done.ranNanos()), PathLog.load(done, result.expanded()), PathLog.ms(done.queuedNanos()), result.stop(),
                what, result.raised().isEmpty() ? why : " 抬高了 " + result.raised().size() + " 格的估价" + why);
        if (route != null && PathLog.debugging()) {
            PathLog.debug("{} 路线 {}", rig.who, PathLog.legs(route));
        }
    }

    private void install(Route route, boolean arrived) {
        unsettled = 0;
        course.install(route, arrived);
        continuation.reset();
        step = null;
        tracker.reset();
    }

    /** 路线快走完、又没到目标:从终点提前搜下一段。 */
    private void lookahead() {
        if (pending == null && continuation.due(course)) {
            next();
        }
    }

    /** 扔掉当前路线,从身体脚下重新搜,旧路打折;{@code why} 记进日志。 */
    private void replan(String why) {
        rig.journal.replanned();
        PathLog.info("{} 重搜:{} 身体 {}", rig.who, why, PathLog.at(rig.entity.position()));
        reset();
    }

    /** 扔掉当前路线,下一刻从身体脚下重新搜,旧路打折。 */
    private void reset() {
        unsettled = 0;
        if (!course.isEmpty()) {
            favoring = Favoring.along(course.route());
        }
        abandon();
        rig.mouse.release();
        course.clear();
        continuation.reset();
        tracker.reset();
        step = null;
    }

    /**
     * 一步走不下去:这一步(走法、起点、落点)在这一趟里记为不通,以后的搜索不再走它;只扔掉在走的路,从身体此刻的位置重搜这一段。
     * 同一步不会走第二次,重搜多少次由这一趟的总预算管。
     */
    private void fail(Blockage blockage) {
        fail(course.current(), blockage);
    }

    /** 同 {@link #fail(Blockage)},没走成的是 {@code m} 这一步(路线走完之后停不到终点,是最后一步没走成)。 */
    private void fail(Maneuver m, Blockage blockage) {
        memory.block(m.kind(), m.from(), m.to());
        lastBlockage = blockage;
        rig.journal.blocked();
        PathLog.info("{} 走不下去 {} 这一步 {} -> 记为不通,从脚下重搜这一段 {}", rig.who, PathLog.blockage(blockage),
                PathLog.step(m), PathLog.body(rig.entity));
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
        abandon();
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
