package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Scenes.stair;
import static com.dwinovo.numen.pathing.gametest.Scenes.trapdoor;
import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.properties.Half;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** 攀爬:梯子上下、藤蔓、阁楼、梯子顶端出口、脚手架、长梯子一直在推进、挂在梯子上挖。 */
@GameTestHolder("numen")
@PrefixGameTestTemplate(false)
public class ClimbGameTests {

    private static final String BATCH = "pathing_climb";

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    /** 一堵 {@code height} 格高的墙(x 9..12),西面贴一道梯子(x = 8,z = 5);墙顶的脚高是 {@code height + 1}。 */
    private static void ladderWall(Trial t, int height) {
        t.fill(9, 1, 3, 12, height, 7, Blocks.STONE);
        for (int y = 1; y <= height; y++) {
            t.set(8, y, 5, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST));
        }
    }

    /** 顺着梯子爬上五格高的墙,从梯子顶端跨到墙顶上。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void climbs_up_a_ladder_and_steps_off_the_top(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        ladderWall(t, 5);
        TestBody body = t.body(5, 1, 5);
        t.go(body, Goals.at(t.at(10, 6, 5)), RouteSpec.defaults()).arrives();
    }

    /** 从墙顶顺着梯子下来。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void climbs_down_a_ladder(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        ladderWall(t, 5);
        TestBody body = t.body(10, 6, 5);
        t.go(body, Goals.at(t.at(4, 1, 5)), RouteSpec.defaults()).arrives().then(UpDownGameTests::unhurt);
    }

    /** 藤蔓与梯子是同一种攀爬:顺着贴墙的藤蔓爬上去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void climbs_vines_like_a_ladder(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(9, 1, 3, 12, 5, 7, Blocks.STONE);
        for (int y = 1; y <= 5; y++) {
            t.set(8, y, 5, Blocks.VINE.defaultBlockState().setValue(VineBlock.EAST, true));
        }
        TestBody body = t.body(5, 1, 5);
        t.go(body, Goals.at(t.at(10, 6, 5)), RouteSpec.defaults()).arrives();
    }

    /** 阁楼:四格高处一层楼板,板上一个洞,梯子从洞里伸上去;洞口上方隔两格才是屋顶楼梯。 */
    private static void attic(Trial t, int roofY) {
        t.fill(3, 5, 3, 12, 5, 12, Blocks.OAK_PLANKS);
        t.set(8, 5, 8, Blocks.AIR);
        t.fill(8, 1, 9, 8, 5, 9, Blocks.STONE);
        for (int y = 1; y <= 5; y++) {
            t.set(8, y, 8, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH));
        }
        t.set(8, roofY, 8, stair(Direction.EAST, Half.TOP));
    }

    /** 屋顶楼梯压得不低,身体在洞口站得直:爬梯子进阁楼。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void climbs_into_an_attic_under_a_roof_stair(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        attic(t, 8);
        TestBody body = t.body(8, 1, 5);
        t.go(body, Goals.at(t.at(6, 6, 8)), RouteSpec.defaults()).arrives();
    }

    /**
     * 屋顶楼梯正压在洞口上方一格(倒扣的楼梯,只剩半格):身子从梯子顶上直不起来,也挪不进阁楼——不许改地形,就报要改地形,
     * 不在洞口来回卡着。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void a_roof_stair_right_over_the_hatch_needs_altering(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        attic(t, 7);
        TestBody body = t.body(8, 1, 5);
        t.go(body, Goals.at(t.at(6, 6, 8)), RouteSpec.defaults()).within(300).fails(Outcome.NeedsAlter.class);
    }

    /** 阁楼口是一扇关着的活板门:爬到顶,推开活板门,进阁楼。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void opens_a_trapdoor_over_a_ladder(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        attic(t, 9);
        t.set(8, 5, 8, trapdoor(Blocks.OAK_TRAPDOOR, Direction.NORTH, Half.TOP, false));
        t.set(8, 4, 8, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH));
        TestBody body = t.body(8, 1, 5);
        t.go(body, Goals.at(t.at(6, 6, 8)), RouteSpec.defaults()).arrives().then(r -> {
            if (r.report.ledger().entries().stream().noneMatch(e -> e instanceof EditLedger.Toggled)) {
                throw new GameTestAssertException("没推开活板门:" + r.report.ledger().entries());
            }
        });
    }

    /** 顺着一列脚手架爬上去,跨到旁边的台子上。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void climbs_scaffolding(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(9, 1, 3, 12, 5, 7, Blocks.STONE);
        for (int y = 1; y <= 5; y++) {
            t.set(8, y, 5, Blocks.SCAFFOLDING.defaultBlockState().setValue(ScaffoldingBlock.DISTANCE, 0));
        }
        TestBody body = t.body(5, 1, 5);
        t.go(body, Goals.at(t.at(10, 6, 5)), RouteSpec.defaults()).arrives();
    }

    /** 连续爬九格的梯子:一路上对外一直是"在推进",脱困反射不会来打断。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void keeps_progressing_up_a_long_ladder(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        ladderWall(t, 9);
        TestBody body = t.body(5, 1, 5);
        t.go(body, Goals.at(t.at(10, 10, 5)), RouteSpec.defaults())
                .during(r -> {
                    if (r.ticks > 5 && !r.navigation.progressing()) {
                        throw new GameTestAssertException("爬梯子途中不在推进了(第 " + r.ticks + " 刻)");
                    }
                })
                .arrives();
    }

    /** 梯子顶端的出口被一块泥土堵着:许改自然地形,挂在梯子上把它挖开再出去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 800)
    public static void digs_while_hanging_on_a_ladder(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        ladderWall(t, 4);
        // 墙顶上一条两格高、有顶的隧道,口子被一块泥土堵着:从泥土上面翻不过去
        t.fill(9, 5, 3, 12, 7, 7, Blocks.STONE);
        t.fill(9, 5, 5, 12, 6, 5, Blocks.AIR);
        t.set(9, 5, 5, Blocks.DIRT);
        t.set(8, 5, 5, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST));
        TestBody body = t.body(5, 1, 5);
        Trial.give(body, new ItemStack(Items.IRON_SHOVEL));
        t.go(body, Goals.at(t.at(11, 5, 5)), RouteSpec.defaults().edit().alter(RouteSpec.Alter.NATURAL).build())
                .within(700).arrives().then(r -> {
                    if (r.report.ledger().entries().stream().noneMatch(e -> e instanceof EditLedger.Dug)) {
                        throw new GameTestAssertException("没挖就出去了?" + r.report.ledger().entries());
                    }
                });
    }
}
