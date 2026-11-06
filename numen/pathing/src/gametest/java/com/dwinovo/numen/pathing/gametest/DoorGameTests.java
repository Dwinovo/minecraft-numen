package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Scenes.door;
import static com.dwinovo.numen.pathing.gametest.Scenes.trapdoor;
import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.Half;

/** 门:木门自己开、地上开着的活板门、门板在侧面。每种门在每种走法里各走一步的真世界对照在慢批的 matrix_doors、matrix_trapdoors。 */
public class DoorGameTests {

    private static final String BATCH = "pathing_doors";

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    /** 一间三格高的石屋,南墙正中留一个门洞。 */
    private static void room(Trial t) {
        t.fill(3, 1, 3, 7, 3, 7, Blocks.STONE);
        t.fill(4, 1, 4, 6, 3, 6, Blocks.AIR);
        t.fill(5, 1, 7, 5, 2, 7, Blocks.AIR);
    }

    private static void toggled(Trial.Run r) {
        if (r.report.ledger().entries().stream().noneMatch(e -> e instanceof EditLedger.Toggled)) {
            throw new GameTestAssertException("没开门:" + r.report.ledger().entries());
        }
    }

    private static void untouched(Trial.Run r) {
        if (!r.report.ledger().entries().isEmpty()) {
            throw new GameTestAssertException("动了门:" + r.report.ledger().entries());
        }
    }

    /** 关在屋里,唯一的出口是一扇关着的木门:自己开门出去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 282)
    public static void opens_a_closed_wooden_door(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        room(t);
        door(t, 5, 1, 7, Blocks.OAK_DOOR, Direction.SOUTH, false);
        TestBody body = t.body(5, 1, 5);
        t.go(body, Goals.at(t.at(5, 1, 12)), RouteSpec.defaults()).arrives().then(DoorGameTests::toggled);
    }

    /** 一格宽的走道上,地面有一扇开着的活板门(竖在走道一侧):从旁边走过去,不动它。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 285)
    public static void walks_past_an_open_trapdoor_on_the_ground(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(3, 1, 4, 14, 2, 4, Blocks.STONE);
        t.fill(3, 1, 6, 14, 2, 6, Blocks.STONE);
        t.set(8, 1, 5, trapdoor(Blocks.OAK_TRAPDOOR, Direction.NORTH, Half.BOTTOM, true));
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(13, 1, 5)), RouteSpec.defaults()).arrives().then(DoorGameTests::untouched);
    }

    /** 门板贴在走道一侧(门朝着与走道垂直的方向关着):身子从门板旁边过得去,不必开门。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 285)
    public static void passes_a_door_whose_leaf_lies_along_the_way(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(3, 1, 4, 14, 3, 4, Blocks.STONE);
        t.fill(3, 1, 6, 14, 3, 6, Blocks.STONE);
        door(t, 8, 1, 5, Blocks.OAK_DOOR, Direction.SOUTH, false);
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(13, 1, 5)), RouteSpec.defaults()).arrives().then(DoorGameTests::untouched);
    }
}
