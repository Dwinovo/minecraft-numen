package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;
import static com.dwinovo.numen.pathing.gametest.Trial.LONG;

import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.body.BodyAction;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** 平地:直走、斜走、绕开障碍、窄道、不切角、目标就在脚下、长距离、四面封死,以及普查补充的起步情形。 */
@GameTestHolder("numen")
@PrefixGameTestTemplate(false)
public class FlatGameTests {

    private static final String BATCH = "pathing_flat";

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    /** 直走十格到那一格。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void walks_straight(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(3, 1, 3);
        t.go(body, Goals.at(t.at(13, 1, 3)), RouteSpec.defaults()).arrives();
    }

    /** 斜着走过去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void walks_diagonally(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(3, 1, 3);
        t.go(body, Goals.at(t.at(12, 1, 12)), RouteSpec.defaults()).arrives();
    }

    /** 中间一堵墙,绕过去;不许改地形,世界不变。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void walks_around_an_obstacle(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(8, 1, 2, 8, 2, 10, Blocks.STONE);
        TestBody body = t.body(4, 1, 6);
        t.go(body, Goals.at(t.at(12, 1, 6)), RouteSpec.defaults()).arrives();
    }

    /** 一格宽、两格高的窄道走到头。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void walks_a_one_wide_corridor(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(2, 1, 4, 16, 2, 4, Blocks.STONE);
        t.fill(2, 1, 6, 16, 2, 6, Blocks.STONE);
        TestBody body = t.body(3, 1, 5);
        t.go(body, Goals.at(t.at(15, 1, 5)), RouteSpec.defaults()).arrives();
    }

    /** 两块斜对着的石头之间的缝不许斜穿:绕过去,身体从不挤进那道缝。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void does_not_cut_a_corner_between_two_blocks(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(6, 1, 7, 6, 2, 7, Blocks.STONE);
        t.fill(7, 1, 6, 7, 2, 6, Blocks.STONE);
        TestBody body = t.body(6, 1, 6);
        double gapX = t.at(7, 1, 7).getX();
        double gapZ = t.at(7, 1, 7).getZ();
        t.go(body, Goals.at(t.at(7, 1, 7)), RouteSpec.defaults())
                .during(r -> {
                    double dx = r.body.getX() - gapX;
                    double dz = r.body.getZ() - gapZ;
                    if (dx * dx + dz * dz < 0.3 * 0.3) {
                        throw new GameTestAssertException("身体挤进了两块之间的缝");
                    }
                })
                .arrives();
    }

    /** 目标就在脚下:当场到达,一步不走。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 300)
    public static void arrives_at_once_when_standing_in_the_goal(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(5, 1, 5);
        t.go(body, Goals.at(t.at(5, 1, 5)), RouteSpec.defaults()).within(40)
                .then(r -> {
                    if (r.report.ledger().steps() != 0) {
                        throw new GameTestAssertException("目标就在脚下却走了 " + r.report.ledger().steps() + " 步");
                    }
                })
                .arrives();
    }

    /** 两百格跨十几个区块。 */
    @GameTest(template = LONG, batch = BATCH, timeoutTicks = 1700)
    public static void walks_across_many_chunks(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(2, 1, 12);
        t.go(body, Goals.at(t.at(210, 1, 12)), RouteSpec.defaults()).within(1500).arrives();
    }

    /** 四面与头顶都是基岩:搜完没有路。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void a_sealed_box_has_no_route(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(4, 0, 4, 8, 4, 8, Blocks.BEDROCK);
        t.fill(5, 1, 5, 7, 3, 7, Blocks.AIR);
        TestBody body = t.body(6, 1, 6);
        t.go(body, Goals.at(t.at(15, 1, 15)), RouteSpec.defaults()).fails(Outcome.NoRoute.class);
    }

    // ==================== 普查补充 ====================

    /** 站在一根柱子顶上的边沿起步(身体中心已经悬空),不误判离开了路线,照样下去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void starts_from_the_edge_of_a_pillar_top(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(5, 1, 5, 5, 2, 5, Blocks.STONE);
        TestBody body = t.body(5, 3, 5);
        body.moveTo(body.getX() + 0.75, body.getY(), body.getZ(), 0, 0);
        t.go(body, Goals.at(t.at(10, 1, 5)), RouteSpec.defaults()).arrives();
    }

    /** 路上有下半砖、楼梯把身体抬起来:照走不停,十几格不到一百刻。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void keeps_walking_when_slabs_and_stairs_lift_the_body(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.set(6, 1, 5, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        t.set(7, 1, 5, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        t.set(9, 1, 5, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
        t.fill(10, 1, 5, 12, 1, 5, Blocks.STONE);
        TestBody body = t.body(3, 1, 5);
        t.go(body, Goals.at(t.at(12, 2, 5)), RouteSpec.defaults()).within(100).arrives();
    }

    /** 开走没几刻就被传送到别处:从新的地方重新搜,照样到。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void replans_right_after_a_teleport(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(3, 1, 3);
        net.minecraft.core.BlockPos away = t.at(3, 1, 20);
        t.go(body, Goals.at(t.at(14, 1, 10)), RouteSpec.defaults())
                .during(r -> {
                    if (r.ticks == 8) {
                        r.body.teleportTo(away.getX() + 0.5, away.getY(), away.getZ() + 0.5);
                    }
                })
                .arrives();
    }

    /** 三具身体同时各走各的,交叉而过,都到。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void several_bodies_walk_at_once(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.go(t.body(2, 1, 2), Goals.at(t.at(14, 1, 14)), RouteSpec.defaults()).arrives();
        t.go(t.body(14, 1, 2), Goals.at(t.at(2, 1, 14)), RouteSpec.defaults()).arrives();
        t.go(t.body(8, 1, 2), Goals.at(t.at(8, 1, 15)), RouteSpec.defaults()).arrives();
    }

    /** 起步时头顶压着方块(原版让身体趴着):先挪到旁边站得起来的地方,再走。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void starts_with_the_head_inside_a_block(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.set(5, 2, 5, Blocks.STONE);
        TestBody body = t.body(5, 1, 5);
        t.go(body, Goals.at(t.at(11, 1, 5)), RouteSpec.defaults())
                .arrives();
    }

    /** 起步坐在船上:按潜行下船,记进身体动作,再走。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void steps_off_a_vehicle_first(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(5, 1, 5);
        Boat boat = EntityType.BOAT.create(t.level);
        net.minecraft.core.BlockPos at = t.at(5, 1, 5);
        boat.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        t.level.addFreshEntity(boat);
        body.startRiding(boat, true);
        t.go(body, Goals.at(t.at(11, 1, 5)), RouteSpec.defaults())
                .then(r -> {
                    boolean dismounted = r.report.actions().stream().anyMatch(a -> a instanceof BodyAction.Dismounted);
                    if (!dismounted) {
                        throw new GameTestAssertException("下了船却没记进身体动作:" + r.report.actions());
                    }
                    boat.discard();
                })
                .arrives();
    }
}
