package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Scenes.slab;
import static com.dwinovo.numen.pathing.gametest.Scenes.stair;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** 挡路的方块与净空:栅栏、墙、栅栏门、玻璃板与铁栏杆;两格高的通道、一格半的缝、头顶按真实形状、起跳不撞头。 */
@GameTestHolder("numen")
@PrefixGameTestTemplate(false)
public class BlockGameTests {

    private static final String BATCH = "pathing_blocks";

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    /** 一道横贯场地、{@code height} 格高的 {@code block},只在 {@code gapZ} 留一个口:从口里过去,不从上面翻。 */
    private static void barrier(Trial t, int x, int gapZ, Block block, int height) {
        for (int z = 0; z <= 39; z++) {
            for (int y = 1; y <= height && z != gapZ; y++) {
                t.set(x, y, z, block);
            }
        }
    }

    private static void barrier(Trial t, int x, int gapZ, Block block) {
        barrier(t, x, gapZ, block, 1);
    }

    /** 从来没翻过去:身体的脚一直在地面那一层。 */
    private static void stayedLow(Trial.Run r) {
        if (r.highestFeet > 1.3) {
            throw new GameTestAssertException("翻过了挡路的方块:脚到过 " + r.highestFeet);
        }
    }

    /** 栅栏挡着(一格半高,跳不过去),从口里绕过去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void goes_around_a_fence(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        barrier(t, 8, 16, Blocks.OAK_FENCE);
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(12, 1, 5)), RouteSpec.defaults()).arrives().then(BlockGameTests::stayedLow);
    }

    /** 墙挡着,从口里绕过去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void goes_around_a_wall(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        barrier(t, 8, 16, Blocks.COBBLESTONE_WALL);
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(12, 1, 5)), RouteSpec.defaults()).arrives().then(BlockGameTests::stayedLow);
    }

    /** 栅栏上一扇开着的栅栏门:直接穿过去,不动它。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void passes_an_open_fence_gate(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        barrier(t, 8, 5, Blocks.OAK_FENCE);
        t.set(8, 1, 5, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.EAST)
                .setValue(FenceGateBlock.OPEN, true));
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(12, 1, 5)), RouteSpec.defaults()).arrives().then(r -> {
            if (!r.report.ledger().entries().isEmpty()) {
                throw new GameTestAssertException("开着的门也动了:" + r.report.ledger().entries());
            }
        });
    }

    /**
     * 栅栏上一扇关着的栅栏门,别处没有口:栅栏门身体能用手开,照原版打开它穿过去(开门不算改地形),实际账里记下这一开。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void opens_a_closed_fence_gate(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        barrier(t, 8, 5, Blocks.OAK_FENCE);
        t.set(8, 1, 5, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.EAST));
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(12, 1, 5)), RouteSpec.defaults()).arrives().then(r -> {
            boolean toggled = r.report.ledger().entries().stream().anyMatch(e -> e instanceof EditLedger.Toggled);
            if (!toggled) {
                throw new GameTestAssertException("没开门就过去了?" + r.report.ledger().entries());
            }
        });
    }

    /**
     * 两格高的玻璃板挡着:板子薄,身体也挤不过去,当墙,从口里绕过去。(一格高的玻璃板照原版站得上去,能踩着翻过去。)
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void treats_glass_panes_as_a_wall(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        barrier(t, 8, 16, Blocks.GLASS_PANE, 2);
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(12, 1, 5)), RouteSpec.defaults()).arrives().then(BlockGameTests::stayedLow);
    }

    /** 两格高的铁栏杆挡着,当墙,从口里绕过去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void treats_iron_bars_as_a_wall(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        barrier(t, 8, 16, Blocks.IRON_BARS, 2);
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(12, 1, 5)), RouteSpec.defaults()).arrives().then(BlockGameTests::stayedLow);
    }

    // ==================== 净空 ====================

    /** 两格高的通道照常走过去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void walks_a_two_high_tunnel(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(5, 1, 4, 14, 3, 6, Blocks.STONE);
        t.fill(5, 1, 5, 14, 2, 5, Blocks.AIR);
        TestBody body = t.body(2, 1, 5);
        t.go(body, Goals.at(t.at(16, 1, 5)), RouteSpec.defaults()).arrives();
    }

    /** 一格半高的缝(顶上是上半砖)钻不过去:绕远路,从不进那道缝。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void does_not_squeeze_through_a_one_and_a_half_gap(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(8, 1, 1, 8, 3, 20, Blocks.STONE);
        t.fill(8, 1, 5, 8, 2, 5, Blocks.AIR);
        t.set(8, 2, 5, slab(SlabType.TOP));
        t.fill(8, 1, 18, 8, 2, 18, Blocks.AIR);
        TestBody body = t.body(4, 1, 5);
        net.minecraft.core.BlockPos gap = t.at(8, 1, 5);
        t.go(body, Goals.at(t.at(12, 1, 5)), RouteSpec.defaults())
                .during(r -> {
                    if (r.body.blockPosition().equals(gap)) {
                        throw new GameTestAssertException("钻进了一格半高的缝");
                    }
                })
                .within(600).arrives();
    }

    /**
     * 头顶按真实形状判:通道顶上是关着的上半活板门(占格顶上 3/16,底下还剩 1.81 格)能过;另一条通道顶上是倒扣的楼梯
     * (只剩一格半)不能过。身体从活板门那条过去,从不进楼梯那条。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void judges_the_ceiling_by_its_real_shape(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(8, 1, 1, 10, 3, 20, Blocks.STONE);
        // z = 5:顶是倒扣的楼梯
        t.fill(8, 1, 5, 10, 1, 5, Blocks.AIR);
        t.fill(8, 2, 5, 10, 2, 5, stair(Direction.EAST, Half.TOP));
        // z = 9:顶是关着的上半活板门
        t.fill(8, 1, 9, 10, 1, 9, Blocks.AIR);
        t.fill(8, 2, 9, 10, 2, 9, trapdoor(Blocks.OAK_TRAPDOOR, Direction.NORTH, Half.TOP, false));
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(14, 1, 5)), RouteSpec.defaults())
                .during(r -> {
                    net.minecraft.core.BlockPos p = r.body.blockPosition().subtract(t.origin);
                    if (p.getZ() == 5 && p.getX() >= 8 && p.getX() <= 10) {
                        throw new GameTestAssertException("进了顶上是倒扣楼梯的那条");
                    }
                })
                .within(600).arrives().then(r -> {
                    if (!r.report.ledger().entries().isEmpty()) {
                        throw new GameTestAssertException("动了活板门:" + r.report.ledger().entries());
                    }
                });
    }

    /** 起跳上一格,起步那一列头顶三格处有方块:跳起来会碰头但照样上得去,不卡住。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void jumps_up_under_a_low_ceiling(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(8, 1, 3, 12, 1, 7, Blocks.STONE);
        t.fill(4, 4, 3, 7, 4, 7, Blocks.STONE);
        TestBody body = t.body(5, 1, 5);
        t.go(body, Goals.at(t.at(10, 2, 5)), RouteSpec.defaults()).arrives();
    }
}
