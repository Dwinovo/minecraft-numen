package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;
import static com.dwinovo.numen.pathing.gametest.Trial.LONG;

import com.dwinovo.numen.api.entity.BodyAction;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import com.dwinovo.numen.api.gametest.BeforeBatch;
import com.dwinovo.numen.api.gametest.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.Blocks;

/** 平地:直走、斜走、长距离,以及普查补充的起步情形。 */
public class FlatGameTests {

    private static final String BATCH = "pathing_flat";

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    /** 直走十格到那一格。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 297)
    public static void walks_straight(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(3, 1, 3);
        t.go(body, Goals.at(t.at(13, 1, 3)), RouteSpec.defaults()).arrives();
    }

    /** 斜着走过去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 324)
    public static void walks_diagonally(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(3, 1, 3);
        t.go(body, Goals.at(t.at(12, 1, 12)), RouteSpec.defaults()).arrives();
    }

    /** 两百格跨十几个区块。 */
    @GameTest(template = LONG, batch = BATCH, timeoutTicks = 1700)
    public static void walks_across_many_chunks(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(2, 1, 12);
        t.go(body, Goals.at(t.at(210, 1, 12)), RouteSpec.defaults()).within(1500).arrives();
    }

    // ==================== 普查补充 ====================

    /** 站在一根柱子顶上的边沿起步(身体中心已经悬空),不误判离开了路线,照样下去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 255)
    public static void starts_from_the_edge_of_a_pillar_top(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(5, 1, 5, 5, 2, 5, Blocks.STONE);
        TestBody body = t.body(5, 3, 5);
        body.snapTo(body.getX() + 0.75, body.getY(), body.getZ(), 0, 0);
        t.go(body, Goals.at(t.at(10, 1, 5)), RouteSpec.defaults()).arrives();
    }

    /** 三具身体同时各走各的,交叉而过,都到。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 375)
    public static void several_bodies_walk_at_once(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.go(t.body(2, 1, 2), Goals.at(t.at(14, 1, 14)), RouteSpec.defaults()).arrives();
        t.go(t.body(14, 1, 2), Goals.at(t.at(2, 1, 14)), RouteSpec.defaults()).arrives();
        t.go(t.body(8, 1, 2), Goals.at(t.at(8, 1, 15)), RouteSpec.defaults()).arrives();
    }

    /** 起步时头顶压着方块(原版让身体趴着):先挪到旁边站得起来的地方,再走。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 267)
    public static void starts_with_the_head_inside_a_block(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.set(5, 2, 5, Blocks.STONE);
        TestBody body = t.body(5, 1, 5);
        t.go(body, Goals.at(t.at(11, 1, 5)), RouteSpec.defaults())
                .arrives();
    }
}
