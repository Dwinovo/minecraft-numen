package com.dwinovo.numen.core.gametest;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

import java.util.UUID;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.route.Itinerary;
import com.dwinovo.numen.core.route.Plan;
import com.dwinovo.numen.core.route.Routes;
import com.dwinovo.numen.core.task.move.MoveToTaskRecord;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskRecord;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 路线:{@code route new} + {@code route plan} 只规划不动身体;{@code move go} 照计划走,只改计划里的格;路上世界变了、要承诺外的格时
 * 停下并说是哪几格;从别处出发、新计划超出承诺时不走并说出差别;途经点照顺序走;{@code move goto} 简写与分开三步结局相同;
 * 重启后 {@code move go} 照常重放。都从工具入口进。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class RouteGameTests {

    private static final String BATCH = "numen_route";

    @BeforeBatch(batch = BATCH)
    public static void prepare(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    private static Routes routes(NumenPlayer companion) {
        return Routes.of(companion.getServer(), companion.getOwnerUuid());
    }

    /** 木板屋的屋外那一格(屋子围着 7,7,她在屋里)。 */
    private static final BlockPos OUTSIDE = new BlockPos(13, 2, 7);

    /**
     * 规划不动身体:关在木板屋里,{@code route new} 带 {@code --alter natural},{@code route plan}。回执列出要挖的木板;她一步没动,
     * 墙一块不少;计划记在路线上,{@code route show} 照着说。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void planning_a_route_moves_nothing_and_lists_the_digs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        plankRoomAround(helper, 7, 7);
        int planksBefore = plankCount(helper, 7, 7);
        NumenPlayer companion = spawnAt(helper, "gametest_surveyor2", new BlockPos(7, 2, 7), false);
        BlockPos start = companion.blockPosition();
        ToolRun made = command(companion, "route new out --to " + at(helper, OUTSIDE) + " --alter natural");
        ToolRun plan = command(companion, "route plan out");

        succeedWhen(helper, () -> {
            helper.assertTrue(made.succeeded(), "route new failed: " + made.outcome());
            helper.assertTrue(plan.done(), "route plan has not replied");
            helper.assertTrue(plan.succeeded() && plan.reply().contains("break")
                            && plan.reply().contains("oak_planks") && plan.reply().contains("move go out"),
                    "the plan does not list the planks it would break: " + plan.reply());
            helper.assertTrue(companion.blockPosition().equals(start), "planning moved the body");
            helper.assertTrue(plankCount(helper, 7, 7) == planksBefore, "planning altered the wall");
            Itinerary route = routes(companion).get("out");
            helper.assertTrue(route.plan() != null && !route.plan().digs().isEmpty(),
                    "the plan was not kept on the route: " + route);
            String shown = command(companion, "route show out").reply();
            helper.assertTrue(shown.contains("plan of route out") && shown.contains("oak_planks"),
                    "route show does not tell the plan: " + shown);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 照计划走:同一间屋,规划之后 {@code move go}。她拆墙出去到达,拆掉的每一块都在计划里,回执如实记账并点名路线;
     * 这一趟记进路线走过的记录。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void move_go_walks_the_plan_and_changes_only_its_cells(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        plankRoomAround(helper, 7, 7);
        int planksBefore = plankCount(helper, 7, 7);
        NumenPlayer companion = spawnAt(helper, "gametest_keeper", new BlockPos(7, 2, 7), false);
        BlockPos target = helper.absolutePos(OUTSIDE);
        command(companion, "route new out --to " + xyz(target) + " --alter natural");
        ToolRun plan = command(companion, "route plan out");
        ToolRun[] walk = new ToolRun[1];
        LongSet[] promised = new LongSet[1];

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(plan.done(), "route plan has not replied"))
                .thenExecute(() -> {
                    promised[0] = routes(companion).get("out").plan().digs();
                    walk[0] = command(companion, "move go out");
                })
                .thenWaitUntil(() -> helper.assertTrue(walk[0].done(), "move go has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(walk[0].succeeded() && companion.blockPosition().distSqr(target) <= 2,
                            "she did not walk the route out: " + walk[0].outcome());
                    helper.assertTrue(walk[0].outcome().contains("via route out")
                                    && walk[0].outcome().contains("En route") && walk[0].outcome().contains("oak_planks"),
                            "the reply does not name the route and what was broken: " + walk[0].outcome());
                    helper.assertTrue(plankCount(helper, 7, 7) < planksBefore, "no plank was broken");
                    for (int x = 5; x <= 9; x++) {
                        for (int z = 5; z <= 9; z++) {
                            for (int y = 2; y <= 4; y++) {
                                BlockPos cell = helper.absolutePos(new BlockPos(x, y, z));
                                boolean wall = x == 5 || x == 9 || z == 5 || z == 9;
                                helper.assertTrue(!wall || level.getBlockState(cell).is(Blocks.OAK_PLANKS)
                                                || promised[0].contains(cell.asLong()),
                                        "a plank outside the plan was broken at " + cell.toShortString());
                            }
                        }
                    }
                    Itinerary route = routes(companion).get("out");
                    helper.assertTrue(route.walks().size() == 1 && route.walks().get(0).arrived(),
                            "the walk was not written onto the route: " + route.walks());
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /**
     * 一条全封死的基岩走廊,中段一道两格高的泥土墙:计划挖那两格。她走起来之后,前面又被人砌了一道两格高的石墙——要挖它才过得去,
     * 它不在计划里。她停下,说要承诺外的哪几格;石墙一块不少。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void a_walk_stops_when_the_way_on_needs_cells_outside_the_plan(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 1; x <= 14; x++) {
            for (int z = 6; z <= 8; z++) {
                for (int y = 1; y <= 4; y++) {
                    boolean hollow = z == 7 && x >= 2 && x <= 13 && y >= 2 && y <= 3;
                    level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, y, z)),
                            hollow ? Blocks.AIR.defaultBlockState() : Blocks.BEDROCK.defaultBlockState());
                }
            }
        }
        for (int y = 2; y <= 3; y++) {
            level.setBlockAndUpdate(helper.absolutePos(new BlockPos(10, y, 7)), Blocks.DIRT.defaultBlockState());
        }
        NumenPlayer companion = spawnAt(helper, "gametest_promiser", new BlockPos(3, 2, 7), false);
        BlockPos start = companion.blockPosition();
        command(companion, "route new tunnel --to " + at(helper, new BlockPos(12, 2, 7)) + " --alter natural");
        ToolRun plan = command(companion, "route plan tunnel");
        ToolRun[] walk = new ToolRun[1];
        BlockPos low = helper.absolutePos(new BlockPos(7, 2, 7));
        boolean[] walled = new boolean[1];
        helper.onEachTick(() -> {
            if (walk[0] != null && !walled[0] && companion.blockPosition().distSqr(start) >= 4) {
                level.setBlockAndUpdate(low, Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(low.above(), Blocks.STONE.defaultBlockState());
                walled[0] = true;
            }
        });

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(plan.done(), "route plan has not replied"))
                .thenExecute(() -> {
                    helper.assertTrue(plan.succeeded() && plan.reply().contains("dirt"),
                            "the plan does not dig the dirt wall: " + plan.reply());
                    walk[0] = command(companion, "move go tunnel");
                })
                .thenWaitUntil(() -> helper.assertTrue(walk[0].done(), "move go has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(walled[0], "she never set off, so the wall was never put in her way");
                    String said = walk[0].outcome();
                    helper.assertTrue(!walk[0].succeeded() && said.contains("outside the plan")
                                    && said.contains("stone") && said.contains("route plan tunnel"),
                            "the stop does not say which cells lie outside the plan: " + said);
                    helper.assertTrue(level.getBlockState(low).is(Blocks.STONE)
                                    && level.getBlockState(low.above()).is(Blocks.STONE),
                            "she dug the wall that was not in the plan");
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /**
     * 从别处出发:在屋外规划(绕着屋子走,一格不改),再把她挪进木板屋里 {@code move go}。从屋里走要拆墙,超出了那份计划:
     * 她不走,说出多出来的是哪几格;墙一块不少,她还在屋里。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void from_elsewhere_a_walk_beyond_the_promise_does_not_set_off(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        plankRoomAround(helper, 7, 7);
        int planksBefore = plankCount(helper, 7, 7);
        NumenPlayer companion = spawnAt(helper, "gametest_stickler", new BlockPos(2, 2, 2), false);
        command(companion, "route new out --to " + at(helper, OUTSIDE) + " --alter natural");
        ToolRun plan = command(companion, "route plan out");
        ToolRun[] walk = new ToolRun[1];
        BlockPos inside = helper.absolutePos(new BlockPos(7, 2, 7));

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(plan.done(), "route plan has not replied"))
                .thenExecute(() -> {
                    helper.assertTrue(plan.succeeded() && plan.reply().contains("no terrain change"),
                            "from outside the room the plan should change nothing: " + plan.reply());
                    companion.teleportTo(inside.getX() + 0.5, inside.getY(), inside.getZ() + 0.5);
                })
                .thenIdle(5)
                .thenExecute(() -> walk[0] = command(companion, "move go out"))
                .thenWaitUntil(() -> helper.assertTrue(walk[0].done(), "move go has not finished"))
                .thenExecute(() -> {
                    String said = walk[0].outcome();
                    helper.assertTrue(!walk[0].succeeded() && said.contains("beyond the plan")
                                    && said.contains("oak_planks") && said.contains("did not set off"),
                            "the refusal does not say what goes beyond the plan: " + said);
                    helper.assertTrue(plankCount(helper, 7, 7) == planksBefore, "a plank was broken");
                    helper.assertTrue(companion.blockPosition().distSqr(inside) <= 1, "she left the room");
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /**
     * 途经点照顺序走:从场地一角到同一边的另一角,中途要经过对面那一边的一点。她经过那一点(一格以内),再到终点;
     * 计划有两段,走过的记录记着到了。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void a_route_walks_through_its_waypoint(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_rambler", new BlockPos(2, 2, 2), false);
        BlockPos end = helper.absolutePos(new BlockPos(2, 2, 13));
        BlockPos waypoint = helper.absolutePos(new BlockPos(13, 2, 7));
        ToolRun made = command(companion, "route new loop --to " + xyz(end));
        ToolRun via = command(companion, "route via loop " + xyz(waypoint));
        ToolRun walk = command(companion, "move go loop");
        double[] nearest = {Double.MAX_VALUE};
        helper.onEachTick(() -> nearest[0] = Math.min(nearest[0],
                companion.position().distanceTo(Vec3.atBottomCenterOf(waypoint))));

        succeedWhen(helper, () -> {
            helper.assertTrue(made.succeeded() && via.succeeded(), "making the route failed: " + made.outcome()
                    + " / " + via.outcome());
            helper.assertTrue(walk.done(), "move go has not finished");
            helper.assertTrue(walk.succeeded() && companion.blockPosition().distSqr(end) <= 1,
                    "she did not arrive: " + walk.outcome());
            helper.assertTrue(nearest[0] <= 1.0, "she never passed the waypoint, nearest " + nearest[0]);
            Itinerary route = routes(companion).get("loop");
            helper.assertTrue(route.plan() != null && route.plan().legs().size() == 2
                            && route.plan().legs().stream().allMatch(l -> l.reach() == Plan.Reach.WALKABLE),
                    "the plan is not two walkable legs: " + route.plan());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 简写与三步同一个结局:{@code move_goto} 走到一处;挪回原处,{@code route new} + {@code route plan} + {@code move go} 走到同一处。
     * 两次停在同一格,记下的计划一样,回执除了路线名与数字一字不差。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void goto_is_the_same_walk_as_new_plan_and_go(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos startRel = new BlockPos(2, 2, 2);
        NumenPlayer companion = spawnAt(helper, "gametest_twin", startRel, false);
        BlockPos start = helper.absolutePos(startRel);
        BlockPos there = helper.absolutePos(new BlockPos(12, 2, 11));
        ToolRun shorthand = call(companion, "move_goto", args("x", there.getX(), "y", there.getY(), "z", there.getZ()));
        BlockPos[] firstEnd = new BlockPos[1];
        ToolRun[] plan = new ToolRun[1];
        ToolRun[] walk = new ToolRun[1];

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(shorthand.done(), "move_goto has not finished"))
                .thenExecute(() -> {
                    firstEnd[0] = companion.blockPosition();
                    companion.teleportTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    command(companion, "route new trip --to " + xyz(there));
                    plan[0] = command(companion, "route plan trip");
                })
                .thenWaitUntil(() -> helper.assertTrue(plan[0].done(), "route plan has not replied"))
                .thenExecute(() -> walk[0] = command(companion, "move go trip"))
                .thenWaitUntil(() -> helper.assertTrue(walk[0].done(), "move go has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(shorthand.succeeded() && walk[0].succeeded(),
                            "one of the two walks failed: " + shorthand.outcome() + " / " + walk[0].outcome());
                    helper.assertTrue(companion.blockPosition().equals(firstEnd[0]),
                            "the two walks ended apart: " + firstEnd[0] + " / " + companion.blockPosition());
                    Plan anonymous = routes(companion).get(Itinerary.gotoOf("gametest_twin")).plan();
                    Plan spelled = routes(companion).get("trip").plan();
                    helper.assertTrue(anonymous != null && anonymous.legs().equals(spelled.legs()),
                            "the two plans differ: " + anonymous + " / " + spelled);
                    String a = shorthand.outcome().replace(Itinerary.gotoOf("gametest_twin"), "R")
                            .replaceAll("-?\\d+", "#");
                    String b = walk[0].outcome().replace("trip", "R").replaceAll("-?\\d+", "#");
                    helper.assertTrue(a.equals(b), "the shorthand reports differently: " + shorthand.outcome()
                            + " / " + walk[0].outcome());
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /**
     * 重启后照常重放:{@code move go home} 派下去之后她休眠(身体落盘离场),把落盘的那条记录放回去再复活——路线在主人的存档里,
     * 重放的那一行照样找到它,她走到 home。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void a_restored_move_go_walks_on(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        BlockPos spawn = helper.absolutePos(new BlockPos(2, 2, 2));
        NumenPlayer first = com.dwinovo.numen.entity.Companions.summon(server, UUID.randomUUID(),
                "gametest_homebound", level, new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
        UUID uuid = first.getUUID();
        BlockPos home = helper.absolutePos(new BlockPos(13, 2, 12));
        ToolRun made = command(first, "route new home --to " + xyz(home));
        ToolRun go = command(first, "move go home");
        helper.assertTrue(made.succeeded() && go.task() != null, "the walk was not accepted: " + made.outcome()
                + " / " + go.reply());
        var registry = com.dwinovo.numen.entity.CompanionRegistry.get(server);
        var recorded = registry.find(uuid);
        com.dwinovo.numen.entity.Companions.dormant(server, first);
        registry.put(uuid, registry.find(uuid).doing(recorded.taskName(), recorded.taskTool(), recorded.taskArgs()));
        NumenPlayer second = com.dwinovo.numen.entity.Companions.respawn(server, uuid);
        helper.assertTrue(second != null, "the body was not rebuilt");
        boolean[] replayed = new boolean[1];

        succeedWhen(helper, () -> {
            TaskRecord now = com.dwinovo.numen.task.CompanionTickDispatcher.currentTaskFor(uuid);
            replayed[0] |= now instanceof MoveToTaskRecord m && m.route.equals("home");
            helper.assertTrue(replayed[0], "the replayed task is not the walk along home: " + now);
            helper.assertTrue(second.blockPosition().distSqr(home) <= 1, "she has not reached home after the restart");
            com.dwinovo.numen.entity.Companions.dismiss(server, second);
        });
    }
}
