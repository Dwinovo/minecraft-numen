package com.dwinovo.numen.gametest;

import static com.dwinovo.numen.gametest.GameTestKit.*;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.Blocks;

/**
 * 走路:{@code numen.move.to} 与 {@code numen.move.away} 默认可挖可搭、只看价钱——能走就不挖,绕太远才挖;路上世界变了接着从当时的位置走,不比对
 * 什么承诺;回执交代这一路(停在哪、还差多远、挖了放了几格);{@code numen.use.dismount} 从船上下来;坐着驾驭不了的东西当场拒绝。都从脚本的入口进。
 */
public class WalkGameTests {

    private static final String BATCH = "numen_walk";

    @BeforeBatch(batch = BATCH)
    public static void prepare(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 木板屋的屋外那一格(屋子围着 7,7,她在屋里)。 */
    private static final BlockPos OUTSIDE = new BlockPos(13, 2, 7);

    /** 整段程序返回的那个值(表)。 */
    private static JsonObject returned(ToolRun run) {
        JsonElement value = run.data().get("returned");
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    /**
     * 关在木板屋里,目标在屋外,什么都没许:她拆墙出去到达,回执如实说拆了什么,路程回执的数字对得上世界里少掉的木板。
     */
    @GameTest(template = "floor16", timeoutTicks = 486, batch = BATCH)
    public static void a_walk_out_of_a_closed_room_digs_its_way_and_says_so(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        roomAround(helper, 7, 7, Blocks.STONE);
        int wallBefore = roomCount(helper, 7, 7, Blocks.STONE);
        NumenPlayer companion = spawnBare(helper, "gametest_keeper", new BlockPos(7, 2, 7), false);
        BlockPos target = helper.absolutePos(OUTSIDE);
        ToolRun walk = lua(companion, "return numen.move.to(" + xyz(target) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "move to has not finished");
            helper.assertTrue(walk.succeeded() && companion.blockPosition().distSqr(target) <= 2,
                    "she did not walk out: " + walk.outcome());
            helper.assertTrue(walk.outcome().contains("En route") && walk.outcome().contains("stone"),
                    "the reply does not say what was broken: " + walk.outcome());
            int broken = wallBefore - roomCount(helper, 7, 7, Blocks.STONE);
            helper.assertTrue(broken > 0, "no block of the wall was broken");
            JsonObject account = returned(walk);
            helper.assertTrue(account.get("dug").getAsInt() == broken && account.get("distance_left").getAsDouble() < 2,
                    "the account does not match the world (" + broken + " blocks gone): " + account);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 一条全封死的基岩走廊,中段一道两格高的泥土墙。她走起来之后,前面又被人砌了一道两格高的石墙:没有承诺要守,她从当时的位置接着走,
     * 把石墙也挖穿,到达。
     */
    @GameTest(template = "floor16", timeoutTicks = 1182, batch = BATCH)
    public static void a_wall_put_in_her_way_mid_walk_is_dug_like_any_other(GameTestHelper helper) {
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
        NumenPlayer companion = spawnBare(helper, "gametest_promiser", new BlockPos(3, 2, 7), false);
        BlockPos start = companion.blockPosition();
        BlockPos goal = helper.absolutePos(new BlockPos(12, 2, 7));
        ToolRun walk = lua(companion, "return numen.move.to(" + xyz(goal) + ")");
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
            helper.assertTrue(walk.done(), "move to has not finished");
            helper.assertTrue(walled[0], "she never set off, so the wall was never put in her way");
            helper.assertTrue(walk.succeeded() && companion.blockPosition().distSqr(goal) <= 2,
                    "she did not get through: " + walk.outcome());
            helper.assertTrue(returned(walk).get("dug").getAsInt() >= 4, "the account does not count the stone: " + returned(walk));
            helper.assertTrue(level.getBlockState(low).isAir() && level.getBlockState(low.above()).isAir(),
                    "the stone wall is still there");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 能走就不挖:一道泥土墙横贯场地,近处留了口。她绕到口子过去,墙一格不少;把口子也堵上,绕不过去了,她才挖。
     */
    @GameTest(template = "floor16", timeoutTicks = 387, batch = BATCH)
    public static void she_goes_around_a_wall_and_digs_only_when_there_is_no_way_around(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int z = 0; z <= 15; z++) {
            for (int y = 2; y <= 3; y++) {
                if (z != 4) {
                    level.setBlockAndUpdate(helper.absolutePos(new BlockPos(8, y, z)), Blocks.DIRT.defaultBlockState());
                }
            }
        }
        NumenPlayer companion = spawnAt(helper, "gametest_detour", new BlockPos(2, 2, 2), false);
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 2));
        ToolRun around = lua(companion, "return numen.move.to(" + xyz(target) + ")");
        ToolRun[] through = new ToolRun[1];

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(around.done(), "the walk around has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(around.succeeded() && companion.blockPosition().distSqr(target) <= 1,
                            "she did not get around the wall: " + around.outcome());
                    helper.assertTrue(!around.outcome().contains("En route I had to") && returned(around).get("dug").getAsInt() == 0,
                            "she changed the world though a way around was close: " + around.outcome());
                    for (int y = 2; y <= 3; y++) {
                        level.setBlockAndUpdate(helper.absolutePos(new BlockPos(8, y, 4)), Blocks.DIRT.defaultBlockState());
                    }
                    through[0] = lua(companion, "return numen.move.to(" + at(helper, new BlockPos(2, 2, 2)) + ")");
                })
                .thenWaitUntil(() -> helper.assertTrue(through[0].done(), "the walk back has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(through[0].succeeded() && returned(through[0]).get("dug").getAsInt() > 0,
                            "with no way around she should have dug: " + through[0].outcome());
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /** {@code numen.move.away}:离开一处至少几格,停下时离它够远,回执说还差 0。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void away_gets_far_enough_from_a_place(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_skittish", new BlockPos(7, 2, 7), false);
        BlockPos danger = helper.absolutePos(new BlockPos(8, 2, 8));
        ToolRun away = lua(companion, "return numen.move.away(" + xyz(danger) + ", 6)");

        succeedWhen(helper, () -> {
            helper.assertTrue(away.done(), "away has not finished");
            helper.assertTrue(away.succeeded(), "away failed: " + away.outcome());
            BlockPos at = companion.blockPosition();
            double dx = at.getX() - danger.getX();
            double dy = at.getY() - danger.getY();
            double dz = at.getZ() - danger.getZ();
            helper.assertTrue(dx * dx + dy * dy + dz * dz >= 36, "she stopped too close: " + at.toShortString());
            helper.assertTrue(away.outcome().contains("at least 6 blocks away"), away.outcome());
            helper.assertTrue(returned(away).get("distance_left").getAsDouble() == 0, "away is done but says it is not: " + returned(away));
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** {@code numen.use.dismount}:坐在船上的她下来,交回下来了什么、站在哪;什么都没坐时是失败。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
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
                    off[0] = lua(companion, "return numen.use.dismount()");
                })
                .thenWaitUntil(() -> helper.assertTrue(off[0].done(), "dismount has not replied"))
                .thenExecute(() -> {
                    helper.assertTrue(off[0].ranToTheEnd() && !companion.isPassenger(),
                            "she is still in the boat: " + off[0].receipt());
                    helper.assertTrue(returned(off[0]).get("vehicle").getAsString().contains("boat"),
                            "the reply does not say what she stepped off: " + off[0].receipt());
                    again[0] = lua(companion, "numen.use.dismount()");
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

    /**
     * 坐着驾驭不了的东西:走路的一趟当场拒绝(没有任务编号、她还坐着),说清下一步是 {@code numen.use.dismount()};从不自己下车。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void a_walk_while_riding_a_minecart_is_refused_and_never_steps_off(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_passenger", new BlockPos(7, 2, 7), false);
        net.minecraft.world.entity.vehicle.Minecart cart = EntityType.MINECART.create(level);
        BlockPos at = helper.absolutePos(new BlockPos(7, 2, 7));
        cart.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        level.addFreshEntity(cart);
        companion.startRiding(cart, true);
        ToolRun walk = lua(companion, "numen.move.to(" + xyz(helper.absolutePos(new BlockPos(12, 2, 12))) + ")");
        ToolRun away = lua(companion, "numen.move.away(" + xyz(helper.absolutePos(new BlockPos(9, 2, 9))) + ", 5)");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done() && away.done(), "the refusals have not replied");
            for (ToolRun refused : new ToolRun[] {walk, away}) {
                helper.assertTrue(!refused.ranToTheEnd() && refused.tasks("numen.move.to").isEmpty()
                                && refused.receipt().contains("numen.use.dismount()"),
                        "riding should refuse the walk and name numen.use.dismount(): " + refused.receipt());
            }
            helper.assertTrue(companion.getVehicle() == cart, "the walk stepped off by itself");
            cart.discard();
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 寻路模块的长场地(它的套件 numen_pathing 的模板):224 × 24,两百格跨十几个区块,远过一次规划看得清的范围(快照从起点往外 96 格)。 */
    private static final String LONG = "numen_pathing:pathing_long";

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

    /** 远途、一格不改:两百格外的一处,一路走到。一截一截规划,不比对什么。 */
    @GameTest(template = LONG, timeoutTicks = 2244, batch = BATCH)
    public static void a_far_walk_that_changes_nothing_walks_all_the_way(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        longFloor(helper);
        NumenPlayer companion = spawnAt(helper, "gametest_wanderer2", new BlockPos(2, 1, 12), false);
        BlockPos far = helper.absolutePos(new BlockPos(210, 1, 12));
        ToolRun walk = lua(companion, "return numen.move.to({x = " + far.getX() + ", z = " + far.getZ() + "})");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "the walk has not finished");
            helper.assertTrue(walk.ranToTheEnd(), "the far walk did not arrive: " + walk.receipt());
            helper.assertTrue(companion.getBlockX() == far.getX() && companion.getBlockZ() == far.getZ(),
                    "she stopped short at " + companion.blockPosition().toShortString());
            helper.assertTrue(returned(walk).get("dug").getAsInt() == 0 && returned(walk).get("placed").getAsInt() == 0,
                    "a walk on open ground changed the world: " + returned(walk));
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 远途、要改地形:长场地上两面横墙,近的是泥土,远的是石头,都在一次规划看不清的远处。她一截一截走,两面墙都挖穿(各一条两格高的洞),
     * 到达;其余的墙一块不少。
     */
    @GameTest(template = LONG, timeoutTicks = 3312, batch = BATCH)
    public static void a_far_walk_digs_through_walls_beyond_what_one_look_sees(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        longFloor(helper);
        wallAcross(helper, 30, Blocks.DIRT);
        wallAcross(helper, 150, Blocks.STONE);
        NumenPlayer companion = spawnBare(helper, "gametest_pioneer", new BlockPos(2, 1, 12), false);
        BlockPos goal = helper.absolutePos(new BlockPos(210, 1, 12));
        ToolRun walk = lua(companion, "return numen.move.to(" + xyz(goal) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "the walk has not finished");
            helper.assertTrue(walk.succeeded() && companion.blockPosition().distSqr(goal) <= 2,
                    "she did not get through both walls: " + walk.outcome());
            int stone = 0;
            for (int y = 1; y < 16; y++) {
                for (int z = 0; z < 24; z++) {
                    if (level.getBlockState(helper.absolutePos(new BlockPos(150, y, z))).is(Blocks.STONE)) {
                        stone++;
                    }
                }
            }
            helper.assertTrue(stone < 15 * 24 && stone >= 15 * 24 - 8, "the stone wall was not dug through by a narrow tunnel: " + stone);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 垫高上去再回到原处:够不着的高处靠一根垫柱上去(垫的料经 useItemOn 放下,放置记号照记),走到之后要回出发的地方。
     * 记号让路线不挖自己垫的柱子——回程是不是还有路,看这条用例。
     */
    @GameTest(template = "floor16", timeoutTicks = 900, batch = BATCH)
    public static void a_walk_can_come_back_down_from_a_pillar_she_built(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos perch = helper.absolutePos(new BlockPos(8, 5, 8));
        level.setBlockAndUpdate(perch.below(), Blocks.STONE.defaultBlockState());
        NumenPlayer companion = spawnBare(helper, "gametest_climber", new BlockPos(2, 2, 2), false);
        companion.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 32));
        BlockPos home = companion.blockPosition();
        ToolRun[] runs = {null, null};
        int[] marked = {0};
        steps(helper)
                .thenExecute(() -> runs[0] = lua(companion, "return numen.move.to(" + xyz(perch) + ")"))
                .thenWaitUntil(() -> helper.assertTrue(runs[0].done(), "the climb has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(runs[0].succeeded() && companion.blockPosition().distSqr(perch) <= 2,
                            "she did not get up: " + runs[0].outcome());
                    marked[0] = com.dwinovo.numen.api.permission.PlacedBlocks.of(level).size();
                    helper.assertTrue(marked[0] > 0, "the pillar blocks she placed carry no placement marks");
                    runs[1] = lua(companion, "return numen.move.to(" + xyz(home) + ")");
                })
                .thenWaitUntil(() -> helper.assertTrue(runs[1].done(), "the way back has not finished (the cell at home carries a mark: "
                        + marked[0] + ")"))
                .thenExecute(() -> {
                    helper.assertTrue(runs[1].succeeded() && companion.blockPosition().distSqr(home) <= 4,
                            "she did not get back down (pillar blocks marked: " + marked[0] + "): " + runs[1].outcome());
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /**
     * 垫柱把自己的出口填满:她在一根三格高的基岩烟囱里(墙挖不动),只能垫柱子升上去、翻出去;出来之后要回烟囱底。垫的柱子是走路
     * 顺手垫的路(放置记号是垫路),走路规则不禁挖它,回程拆自己的柱子下去,照常经权限层(她自己放的,放行)。
     */
    @GameTest(template = "floor16", timeoutTicks = 900, batch = BATCH)
    public static void a_walk_back_into_the_chimney_she_pillared_out_of(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int y = 2; y <= 4; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dz != 0) {
                        level.setBlockAndUpdate(helper.absolutePos(new BlockPos(8 + dx, y, 8 + dz)),
                                Blocks.BEDROCK.defaultBlockState());
                    }
                }
            }
        }
        NumenPlayer companion = spawnBare(helper, "gametest_chimney", new BlockPos(8, 2, 8), false);
        companion.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 32));
        BlockPos home = companion.blockPosition();
        BlockPos outside = helper.absolutePos(new BlockPos(13, 2, 8));
        ToolRun[] runs = {null, null};
        steps(helper)
                .thenExecute(() -> runs[0] = lua(companion, "return numen.move.to(" + xyz(outside) + ")"))
                .thenWaitUntil(() -> helper.assertTrue(runs[0].done(), "the climb out has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(runs[0].succeeded() && companion.blockPosition().distSqr(outside) <= 2,
                            "she did not get out: " + runs[0].outcome());
                    var placed = com.dwinovo.numen.api.permission.PlacedBlocks.of(level);
                    helper.assertTrue(placed.isPlaced(home, level.getBlockState(home)) && !placed.isBuilt(home.asLong()),
                            "the pillar block she laid at home is not marked as laid path");
                    runs[1] = lua(companion, "return numen.move.to(" + xyz(home) + ")");
                })
                .thenWaitUntil(() -> helper.assertTrue(runs[1].done(), "the way back has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(runs[1].succeeded() && companion.blockPosition().distSqr(home) <= 4,
                            "she did not get back in: " + runs[1].outcome());
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

}
