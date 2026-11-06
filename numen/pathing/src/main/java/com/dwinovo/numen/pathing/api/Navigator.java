package com.dwinovo.numen.pathing.api;

import java.util.List;
import java.util.Optional;

import com.dwinovo.numen.pathing.body.Body;
import com.dwinovo.numen.pathing.drive.Admission;
import com.dwinovo.numen.pathing.drive.Voyage;
import com.dwinovo.numen.pathing.drive.LiveWorld;
import com.dwinovo.numen.pathing.drive.PathLog;
import com.dwinovo.numen.pathing.plan.BodySnapshot;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.search.Favoring;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Origin;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.search.Search;
import com.dwinovo.numen.pathing.search.Searches;
import com.dwinovo.numen.pathing.search.WorldSnapshot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * 寻路的门面:一具身体加宿主的端口。规划是查询——{@link #plan} 只搜不走,不占身体、不碰世界,交出路线与预算;
 * 执行是任务——{@link #drive} 交出一次在走的导航。请求、结局都是数据,
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

    /**
     * 只搜不走:从身体脚下(或接在 {@link PlanQuery#after} 那条路线后面)出路线。规划的是走({@link Travel#WALK}):快照按没有飞行能力算;
     * 身体正在飞的话,起点是它垂直落下去会落在的那一格({@link Origin#alighting},开出去时先落地)。
     */
    public Planning plan(PlanQuery query) {
        ServerPlayer entity = body.entity();
        BodySnapshot snapshot = body.snapshot().afoot();
        String who = PathLog.who(entity);
        BlockPos from;
        if (query.after() != null) {
            from = query.after().end();
        } else {
            LiveWorld world = new LiveWorld(entity.serverLevel());
            Optional<BlockPos> start = Snapshots.flying(entity)
                    ? Origin.alighting(world, snapshot.stats(), entity.getX(), entity.getY(), entity.getZ())
                    : Origin.of(world, snapshot.stats(), entity.getX(), entity.getY(), entity.getZ());
            if (start.isEmpty()) {
                BlockPos at = entity.blockPosition();
                PathLog.info("{} 规划 去 {}:起点待不住 {}", who, query.goal(), PathLog.body(entity));
                return new Planning(new PlanResult(null,
                        new Outcome.Stranded(at, entity.level().getBlockState(at)), null));
            }
            from = start.get();
        }
        long t0 = System.nanoTime();
        WorldSnapshot view = WorldSnapshot.around(entity.serverLevel(), from, WorldSnapshot.SEARCH_RADIUS);
        CostModel model = CostModel.of(query.spec(), snapshot, ports.terrain(), ports.materials(), ports.threats());
        PathLog.mainThread(who, "规划时拷快照与组成本模型", System.nanoTime() - t0);
        Search search = new Search(view, model, from, query.goal(), query.budget(), Favoring.NONE).after(query.after(), 0);
        return new Planning(Searches.submit(search, Searches.Urgency.NOW), search, who);
    }

    /**
     * 受理一次飞({@link Travel#FLY} 创造飞行、{@link Travel#GLIDE} 鞘翅滑翔):创造飞行要身体许飞,滑翔要穿着可用的鞘翅、排得出一份滑翔计划。
     * 缺什么({@link com.dwinovo.numen.pathing.drive.Lack})当场交回,宿主据此拒绝;受理了的结论交给 {@link NavRequest#travelling},开出去的导航直接用它的计划。
     */
    public Admission admit(Travel travel, Goal goal) {
        return Voyage.admit(body, travel, ports.terrain(), ports.materials(), ports.threats(), goal);
    }

    /** 去:交出一次在走的导航,宿主每刻 {@link Navigation#tick} 一次。 */
    public Navigation drive(NavRequest request) {
        ServerPlayer entity = body.entity();
        PathLog.debug("{} 出发 去 {} {} 预算 {}{} 刻速 {} {}", PathLog.who(entity), request.goal(),
                PathLog.spec(request.spec()), request.budget(),
                request.route() != null ? " 先照这条 " + PathLog.route(request.route()) : "",
                PathLog.num(entity.level().tickRateManager().tickrate()), PathLog.body(entity));
        return new Navigation(new Voyage(body, request.travel(), request.admission(), ports.terrain(), ports.materials(), ports.threats(),
                request.goal(), request.spec(), request.budget(), request.route(), request.through(), ports.clock()),
                entity, ports.trace());
    }
}
