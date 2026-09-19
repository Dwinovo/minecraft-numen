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

/** 移动:{@code goto}、开门、地形许可(不改世界、按路线编号走)、{@code plan_route}、{@code follow}、载具,以及任务与编号跨重建的延续。 */
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

        TaskRecord record = call(companion, "goto", args(
                "x", (double) target.getX(),
                "y", (double) target.getY(),
                "z", (double) target.getZ())).task();

        helper.succeedWhen(() -> {
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
        TaskRecord record = call(companion, "goto", args(
                "x", (double) target.getX(),
                "z", (double) target.getZ())).task();
        boolean[] stopped = {false};

        helper.succeedWhen(() -> {
            if (!stopped[0]) {
                helper.assertTrue(record.getState() == com.dwinovo.numen.task.TaskState.RUNNING,
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
        TaskRecord record = call(companion, "goto", args(
                "x", (double) target.getX(),
                "y", (double) target.getY(),
                "z", (double) target.getZ())).task();
        helper.succeedWhen(() -> {
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
            TaskRecord record = call(companion, "goto", args(
                    "x", (double) target.getX(),
                    "y", (double) target.getY(),
                    "z", (double) target.getZ())).task();
        });

        helper.succeedWhen(() -> {
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
            TaskRecord press = call(companion, "interact_entity", args(
                    "button", "right",
                    "entity_id", boat.getId())).task();
        });
        helper.runAfterDelay(30, () -> {
            TaskRecord dig = call(companion, "interact_at", args(
                    "button", "left",
                    "x", stone.getX(),
                    "y", stone.getY(),
                    "z", stone.getZ())).task();
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(level.getBlockState(stone).isAir(),
                    "the follow-up dig never ran — the self-click press hung the sync slot");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 步行即下座驾,且只有一处说了算(PlayerNav):坐在矿车里对远处的盔甲架发
     * interact_entity,这不是 goto,任务层没有任何载具处置——她必须自己下车、走过去
     * 把它打掉(创造模式一下即碎)。乘客的行走输入对载具无效,没有这条规则她会坐着
     * "走"到失速。
     */
    @GameTest(template = "floor16", timeoutTicks = 600, batch = "numen_vehicle")
    public static void walking_task_steps_off_vehicle(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos cartAt = helper.absolutePos(new BlockPos(3, 2, 8));
        var cart = new net.minecraft.world.entity.vehicle.Minecart(
                level, cartAt.getX() + 0.5, cartAt.getY(), cartAt.getZ() + 0.5);
        level.addFreshEntity(cart);
        BlockPos standAt = helper.absolutePos(new BlockPos(11, 2, 8));
        var stand = new net.minecraft.world.entity.decoration.ArmorStand(
                level, standAt.getX() + 0.5, standAt.getY(), standAt.getZ() + 0.5);
        level.addFreshEntity(stand);

        NumenPlayer companion = spawnAt(helper, "gametest_rider", new BlockPos(3, 2, 6), true);
        helper.runAfterDelay(2, () -> {
            companion.startRiding(cart, true);
            TaskRecord hit = call(companion, "interact_entity", args(
                    "button", "left",
                    "entity_id", stand.getId())).task();
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(!companion.isPassenger(), "companion is still sitting in the minecart");
            helper.assertTrue(stand.isRemoved(),
                    "the armor stand was never reached — walking did not step off the vehicle");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 默认不开路:被木板屋关住,目标在屋外,goto 不带规格。她不能拆墙;回执必须是 TERRAIN_BLOCKED
     * 的候选清单——点名 oak_planks、给出路线 id 与取用方式——而且墙一块不少。
     * 这就是"挖穿主人的房子"那类投诉的根治点:路上动地形从引擎顺手干,变成模型选了才干。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void goto_refuses_to_tunnel_by_default(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        plankRoomAround(helper, 7, 7);
        int planksBefore = plankCount(helper, 7, 7);
        NumenPlayer companion = spawnAt(helper, "gametest_guest", new BlockPos(7, 2, 7), false);
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 7));
        TaskRecord record = call(companion, "goto", args(
                "x", (double) target.getX(),
                "y", (double) target.getY(),
                "z", (double) target.getZ())).task();

        helper.succeedWhen(() -> {
            String reply = record.getResult() == null ? null : record.getResult().message();
            helper.assertTrue(reply != null, "goto has not finished");
            helper.assertTrue(reply.contains("oak_planks"),
                    "the refusal does not name the blocks in the way: " + reply);
            helper.assertTrue(reply.contains("goto route:") && firstRouteId(reply) != null,
                    "the refusal does not list candidate routes by id: " + reply);
            helper.assertTrue(com.dwinovo.numen.core.pathing.plan.RouteBook.of(companion)
                    .get(firstRouteId(reply)) != null, "the listed route is not in the route book");
            helper.assertTrue(plankCount(helper, 7, 7) == planksBefore,
                    "the wall was damaged without consent");
            helper.assertTrue(companion.blockPosition().distSqr(target) > 3 * 3,
                    "companion got out without altering terrain?!");
            CompanionFactory.despawn(level.getServer(), companion);
        });
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
        TaskRecord record = call(companion, "goto", args(
                "x", (double) target.getX(),
                "y", (double) target.getY(),
                "z", (double) target.getZ(),
                "spec", naturalSpec())).task();

        helper.succeedWhen(() -> {
            helper.assertTrue(companion.blockPosition().distSqr(target) <= 2 * 2,
                    "companion has not reached the target with consent to dig");
            helper.assertTrue(plankCount(helper, 7, 7) < planksBefore, "no plank was broken");
            String reply = record.getResult() == null ? null : record.getResult().message();
            helper.assertTrue(reply != null && reply.contains("En route") && reply.contains("oak_planks"),
                    "the reply does not report what was broken en route: " + reply);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 选一条候选就开路:同一间屋,第一次 goto 被拒并列出候选,第二次 goto 带那条路的 id。
     * 她沿那条路拆墙出去到达目标,回执如实记账,路线簿里那条已划掉。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void goto_by_route_id(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        plankRoomAround(helper, 7, 7);
        int planksBefore = plankCount(helper, 7, 7);
        NumenPlayer companion = spawnAt(helper, "gametest_chooser", new BlockPos(7, 2, 7), false);
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 7));
        TaskRecord refused = call(companion, "goto", args(
                "x", (double) target.getX(),
                "y", (double) target.getY(),
                "z", (double) target.getZ())).task();
        TaskRecord[] walk = new TaskRecord[1];
        String[] chosen = new String[1];

        helper.succeedWhen(() -> {
            if (walk[0] == null) {
                String reply = refused.getResult() == null ? null : refused.getResult().message();
                helper.assertTrue(reply != null, "the first goto has not finished");
                chosen[0] = firstRouteId(reply);
                helper.assertTrue(chosen[0] != null, "the refusal lists no route id: " + reply);
                walk[0] = call(companion, "goto", args("route", chosen[0])).task();
            }
            helper.assertTrue(companion.blockPosition().distSqr(target) <= 2 * 2,
                    "companion has not reached the target along route " + chosen[0]);
            helper.assertTrue(plankCount(helper, 7, 7) < planksBefore, "no plank was broken");
            String reply = walk[0].getResult() == null ? null : walk[0].getResult().message();
            helper.assertTrue(reply != null && reply.contains("En route") && reply.contains("oak_planks"),
                    "the reply does not report what was broken en route: " + reply);
            helper.assertTrue(com.dwinovo.numen.core.pathing.plan.RouteBook.of(companion).get(chosen[0]) == null,
                    "a walked route is still in the route book");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 只算不走:同一间屋,plan_route 带 alter=natural 要两条候选。回执列出候选(点名 oak_planks、
     * 带 id),id 进了路线簿;她一步没动,墙一块不少。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void plan_route_lists_candidates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        plankRoomAround(helper, 7, 7);
        int planksBefore = plankCount(helper, 7, 7);
        NumenPlayer companion = spawnAt(helper, "gametest_planner", new BlockPos(7, 2, 7), false);
        BlockPos spawnPos = companion.blockPosition();
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 7));
        ToolRun reply = call(companion, "plan_route", args("x", target.getX(), "y", target.getY(), "z", target.getZ(),
                "spec", naturalSpec(), "alternatives", 2));

        helper.succeedWhen(() -> {
            helper.assertTrue(reply.reply() != null, "plan_route has not replied");
            helper.assertTrue(reply.reply().contains("oak_planks"),
                    "the plan does not name the blocks a route would break: " + reply.reply());
            String id = firstRouteId(reply.reply());
            helper.assertTrue(id != null && reply.reply().contains("goto route:"),
                    "the plan lists no route id: " + reply.reply());
            helper.assertTrue(com.dwinovo.numen.core.pathing.plan.RouteBook.of(companion).get(id) != null,
                    "the planned route is not in the route book");
            helper.assertTrue(plankCount(helper, 7, 7) == planksBefore, "planning altered the wall");
            helper.assertTrue(companion.blockPosition().equals(spawnPos), "planning moved the body");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 接近类动作从不动世界:盔甲架关在玻璃罩里,interact_entity 左键它。她到不了触及
     * 距离内的视线位,任务失败并把挡路的玻璃点名(goto 开路是模型的决定),玻璃一块不碎。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void approach_never_breaks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos standAt = helper.absolutePos(new BlockPos(8, 2, 8));
        var stand = new net.minecraft.world.entity.decoration.ArmorStand(
                level, standAt.getX() + 0.5, standAt.getY(), standAt.getZ() + 0.5);
        level.addFreshEntity(stand);
        // 玻璃罩:3×3×3,盔甲架在正中
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = 2; y <= 4; y++) {
                    boolean inside = dx == 0 && dz == 0 && y <= 3;
                    if (inside) continue;
                    level.setBlockAndUpdate(helper.absolutePos(new BlockPos(8 + dx, y, 8 + dz)),
                            Blocks.GLASS.defaultBlockState());
                }
            }
        }
        NumenPlayer companion = spawnAt(helper, "gametest_knocker", new BlockPos(3, 2, 3), true);
        TaskRecord hit = call(companion, "interact_entity", args(
                "button", "left",
                "entity_id", stand.getId())).task();

        helper.succeedWhen(() -> {
            String reply = hit.getResult() == null ? null : hit.getResult().message();
            helper.assertTrue(reply != null, "interact_entity has not finished");
            helper.assertTrue(stand.isAlive(), "the armor stand was hit through/after breaking glass");
            helper.assertTrue(reply.contains("glass"),
                    "the failure does not name the glass in the way: " + reply);
            int glass = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int y = 2; y <= 4; y++) {
                        if (level.getBlockState(helper.absolutePos(new BlockPos(8 + dx, y, 8 + dz)))
                                .is(Blocks.GLASS)) glass++;
                    }
                }
            }
            helper.assertTrue(glass == 25, "glass was broken: " + glass + "/25 left");
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
     * 回执列出候选路线(id 与要动的方块)——不是退避着站在原地空算,主人和模型都蒙在鼓里。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void follow_reports_when_terrain_blocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var stand = standOnPillar(helper);
        NumenPlayer companion = spawnAt(helper, "gametest_tail", new BlockPos(3, 2, 8), true);
        ToolRun follow = call(companion, "follow", args("entity_id", stand.getId(), "distance", 3));

        helper.succeedWhen(() -> {
            helper.assertTrue(follow.done() && !follow.succeeded(),
                    "follow should end with a failure, got: " + follow.outcome());
            String said = follow.outcome();
            helper.assertTrue(said.contains("altering terrain") && said.contains("goto route:")
                            && firstRouteId(said) != null,
                    "the reason must name the terrain and list candidate routes, got: " + said);
            helper.assertTrue(level.getBlockState(helper.absolutePos(new BlockPos(10, 5, 8))).is(Blocks.STONE),
                    "the pillar was touched without consent");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** plan_route 到 {@code rel} 那一格(回执稍后才到)。 */
    private static ToolRun planTo(GameTestHelper helper, NumenPlayer companion, BlockPos rel) {
        BlockPos target = helper.absolutePos(rel);
        return call(companion, "plan_route", args("x", target.getX(), "y", target.getY(), "z", target.getZ()));
    }

    /** {@code r12}、{@code g7} 里的数字。 */
    private static long idNumber(String id) {
        return Long.parseLong(id.substring(1));
    }

    /**
     * 编号跨身体重建接着往上数:扫一次、规划一次,拿到 g 与 r 两个编号;她休眠(身体落盘离场)再回来,是一具新身体、
     * 簿子是空的——旧的团编号说清楚没有扫描结果;再扫一次、再规划一次,新编号的数字都比休眠前的大,旧编号不会
     * 指到新团、新路上。
     */
    @GameTest(template = "floor16", timeoutTicks = 4000, batch = "numen_terrain")
    public static void ids_keep_counting_after_the_body_is_rebuilt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        BlockPos markRel = new BlockPos(6, 2, 6);
        level.setBlockAndUpdate(helper.absolutePos(markRel), Blocks.HONEYCOMB_BLOCK.defaultBlockState());
        BlockPos spawn = helper.absolutePos(new BlockPos(3, 2, 6));
        NumenPlayer first = com.dwinovo.numen.entity.Companions.summon(server, UUID.randomUUID(),
                "gametest_numberer", level, new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
        UUID uuid = first.getUUID();
        // 召唤会替她挑一个站得住的落点,不一定正好在 spawn 那格:半径给宽一点
        ToolRun firstScan = scan(first, 10, "minecraft:honeycomb_block");
        ToolRun[] firstPlan = new ToolRun[1];
        NumenPlayer[] second = new NumenPlayer[1];
        ToolRun[] secondScan = new ToolRun[1];
        ToolRun[] secondPlan = new ToolRun[1];
        String[] before = new String[2];   // 休眠前的 g 与 r

        helper.succeedWhen(() -> {
            if (before[0] == null) {
                helper.assertTrue(firstScan.reply() != null, "the first scan has not replied");
                var group = groupHolding(groupsIn(firstScan.reply()), helper.absolutePos(markRel));
                helper.assertTrue(group != null, "the first scan did not list the block: " + firstScan.reply());
                before[0] = group.get("id").getAsString();
                firstPlan[0] = planTo(helper, first, new BlockPos(3, 2, 11));
            }
            if (before[1] == null) {
                helper.assertTrue(firstPlan[0].reply() != null, "the first plan_route has not replied");
                before[1] = firstRouteId(firstPlan[0].reply());
                helper.assertTrue(before[1] != null, "the first plan lists no route id: " + firstPlan[0].reply());
                com.dwinovo.numen.entity.Companions.dormant(server, first);
                second[0] = com.dwinovo.numen.entity.Companions.respawn(server, uuid);
                helper.assertTrue(second[0] != null && second[0] != first, "the body was not rebuilt");
                String stale = com.dwinovo.numen.core.scan.GroupBook.of(second[0])
                        .staleMessage(List.of(before[0]));
                helper.assertTrue(stale != null && stale.contains("no scan_blocks result"),
                        "the rebuilt body still claims the old scan: " + stale);
                secondScan[0] = scan(second[0], 10, "minecraft:honeycomb_block");
            }
            if (secondPlan[0] == null) {
                helper.assertTrue(secondScan[0].reply() != null, "the second scan has not replied");
                secondPlan[0] = planTo(helper, second[0], new BlockPos(3, 2, 11));
            }
            helper.assertTrue(secondPlan[0].reply() != null, "the second plan_route has not replied");
            var group = groupHolding(groupsIn(secondScan[0].reply()), helper.absolutePos(markRel));
            helper.assertTrue(group != null, "the second scan did not list the block: " + secondScan[0].reply());
            String g = group.get("id").getAsString();
            String r = firstRouteId(secondPlan[0].reply());
            long highest = Math.max(idNumber(before[0]), idNumber(before[1]));
            helper.assertTrue(r != null && idNumber(g) > highest && idNumber(r) > idNumber(g),
                    "ids started over after the rebuild: before " + before[0] + "/" + before[1]
                            + ", after " + g + "/" + r);
            com.dwinovo.numen.entity.Companions.dismiss(server, second[0]);
        });
    }

    /**
     * 重启后接不回来的活不许让调度 tick 抛出去:存下的参数重放时已经不成立(mine 同时给了 block_ids 与 groups,
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
        registry.put(uuid, registry.find(uuid).doing("mine",
                "{\"block_ids\":[\"minecraft:stone\"],\"groups\":[\"g1\"],\"count\":1}"));
        NumenPlayer second = com.dwinovo.numen.entity.Companions.respawn(server, uuid);
        helper.assertTrue(second != null, "the body was not rebuilt");
        StringBuilder told = new StringBuilder();
        helper.succeedWhen(() -> {
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

    /** 一座三格高、3×3 的黑曜石台,台顶是 (11,5,7)。空手挖不动它,想上去只能垫方块。 */
    private static BlockPos obsidianTower(GameTestHelper helper) {
        for (int x = 10; x <= 12; x++) {
            for (int z = 6; z <= 8; z++) {
                for (int y = 2; y <= 4; y++) {
                    helper.getLevel().setBlockAndUpdate(helper.absolutePos(new BlockPos(x, y, z)),
                            Blocks.OBSIDIAN.defaultBlockState());
                }
            }
        }
        return helper.absolutePos(new BlockPos(11, 5, 7));
    }

    /** 默认不改地形:上高台要垫方块,她只列出候选路线让模型选,不动手,泥土一块没用。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void goto_up_a_tower_by_default_only_lists_routes(GameTestHelper helper) {
        BlockPos top = obsidianTower(helper);
        NumenPlayer companion = spawnAt(helper, "gametest_asker", new BlockPos(3, 2, 7), false);
        companion.getInventory().add(new ItemStack(Items.DIRT, 16));
        ToolRun walk = call(companion, "goto", args("x", top.getX(), "y", top.getY(), "z", top.getZ()));

        helper.succeedWhen(() -> {
            helper.assertTrue(walk.done(), "goto has not finished");
            helper.assertTrue(!walk.succeeded() && walk.outcome().contains("goto route:"),
                    "the reply does not offer routes to choose from: " + walk.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.DIRT) == 16
                            && companion.blockPosition().getY() < top.getY(),
                    "she built her way up without being allowed to");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 规格允许改地形、身上带着泥土:垫着爬上高台,泥土用掉了几块。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void goto_up_a_tower_with_natural_spec_pillars_up(GameTestHelper helper) {
        BlockPos top = obsidianTower(helper);
        NumenPlayer companion = spawnAt(helper, "gametest_climber", new BlockPos(3, 2, 7), false);
        companion.getInventory().add(new ItemStack(Items.DIRT, 16));
        ToolRun walk = call(companion, "goto", args("x", top.getX(), "y", top.getY(), "z", top.getZ(),
                "spec", naturalSpec()));

        helper.succeedWhen(() -> {
            helper.assertTrue(walk.done(), "goto has not finished");
            helper.assertTrue(walk.succeeded() && companion.blockPosition().distSqr(top) <= 2,
                    "she did not get onto the tower: " + walk.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.DIRT) < 16, "no dirt was spent climbing");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 允许改地形,但身上没有能垫的方块:上不去,回执说清楚缺的是垫脚的方块。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void goto_up_a_tower_without_scaffold_says_so(GameTestHelper helper) {
        BlockPos top = obsidianTower(helper);
        NumenPlayer companion = spawnAt(helper, "gametest_grounded", new BlockPos(3, 2, 7), false);
        ToolRun walk = call(companion, "goto", args("x", top.getX(), "y", top.getY(), "z", top.getZ(),
                "spec", naturalSpec()));

        helper.succeedWhen(() -> {
            helper.assertTrue(walk.done(), "goto has not finished");
            helper.assertTrue(!walk.succeeded() && walk.outcome().contains("scaffolding"),
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
        ToolRun walk = call(companion, "goto", args("x", target.getX(), "y", target.getY(), "z", target.getZ()));

        helper.succeedWhen(() -> {
            helper.assertTrue(walk.done(), "goto has not finished");
            helper.assertTrue(walk.succeeded() && companion.blockPosition().distSqr(target) <= 2,
                    "she did not get across the channel: " + walk.outcome());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    // ---- y 给得不对:半空、地里;水底、岩浆 ----

    /** y 猜到了半空(离地三格):那一格没法站,她走到那一列的地面上,算到达,回执教她下次省掉 y。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void goto_with_y_in_the_air_lands_on_the_ground_below(GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(11, 5, 7));
        NumenPlayer companion = spawnAt(helper, "gametest_skyward", new BlockPos(3, 2, 7), false);
        ToolRun walk = call(companion, "goto", args("x", target.getX(), "y", target.getY(), "z", target.getZ()));

        helper.succeedWhen(() -> {
            helper.assertTrue(walk.done(), "goto has not finished");
            helper.assertTrue(walk.succeeded() && walk.outcome().contains("omit y"),
                    "the mid-air y did not end on the ground with the hint: " + walk.outcome());
            BlockPos at = companion.blockPosition();
            int dx = at.getX() - target.getX();
            int dz = at.getZ() - target.getZ();
            helper.assertTrue(at.getY() == target.getY() - 3 && dx * dx + dz * dz <= 9,
                    "she is not on the ground beneath the target: " + at.toShortString());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** y 给成了地面那一块本身:那一格是实心的,她站到它上面,算到达,回执同样教她省掉 y。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void goto_with_y_inside_the_floor_lands_on_top_of_it(GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(11, 1, 7));
        NumenPlayer companion = spawnAt(helper, "gametest_grounded_y", new BlockPos(3, 2, 7), false);
        ToolRun walk = call(companion, "goto", args("x", target.getX(), "y", target.getY(), "z", target.getZ()));

        helper.succeedWhen(() -> {
            helper.assertTrue(walk.done(), "goto has not finished");
            helper.assertTrue(walk.succeeded() && walk.outcome().contains("omit y"),
                    "the y inside the floor did not end on top of it with the hint: " + walk.outcome());
            BlockPos at = companion.blockPosition();
            int dx = at.getX() - target.getX();
            int dz = at.getZ() - target.getZ();
            helper.assertTrue(at.getY() == target.getY() + 1 && dx * dx + dz * dz <= 9,
                    "she is not standing on the floor by the target: " + at.toShortString());
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
        ToolRun walk = call(companion, "goto", args("x", target.getX(), "y", target.getY(), "z", target.getZ()));

        helper.succeedWhen(() -> {
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
        ToolRun walk = call(companion, "goto", args("x", target.getX(), "y", target.getY(), "z", target.getZ()));
        boolean[] burned = new boolean[1];
        helper.onEachTick(() -> burned[0] |= companion.isOnFire() || companion.getHealth() < companion.getMaxHealth());

        helper.succeedWhen(() -> {
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
        NumenPlayer owner = presentOwner(helper, companion, "gametest_wanderer");
        ToolRun follow = call(companion, "follow", args());
        BlockPos far = helper.absolutePos(new BlockPos(13, 2, 13));

        helper.startSequence()
                .thenIdle(20)
                .thenExecute(() -> owner.moveTo(far.getX() + 0.5, far.getY(), far.getZ() + 0.5))
                .thenWaitUntil(() -> helper.assertTrue(companion.distanceTo(owner) <= 4.5,
                        "she did not catch up with the owner: " + companion.distanceTo(owner)))
                .thenExecute(() -> helper.assertTrue(!follow.done(),
                        "follow ended although it is a standing job: " + follow.outcome()))
                .thenExecute(() -> {
                    CompanionFactory.despawn(level.getServer(), companion);
                    CompanionFactory.despawn(level.getServer(), owner);
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
        ToolRun follow = call(companion, "follow", args("entity_id", pig.getId()));

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(companion.distanceTo(pig) <= 4.5,
                        "she did not catch up with the pig: " + companion.distanceTo(pig)))
                .thenExecute(pig::discard)
                .thenWaitUntil(() -> helper.assertTrue(follow.done() && !follow.succeeded()
                                && follow.outcome().contains("is gone"),
                        "follow did not end when the pig was gone: " + follow.outcome()))
                .thenExecute(() -> CompanionFactory.despawn(level.getServer(), companion))
                .thenSucceed();
    }

    /** 给了一个这里没有的实体编号:当场失败,叫她先扫一眼附近的实体。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_terrain")
    public static void follow_an_unknown_entity_id_says_so(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_lost_tail", new BlockPos(3, 2, 3), false);
        ToolRun follow = call(companion, "follow", args("entity_id", 999999));

        helper.succeedWhen(() -> {
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
        NumenPlayer owner = presentOwner(helper, companion, "gametest_releaser");
        ToolRun follow = call(companion, "follow", args());

        helper.startSequence()
                .thenIdle(20)
                .thenExecute(() -> com.dwinovo.numen.task.CompanionTickDispatcher.cancelFor(companion))
                .thenWaitUntil(() -> helper.assertTrue(follow.done() && follow.outcome().startsWith("the owner pressed Stop"),
                        "following did not end as stopped by the owner: " + follow.outcome()))
                .thenExecute(() -> {
                    CompanionFactory.despawn(level.getServer(), companion);
                    CompanionFactory.despawn(level.getServer(), owner);
                })
                .thenSucceed();
    }

    // ---- plan_route:默认规格没路、超预算 ----

    /** 默认规格(不改地形)规划上高台:没有路,回执失败,并提示换 alter:'natural' 再规划看看;身体不动。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void plan_route_with_no_clean_way_says_what_to_try(GameTestHelper helper) {
        BlockPos top = obsidianTower(helper);
        NumenPlayer companion = spawnAt(helper, "gametest_surveyor", new BlockPos(3, 2, 7), false);
        BlockPos start = companion.blockPosition();
        ToolRun plan = call(companion, "plan_route", args("x", top.getX(), "y", top.getY(), "z", top.getZ()));

        helper.succeedWhen(() -> {
            helper.assertTrue(plan.done(), "plan_route has not replied");
            helper.assertTrue(!plan.succeeded() && plan.reply().contains("alter:'natural'"),
                    "the reply does not point at the natural spec: " + plan.reply());
            helper.assertTrue(companion.blockPosition().equals(start), "planning moved the body");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 允许改地形但改动预算只有 1 格,上高台至少要垫 3 格:没有预算内的路,回执说出最便宜的那条要改几格。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_terrain")
    public static void plan_route_over_the_alter_budget_names_the_cheapest(GameTestHelper helper) {
        BlockPos top = obsidianTower(helper);
        NumenPlayer companion = spawnAt(helper, "gametest_frugal", new BlockPos(3, 2, 7), false);
        companion.getInventory().add(new ItemStack(Items.DIRT, 16));
        com.google.gson.JsonObject spec = naturalSpec();
        spec.addProperty("alter_budget", 1);
        ToolRun plan = call(companion, "plan_route", args("x", top.getX(), "y", top.getY(), "z", top.getZ(),
                "spec", spec));

        helper.succeedWhen(() -> {
            helper.assertTrue(plan.done(), "plan_route has not replied");
            helper.assertTrue(!plan.succeeded() && plan.reply().contains("alter_budget of 1"),
                    "the reply does not say the budget ruled the routes out: " + plan.reply());
            helper.assertTrue(companion.getInventory().countItem(Items.DIRT) == 16, "planning spent dirt");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }
}
