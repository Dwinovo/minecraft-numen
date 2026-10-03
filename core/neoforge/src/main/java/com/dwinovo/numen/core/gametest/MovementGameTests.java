package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskRecord;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * 移动:{@code goto}、开门、地形许可(不改世界、失败时给出能照抄的下一步)、{@code numen.route.plan} 没路与超预算、{@code numen.move.follow}、
 * 载具,以及任务与编号跨重建的延续。路线本身(规划、照承诺走、超出承诺)见 {@link RouteGameTests}。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class MovementGameTests {

    /** 冒烟批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_smoke")
    public static void prepareSmokeBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 地形许可批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_terrain")
    public static void prepareTerrainBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 载具批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_vehicle")
    public static void prepareVehicleBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /**
     * 冒烟:同伴能在测试世界里存活并走完一段路。验证的是整条链路——假玩家生成
     * (载档→入场→落位)、任务入队、后台 A* 搜索、逐 tick 执行——在无头环境下全通。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_smoke")
    public static void companion_goto(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos spawn = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 13));

        NumenPlayer companion = CompanionFactory.spawn(level.getServer(), UUID.randomUUID(),
                "gametest_scout", UUID.randomUUID(), level,
                new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));

        TaskRecord record = lua(companion, "numen.move.goto_(" + xyz(target) + ")").task();

        succeedWhen(helper, () -> {
            helper.assertTrue(companion.blockPosition().distSqr(target) <= 2 * 2,
                    "companion has not reached the goto target");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 主人按停止,结果里说清是主人停的:派一段后台 goto,跑起来后走主人停止那条路(与 CancelTasksPayload
     * 同一个入口),收尾的消息以"the owner pressed Stop"开头——模型不用猜是谁、为什么停的。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_smoke")
    public static void an_owner_stop_says_the_owner_stopped_it(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos spawn = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 13));
        NumenPlayer companion = CompanionFactory.spawn(level.getServer(), UUID.randomUUID(),
                "gametest_stopped", UUID.randomUUID(), level,
                new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
        ToolRun walk = lua(companion, "numen.move.goto_({x = " + target.getX() + ", z = " + target.getZ() + "})");
        boolean[] stopped = {false};

        succeedWhen(helper, () -> {
            TaskRecord record = walk.task();
            if (!stopped[0]) {
                helper.assertTrue(record != null && record.getState() == com.dwinovo.numen.task.TaskState.RUNNING,
                        "goto is not running yet");
                com.dwinovo.numen.task.CompanionTickDispatcher.cancelFor(companion);
                stopped[0] = true;
            }
            String message = record.getResult() == null ? null : record.getResult().message();
            helper.assertTrue(message != null, "the stopped goto has not settled");
            helper.assertTrue(message.startsWith("the owner pressed Stop"),
                    "the result does not say the owner stopped it: " + message);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 开门出屋:同伴被关在四面石墙(3 高、无顶但空背包无从垫高、徒手拆墙
     * 代价高昂)的屋里,唯一出口是一扇关着的橡木门——goto 屋外目标必须
     * 走"规划穿门 + 执行层右键开门"这条链。守的是 MovementTraverse 的
     * 门交互与 canWalkThroughBlockState 的木门可通行假定。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_smoke")
    public static void goto_through_closed_door(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 周界石墙 rel x,z ∈ [1,5]、y 2..4;南墙中央 (3,*,5) 留门洞
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 5; z++) {
                boolean perimeter = x == 1 || x == 5 || z == 1 || z == 5;
                if (!perimeter) continue;
                for (int y = 2; y <= 4; y++) {
                    if (x == 3 && z == 5 && y <= 3) continue;   // 门占的两格
                    level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, y, z)),
                            Blocks.STONE.defaultBlockState());
                }
            }
        }
        BlockPos doorLow = helper.absolutePos(new BlockPos(3, 2, 5));
        var lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DoorBlock.FACING,
                        net.minecraft.core.Direction.SOUTH);
        level.setBlockAndUpdate(doorLow, lower);
        level.setBlockAndUpdate(doorLow.above(), lower.setValue(
                net.minecraft.world.level.block.DoorBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));

        NumenPlayer companion = spawnAt(helper, "gametest_shutin", new BlockPos(3, 2, 3), false);
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 13));
        TaskRecord record = lua(companion, "numen.move.goto_(" + xyz(target) + ")").task();
        succeedWhen(helper, () -> {
            helper.assertTrue(companion.blockPosition().distSqr(target) <= 2 * 2,
                    "companion has not escaped through the door");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 驾船横渡:坐在船上发 goto,她该把船开过水面、靠岸下船、走完最后一段。
     * 守的是整条载具链——服务端权威开关(没有它船每刻被清零)、桨物理驱动、
     * 水面 A*、靠岸后与步行导航的接力。
     */
    @GameTest(template = "floor16", timeoutTicks = 2400, batch = "numen_vehicle")
    public static void boat_goto_pilots_across_water(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 把地板挖成一条水道(x 5..11 × z 5..11),两岸是原地板
        for (int x = 5; x <= 11; x++) {
            for (int z = 5; z <= 11; z++) {
                level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, 1, z)),
                        Blocks.WATER.defaultBlockState());
            }
        }
        // 船放在水面高度(rel y1 的水,面在 +0.9),别沉进水里被浮力弹上天
        BlockPos boatAt = helper.absolutePos(new BlockPos(6, 2, 8));
        var boat = new net.minecraft.world.entity.vehicle.Boat(
                level, boatAt.getX() + 0.5, boatAt.getY() - 1 + 0.9, boatAt.getZ() + 0.5);
        level.addFreshEntity(boat);

        NumenPlayer companion = spawnAt(helper, "gametest_pilot", new BlockPos(3, 2, 8), false);
        BlockPos target = helper.absolutePos(new BlockPos(14, 2, 8));
        double boatStartDist = boat.position().distanceTo(Vec3.atCenterOf(target));
        helper.runAfterDelay(2, () -> {
            companion.startRiding(boat, true);
            TaskRecord record = lua(companion, "numen.move.goto_(" + xyz(target) + ")").task();
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(companion.blockPosition().distSqr(target) <= 2 * 2,
                    "companion has not crossed the water to the target");
            helper.assertTrue(!companion.isPassenger(), "companion is still in the boat");
            // 结构可旋转,方向断言必须与坐标系无关:船开过就是离目标近了一大截
            double now = boat.position().distanceTo(Vec3.atCenterOf(target));
            helper.assertTrue(now < boatStartDist - 3.0,
                    "the boat never drove toward the target (start " + boatStartDist
                            + ", now " + now + ")");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 自载具守卫:对自己坐着的船再按右键必须立刻了结,不许挂死同步槽。
     * 证据链用第三个动作闭合——守卫失效时按压永远等不到准星确认,后续的
     * 同步破块也就永远轮不上;石头碎了 = 槽是活的。
     */
    @GameTest(template = "floor16", timeoutTicks = 600, batch = "numen_vehicle")
    public static void interact_own_vehicle_ends_immediately(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 6; x <= 9; x++) {
            for (int z = 6; z <= 9; z++) {
                level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, 1, z)),
                        Blocks.WATER.defaultBlockState());
            }
        }
        BlockPos boatAt = helper.absolutePos(new BlockPos(7, 2, 7));
        var boat = new net.minecraft.world.entity.vehicle.Boat(
                level, boatAt.getX() + 0.5, boatAt.getY() - 1, boatAt.getZ() + 0.5);
        level.addFreshEntity(boat);
        BlockPos stone = helper.absolutePos(new BlockPos(7, 2, 5));
        level.setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());

        NumenPlayer companion = spawnAt(helper, "gametest_seated", new BlockPos(7, 2, 4), true);
        helper.runAfterDelay(2, () -> {
            companion.startRiding(boat, true);
            TaskRecord press = lua(companion, "numen.use.entity(" + boat.getId() + ")").task();
        });
        helper.runAfterDelay(30, () -> {
            TaskRecord dig = lua(companion, "numen.use.hit(" + xyz(stone) + ")").task();
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(level.getBlockState(stone).isAir(),
                    "the follow-up dig never ran — the self-click press hung the sync slot");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 默认不开路:被木板屋关住,目标在屋外,goto 不带规格。她不能拆墙;回执是 TERRAIN_BLOCKED,说出要改几格,并给出能照抄的
     * 下一步——改她那条匿名路线的规格、再规划看是哪几格。照抄那两行:计划点名 oak_planks,她一步没动,墙一块不少。
     * 这就是"挖穿主人的房子"那类投诉的根治点:路上动地形从引擎顺手干,变成模型看过计划、选了才干。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void goto_refuses_to_tunnel_by_default(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        plankRoomAround(helper, 7, 7);
        int planksBefore = plankCount(helper, 7, 7);
        NumenPlayer companion = spawnAt(helper, "gametest_guest", new BlockPos(7, 2, 7), false);
        BlockPos start = companion.blockPosition();
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 7));
        ToolRun walk = lua(companion, "numen.move.goto_(" + xyz(target) + ")");
        String route = "goto-gametest_guest";
        ToolRun[] spec = new ToolRun[1];
        ToolRun[] plan = new ToolRun[1];

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(walk.done(), "goto has not replied"))
                .thenExecute(() -> {
                    helper.assertTrue(walk.refused(), "a walk with no clean route was accepted: " + walk.reply());
                    String reply = walk.outcome();
                    helper.assertTrue(reply.contains("without altering terrain"),
                            "the refusal does not say it needs altering terrain: " + reply);
                    helper.assertTrue(reply.contains("`numen.route.spec(\"" + route + "\", {alter = \"natural\"})`")
                                    && reply.contains("`numen.route.plan(\"" + route + "\")`"),
                            "the refusal does not give the lines that change and plan her route: " + reply);
                    spec[0] = lua(companion, "numen.route.spec(\"" + route + "\", {alter = \"natural\"})");
                    plan[0] = lua(companion, "numen.route.plan(\"" + route + "\")");
                })
                .thenWaitUntil(() -> helper.assertTrue(plan[0].done(), "route plan has not replied"))
                .thenExecute(() -> {
                    helper.assertTrue(spec[0].succeeded(), "route spec failed: " + spec[0].outcome());
                    helper.assertTrue(plan[0].succeeded() && plan[0].reply().contains("break")
                                    && plan[0].reply().contains("oak_planks"),
                            "the plan does not name the planks it would break: " + plan[0].reply());
                    helper.assertTrue(plankCount(helper, 7, 7) == planksBefore,
                            "the wall was damaged without consent");
                    helper.assertTrue(companion.blockPosition().equals(start), "she moved while refusing or planning");
                    CompanionFactory.despawn(level.getServer(), companion);
                })
                .thenSucceed();
    }

    /**
     * 规格说了可自然改动就开路:同一间屋,goto 带 spec alter=natural。她拆墙出去到达目标,
     * 回执如实记账(En route … break … oak_planks),墙上确实少了木板。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void goto_terraforms_when_permitted(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        plankRoomAround(helper, 7, 7);
        int planksBefore = plankCount(helper, 7, 7);
        NumenPlayer companion = spawnAt(helper, "gametest_digger", new BlockPos(7, 2, 7), false);
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 7));
        ToolRun walk = lua(companion, "numen.move.goto_(" + xyz(target) + ", {alter = \"natural\"})");

        succeedWhen(helper, () -> {
            helper.assertTrue(companion.blockPosition().distSqr(target) <= 2 * 2,
                    "companion has not reached the target with consent to dig");
            helper.assertTrue(plankCount(helper, 7, 7) < planksBefore, "no plank was broken");
            String reply = walk.task() == null ? null : walk.outcome();
            helper.assertTrue(reply != null && reply.contains("En route") && reply.contains("oak_planks"),
                    "the reply does not report what was broken en route: " + reply);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }


    /** 盔甲架立在一根 4 高的石柱顶上:不垫不挖就够不着。 */
    private static net.minecraft.world.entity.decoration.ArmorStand standOnPillar(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int y = 2; y <= 5; y++) {
            level.setBlockAndUpdate(helper.absolutePos(new BlockPos(10, y, 8)), Blocks.STONE.defaultBlockState());
        }
        BlockPos top = helper.absolutePos(new BlockPos(10, 6, 8));
        var stand = new net.minecraft.world.entity.decoration.ArmorStand(
                level, top.getX() + 0.5, top.getY(), top.getZ() + 0.5);
        level.addFreshEntity(stand);
        return stand;
    }

    /**
     * 跟不上就以结果收场:目标在够不着的柱顶,follow 从不改地形。任务必须 FAILED,
     * 回执说出不改地形没有路、要改几格——不是退避着站在原地空算,主人和模型都蒙在鼓里。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void follow_reports_when_terrain_blocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var stand = standOnPillar(helper);
        NumenPlayer companion = spawnAt(helper, "gametest_tail", new BlockPos(3, 2, 8), true);
        ToolRun follow = lua(companion, "numen.move.follow(" + stand.getId() + ", {distance = 3})");

        succeedWhen(helper, () -> {
            helper.assertTrue(follow.done() && !follow.succeeded(),
                    "follow should end with a failure, got: " + follow.outcome());
            String said = follow.outcome();
            helper.assertTrue(said.contains("without altering terrain") && said.contains("block(s)"),
                    "the reason must say it needs altering terrain and how many blocks, got: " + said);
            helper.assertTrue(level.getBlockState(helper.absolutePos(new BlockPos(10, 5, 8))).is(Blocks.STONE),
                    "the pillar was touched without consent");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 重启后接不回来的活不许让调度 tick 抛出去:存下的参数重放时已经不成立(dig 点名的那块铁矿已经不在了,
     * 工具当场拒收),新身体照样起来,她收到一条 task_finished 说清这件活没接回来,记录清掉。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_terrain")
    public static void a_restored_task_whose_args_no_longer_hold_is_reported(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        BlockPos spawn = helper.absolutePos(new BlockPos(3, 2, 6));
        NumenPlayer first = com.dwinovo.numen.entity.Companions.summon(server, UUID.randomUUID(),
                "gametest_restorer", level, new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
        UUID uuid = first.getUUID();
        com.dwinovo.numen.entity.Companions.dormant(server, first);
        var registry = com.dwinovo.numen.entity.CompanionRegistry.get(server);
        BlockPos gone = helper.absolutePos(new BlockPos(8, 2, 6));
        registry.put(uuid, registry.find(uuid).doing("work_dig", "work_dig",
                "{\"blocks\":[{\"name\":\"minecraft:iron_ore\",\"pos\":{\"x\":" + gone.getX() + ",\"y\":" + gone.getY()
                        + ",\"z\":" + gone.getZ() + "}}],\"count\":1}"));
        NumenPlayer second = com.dwinovo.numen.entity.Companions.respawn(server, uuid);
        helper.assertTrue(second != null, "the body was not rebuilt");
        StringBuilder told = new StringBuilder();
        succeedWhen(helper, () -> {
            helper.assertTrue(registry.find(uuid).taskTool().isBlank(), "the task that cannot be replayed is still on record");
            for (var entry : com.dwinovo.numen.entity.EventOutbox.get(server).peek(uuid)
                    .takeEntries(System.currentTimeMillis())) {
                told.append(entry.text());
            }
            helper.assertTrue(told.toString().contains("task_finished") && told.toString().contains("没能接回来"),
                    "she was not told the task could not be restored: " + told);
            com.dwinovo.numen.entity.Companions.dismiss(server, second);
        });
    }

    // ---- 地形边界:高台、水沟 ----

    /**
     * 一座三格高、3×3 的基岩台,台顶是 (11,5,7)。基岩挖不动,想上去只能垫方块(黑曜石原版空手也挖得动,只是慢,
     * 寻路照样会挖出台阶上去)。
     */
    private static BlockPos bedrockTower(GameTestHelper helper) {
        for (int x = 10; x <= 12; x++) {
            for (int z = 6; z <= 8; z++) {
                for (int y = 2; y <= 4; y++) {
                    helper.getLevel().setBlockAndUpdate(helper.absolutePos(new BlockPos(x, y, z)),
                            Blocks.BEDROCK.defaultBlockState());
                }
            }
        }
        return helper.absolutePos(new BlockPos(11, 5, 7));
    }

    /** 默认不改地形:上高台要垫方块,她只说要改几格、照抄哪一行能放开,不动手,泥土一块没用。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void goto_up_a_tower_by_default_says_what_it_would_take(GameTestHelper helper) {
        BlockPos top = bedrockTower(helper);
        NumenPlayer companion = spawnAt(helper, "gametest_asker", new BlockPos(3, 2, 7), false);
        companion.getInventory().add(new ItemStack(Items.DIRT, 16));
        ToolRun walk = lua(companion, "numen.move.goto_(" + xyz(top) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "goto has not finished");
            helper.assertTrue(!walk.succeeded()
                            && walk.outcome().contains("`numen.route.spec(\"goto-gametest_asker\", {alter = \"natural\"})`"),
                    "the reply does not give the line that lets her build up: " + walk.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.DIRT) == 16
                            && companion.blockPosition().getY() < top.getY(),
                    "she built her way up without being allowed to");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 规格允许改地形、身上带着泥土:垫着爬上高台,泥土用掉了几块。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void goto_up_a_tower_with_natural_spec_pillars_up(GameTestHelper helper) {
        BlockPos top = bedrockTower(helper);
        NumenPlayer companion = spawnAt(helper, "gametest_climber", new BlockPos(3, 2, 7), false);
        companion.getInventory().add(new ItemStack(Items.DIRT, 16));
        ToolRun walk = lua(companion, "numen.move.goto_(" + xyz(top) + ", {alter = \"natural\"})");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "goto has not finished");
            helper.assertTrue(walk.succeeded() && companion.blockPosition().distSqr(top) <= 2,
                    "she did not get onto the tower: " + walk.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.DIRT) < 16, "no dirt was spent climbing");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 她垫的柱子是她自己的:垫着爬上高台后,柱子里的泥土记在她名下;主人在场,{@code numen.work.dig} 拆掉其中一格,
     * 由出厂的 {@code break(self_placed & !contents)} 放行,一张卡都不弹。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void the_pillar_she_built_is_hers_to_take_down(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos top = bedrockTower(helper);
        NumenPlayer companion = spawnAt(helper, "gametest_stacker", new BlockPos(3, 2, 7), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_watcher");
        companion.getInventory().add(new ItemStack(Items.DIRT, 16));
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= com.dwinovo.numen.permission.ConsentDesk.of(companion).pending() != null);
        ToolRun walk = lua(companion, "numen.move.goto_(" + xyz(top) + ", {alter = \"natural\"})");
        BlockPos[] pillar = new BlockPos[1];
        ToolRun[] clear = new ToolRun[1];

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(walk.done() && walk.succeeded(),
                        "she did not get onto the tower: " + walk.outcome()))
                .thenExecute(() -> {
                    var placed = com.dwinovo.numen.permission.PlacedBlocks.of(level);
                    pillar[0] = BlockPos.betweenClosedStream(helper.absolutePos(new BlockPos(0, 2, 0)),
                                    helper.absolutePos(new BlockPos(15, 6, 15)))
                            .filter(p -> level.getBlockState(p).is(Blocks.DIRT))
                            .map(BlockPos::immutable)
                            .findFirst().orElse(null);
                    helper.assertTrue(pillar[0] != null, "no dirt of hers stands anywhere");
                    var placer = placed.placerAt(pillar[0], level.getBlockState(pillar[0]));
                    helper.assertTrue(placer != null && placer.id().equals(companion.getUUID()),
                            "the dirt she pillared with is not recorded as hers: " + placer);
                    clear[0] = lua(companion, "numen.move.goto_(" + xyz(pillar[0]) + ", {arrive = \"dig\"})\n"
                            + "numen.work.dig(" + xyz(pillar[0]) + ")");
                })
                .thenWaitUntil(() -> helper.assertTrue(clear[0].done(), "taking the pillar down has not finished"))
                .thenExecute(() -> {
                    helper.assertTrue(clear[0].ranToTheEnd() && level.getBlockState(pillar[0]).isAir(),
                            "her pillar block is still there: " + clear[0].receipt());
                    helper.assertTrue(!asked[0], "she asked the owner about her own pillar");
                    CompanionFactory.despawn(level.getServer(), companion);
                    leave(owner);
                })
                .thenSucceed();
    }

    /** 允许改地形,但身上没有能垫的方块:上不去,回执说清楚缺的是垫脚的方块。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void goto_up_a_tower_without_throwaway_says_so(GameTestHelper helper) {
        BlockPos top = bedrockTower(helper);
        NumenPlayer companion = spawnAt(helper, "gametest_grounded", new BlockPos(3, 2, 7), false);
        ToolRun walk = lua(companion, "numen.move.goto_(" + xyz(top) + ", {alter = \"natural\"})");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "goto has not finished");
            helper.assertTrue(!walk.succeeded() && walk.outcome().contains("throwaway"),
                    "the failure does not say she has nothing to pillar with: " + walk.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 场地垫高两层,正中一道两格宽、两格深的水沟横着切开:她游过去,爬上对岸,到达目标。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void goto_swims_across_a_water_channel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                boolean channel = x == 8 || x == 9;
                for (int y = 2; y <= 3; y++) {
                    level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, y, z)),
                            channel ? Blocks.WATER.defaultBlockState() : Blocks.STONE.defaultBlockState());
                }
            }
        }
        BlockPos target = helper.absolutePos(new BlockPos(13, 4, 7));
        NumenPlayer companion = spawnAt(helper, "gametest_swimmer", new BlockPos(3, 4, 7), false);
        ToolRun walk = lua(companion, "numen.move.goto_(" + xyz(target) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "goto has not finished");
            helper.assertTrue(walk.succeeded() && companion.blockPosition().distSqr(target) <= 2,
                    "she did not get across the channel: " + walk.outcome());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    // ---- y 给得不对:半空、地里;水底、岩浆 ----

    /**
     * y 猜到了半空(离地三格):给了 y 就是那一格,那一格没东西托,这一趟又不改地形。受理当场提醒——说那一格在半空、那一列的地面
     * 在哪一层,教她省掉 y 或许改地形垫上去——不派活,她不动。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_terrain")
    public static void goto_with_y_in_the_air_is_refused_at_once(GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(11, 5, 7));
        NumenPlayer companion = spawnAt(helper, "gametest_skyward", new BlockPos(3, 2, 7), false);
        BlockPos before = companion.blockPosition();
        ToolRun walk = lua(companion, "numen.move.goto_(" + xyz(target) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.task() == null && walk.done() && !walk.succeeded(), "it was not refused at once");
            helper.assertTrue(walk.outcome().contains("in mid-air") && walk.outcome().contains("Give {x = …, z = …} alone")
                            && walk.outcome().contains("y=" + (target.getY() - 3)),
                    "the mid-air y was not refused with the reminder: " + walk.outcome());
            helper.assertTrue(companion.blockPosition().equals(before), "she moved");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * y 给成了地面那一块本身:那一格是实心的,站不进去,这一趟又不改地形。受理当场提醒——说那一格是什么,要用它写
     * arrive:use、站上去照抄它上面那一格、停在附近写 arrive:near——不派活,地面那一块还在。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_terrain")
    public static void goto_with_y_inside_the_floor_is_refused_at_once(GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(11, 1, 7));
        NumenPlayer companion = spawnAt(helper, "gametest_grounded_y", new BlockPos(3, 2, 7), false);
        ToolRun walk = lua(companion, "numen.move.goto_(" + xyz(target) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.task() == null && walk.done() && !walk.succeeded(), "it was not refused at once");
            helper.assertTrue(walk.outcome().contains("no room to stand in it")
                            && walk.outcome().contains("`numen.move.goto_(" + xyz(target) + ", {arrive = \"use\"})`")
                            && walk.outcome().contains("to stand on top of it: `numen.move.goto_(" + xyz(target.above()) + ")`;")
                            && walk.outcome().contains("`numen.move.goto_(" + xyz(target) + ", {arrive = \"near\"})`"),
                    "the y inside the floor was not refused with the reminder: " + walk.outcome());
            helper.assertTrue(helper.getLevel().getBlockState(target).isSolid(), "the floor block was dug out");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 场地垫高三层,中间一个 3×3、三格深的水池;目标是池底正中那一格:她下水沉到池底,就站在那一格。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void goto_to_the_bottom_of_a_pool(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                boolean pool = x >= 9 && x <= 11 && z >= 6 && z <= 8;
                for (int y = 2; y <= 4; y++) {
                    level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, y, z)),
                            pool ? Blocks.WATER.defaultBlockState() : Blocks.STONE.defaultBlockState());
                }
            }
        }
        BlockPos target = helper.absolutePos(new BlockPos(10, 2, 7));
        NumenPlayer companion = spawnAt(helper, "gametest_diver", new BlockPos(3, 5, 7), false);
        ToolRun walk = lua(companion, "numen.move.goto_(" + xyz(target) + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "goto has not finished");
            helper.assertTrue(walk.succeeded() && companion.blockPosition().distSqr(target) <= 1,
                    "she did not reach the bottom of the pool: " + walk.outcome()
                            + " at " + companion.blockPosition().toShortString());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 场地垫高一层,中间一道一格宽的岩浆沟横着拦住大半边,只在一头留出通路:她绕过去到达目标,
     * 一路上没着过火、没掉过血。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void goto_goes_around_a_lava_channel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                boolean lava = x == 8 && z >= 1 && z <= 12;
                level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, 2, z)),
                        lava ? Blocks.LAVA.defaultBlockState() : Blocks.STONE.defaultBlockState());
            }
        }
        BlockPos target = helper.absolutePos(new BlockPos(13, 3, 7));
        NumenPlayer companion = spawnAt(helper, "gametest_firewalker", new BlockPos(3, 3, 7), false);
        ToolRun walk = lua(companion, "numen.move.goto_(" + xyz(target) + ")");
        boolean[] burned = new boolean[1];
        helper.onEachTick(() -> burned[0] |= companion.isOnFire() || companion.getHealth() < companion.getMaxHealth());

        succeedWhen(helper, () -> {
            helper.assertTrue(walk.done(), "goto has not finished");
            helper.assertTrue(walk.succeeded() && companion.blockPosition().distSqr(target) <= 2,
                    "she did not get past the lava: " + walk.outcome());
            helper.assertTrue(!burned[0], "she was burned on the way");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    // ---- follow:跟主人、跟的东西没了、编号不对、叫停 ----

    /** 跟着主人:主人挪到场地另一头,她跟过去停在身边;跟随是常驻的活,跟上了也不收场。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void follow_the_owner_keeps_up_when_they_move(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_shadow", new BlockPos(4, 2, 4), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_wanderer");
        ToolRun follow = lua(companion, "numen.move.follow()");
        BlockPos far = helper.absolutePos(new BlockPos(13, 2, 13));

        steps(helper)
                .thenIdle(20)
                .thenExecute(() -> owner.moveTo(far.getX() + 0.5, far.getY(), far.getZ() + 0.5))
                .thenWaitUntil(() -> helper.assertTrue(companion.distanceTo(owner) <= 4.5,
                        "she did not catch up with the owner: " + companion.distanceTo(owner)))
                .thenExecute(() -> helper.assertTrue(!follow.ended(),
                        "follow ended although it is a standing job: " + follow.outcome()))
                .thenExecute(() -> {
                    CompanionFactory.despawn(level.getServer(), companion);
                    leave(owner);
                })
                .thenSucceed();
    }

    /** 跟着一头猪:跟到了身边,猪没了,跟随自己收场,回执说跟的东西不在了。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void follow_an_entity_ends_when_it_is_gone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var pig = net.minecraft.world.entity.EntityType.PIG.create(level);
        BlockPos at = helper.absolutePos(new BlockPos(11, 2, 11));
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        pig.setNoAi(true);
        level.addFreshEntity(pig);
        NumenPlayer companion = spawnAt(helper, "gametest_swinefollower", new BlockPos(3, 2, 3), false);
        ToolRun follow = lua(companion, "numen.move.follow(" + pig.getId() + ")");

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(companion.distanceTo(pig) <= 4.5,
                        "she did not catch up with the pig: " + companion.distanceTo(pig)))
                .thenExecute(pig::discard)
                .thenWaitUntil(() -> helper.assertTrue(follow.ended() && !follow.succeeded()
                                && follow.outcome().contains("is gone"),
                        "follow did not end when the pig was gone: " + follow.outcome()))
                .thenExecute(() -> CompanionFactory.despawn(level.getServer(), companion))
                .thenSucceed();
    }

    /**
     * 跟着一只点名的实体,重启后接回来认的还是那一只:落盘的重放那一行写的是它的 UUID,不是只在这一次开服里有效的
     * 运行期编号(重启后同一个号会发给别的东西)。重启用"休眠 + 把落盘的那条记录放回去 + 复活"来演。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_terrain")
    public static void a_restored_follow_finds_the_same_entity_by_uuid(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var pig = net.minecraft.world.entity.EntityType.PIG.create(level);
        BlockPos at = helper.absolutePos(new BlockPos(11, 2, 11));
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        pig.setNoAi(true);
        level.addFreshEntity(pig);
        BlockPos spawn = helper.absolutePos(new BlockPos(3, 2, 3));
        NumenPlayer first = com.dwinovo.numen.entity.Companions.summon(server, UUID.randomUUID(),
                "gametest_uuid_follower", level, new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
        UUID uuid = first.getUUID();
        ToolRun follow = lua(first, "numen.move.follow(" + pig.getId() + ", {distance = 3})");
        var registry = com.dwinovo.numen.entity.CompanionRegistry.get(server);
        NumenPlayer[] second = new NumenPlayer[1];

        // 猪在远处:受理之前先规划走过去的路,受理之后才落盘
        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(follow.reply() != null, "follow has not replied"))
                .thenExecute(() -> {
                    helper.assertTrue(follow.task() != null, "follow was not accepted: " + follow.reply());
                    var recorded = registry.find(uuid);
                    helper.assertTrue(recorded.taskArgs().contains("move follow " + pig.getUUID())
                                    && !recorded.taskArgs().contains("move follow " + pig.getId() + " "),
                            "the replay recipe names the pig by its runtime id, not its UUID: " + recorded.taskArgs());
                    com.dwinovo.numen.entity.Companions.dormant(server, first);
                    registry.put(uuid, registry.find(uuid).doing(recorded.taskName(), recorded.taskTool(),
                            recorded.taskArgs()));
                    second[0] = com.dwinovo.numen.entity.Companions.respawn(server, uuid);
                    helper.assertTrue(second[0] != null, "the body was not rebuilt");
                })
                .thenWaitUntil(() -> {
                    TaskRecord now = com.dwinovo.numen.task.CompanionTickDispatcher.currentTaskFor(uuid);
                    helper.assertTrue(now instanceof com.dwinovo.numen.core.task.move.FollowTaskRecord f
                                    && pig.getUUID().equals(f.target),
                            "the replayed follow is not after the same pig: " + now);
                })
                .thenExecute(() -> {
                    com.dwinovo.numen.entity.Companions.dismiss(server, second[0]);
                    pig.discard();
                })
                .thenSucceed();
    }

    /** 给了一个这里没有的实体编号:当场失败,叫她先扫一眼附近的实体。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_terrain")
    public static void follow_an_unknown_entity_id_says_so(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_lost_tail", new BlockPos(3, 2, 3), false);
        ToolRun follow = lua(companion, "numen.move.follow(999999)");

        succeedWhen(helper, () -> {
            helper.assertTrue(follow.done(), "follow has not replied");
            helper.assertTrue(!follow.succeeded() && follow.outcome().contains("no entity with id 999999"),
                    "the failure does not name the missing id: " + follow.outcome());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 跟着主人的时候主人按停止:跟随按主人停止收场。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void owner_stop_while_following_ends_it(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_dismissed", new BlockPos(4, 2, 4), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_releaser");
        ToolRun follow = lua(companion, "numen.move.follow()");

        steps(helper)
                .thenIdle(20)
                .thenExecute(() -> com.dwinovo.numen.task.CompanionTickDispatcher.cancelFor(companion))
                .thenWaitUntil(() -> helper.assertTrue(follow.ended() && follow.outcome().startsWith("the owner pressed Stop"),
                        "following did not end as stopped by the owner: " + follow.outcome()))
                .thenExecute(() -> {
                    CompanionFactory.despawn(level.getServer(), companion);
                    leave(owner);
                })
                .thenSucceed();
    }

    // ---- numen.route.plan:默认规格没路、超预算 ----

    /** 默认规格(不改地形)规划上高台:没有路,回执失败,并给出放开自然地形的那一行;身体不动。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void route_plan_with_no_clean_way_says_what_to_try(GameTestHelper helper) {
        BlockPos top = bedrockTower(helper);
        NumenPlayer companion = spawnAt(helper, "gametest_surveyor", new BlockPos(3, 2, 7), false);
        BlockPos start = companion.blockPosition();
        ToolRun made = lua(companion, "numen.route.new(\"tower\", {to = " + xyz(top) + "})");
        ToolRun plan = lua(companion, "numen.route.plan(\"tower\")");

        succeedWhen(helper, () -> {
            helper.assertTrue(made.succeeded(), "route new failed: " + made.outcome());
            helper.assertTrue(plan.done(), "route plan has not replied");
            helper.assertTrue(!plan.succeeded() && plan.outcome().contains("numen.route.spec(\"tower\", {alter = \"natural\"})"),
                    "the reply does not point at the natural spec: " + plan.outcome());
            helper.assertTrue(companion.blockPosition().equals(start), "planning moved the body");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 允许改地形但改动预算只有 1 格,上高台至少要垫 3 格:没有预算内的路,回执说出最便宜的那条要改几格。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void route_plan_over_the_alter_budget_names_the_cheapest(GameTestHelper helper) {
        BlockPos top = bedrockTower(helper);
        NumenPlayer companion = spawnAt(helper, "gametest_frugal", new BlockPos(3, 2, 7), false);
        companion.getInventory().add(new ItemStack(Items.DIRT, 16));
        ToolRun made = lua(companion, "numen.route.new(\"tower\", {to = " + xyz(top) + ", alter = \"natural\", alter_budget = 1})");
        ToolRun plan = lua(companion, "numen.route.plan(\"tower\")");

        succeedWhen(helper, () -> {
            helper.assertTrue(made.succeeded(), "route new failed: " + made.outcome());
            helper.assertTrue(plan.done(), "route plan has not replied");
            helper.assertTrue(!plan.succeeded() && plan.outcome().contains("alter_budget of 1")
                            && plan.outcome().contains("numen.route.spec(\"tower\", {alter_budget = "),
                    "the reply does not say the budget ruled the routes out: " + plan.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.DIRT) == 16, "planning spent dirt");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }
}
