package com.dwinovo.numen.core.gametest;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 路线:{@code numen.route.plan} 照一份描述只规划不动身体,走不通不抛错、计划说为什么;{@code numen.move.go} 照计划走,只改计划里的格;路上世界
 * 变了、要承诺外的格时停下并说是哪几格;远途一格不改的路一路走到;走进计划看不清的部分后要改承诺外的格时停下;从别处出发、新计划超出
 * 承诺时不走并说出差别;途经点路过不停、要停的停下;{@code numen.move.to} 与分开两步结局相同;不许挖时一格不改;{@code numen.move.flee} 离开到
 * 够远;{@code numen.move.dismount} 从船上下来。都从脚本的入口进。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class RouteGameTests {

    private static final String BATCH = "numen_route";

    @BeforeBatch(batch = BATCH)
    public static void prepare(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 木板屋的屋外那一格(屋子围着 7,7,她在屋里)。 */
    private static final BlockPos OUTSIDE = new BlockPos(13, 2, 7);

    /** 许挖许放、要问主人的格当墙。 */
    private static final String DIGGING = "costs = {dig = true, place = true, consent = false}";

    /** 整段程序返回的那个值(表)。 */
    private static JsonObject returned(ToolRun run) {
        JsonElement value = run.data().get("returned");
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    /** 一段计划里要挖的格(全部段)。 */
    private static LongSet breaks(JsonObject plan) {
        LongSet out = new LongOpenHashSet();
        for (JsonElement leg : plan.getAsJsonArray("legs")) {
            for (JsonElement block : leg.getAsJsonObject().getAsJsonArray("breaks")) {
                JsonObject pos = block.getAsJsonObject().getAsJsonObject("pos");
                out.add(BlockPos.asLong(pos.get("x").getAsInt(), pos.get("y").getAsInt(), pos.get("z").getAsInt()));
            }
        }
        return out;
    }

    /**
     * 规划不动身体:关在木板屋里,描述许挖。计划列出要挖的木板,一步步的路读得到、不进回执;她一步没动,墙一块不少。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void planning_a_route_moves_nothing_and_lists_the_digs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        plankRoomAround(helper, 7, 7);
        int planksBefore = plankCount(helper, 7, 7);
        NumenPlayer companion = spawnAt(helper, "gametest_surveyor2", new BlockPos(7, 2, 7), false);
        BlockPos start = companion.blockPosition();
        ToolRun plan = lua(companion, "local p = numen.route.plan({to = " + at(helper, OUTSIDE) + ", " + DIGGING + "})\n"
                + "print(#p.legs[1].path, p.legs[1].path[#p.legs[1].path].move)\nreturn p");

        succeedWhen(helper, () -> {
            helper.assertTrue(plan.done(), "route plan has not replied");
            helper.assertTrue(plan.ranToTheEnd(), "planning raised: " + plan.receipt());
            JsonObject p = returned(plan);
            JsonObject leg = p.getAsJsonArray("legs").get(0).getAsJsonObject();
            helper.assertTrue(p.get("ok").getAsBoolean() && leg.get("reach").getAsString().equals("reached")
                            && leg.getAsJsonArray("breaks").toString().contains("oak_planks"),
                    "the plan does not list the planks it would break: " + p);
            helper.assertTrue(!leg.has("path"), "the path is printed with the plan: " + leg);
            helper.assertTrue(plan.receipt().contains("stdout:\\n") && plan.receipt().contains("\\twalk"),
                    "the path is not there to read: " + plan.receipt());
            helper.assertTrue(companion.blockPosition().equals(start), "planning moved the body");
            helper.assertTrue(plankCount(helper, 7, 7) == planksBefore, "planning altered the wall");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 照计划走:同一间屋,规划之后 {@code numen.move.go}。她拆墙出去到达,拆掉的每一块都在计划里,回执如实记账。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void move_go_walks_the_plan_and_changes_only_its_cells(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        plankRoomAround(helper, 7, 7);
        int planksBefore = plankCount(helper, 7, 7);
        NumenPlayer companion = spawnAt(helper, "gametest_keeper", new BlockPos(7, 2, 7), false);
        BlockPos target = helper.absolutePos(OUTSIDE);
        ToolRun walk = lua(companion, "local p = numen.route.plan({to = " + xyz(target) + ", " + DIGGING + "})\n"
                + "local moved = numen.move.go(p)\nreturn {plan = p, moved = moved}");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "move go has not finished");
            helper.assertTrue(walk.succeeded() && companion.blockPosition().distSqr(target) <= 2,
                    "she did not walk out: " + walk.outcome());
            helper.assertTrue(walk.outcome().contains("En route") && walk.outcome().contains("oak_planks"),
                    "the reply does not say what was broken: " + walk.outcome());
            LongSet promised = breaks(returned(walk).getAsJsonObject("plan"));
            helper.assertTrue(plankCount(helper, 7, 7) < planksBefore, "no plank was broken");
            for (int x = 5; x <= 9; x++) {
                for (int z = 5; z <= 9; z++) {
                    for (int y = 2; y <= 4; y++) {
                        BlockPos cell = helper.absolutePos(new BlockPos(x, y, z));
                        boolean wall = x == 5 || x == 9 || z == 5 || z == 9;
                        helper.assertTrue(!wall || level.getBlockState(cell).is(Blocks.OAK_PLANKS)
                                        || promised.contains(cell.asLong()),
                                "a plank outside the plan was broken at " + cell.toShortString());
                    }
                }
            }
            CompanionFactory.despawn(level.getServer(), companion);
        });
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
        ToolRun walk = lua(companion, "local p = numen.route.plan({to = " + at(helper, new BlockPos(12, 2, 7)) + ", "
                + DIGGING + "})\nprint(\"breaks=\" .. #p.legs[1].breaks)\nreturn numen.move.go(p)");
        BlockPos low = helper.absolutePos(new BlockPos(7, 2, 7));
        boolean[] walled = new boolean[1];
        helper.onEachTick(() -> {
            if (!walled[0] && companion.blockPosition().distSqr(start) >= 4) {
                level.setBlockAndUpdate(low, Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(low.above(), Blocks.STONE.defaultBlockState());
                walled[0] = true;
            }
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "move go has not finished");
            helper.assertTrue(walled[0], "she never set off, so the wall was never put in her way");
            String said = walk.outcome();
            helper.assertTrue(!walk.succeeded() && said.contains("outside the plan") && said.contains("stone")
                            && walk.hint() != null && walk.hint().contains("numen.route.plan"),
                    "the stop does not say which cells lie outside the plan: " + said + " / " + walk.hint());
            helper.assertTrue(walk.receipt().contains("breaks=2"), "the plan did not dig the dirt wall: " + walk.receipt());
            helper.assertTrue(level.getBlockState(low).is(Blocks.STONE) && level.getBlockState(low.above()).is(Blocks.STONE),
                    "she dug the wall that was not in the plan");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 从别处出发:在屋外规划(绕着屋子走,一格不改),同一段程序里跟一会儿主人的空位,这期间她被挪进木板屋里,再 {@code numen.move.go}。
     * 从屋里走要拆墙,超出了那份计划:她不走,说出多出来的是哪几格;墙一块不少,她还在屋里。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void from_elsewhere_a_walk_beyond_the_promise_does_not_set_off(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        plankRoomAround(helper, 7, 7);
        int planksBefore = plankCount(helper, 7, 7);
        NumenPlayer companion = spawnAt(helper, "gametest_stickler", new BlockPos(2, 2, 2), false);
        BlockPos inside = helper.absolutePos(new BlockPos(7, 2, 7));
        ToolRun walk = lua(companion, "local p = numen.route.plan({to = " + at(helper, OUTSIDE) + ", " + DIGGING + "})\n"
                + "print(\"breaks=\" .. #p.legs[1].breaks)\nnumen.move.follow({seconds = 2})\nreturn numen.move.go(p)");
        boolean[] moved = new boolean[1];
        helper.onEachTick(() -> {
            if (!moved[0] && !walk.tasks("numen.move.follow").isEmpty()) {
                companion.teleportTo(inside.getX() + 0.5, inside.getY(), inside.getZ() + 0.5);
                moved[0] = true;
            }
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "move go has not finished");
            helper.assertTrue(walk.receipt().contains("breaks=0"), "from outside the room the plan should change nothing: "
                    + walk.receipt());
            String said = walk.outcome();
            helper.assertTrue(!walk.succeeded() && said.contains("beyond the plan") && said.contains("oak_planks")
                            && said.contains("did not set off"),
                    "the refusal does not say what goes beyond the plan: " + said);
            helper.assertTrue(plankCount(helper, 7, 7) == planksBefore, "a plank was broken");
            helper.assertTrue(companion.blockPosition().distSqr(inside) <= 1, "she left the room");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 途经点:两条平行的直道,各有一个途经点在半路。路过的那一个(type 是 through,出厂就是)她经过时不停——站在那一格里的每一刻都
     * 还带着走路的速度;要停的那一个(type = "stop")她在那里停稳过一刻。两个都走到终点。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void a_through_stop_is_passed_and_a_stop_stop_is_come_to_rest_at(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer passer = spawnAt(helper, "gametest_rambler", new BlockPos(1, 2, 4), false);
        NumenPlayer stopper = spawnAt(helper, "gametest_dawdler", new BlockPos(1, 2, 11), false);
        BlockPos passEnd = helper.absolutePos(new BlockPos(14, 2, 4));
        BlockPos stopEnd = helper.absolutePos(new BlockPos(14, 2, 11));
        BlockPos passVia = helper.absolutePos(new BlockPos(8, 2, 4));
        BlockPos stopVia = helper.absolutePos(new BlockPos(8, 2, 11));
        ToolRun pass = lua(passer, "return numen.move.go(numen.route.plan({to = " + xyz(passEnd) + ", stops = {{to = "
                + xyz(passVia) + "}}}))");
        ToolRun stop = lua(stopper, "return numen.move.go(numen.route.plan({to = " + xyz(stopEnd) + ", stops = {{to = "
                + xyz(stopVia) + ", type = \"stop\"}}}))");
        double[] slowestPassing = {Double.MAX_VALUE};
        double[] slowestStopping = {Double.MAX_VALUE};
        helper.onEachTick(() -> {
            if (passer.blockPosition().equals(passVia)) {
                slowestPassing[0] = Math.min(slowestPassing[0], passer.getDeltaMovement().horizontalDistance());
            }
            if (stopper.blockPosition().equals(stopVia)) {
                slowestStopping[0] = Math.min(slowestStopping[0], stopper.getDeltaMovement().horizontalDistance());
            }
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(pass.done() && stop.done(), "the walks have not finished");
            helper.assertTrue(pass.succeeded() && passer.blockPosition().distSqr(passEnd) <= 1,
                    "the walk through did not arrive: " + pass.outcome());
            helper.assertTrue(stop.succeeded() && stopper.blockPosition().distSqr(stopEnd) <= 1,
                    "the walk with a stop did not arrive: " + stop.outcome());
            helper.assertTrue(slowestPassing[0] != Double.MAX_VALUE && slowestPassing[0] > 0.03,
                    "she slowed down to a stop at the through stop: " + slowestPassing[0]);
            helper.assertTrue(slowestStopping[0] < 0.03, "she never came to rest at the stop: " + slowestStopping[0]);
            CompanionFactory.despawn(level.getServer(), passer);
            CompanionFactory.despawn(level.getServer(), stopper);
        });
    }

    /**
     * 库里的 {@code numen.move.to} 就是 {@code numen.route.plan} 加 {@code numen.move.go}:{@code numen.move.to} 走到一处;挪回原处,分开两步走到同一处。两次
     * 停在同一格,回执除了数字一字不差。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void move_to_is_the_same_walk_as_plan_and_go(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos startRel = new BlockPos(2, 2, 2);
        NumenPlayer companion = spawnAt(helper, "gametest_twin", startRel, false);
        BlockPos start = helper.absolutePos(startRel);
        BlockPos there = helper.absolutePos(new BlockPos(12, 2, 11));
        ToolRun shorthand = lua(companion, "numen.move.to(" + xyz(there) + ")");
        BlockPos[] firstEnd = new BlockPos[1];
        ToolRun[] walk = new ToolRun[1];

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(shorthand.done(), "numen.move.to has not finished"))
                .thenExecute(() -> {
                    firstEnd[0] = companion.blockPosition();
                    companion.teleportTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
                })
                .thenIdle(5)
                .thenExecute(() -> walk[0] = lua(companion, "local p = numen.route.plan({to = " + xyz(there) + "})\n"
                        + "numen.move.go(p)"))
                .thenWaitUntil(() -> helper.assertTrue(walk[0].done(), "move go has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(shorthand.succeeded() && walk[0].succeeded(),
                            "one of the two walks failed: " + shorthand.outcome() + " / " + walk[0].outcome());
                    helper.assertTrue(companion.blockPosition().equals(firstEnd[0]),
                            "the two walks ended apart: " + firstEnd[0] + " / " + companion.blockPosition());
                    String a = shorthand.outcome().replaceAll("-?\\d+", "#");
                    String b = walk[0].outcome().replaceAll("-?\\d+", "#");
                    helper.assertTrue(a.equals(b), "the shorthand reports differently: " + shorthand.outcome()
                            + " / " + walk[0].outcome());
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /**
     * 一道泥土墙横贯场地,只在一头留了口。描述不许挖({@code dig = false}):她绕到口子过去,墙一格不少,世界一格不改;同一道墙口子也堵上,
     * 不许挖的计划走不通——{@code numen.route.plan} 不抛错,{@code ok} 是 false,{@code why} 说许挖就有路。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void without_digging_she_goes_around_and_a_dead_end_plan_says_why(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int z = 0; z <= 15; z++) {
            for (int y = 2; y <= 3; y++) {
                if (z != 14) {
                    level.setBlockAndUpdate(helper.absolutePos(new BlockPos(8, y, z)), Blocks.DIRT.defaultBlockState());
                }
            }
        }
        NumenPlayer companion = spawnAt(helper, "gametest_detour", new BlockPos(2, 2, 2), false);
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 2));
        ToolRun walk = lua(companion, "return numen.move.to(" + xyz(target) + ", {costs = {dig = false, place = false}})");
        ToolRun[] dead = new ToolRun[1];

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(walk.done(), "the walk around has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(walk.succeeded() && companion.blockPosition().distSqr(target) <= 1,
                            "she did not get around the wall: " + walk.outcome());
                    helper.assertTrue(!walk.outcome().contains("En route I had to"), "she changed the world: "
                            + walk.outcome());
                    for (int y = 2; y <= 3; y++) {
                        level.setBlockAndUpdate(helper.absolutePos(new BlockPos(8, y, 14)), Blocks.DIRT.defaultBlockState());
                    }
                    dead[0] = lua(companion, "local p = numen.route.plan({to = " + at(helper, new BlockPos(2, 2, 2))
                            + ", costs = {dig = false}})\nreturn {ok = p.ok, why = p.why}");
                })
                .thenWaitUntil(() -> helper.assertTrue(dead[0].done(), "the dead-end plan has not replied"))
                .thenExecute(() -> {
                    helper.assertTrue(dead[0].ranToTheEnd(), "a plan that can't be walked raised: " + dead[0].receipt());
                    JsonObject p = returned(dead[0]);
                    helper.assertTrue(!p.get("ok").getAsBoolean() && p.get("why").getAsString().contains("dig = true"),
                            "the dead end does not say how a way opens: " + p);
                    for (int z = 0; z <= 15; z++) {
                        helper.assertTrue(level.getBlockState(helper.absolutePos(new BlockPos(8, 2, z))).is(Blocks.DIRT),
                                "the wall lost a block at z=" + z);
                    }
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /** {@code numen.move.flee}:离开一处至少几格(到达方式 away),停下时离它够远。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void flee_gets_far_enough_from_a_place(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_skittish", new BlockPos(7, 2, 7), false);
        BlockPos danger = helper.absolutePos(new BlockPos(8, 2, 8));
        ToolRun flee = lua(companion, "return numen.move.flee(" + xyz(danger) + ", {distance = 6})");

        succeedWhen(helper, () -> {
            helper.assertTrue(flee.done(), "flee has not finished");
            helper.assertTrue(flee.succeeded(), "flee failed: " + flee.outcome());
            BlockPos at = companion.blockPosition();
            double dx = at.getX() - danger.getX();
            double dy = at.getY() - danger.getY();
            double dz = at.getZ() - danger.getZ();
            helper.assertTrue(dx * dx + dy * dy + dz * dz >= 36, "she stopped too close: " + at.toShortString());
            helper.assertTrue(flee.outcome().contains("at least 6 blocks away"), flee.outcome());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** {@code numen.move.dismount}:坐在船上的她下来,交回下来了什么、站在哪;什么都没坐时是失败。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void dismount_steps_off_the_boat(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_rower", new BlockPos(7, 2, 7), false);
        Boat boat = EntityType.BOAT.create(level);
        BlockPos at = helper.absolutePos(new BlockPos(7, 2, 7));
        boat.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        level.addFreshEntity(boat);
        companion.startRiding(boat, true);
        ToolRun[] off = new ToolRun[1];
        ToolRun[] again = new ToolRun[1];

        steps(helper)
                .thenExecute(() -> {
                    helper.assertTrue(companion.getVehicle() == boat, "she did not board the boat");
                    off[0] = lua(companion, "return numen.move.dismount()");
                })
                .thenWaitUntil(() -> helper.assertTrue(off[0].done(), "dismount has not replied"))
                .thenExecute(() -> {
                    helper.assertTrue(off[0].ranToTheEnd() && !companion.isPassenger(),
                            "she is still in the boat: " + off[0].receipt());
                    helper.assertTrue(returned(off[0]).get("vehicle").getAsString().contains("boat"),
                            "the reply does not say what she stepped off: " + off[0].receipt());
                    again[0] = lua(companion, "numen.move.dismount()");
                })
                .thenWaitUntil(() -> helper.assertTrue(again[0].done(), "the second dismount has not replied"))
                .thenExecute(() -> {
                    helper.assertTrue(!again[0].ranToTheEnd() && "failed".equals(again[0].kind()),
                            "riding nothing should fail: " + again[0].receipt());
                    boat.discard();
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /** 寻路模块的长场地:224 × 24,两百格跨十几个区块,远过一次规划看得清的范围(快照从起点往外 96 格)。 */
    private static final String LONG = "pathing_long";

    /** 长场地铺一层石头地面(y=0),她站在 y=1。 */
    private static void longFloor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 224; x++) {
            for (int z = 0; z < 24; z++) {
                level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, 0, z)), Blocks.STONE.defaultBlockState());
            }
        }
    }

    /** 长场地横着砌一整面墙(x 这一列,地面以上到顶、两边到头):要过去只能挖穿它。 */
    private static void wallAcross(GameTestHelper helper, int x, net.minecraft.world.level.block.Block block) {
        for (int y = 1; y < 16; y++) {
            for (int z = 0; z < 24; z++) {
                helper.getLevel().setBlockAndUpdate(helper.absolutePos(new BlockPos(x, y, z)), block.defaultBlockState());
            }
        }
    }

    /**
     * 远途、一格不改:两百格外的一处,一次规划只看清开头那一截(partial)。{@code numen.move.go} 照样一路走到——一格不改的承诺怎么走都
     * 不越界,看不清的部分由执行层边走边算。
     */
    @GameTest(template = LONG, timeoutTicks = 100000, batch = BATCH)
    public static void a_far_walk_that_changes_nothing_walks_all_the_way(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        longFloor(helper);
        NumenPlayer companion = spawnAt(helper, "gametest_wanderer2", new BlockPos(2, 1, 12), false);
        BlockPos far = helper.absolutePos(new BlockPos(210, 1, 12));
        ToolRun walk = lua(companion, "local p = numen.route.plan({to = {x = " + far.getX() + ", z = " + far.getZ() + "}})\n"
                + "numen.move.go(p)\nreturn {reach = p.legs[1].reach}");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "the walk has not finished");
            helper.assertTrue(walk.ranToTheEnd(), "the far walk did not arrive: " + walk.receipt());
            helper.assertTrue(companion.getBlockX() == far.getX() && companion.getBlockZ() == far.getZ(),
                    "she stopped short at " + companion.blockPosition().toShortString());
            helper.assertTrue("partial".equals(returned(walk).get("reach").getAsString()),
                    "the plan should have seen only the first stretch of a walk this long: " + walk.receipt());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 远途、要改地形:长场地上两面横墙,近的是泥土(计划看得见,要挖),远的是石头(在计划看不清的那一截之后)。规划只看清开头,
     * {@code numen.move.go} 照走:挖穿泥土、走进未知部分,到石墙前要挖承诺外的格——停下,说出是哪几格;石墙一块不少。
     */
    @GameTest(template = LONG, timeoutTicks = 100000, batch = BATCH)
    public static void a_walk_into_the_unknown_stops_where_it_needs_cells_outside_the_plan(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        longFloor(helper);
        wallAcross(helper, 30, Blocks.DIRT);
        wallAcross(helper, 150, Blocks.STONE);
        NumenPlayer companion = spawnAt(helper, "gametest_pioneer", new BlockPos(2, 1, 12), false);
        BlockPos start = companion.blockPosition();
        ToolRun walk = lua(companion, "local p = numen.route.plan({to = " + at(helper, new BlockPos(210, 1, 12)) + ", "
                + DIGGING + "})\nprint(\"seen=\" .. p.legs[1].reach .. \"/\" .. p.legs[1].breaks[1].name)\nreturn numen.move.go(p)");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "move go has not finished");
            helper.assertTrue(walk.receipt().contains("seen=partial/minecraft:dirt"),
                    "the plan should dig the dirt wall and see nothing of the stone one: " + walk.receipt());
            String said = walk.outcome();
            helper.assertTrue(!walk.succeeded() && said.contains("outside the plan") && said.contains("stone"),
                    "the stop does not say which cells lie outside the plan: " + said);
            helper.assertTrue(Math.sqrt(companion.blockPosition().distSqr(start)) > 100,
                    "she did not walk on into the part the plan could not see: "
                            + companion.blockPosition().toShortString());
            int stone = 0;
            for (int y = 1; y < 16; y++) {
                for (int z = 0; z < 24; z++) {
                    if (level.getBlockState(helper.absolutePos(new BlockPos(150, y, z))).is(Blocks.STONE)) {
                        stone++;
                    }
                }
            }
            helper.assertTrue(stone == 15 * 24, "she dug the stone wall the plan never listed: " + stone);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 一份计划只在算出它的那一段程序里有效:下一段程序拿它走,说它已经作废,让她再规划。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = BATCH)
    public static void a_plan_from_an_earlier_program_is_gone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_forgetful", new BlockPos(2, 2, 2), false);
        ToolRun plan = lua(companion, "return numen.route.plan({to = " + at(helper, new BlockPos(12, 2, 12)) + "}).id");
        ToolRun[] walk = new ToolRun[1];

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(plan.done(), "route plan has not replied"))
                .thenExecute(() -> {
                    String id = plan.data().get("returned").getAsString();
                    walk[0] = lua(companion, "numen.move.go({id = \"" + id + "\"})");
                })
                .thenWaitUntil(() -> helper.assertTrue(walk[0].done(), "move go has not replied"))
                .thenExecute(() -> {
                    helper.assertTrue("not_found".equals(walk[0].kind())
                                    && walk[0].outcome().contains("good only within the program that made it"),
                            "a stale plan should be gone: " + walk[0].receipt());
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }
}
