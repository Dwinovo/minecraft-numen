package com.dwinovo.numen.pathing.drive;

import java.util.List;

import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.plan.WorldView;
import com.dwinovo.numen.pathing.search.Deadline;
import com.dwinovo.numen.pathing.search.Favoring;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Pending;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.search.Search;
import com.dwinovo.numen.pathing.search.SearchClock;
import com.dwinovo.numen.pathing.search.SearchResult;
import com.dwinovo.numen.pathing.search.Searches;
import com.dwinovo.numen.pathing.search.TripMemory;
import com.dwinovo.numen.pathing.search.WorldSnapshot;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;

/**
 * 一趟的规划那一半,不带身体:怎么搜、搜到哪交出、接着往下搜、什么时候收场,都在这里。每次搜索的目标都是真终点,时限由表定
 * ({@link Allowance}):从身体脚下搜的首段想几十毫秒,到了就交整条,没到就交半程让身体先动;半程一交出,马上从它的终点在后台接着搜,
 * 接在后面({@link Continuation}),前后两截之间不停顿。路线与进度是 {@link Course},这一趟的记忆是 {@link TripMemory},
 * 搜索的结论怎么变成路线、变成收场({@link Halt}),也只在这里判。
 *
 * <p>身体与世界从 {@link Ground} 进来:搜索读哪份快照、按哪份成本模型、身体此刻站在哪个节点。执行({@link Driver})是它的一个用户——
 * 身体真走、认步、停稳;只搜不走的评测是另一个:用一具虚拟的身体按路线推进,同一份路线、同一条接续、同一张总预算。
 * 一趟里重搜的原因只有 {@link Journal.Replan} 这几种,统一在 {@link #replan} 记账与记日志。
 */
public final class Trip {

    /** 一趟向身体与世界要的东西。只在世界所在的线程上调用。 */
    public interface Ground {

        /** 为从 {@code from} 出发的一次搜索拷下的世界快照。 */
        WorldSnapshot snapshot(BlockPos from);

        /** 按身体与宿主端口此刻的样子组成的成本模型。 */
        CostModel model();

        /** 身体此刻稳稳站着的节点与站法;悬在半空、卡在方块里、没停稳为 null。 */
        Standing standing();

        /** 排障日志里身体此刻的样子。 */
        String body();

        /** 一次派发在主线程上拷快照、组成本模型各用了多少纳秒。 */
        default void dispatched(long snapshotNanos, long modelNanos) {}
    }

    /** 身体稳稳站着的节点与站法。 */
    public record Standing(BlockPos node, Stance stance) {}

    /** {@link #poll} 的结果:路线与执行要不要跟着动。 */
    public enum Event {
        /** 没有新结论,或结论没改路线。 */
        NONE,
        /** 手上的路线接长了,或搜出的是"已经站在目标里"的空路线:在走的步照旧。 */
        EXTENDED,
        /** 换了一条新路线:执行从它的第一步起。 */
        FRESH,
        /** 手上的路线扔掉了(接续证明终点是死路),从身体此刻的位置重搜。 */
        DISCARDED,
        /** 这一趟收场了,原因是 {@link #halt}。 */
        HALTED
    }

    /** 为什么派这次搜索。 */
    private enum Purpose {
        /** 从身体脚下搜(首段,或走不下去之后重搜):身体站着等它。 */
        FROM_BODY("从脚下", Searches.Urgency.NOW),
        /** 从当前路线的终点接着搜:身体边走边等它。 */
        NEXT("接续", Searches.Urgency.AHEAD);

        /** 日志里的叫法。 */
        final String label;
        /** 在派发口排哪一级。 */
        final Searches.Urgency urgency;

        Purpose(String label, Searches.Urgency urgency) {
            this.label = label;
            this.urgency = urgency;
        }
    }

    private final Ground ground;
    private final String who;
    private final Journal journal;
    private final RouteSpec spec;
    private final Allowance allowance;
    private Goal goal;

    private final Course course = new Course();
    private final Continuation continuation = new Continuation();
    /** 这一趟的记忆:搜索与执行共用。 */
    private final TripMemory memory = new TripMemory();

    private Pending<SearchResult> pending;
    private Purpose purpose;
    private Search pendingSearch;
    private Favoring favoring = Favoring.NONE;
    /** 最近一次搜索的输入与结论:总预算用完收场时诊断它。 */
    private Search lastSearch;
    private SearchResult lastResult;
    /** 最近一次没到目标的搜索里,离目标最近处朝目标的那一步为什么被拒;还没有为 null。一趟走得久了要对人说"卡在哪",读它。 */
    private Blockage nearestRefusal;
    /** 最近一次没走成的一步的原因;之后走成了一步就清掉。从那里重搜把能走的都搜过了还是没路,没路的原因就是它。 */
    private Blockage lastBlockage;
    /** 这一趟里哪一次搜索因为憋不住气丢下过前提成立的步子:收场诊断"憋得住的话有没有路"看的是整趟,不只最后一次。 */
    private boolean breathless;
    private Halt halt;

    /**
     * @param who    日志里的"谁"
     * @param budget 这一趟总共最多展开几个节点;0 是按起点到终点的估价定({@link Allowance})
     * @param clock  搜索看的表(宿主的端口):只定每次想多久
     */
    public Trip(Ground ground, String who, Journal journal, Goal goal, RouteSpec spec, int budget, SearchClock clock) {
        this.ground = ground;
        this.who = who;
        this.journal = journal;
        this.goal = goal;
        this.spec = spec;
        this.allowance = new Allowance(budget, clock);
    }

    // ==================== 查 ====================

    public Goal goal() {
        return goal;
    }

    public RouteSpec spec() {
        return spec;
    }

    /** 路线与进度。 */
    public Course course() {
        return course;
    }

    /** 收场的原因({@link Event#HALTED} 之后);没收场为 null。 */
    public Halt halt() {
        return halt;
    }

    /** 这一趟里最近一次"离目标最近处被拒"的那一步;没有为 null。 */
    public Blockage nearestRefusal() {
        return nearestRefusal;
    }

    /** 手上没有路,或路走完了还没到目标:要从身体脚下搜。 */
    public boolean stalled() {
        return course.isEmpty() && !course.complete() || course.finished() && !course.complete();
    }

    /** 有一次搜索在飞。 */
    public boolean searching() {
        return pending != null;
    }

    /**
     * 身体此刻站着等一次搜索的结论:没有路可走(或路走完了还没到),或路线末尾的几步要等接续段到了才开始({@link Continuation#settled}),
     * 派出去的搜索还没回来。
     */
    public boolean waiting() {
        return pending != null && (course.finished() || !continuation.settled(course));
    }

    /** 能开始走第 {@link Course#cur} 步:后面接什么已经定下来({@link Continuation#settled})。 */
    public boolean settled() {
        return continuation.settled(course);
    }

    /** 身体不停下来还能走多久(几何最短的刻数,{@link Continuation#aheadTicks}):接续的时限按它定。 */
    public double aheadTicks() {
        return Continuation.aheadTicks(course);
    }

    /** 这一趟的总预算用完了。 */
    public boolean spent() {
        return allowance.spent();
    }

    /** 排障用:在飞的搜索的用途;没有是 "-"。 */
    String pending() {
        return pending == null ? "-" : purpose.toString();
    }

    // ==================== 派搜索 ====================

    /** 从身体站着的 {@code node} 搜(首段,或重搜):身体站着等它。总预算用完与否由调用方先判({@link #spent})。 */
    public void fromBody(BlockPos node) {
        dispatch(Purpose.FROM_BODY, node, null, allowance.first());
    }

    /** 手上的路线没到目标,又没有搜过接续:从它的终点接着搜(边走边搜)。 */
    public void continueAhead() {
        if (pending == null && continuation.due(course)) {
            next();
        }
    }

    /**
     * 从当前路线的终点接着搜;这一趟的总预算用完了就不再接,路线走到头在那里收场。
     */
    private void next() {
        if (allowance.spent()) {
            continuation.skip();
            return;
        }
        journal.replanned(Journal.Replan.CONTINUE);
        dispatch(Purpose.NEXT, course.end(), course.route(), allowance.next(Continuation.aheadTicks(course)));
    }

    /**
     * 从 {@code from} 派一次搜索:在世界所在的线程上拷下以它为中心的快照,成本模型按此刻的身体与端口现组;目标是真终点,
     * 带着这一趟的记忆,最多想到 {@code deadline}。接着手上的路线往下搜时起点是它的终点,读的世界叠上身体从此刻这一步走到那里要做的
     * 改动({@link Search#after(Route, int)});从身体脚下搜时 {@code ahead} 为 null(憋气从身体此刻的样子起)。
     */
    private void dispatch(Purpose why, BlockPos from, Route ahead, Deadline deadline) {
        long t0 = System.nanoTime();
        WorldSnapshot view = ground.snapshot(from);
        long t1 = System.nanoTime();
        CostModel model = ground.model();
        long t2 = System.nanoTime();
        ground.dispatched(t1 - t0, t2 - t1);
        PathLog.debug("{} 派搜索 {} 从 {} 去 {} 时限 {} 拷快照 {} 组成本模型 {}", who, why.label, PathLog.pos(from), goal,
                PathLog.ms(deadline.nanos()), PathLog.ms(t1 - t0), PathLog.ms(t2 - t1));
        Search search = new Search(view, model, from, goal, allowance.nodes(), favoring)
                .advancing(Allowance.advance(model.body().stats())).remembering(memory.frozen()).after(ahead, course.cur())
                .within(deadline);
        pendingSearch = search;
        purpose = why;
        pending = Searches.submit(search, why.urgency);
    }

    /** 在飞的搜索不要了:叫停,连它的输入(快照、成本模型)一起放手。 */
    public void abandon() {
        if (pending != null) {
            pending.cancel();
            pending = null;
            pendingSearch = null;
        }
    }

    // ==================== 收结论 ====================

    /** 在飞的搜索有了结论就收下:路线怎么变、要不要收场,都在这里定。 */
    public Event poll() {
        if (pending == null) {
            return Event.NONE;
        }
        SearchResult result = pending.poll();
        if (result == null) {
            return Event.NONE;
        }
        // 搜索交了结论,它的输入(快照、成本模型)不再由这里握着;收场要诊断的,各自带着
        Search search = pendingSearch;
        pendingSearch = null;
        logSearch(search, result, pending);
        pending = null;
        lastSearch = search;
        lastResult = result;
        if (result.blocking() != null && !result.arrived()) {
            nearestRefusal = Blockage.refused(search, result.blocking());
        }
        allowance.spend(result.expanded());
        if (Double.isFinite(result.startEstimate())) {
            allowance.begun(result.startEstimate(), search.advance());
        }
        breathless |= result.breathless();
        boolean learned = memory.raise(result.raised());
        if (result.route() != null) {
            allowance.found();
        } else if (result.stop() == SearchResult.Stop.TIME || result.stop() == SearchResult.Stop.BUDGET) {
            // 没搜完,半程也截不出来:下一次多想一倍(Azalea 的加时重试),放不放弃只由总预算定
            allowance.missed();
        }
        if (purpose == Purpose.NEXT) {
            if (result.stop() == SearchResult.Stop.EXHAUSTED) {
                // 从路线的终点往外走得到的地方都搜过了,没有目标:那里是死路,记进记忆了(估价无穷大)。身体还没走进去,
                // 从此刻的位置重搜,绕开它——走进去就出不来了(往下挖的竖井、封死的坑)
                replan(Journal.Replan.DEAD_END, "接续证明路线的终点 " + PathLog.pos(course.end()) + " 是死路");
                return Event.DISCARDED;
            }
            if (continuation.splice(course, result.route(), result.arrived())) {
                return Event.EXTENDED;
            }
            if (result.route() == null) {
                PathLog.info("{} 接续没搜出路线,走到终点 {} 再从脚下搜", who, PathLog.pos(course.end()));
            } else {
                // 接续的起点不是路线的终点:走到终点后从脚下再搜
                PathLog.info("{} 接续接不上,走到终点 {} 再从脚下搜", who, PathLog.pos(course.end()));
            }
            return Event.NONE;
        }
        if (result.route() == null) {
            Standing here = ground.standing();
            if (here != null && goalHas(here.node(), here.stance())) {
                // 最后一步进了目标的同一刻,这次搜索没交出路:她已经到了
                PathLog.debug("{} 搜索没交出路,可她已经站在目标里 {}", who, PathLog.pos(here.node()));
                course.clear();
                course.install(new Route(here.node(), here.stance(), List.of()), true);
                return Event.EXTENDED;
            }
            return unsolved(result, search, learned);
        }
        install(result.route(), result.arrived());
        return Event.FRESH;
    }

    /**
     * 一次从脚下的搜索没交出路线。搜完了能走到的地方都没有目标、或根本动不了,这一趟就此收场。时间到、节点上限用完只是没搜完,
     * 与机器快慢有关,不拿它收场:下一刻加时再搜({@link Allowance#missed})。有路伸进没加载的区块而身体没处可推进时,搜索让这一趟的
     * 记忆里估价又升高了({@code learned})就再搜(估价变了,不是同一次搜索),什么也没学到就是原地不动、再搜也一样,收场。
     * 总预算用完由下一次派搜索前判({@link #spentOut})。
     */
    private Event unsolved(SearchResult result, Search search, boolean learned) {
        switch (result.stop()) {
            case TIME, BUDGET -> {
            }
            case UNLOADED -> {
                if (!learned) {
                    return giveUp(result, search);
                }
            }
            default -> {
                return giveUp(result, search);
            }
        }
        return Event.NONE;
    }

    /**
     * 一次搜索没交出路线,这一趟就此收场。走不下去之后重搜把能走的都搜过了还是没路,而最后那步走不下去是执行里出的事(卡住、
     * 看不见、落点变了……)或几何上的缘故,没路的原因就是那一步的阻碍;其余是搜索自己的停因,缺料、不许挖、挖不动、权限这类
     * 说"缺什么"的原因由门面在同一份快照上诊断({@link com.dwinovo.numen.pathing.plan.Reason#informative})。
     */
    private Event giveUp(SearchResult result, Search search) {
        boolean executionFailed = lastBlockage != null && (lastBlockage.hitch() != null || !lastBlockage.reason().informative());
        return end(executionFailed && result.stop() == SearchResult.Stop.EXHAUSTED
                ? new Halt.Blocked(lastBlockage)
                : new Halt.Searched(result.stop(), breathless, search, result.blocking()));
    }

    /** 这一趟的总预算用完了:以最后一次搜索的结论收场,交出原因。 */
    public Halt spentOut() {
        PathLog.info("{} 这一趟的总预算 {} 个节点用完了,还没到,收场", who, allowance.used());
        end(new Halt.Searched(SearchResult.Stop.BUDGET, breathless, lastSearch, lastResult.blocking()));
        return halt;
    }

    private Event end(Halt why) {
        halt = why;
        return Event.HALTED;
    }

    /**
     * 一次搜索的结论记一行:谁、为什么搜、从哪到哪、规格要点、展开几个节点、在工作线程上跑了多久(连同 CPU、JIT、回收、每节点的读数,见 {@link PathLog#load})与排队多久、为什么停、
     * 交出的路线;DEBUG 再记一行路线上的每一步。
     */
    private void logSearch(Search search, SearchResult result, Pending<SearchResult> done) {
        Route route = result.route();
        journal.searched(result.expanded(), done.ranNanos(), !result.raised().isEmpty(), result.checks());
        String why = result.blocking() == null || result.arrived() ? ""
                : " 离目标最近处朝目标的步被拒:" + PathLog.blocking(result.blocking());
        String what = route == null ? "没交出路线"
                : (result.arrived() ? "到目标 " : result.reached() ? "到这一段的终点 " : "往边界推进 ") + PathLog.route(route);
        PathLog.info("{} 搜索 {} {} 去 {} {} 展开 {} 时限 {} 用时 {}({}) 排队 {} 停因 {} {}{}", who, purpose.label,
                PathLog.pos(search.start()), search.goal(), PathLog.spec(search.model().spec()), result.expanded(),
                search.deadline() == Deadline.NONE ? "-" : PathLog.ms(search.deadline().nanos()),
                PathLog.ms(done.ranNanos()), PathLog.load(done, result.expanded()), PathLog.ms(done.queuedNanos()), result.stop(),
                what, result.raised().isEmpty() ? why : " 抬高了 " + result.raised().size() + " 格的估价" + why);
        if (route != null && PathLog.debugging()) {
            PathLog.debug("{} 路线 {}", who, PathLog.legs(route));
        }
    }

    // ==================== 路线 ====================

    /** 换成一条新路线,从第一步起;{@code arrived}:终点在目标里。 */
    void install(Route route, boolean arrived) {
        course.install(route, arrived);
        continuation.reset();
    }

    /** 身体走完了第 {@code to} 步之前的步子:进度前进,上一步没走成的原因清掉。 */
    public void advance(int to) {
        course.advanceTo(to);
        lastBlockage = null;
    }

    /** 第 {@code m} 步走不通:这一步(走法、起点、落点)在这一趟里记为不通,以后的搜索不再走它;原因留着,收场时用。 */
    void blocked(Maneuver m, Blockage blockage) {
        memory.block(m.kind(), m.from(), m.to());
        lastBlockage = blockage;
    }

    /** 按 {@code why} 重规划:记账、记日志({@code detail} 是这一次的经过),扔掉当前路线,下一刻从身体脚下重新搜,旧路打折。 */
    public void replan(Journal.Replan why, String detail) {
        journal.replanned(why);
        PathLog.info("{} 重搜({}):{} 身体 {}", who, why.label, detail, ground.body());
        discard();
    }

    /** 扔掉当前路线与在飞的搜索:下一刻从身体脚下重新搜,旧路打折。 */
    public void discard() {
        if (!course.isEmpty()) {
            favoring = Favoring.along(course.route());
        }
        abandon();
        course.clear();
        continuation.reset();
    }

    /**
     * 换目标(跟着的东西挪了):在走的这条路原本到得了目标,而它的终点在新目标里还算数、停在那儿没变贵
     * ({@link Goal#keepsStop}),就照走;否则调用方该扔掉它({@code false}),从身体脚下按新目标重搜,旧路打折,在飞的搜索作废。手上
     * 还没有路、从脚下的搜索在飞时,让它搜完,交回来的路走到终点再按那时的目标判。交进来的还是同一个目标(目标是值,每刻重编一次
     * 也相等),什么都不变。
     *
     * @return 在走的路与在飞的搜索照旧
     */
    boolean retarget(Goal next, WorldView world) {
        if (next.equals(goal)) {
            return true;
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
            PathLog.debug("{} 换目标 {} -> {},在飞的搜索搜完再说", who, before, next);
            return true;
        }
        BlockPos end = course.end();
        Stance endStance = course.endStance();
        if (course.complete() && end != null && endStance != null && Goal.keepsStop(world, before, next, end, endStance)) {
            PathLog.debug("{} 换目标 {} -> {},在走的路终点还算数,照走", who, before, next);
            return true;
        }
        // 跟随、战斗走位的目标随对方挪动一再换,记 DEBUG;换了之后的搜索照常记 INFO
        PathLog.debug("{} 换目标 {} -> {},从脚下重搜", who, before, next);
        return false;
    }

    boolean goalHas(BlockPos node, Stance stance) {
        return stance != null && goal.contains(node.getX(), node.getY(), node.getZ(), stance);
    }
}
