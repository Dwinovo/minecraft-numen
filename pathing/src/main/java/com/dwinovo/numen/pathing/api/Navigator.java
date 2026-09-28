package com.dwinovo.numen.pathing.api;

import java.util.List;
import java.util.Optional;

import com.dwinovo.numen.pathing.body.Body;
import com.dwinovo.numen.pathing.drive.Driver;
import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.pathing.drive.LiveWorld;
import com.dwinovo.numen.pathing.drive.PathLog;
import com.dwinovo.numen.pathing.drive.TakeBack;
import com.dwinovo.numen.pathing.plan.BodySnapshot;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.search.Favoring;
import com.dwinovo.numen.pathing.search.Origin;
import com.dwinovo.numen.pathing.search.RoutePlanner;
import com.dwinovo.numen.pathing.search.Search;
import com.dwinovo.numen.pathing.search.Searches;
import com.dwinovo.numen.pathing.search.WorldSnapshot;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * 寻路的门面:一具身体加宿主的端口。规划是查询——{@link #plan} 只搜不走,不占身体、不碰世界,交出候选路线与预算账;
 * 执行是任务——{@link #drive} 交出一次在走的导航,{@link #takeBack} 交出一次撤回路上垫块。请求、结局、账单都是数据,
 * 模块里没有给模型或玩家看的话。
 *
 * <p>只在世界所在的线程上调用;搜索在工作线程上跑,读的是派发那一刻在世界线程上拷下的快照。
 */
public final class Navigator {

    private final Body body;
    private final Ports ports;

    private Navigator(Body body, Ports ports) {
        this.body = body;
        this.ports = ports;
    }

    public static Navigator of(Body body, Ports ports) {
        return new Navigator(body, ports);
    }

    /** 只搜不走:从身体脚下出候选路线。 */
    public Planning plan(PlanQuery query) {
        ServerPlayer entity = body.entity();
        BodySnapshot snapshot = body.snapshot();
        Optional<BlockPos> start = Origin.of(new LiveWorld(entity.serverLevel()), snapshot.stats(),
                entity.getX(), entity.getY(), entity.getZ());
        String who = PathLog.who(entity);
        if (start.isEmpty()) {
            BlockPos at = entity.blockPosition();
            PathLog.info("{} 规划 去 {}:起点待不住 {}", who, query.goal(), PathLog.body(entity));
            return new Planning(new PlanResult(List.of(),
                    new Outcome.Stranded(at, entity.level().getBlockState(at))));
        }
        BlockPos from = start.get();
        long t0 = System.nanoTime();
        WorldSnapshot view = WorldSnapshot.around(entity.serverLevel(), from);
        CostModel model = CostModel.of(query.spec(), snapshot, ports.terrain(), ports.materials(), ports.threats());
        PathLog.mainThread(who, "规划时拷快照与组成本模型", System.nanoTime() - t0);
        RoutePlanner.Query planned = new RoutePlanner.Query(view, model, from, query.goal(), query.budget(),
                query.candidates());
        Search probe = new Search(view, model, from, query.goal(), query.budget(), Favoring.NONE);
        return new Planning(Searches.submit(planned), probe, who);
    }

    /**
     * 撤掉路上垫下的方块:交进来的通常是一次或几次导航实际账里的 {@link EditLedger#placedBlocks()}。走过去用 {@code spec},
     * 它必须只走不改。什么时候撤由宿主定。
     */
    public Teardown takeBack(List<EditLedger.Placed> placed, RouteSpec spec) {
        return new Teardown(new TakeBack(body, ports.effector(), ports.terrain(), ports.threats(), placed, spec,
                NavRequest.DEFAULT_BUDGET));
    }

    /** 去:交出一次在走的导航,宿主每刻 {@link Navigation#tick} 一次。 */
    public Navigation drive(NavRequest request) {
        ServerPlayer entity = body.entity();
        PathLog.debug("{} 出发 去 {} {} 预算 {}{} 刻速 {} {}", PathLog.who(entity), request.goal(),
                PathLog.spec(request.spec()), request.budget(),
                request.route() != null ? " 先照候选 " + PathLog.route(request.route()) : "",
                PathLog.num(entity.level().tickRateManager().tickrate()), PathLog.body(entity));
        return new Navigation(new Driver(body, ports.effector(), ports.terrain(), ports.materials(), ports.threats(),
                request.goal(), request.spec(), request.budget(), request.route()), entity);
    }
}
