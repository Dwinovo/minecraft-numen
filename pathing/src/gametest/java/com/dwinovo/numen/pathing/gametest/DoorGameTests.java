package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Scenes.door;
import static com.dwinovo.numen.pathing.gametest.Scenes.trapdoor;
import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.Half;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** 门:木门自己开、铁门当墙、双开门、地上开着与关着的活板门、上一级与下一级与斜走途中的门、门板在侧面、通了红石的铁门。 */
@GameTestHolder("numen")
@PrefixGameTestTemplate(false)
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
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void opens_a_closed_wooden_door(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        room(t);
        door(t, 5, 1, 7, Blocks.OAK_DOOR, Direction.SOUTH, false);
        TestBody body = t.body(5, 1, 5);
        t.go(body, Goals.at(t.at(5, 1, 12)), RouteSpec.defaults()).arrives().then(DoorGameTests::toggled);
    }

    /** 一道墙上是没通红石的铁门,远处墙上有个口:铁门手开不了,当墙,从口里绕过去,铁门一直关着。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 800)
    public static void treats_an_unpowered_iron_door_as_a_wall(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(8, 1, 0, 8, 3, 39, Blocks.STONE);
        t.fill(8, 1, 20, 8, 2, 20, Blocks.AIR);
        door(t, 8, 1, 5, Blocks.IRON_DOOR, Direction.EAST, false);
        TestBody body = t.body(4, 1, 5);
        BlockPos doorway = t.at(8, 1, 5);
        t.go(body, Goals.at(t.at(12, 1, 5)), RouteSpec.defaults()).within(600)
                .during(r -> {
                    if (r.body.blockPosition().equals(doorway)) {
                        throw new GameTestAssertException("穿过了铁门");
                    }
                })
                .arrives().then(DoorGameTests::untouched);
    }

    /** 双开门:两扇门并排,铰链一左一右,都关着。开一扇出去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void goes_through_double_doors(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(3, 1, 3, 8, 3, 7, Blocks.STONE);
        t.fill(4, 1, 4, 7, 3, 6, Blocks.AIR);
        door(t, 5, 1, 7, Blocks.SPRUCE_DOOR, Direction.SOUTH, false);
        door(t, 6, 1, 7, Blocks.SPRUCE_DOOR, Direction.SOUTH, false);
        for (int y = 1; y <= 2; y++) {
            t.set(6, y, 7, t.state(6, y, 7).setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT));
        }
        TestBody body = t.body(5, 1, 5);
        t.go(body, Goals.at(t.at(6, 1, 12)), RouteSpec.defaults()).arrives().then(DoorGameTests::toggled);
    }

    /** 一格宽的走道上,地面有一扇开着的活板门(竖在走道一侧):从旁边走过去,不动它。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void walks_past_an_open_trapdoor_on_the_ground(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(3, 1, 4, 14, 2, 4, Blocks.STONE);
        t.fill(3, 1, 6, 14, 2, 6, Blocks.STONE);
        t.set(8, 1, 5, trapdoor(Blocks.OAK_TRAPDOOR, Direction.NORTH, Half.BOTTOM, true));
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(13, 1, 5)), RouteSpec.defaults()).arrives().then(DoorGameTests::untouched);
    }

    /** 地面上一扇关着的活板门:平躺着,当一层薄地面踩过去,不动它。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void walks_over_a_closed_trapdoor_on_the_ground(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(3, 1, 4, 14, 2, 4, Blocks.STONE);
        t.fill(3, 1, 6, 14, 2, 6, Blocks.STONE);
        t.set(8, 1, 5, trapdoor(Blocks.OAK_TRAPDOOR, Direction.NORTH, Half.BOTTOM, false));
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(13, 1, 5)), RouteSpec.defaults()).arrives().then(DoorGameTests::untouched);
    }

    /** 上一级台阶,落点那一格正是一扇关着的门:开门上去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void opens_a_door_on_a_step_up(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(7, 1, 0, 12, 1, 39, Blocks.STONE);
        t.fill(7, 2, 0, 7, 4, 39, Blocks.STONE);
        door(t, 7, 2, 5, Blocks.OAK_DOOR, Direction.WEST, false);
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(10, 2, 5)), RouteSpec.defaults()).arrives().then(DoorGameTests::toggled);
    }

    /** 下一级台阶,落点那一格正是一扇关着的门(门在台子脚下的墙上):站在台边开门,落进去,走出去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void opens_a_door_on_a_step_down(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(2, 1, 0, 7, 1, 39, Blocks.STONE);
        t.fill(8, 1, 0, 8, 4, 39, Blocks.STONE);
        // 门洞四格高:门在下面两格,上面空着,身子从台上平着进门洞再落下去
        t.fill(8, 3, 5, 8, 4, 5, Blocks.AIR);
        door(t, 8, 1, 5, Blocks.OAK_DOOR, Direction.EAST, false);
        TestBody body = t.body(5, 2, 5);
        t.go(body, Goals.at(t.at(12, 1, 5)), RouteSpec.defaults()).arrives().then(DoorGameTests::toggled);
    }

    /** 门板贴在走道一侧(门朝着与走道垂直的方向关着):身子从门板旁边过得去,不必开门。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void passes_a_door_whose_leaf_lies_along_the_way(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(3, 1, 4, 14, 3, 4, Blocks.STONE);
        t.fill(3, 1, 6, 14, 3, 6, Blocks.STONE);
        door(t, 8, 1, 5, Blocks.OAK_DOOR, Direction.SOUTH, false);
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(13, 1, 5)), RouteSpec.defaults()).arrives().then(DoorGameTests::untouched);
    }

    /** 铁门旁边一块红石块,门被红石顶开着:照它开着的样子走过去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void walks_through_a_powered_iron_door(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(8, 1, 0, 8, 3, 39, Blocks.STONE);
        door(t, 8, 1, 5, Blocks.IRON_DOOR, Direction.EAST, true);
        t.set(8, 1, 4, Blocks.REDSTONE_BLOCK);
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(12, 1, 5)), RouteSpec.defaults()).arrives().then(DoorGameTests::untouched);
    }

    /**
     * 斜着走的路上横着一整排关着的木门(一格一扇,门板贴在各格的南沿,再没有别的口):斜着走过来,开一扇门穿过去,斜着走到。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void opens_a_door_across_a_diagonal_way(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        for (int x = 0; x < 40; x++) {
            door(t, x, 1, 8, Blocks.OAK_DOOR, Direction.NORTH, false);
        }
        TestBody body = t.body(4, 1, 4);
        t.go(body, Goals.at(t.at(12, 1, 12)), RouteSpec.defaults()).arrives().then(DoorGameTests::toggled);
    }
}
