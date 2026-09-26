package com.dwinovo.numen.core.tools.work;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.pathing.astar.PathCalcResult;
import com.dwinovo.numen.core.pathing.bridge.ContextFactory;
import com.dwinovo.numen.core.pathing.bridge.PoolSearchDispatcher;
import com.dwinovo.numen.core.pathing.execute.PathExecutor;
import com.dwinovo.numen.core.pathing.execute.TerrainBill;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.pathing.moves.Movement;
import com.dwinovo.numen.core.pathing.plan.RouteBook;
import com.dwinovo.numen.core.pathing.plan.RoutePlanner;
import com.dwinovo.numen.core.pathing.spec.RouteSpec;
import com.dwinovo.numen.core.task.move.FollowTaskRecord;
import com.dwinovo.numen.core.task.move.MoveToTaskRecord;
import com.dwinovo.numen.core.tools.RouteSpecFlags;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Permission;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskResult;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * {@code move}:走到一处或一种方块旁边、跟着谁走、走之前先给路线报价。
 *
 * <p>三个动作都在服务端。{@code goto} 与 {@code follow} 占身体,交任务槽(受理即回执,收尾走 task_finished);
 * {@code route} 只搜不走,不占身体,搜索出结论那一刻回复。{@code goto} 提升成同名快捷工具——最常用的身体动作。
 * 路线规格的标志三处共用({@link RouteSpecFlags}),goto 与 route 在出厂规格(只走不改)上叠。
 */
public final class MoveCommands {

    static final String GROUP = "move";

    /** follow 默认跟到几米内。3 米大致是"就在旁边"又不至于挤到主人身上。 */
    private static final int DEFAULT_DISTANCE = 3;
    private static final int MIN_DISTANCE = 2;
    private static final int MAX_DISTANCE = 16;

    private static final Param<Integer> X = Param.optional("x", ArgType.integer(),
            "Target X. With z alone: go to that place, Y resolves to the surface. With y and z: stand in that exact cell.");
    private static final Param<Integer> Y = Param.optional("y", ArgType.integer(),
            "Target height. Leave it out to go to a place (x and z; Y resolves to the surface). With x and z: that "
                    + "exact cell. Alone: climb or descend to that height.");
    private static final Param<Integer> Z = Param.optional("z", ArgType.integer(), "Target Z; see x.");
    private static final Param<ResourceLocation> BLOCK = Param.optional("block", ArgType.id(),
            "Walk up BESIDE the block of this kind she can reach most easily, never touching it. Give it alone, "
                    + "no coordinates.");
    private static final Param<String> ROUTE = Param.optional("route", ArgType.word(),
            "A planned route to walk. Give it alone: its destination and route flags are already fixed.")
            .values("a route id (r1, r2, ...) from a goto refusal or a `move route` reply");
    private static final Param<Integer> DISTANCE = Param.optional("distance",
            ArgType.integer(MIN_DISTANCE, MAX_DISTANCE), "How close to stay, in blocks.")
            .whenOmitted("stay within " + DEFAULT_DISTANCE);
    private static final Param<Integer> ENTITY_ID = Param.optional("entity_id", ArgType.integer(),
            "Who to follow.")
            .values("a runtime entity id from scan_nearby_entities")
            .whenOmitted("follow your owner");
    private static final Param<Integer> ALTERNATIVES = Param.optional("alternatives",
            ArgType.integer(1, RoutePlanner.MAX_ALTERNATIVES), "How many distinct routes to return; more take "
                    + "longer to plan.")
            .whenOmitted("return one");

    private MoveCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Getting around: go to a place or beside a block, follow someone, price a "
                + "route before walking it.", MoveCommands::actions);
    }

    private static void actions(CommandGroup move) {
        move.server("goto", "Travel to one destination with full terrain pathfinding.", MoveCommands::goTo,
                        with(List.of(X, Y, Z, BLOCK, ROUTE), RouteSpecFlags.PARAMS))
                .example("move goto --x 120 --z -35")
                .example("move goto --block minecraft:crafting_table")
                .example("move goto --x 120 --y 12 --z -35 --alter natural --avoid_break minecraft:chest")
                .example("move goto --route r2")
                .note("Fill exactly one pattern: x and z (a place), block (beside the easiest one to reach), "
                        + "x, y and z (that exact cell; never aim it at a chest or anything you want to keep), "
                        + "y alone (a height), or route alone.")
                .note("Background work: returns at once; the end arrives as a task_finished event.")
                .note("Changes nothing in the world unless --alter natural or any. With no clean route it fails and "
                        + "lists candidate routes with the blocks each would break or place; a route with blocks "
                        + "that are someone's asks your owner before setting off.")
                .note("Started while sitting in a boat, she pilots it toward the target; any other vehicle is "
                        + "stepped off.")
                .seeAlso("move route", "task stop")
                .promote("goto", """
                        Travel to ONE new destination with full terrain pathfinding: walks, jumps, swims, climbs, opens doors and gates, parkours, and auto-equips tools. Which fields you fill IS your intent — fill exactly one pattern:
                        • x+z — go to a place. Y resolves to the surface. This is the DEFAULT for exploration or "go there"; omit y.
                        • block — e.g. block:'minecraft:crafting_table'. Walks up BESIDE the one she can reach most easily and never damages it. Easiest to reach is not always closest in a straight line, so it may not be the first block scan_blocks listed — give coordinates when it has to be a specific one.
                        • x+y+z — stand EXACTLY in that cell. A block occupying it would have to be dug out (only alter:'natural' allows that), so never aim this at a chest, furnace or anything you want to keep — use block or x+z for those.
                        • y — climb/descend to that elevation.
                        • route — walk a route by id (r1, r2, ...) from an earlier refusal or a `move route` reply. Give it ALONE: its destination and route fields are already fixed. A route is spent once walked, and only valid while you still stand where it was planned.
                        TERRAIN: the walk never changes the world unless you say so — walls, floors, other people's builds and the landscape stay exactly as they were. When there is no clean route, the call FAILS and lists candidate routes, each with an id, its length and exactly which blocks it would break or place — blocks that are someone's are marked as needing consent, and walking such a route asks the owner first; then either goto route:<id> or pick another destination. Underground travel and climbing out of pits usually need alter:'natural'. Every call reports what it actually broke or placed.
                        ROUTE FIELDS (all optional): alter 'none'|'natural'|'any' — may the walk dig, bridge, pillar ('any' also through blocks that need the owner's consent, asking first); avoid — cell types to keep out of (water, flowing_water, lava, hazard, door, ladder, vine, snow_layer); penalty_place / penalty_break / penalty_jump / penalty_wade — make an action pricier so she detours instead; avoid_break / avoid_place / avoid_step — blocks (ids or #tags) or cells/boxes ('x,y,z', 'x1,y1,z1..x2,y2,z2') she must not break, place into, or stand on; parkour — running jumps over gaps; climb_vines; max_fall — highest drop without water; alter_budget — how many blocks the whole route may change; routes over budget are dropped.
                        VEHICLES: start a goto while sitting in a boat (see <riding>) and she pilots it over the water toward the target — a destination on the water keeps her aboard, a destination ashore has her step off at the shore and finish on foot. Any other vehicle is stepped off the moment walking begins. Boarding is `use entity` right on the boat.
                        BACKGROUND: a successful call means movement is already running. Do not call goto again or launch another body action while <current_task> exists; wait for matching task_finished. status=done means that destination is complete, so advance the plan and never resend identical coordinates. Only status=timeout permits the same call to resume.""");
        move.server("follow", "Tag along with your owner, or with an entity you name, until given something else "
                        + "to do.", MoveCommands::follow, DISTANCE, ENTITY_ID)
                .example("move follow")
                .example("move follow --entity_id 184 --distance 5")
                .note("A standing job: there is nothing to finish, so it runs until another body action replaces "
                        + "it and never sends task_finished on its own. She goes quiet while already beside them.")
                .note("Following a named entity ends if it dies or leaves the loaded area; following your owner "
                        + "just waits while they are offline.")
                .note("Never breaks or places a block. When the only way to them needs digging, bridging or "
                        + "pillaring, it ends with a failure that lists candidate routes; goto one of them "
                        + "(ask your owner unless it is obviously natural terrain), then follow again.")
                .seeAlso("scan entities", "move goto", "task stop");
        move.server("route", "Price a walk without taking it: up to three routes to a destination, each with the "
                        + "blocks it would break or place.", MoveCommands::route,
                        with(List.of(X, Y, Z), RouteSpecFlags.PARAMS, List.of(ALTERNATIVES)))
                .example("move route --x 120 --z -35 --alter natural --alternatives 3")
                .example("move route --x 120 --y 12 --z -35")
                .note("Instant and read-only: nothing moves, nothing changes, the body stays free.")
                .note("Destination is x and z (a place), x, y and z (a cell), or y alone (a height). For the "
                        + "nearest block of a kind use `move goto` with --block, which has to scan first.")
                .note("Without route flags it prices the default walk that never changes a block; add "
                        + "--alter natural to see what digging or bridging would cost.")
                .note("Each route gets an id (r1, r2, ...); walk one with `move goto --route r1` while you still "
                        + "stand where you planned it.")
                .seeAlso("move goto");
    }

    /** 一个动作的参数表:自己的几个,再接上共用的那几串。 */
    @SafeVarargs
    private static Param<?>[] with(List<? extends Param<?>>... parts) {
        return Stream.of(parts).flatMap(List::stream).toArray(Param<?>[]::new);
    }

    /** 给了 route 就走那条路:它自带规格,所以不能再给规格标志。 */
    private static void goTo(ServerSource src, CommandArgs args) {
        String route = args.get(ROUTE);
        if (route != null && RouteSpecFlags.given(args)) {
            throw new IllegalArgumentException(
                    "a route already carries the route flags it was planned under — give route alone. To walk"
                    + " under different flags, goto the destination coordinates with them, or run `move route` again.");
        }
        ResourceLocation block = args.get(BLOCK);
        // 坐标、方块、路线几样怎么搭配由记录判(说不通的组合当场报错,教她合法的几种写法)
        RouteSpec spec = route == null ? RouteSpecFlags.parse(args, RouteSpec.defaults()) : null;
        TaskDispatch.setTask(src, new MoveToTaskRecord(src, args.get(X), args.get(Y), args.get(Z),
                block == null ? null : block.toString(), spec, route));
    }

    /**
     * 跟着走——纯<b>常驻</b>的活:它没有"干完"这回事,只有被主人换掉。所以没有 count、没有期限,派下去之后她就一直
     * 跟着,直到主人让她做别的({@code mine}、{@code work fish}……都会顶掉它)。
     *
     * <p>不给 {@code entity_id} 就是跟主人,给了就跟那一只——村民、狼、别的玩家都行。两者目标消失时的含义不同,
     * 见 {@code FollowTaskRecord#entityId}。
     */
    private static void follow(ServerSource src, CommandArgs args) {
        NumenPlayer companion = src.companion();
        Integer asked = args.get(DISTANCE);
        int distance = asked == null ? DEFAULT_DISTANCE : Math.clamp(asked, MIN_DISTANCE, MAX_DISTANCE);
        Integer entityId = args.get(ENTITY_ID);
        UUID targetUuid = null;
        if (entityId != null) {
            Entity target = companion.serverLevel().getEntity(entityId);
            if (target == null || target.isRemoved() || target == companion) {
                src.reply(TaskResult.fail("no entity with id " + entityId
                        + " is here — scan_nearby_entities first, ids do not survive restarts").toJson());
                return;
            }
            targetUuid = target.getUUID();
        }
        TaskDispatch.setTask(src, new FollowTaskRecord(src, distance, entityId, targetUuid));
    }

    /**
     * 只读的规划查询:收 goto 的坐标目标与规格,只搜不走,回执列候选路线与预算账,id 记进路线簿供 {@code goto route:<id>}
     * 取用。查询不独占身体(宪法 §七第一问),所以不进任务槽——搜索在后台跑,由 {@link RoutePlanner#deliver} 在出结论
     * 那一刻回复。
     */
    private static void route(ServerSource src, CommandArgs args) {
        NumenPlayer companion = src.companion();
        Integer x = args.get(X);
        Integer y = args.get(Y);
        Integer z = args.get(Z);
        MoveToTaskRecord.Kind kind = MoveToTaskRecord.resolveKind(x, y, z, null, null);
        GoalCompiler.Compiled goal = MoveToTaskRecord.compile(kind, x == null ? 0 : x, y == null ? 0 : y,
                z == null ? 0 : z);
        RouteSpec spec = RouteSpecFlags.parse(args, RouteSpec.defaults());
        int alternatives = args.get(ALTERNATIVES) == null ? 1 : args.get(ALTERNATIVES);

        RoutePlanner planner = new RoutePlanner(PoolSearchDispatcher.INSTANCE,
                s -> ContextFactory.forSearch(companion, s), companion.level(), () -> Permission.gateFor(companion));
        BlockPos feet = PathExecutor.playerFeet(companion);
        BlockPos start = Movement.pathStart(companion, spec);
        RoutePlanner.Query query = planner.plan(feet, start, goal, spec, alternatives);
        RoutePlanner.deliver(query, candidates -> src.reply(planned(companion, goal, spec, feet, candidates,
                query.exceededBudget() ? query.cheapestChange() : -1, query.unreached())));
    }

    /**
     * 规划的回执:候选记进路线簿,清单文案由账单出;没搜到路时说清在哪个规格下、搜索为什么停、下一步能试什么。
     * 只有搜遍了才说没有路(见 {@link TerrainBill#searchStopped})。
     *
     * @param cheapestOverBudget 一条候选都没留下且是预算所致时,作废候选里最少的改动格数;否则 -1
     * @param stop               收工的那次搜索为什么停({@link RoutePlanner.Query#unreached})
     */
    private static String planned(NumenPlayer companion, GoalCompiler.Compiled goal, RouteSpec spec,
                                  BlockPos feet, List<RoutePlanner.Candidate> candidates,
                                  int cheapestOverBudget, PathCalcResult.Stop stop) {
        BlockPos center = goal.goal().center();
        if (candidates.isEmpty()) {
            String hint;
            if (cheapestOverBudget >= 0) {
                hint = " — " + TerrainBill.overBudget(spec.alterBudget(), cheapestOverBudget)
                        + "; raise --alter_budget or pick another destination.";
            } else if (stop != PathCalcResult.Stop.EXHAUSTED) {
                hint = " — " + TerrainBill.searchStopped(stop) + ".";
            } else if (spec.alter().mayAlter()) {
                hint = " — not even by digging or bridging (" + TerrainBill.searchStopped(stop)
                        + "); pick another destination.";
            } else {
                hint = " without altering terrain (" + TerrainBill.searchStopped(stop) + "); plan again with"
                        + " --alter natural to see what digging or bridging would take, or pick another"
                        + " destination.";
            }
            return TaskResult.fail(String.format("found no route from %s toward %s (about %.0f blocks away)%s",
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
