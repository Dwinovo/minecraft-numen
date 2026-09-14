package com.dwinovo.numen.core.tools.work;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.pathing.bridge.ContextFactory;
import com.dwinovo.numen.core.pathing.bridge.PoolSearchDispatcher;
import com.dwinovo.numen.core.pathing.execute.PathExecutor;
import com.dwinovo.numen.core.pathing.execute.TerrainBill;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.pathing.moves.Movement;
import com.dwinovo.numen.core.pathing.plan.RouteBook;
import com.dwinovo.numen.core.pathing.plan.RoutePlanner;
import com.dwinovo.numen.core.pathing.spec.RouteSpec;
import com.dwinovo.numen.core.task.move.MoveToTaskRecord;
import com.dwinovo.numen.core.tools.RouteSpecJson;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;

/**
 * 只读的规划查询:收 goto 的坐标目标与规格,只搜不走,回执列候选路线与预算账,id 记进路线簿
 * 供 {@code goto route:<id>} 取用。查询不独占身体(宪法 §七第一问),所以不进任务槽——搜索在
 * 后台跑,由 {@link RoutePlanner#deliver} 在出结论那一刻回复。
 */
public final class PlanRouteTool implements NumenTool {

    private static final Gson GSON = new Gson();

    private record Args(Double x, Double y, Double z, JsonObject spec, Integer alternatives) {}

    @Override
    public String name() {
        return "plan_route";
    }

    @Override
    public String description() {
        return """
                Price a walk WITHOUT taking it: plans up to three routes to a destination under a spec and lists each with its length and exactly which blocks it would break or place. Nothing moves. Use it to compare options before committing (a dry route vs a tunnel, around a house vs through it), or to see what a spec change buys. Each route gets an id (r1, r2, ...); walk one with goto route:<id> while you are still standing where you planned it.
                Destination is one of: x+z (a location, Y resolves to the surface), x+y+z (an exact cell), y alone (a height). For the nearest block of a kind use goto block:... directly — it has to scan first.
                spec is the same object goto takes; omit it to price the default walk that never changes a block, or pass {alter:'natural'} to see what digging or bridging would cost.""";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .nullableNumber("x", "Target X. Null for a height-only move (y alone).")
                .nullableNumber("y", "Target Y. LEAVE NULL for a location (x+z); set it only for an exact "
                        + "cell (x+y+z) or a height move (y alone).")
                .nullableNumber("z", "Target Z. Null for a height-only move (y alone).")
                .optionalObject("spec", "Route spec, same as goto's. Omit for the default (never alters "
                        + "terrain).", RouteSpecJson::schema)
                .optionalInteger("alternatives", "How many distinct routes to return (1-"
                        + RoutePlanner.MAX_ALTERNATIVES + "). Default 1. More routes take longer to plan.",
                        1, RoutePlanner.MAX_ALTERNATIVES)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        MoveToTaskRecord.Kind kind = MoveToTaskRecord.resolveKind(a.x(), a.y(), a.z(), null, null);
        int bx = a.x() == null ? 0 : (int) Math.floor(a.x());
        int by = a.y() == null ? 0 : (int) Math.floor(a.y());
        int bz = a.z() == null ? 0 : (int) Math.floor(a.z());
        GoalCompiler.Compiled goal = MoveToTaskRecord.compile(kind, bx, by, bz);
        RouteSpec spec = RouteSpecJson.parse(a.spec());
        int alternatives = a.alternatives() == null ? 1 : a.alternatives();

        RoutePlanner planner = new RoutePlanner(PoolSearchDispatcher.INSTANCE,
                s -> ContextFactory.forSearch(companion, s), companion.level(),
                () -> com.dwinovo.numen.permission.Permission.gateFor(companion));
        BlockPos feet = PathExecutor.playerFeet(companion);
        BlockPos start = Movement.pathStart(companion, spec);
        RoutePlanner.Query query = planner.plan(feet, start, goal, spec, alternatives);
        RoutePlanner.deliver(query, candidates -> reply.accept(result(companion, goal, spec, feet, candidates,
                query.exceededBudget() ? query.cheapestChange() : -1)));
    }

    /** 回执:候选记进路线簿,清单文案由账单出;没路时说清在哪个规格下没路、下一步能试什么。 */
    /** @param cheapestOverBudget 一条候选都没留下且是预算所致时,作废候选里最少的改动格数;否则 -1 */
    private static String result(NumenPlayer companion, GoalCompiler.Compiled goal, RouteSpec spec,
                                 BlockPos feet, List<RoutePlanner.Candidate> candidates,
                                 int cheapestOverBudget) {
        BlockPos center = goal.goal().center();
        if (candidates.isEmpty()) {
            String hint = cheapestOverBudget >= 0
                    ? " — " + TerrainBill.overBudget(spec.alterBudget(), cheapestOverBudget)
                            + "; raise alter_budget or pick another destination."
                    : spec.alter().mayAlter()
                    ? " — not even by digging or bridging; pick another destination or a nearer waypoint."
                    : " without altering terrain; plan again with spec {alter:'natural'} to see what digging"
                            + " or bridging would take, or pick another destination.";
            return TaskResult.fail(String.format("no route from %s toward %s (about %.0f blocks away)%s",
                    feet.toShortString(), center.toShortString(), Math.sqrt(feet.distSqr(center)), hint))
                    .toJson();
        }
        RouteBook book = RouteBook.of(companion);
        long now = companion.level().getGameTime();
        Map<String, TerrainBill> byId = new LinkedHashMap<>();
        for (RoutePlanner.Candidate c : candidates) {
            RouteBook.Route route = book.add(goal, c.spec(), c.path(), c.bill(), now);
            byId.put(route.id(), route.bill());
        }
        return TaskResult.ok(TerrainBill.planned(feet, center, byId),
                Map.of("routes", List.copyOf(byId.keySet()))).toJson();
    }
}
