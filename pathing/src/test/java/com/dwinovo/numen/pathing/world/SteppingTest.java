package com.dwinovo.numen.pathing.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;

import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.world.Stepping.Step;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.pathing.Vanilla.SURVIVAL;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迈步:从一列走进相邻一列,是走过去、要跳还是过不去。场景都摆在 Y - 1 的石头地板上,空地上脚在 Y;
 * 终点节点的脚高一律由 {@link Footing} 给出。
 */
class SteppingTest {

    private static final int Y = 64;
    private static final BlockPos AT = new BlockPos(0, Y, 0);

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    private static TestWorld ground() {
        return new TestWorld().floor(-3, -3, 3, 3, Y - 1);
    }

    /** 从 {@code from} 列、脚在 {@code fromFeet},朝 {@code dir} 走进相邻一列,落在节点 {@code toNode}。 */
    private static Step step(TestWorld world, BlockPos from, double fromFeet, Direction dir, int toNode) {
        int tx = from.getX() + dir.getStepX();
        int tz = from.getZ() + dir.getStepZ();
        double toFeet = Footing.height(world, SURVIVAL, tx, toNode, tz);
        return Stepping.between(world, SURVIVAL, from.getX(), fromFeet, from.getZ(), dir.getStepX(), dir.getStepZ(), toFeet);
    }

    private static BlockState stair(Direction facing) {
        return Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, Half.BOTTOM);
    }

    @Test
    void walkingOntoAStairFromItsFrontNeedsNoJump() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            TestWorld world = ground().set(AT, stair(facing));
            // 朝北的楼梯高的那半在北边:从南面走进来,先上下半层再上上半层,每次半格
            BlockPos front = AT.relative(facing.getOpposite());
            assertEquals(Step.WALK, step(world, front, Y, facing, Y + 1), "朝 " + facing + " 的楼梯从正面走上");
        }
    }

    @Test
    void enteringAStairFromItsBackOrSideNeedsAJump() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            TestWorld world = ground().set(AT, stair(facing));
            BlockPos back = AT.relative(facing);
            assertEquals(Step.JUMP, step(world, back, Y, facing.getOpposite(), Y + 1), "朝 " + facing + " 的楼梯从背面上");
            for (Direction side : new Direction[] {facing.getClockWise(), facing.getCounterClockWise()}) {
                assertEquals(Step.JUMP, step(world, AT.relative(side), Y, side.getOpposite(), Y + 1),
                        "朝 " + facing + " 的楼梯从 " + side + " 侧上");
            }
        }
    }

    @Test
    void walkingDownAStairFromItsTopNeedsNoJump() {
        TestWorld world = ground().set(AT, stair(Direction.NORTH));
        assertEquals(Step.WALK, step(world, AT, Y + 1, Direction.SOUTH, Y));
    }

    @Test
    void aBottomSlabIsWalkedUp() {
        TestWorld world = ground().set(AT, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        assertEquals(Step.WALK, step(world, AT.south(), Y, Direction.NORTH, Y));
    }

    @Test
    void aFullBlockNeedsAJumpAndTwoNeedMore() {
        TestWorld one = ground().set(AT, Blocks.STONE.defaultBlockState());
        assertEquals(Step.JUMP, step(one, AT.south(), Y, Direction.NORTH, Y + 1));
        TestWorld two = ground().set(AT, Blocks.STONE.defaultBlockState()).set(AT.above(), Blocks.STONE.defaultBlockState());
        assertEquals(Step.BLOCKED, step(two, AT.south(), Y, Direction.NORTH, Y + 2));
    }

    @Test
    void fencesAndWallsCannotBeClimbedOrJumped() {
        for (BlockState post : new BlockState[] {Blocks.OAK_FENCE.defaultBlockState(), Blocks.COBBLESTONE_WALL.defaultBlockState()}) {
            TestWorld world = ground().set(AT, post);
            assertEquals(Step.BLOCKED, step(world, AT.south(), Y, Direction.NORTH, Y + 1), post + " 顶上");
        }
    }

    @Test
    void panesAndBarsBlockThePathButTheirTopCanBeJumpedOnto() {
        for (BlockState thin : new BlockState[] {Blocks.GLASS_PANE.defaultBlockState(), Blocks.IRON_BARS.defaultBlockState()}) {
            TestWorld world = ground().set(AT, thin);
            assertEquals(Step.BLOCKED, step(world, AT.south(), Y, Direction.NORTH, Y), thin + " 穿不过去");
            assertEquals(Step.JUMP, step(world, AT.south(), Y, Direction.NORTH, Y + 1), thin + " 顶上跳得上去");
        }
    }

    @Test
    void walkingOffALedgeNeedsNoJump() {
        TestWorld world = ground().set(AT, Blocks.STONE.defaultBlockState());
        assertEquals(Step.WALK, step(world, AT, Y + 1, Direction.SOUTH, Y));
    }

    @Test
    void aJumpUnderALowCeilingBumpsTheHead() {
        BlockPos from = AT.south();
        TestWorld open = ground().set(AT, Blocks.STONE.defaultBlockState());
        assertEquals(Step.JUMP, step(open, from, Y, Direction.NORTH, Y + 1));
        TestWorld low = ground().set(AT, Blocks.STONE.defaultBlockState()).set(from.above(2), Blocks.STONE.defaultBlockState());
        assertEquals(Step.BLOCKED, step(low, from, Y, Direction.NORTH, Y + 1), "起跳时头顶撞上方块");
    }

    @Test
    void honeyHalvesTheJump() {
        BlockPos from = AT.south();
        // 终点是两格高的台,顶面比起点的脚高出一格左右
        TestWorld stone = ground().set(from, Blocks.STONE.defaultBlockState())
                .set(AT, Blocks.STONE.defaultBlockState()).set(AT.above(), Blocks.STONE.defaultBlockState());
        assertEquals(Step.JUMP, step(stone, from, Y + 1, Direction.NORTH, Y + 2));
        TestWorld honey = ground().set(from, Blocks.HONEY_BLOCK.defaultBlockState())
                .set(AT, Blocks.STONE.defaultBlockState()).set(AT.above(), Blocks.STONE.defaultBlockState());
        assertEquals(Step.BLOCKED, step(honey, from, Y + 15 / 16.0, Direction.NORTH, Y + 2), "蜂蜜块上跳不上一格高的台");
    }

    @Test
    void fromSoulSandAFullBlockIsAStepNotAJump() {
        BlockPos from = AT.south();
        TestWorld world = ground().set(from, Blocks.SOUL_SAND.defaultBlockState()).set(AT, Blocks.STONE.defaultBlockState());
        assertEquals(Step.WALK, step(world, from, Y + 14 / 16.0, Direction.NORTH, Y + 1));
    }

    @Test
    void aDiagonalStepCannotCutBetweenTwoCorners() {
        TestWorld open = ground();
        assertEquals(Step.WALK, Stepping.between(open, SURVIVAL, 0, Y, 0, 1, 1, Y));
        TestWorld squeezed = ground();
        for (BlockPos corner : new BlockPos[] {new BlockPos(1, Y, 0), new BlockPos(0, Y, 1)}) {
            squeezed.set(corner, Blocks.STONE.defaultBlockState()).set(corner.above(), Blocks.STONE.defaultBlockState());
        }
        assertEquals(Step.BLOCKED, Stepping.between(squeezed, SURVIVAL, 0, Y, 0, 1, 1, Y));
    }

    /** 在 AT 立一扇朝北的门(上下两半),从南到北穿过门格,两步都能走过去才算穿得过。 */
    private static boolean passesThrough(BlockState lower) {
        TestWorld world = ground().set(AT, lower).set(AT.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        return step(world, AT.south(), Y, Direction.NORTH, Y) == Step.WALK
                && step(world, AT, Y, Direction.NORTH, Y) == Step.WALK;
    }

    @Test
    void aClosedDoorBlocksAndAnOpenedDoorLetsThrough() {
        BlockState closed = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        assertTrue(!passesThrough(closed), "关着的门挡路");
        assertTrue(passesThrough(Semantics.toggled(closed)), "身体把门打开后穿得过");
    }

    @Test
    void anIronDoorIsPassableExactlyWhenItIsOpen() {
        BlockState closed = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        assertTrue(!passesThrough(closed));
        assertTrue(passesThrough(closed.setValue(DoorBlock.OPEN, true)), "红石开着的铁门照真实状态可过");
    }

    @Test
    void walkingAlongAClosedDoorPanelIsNotBlocked() {
        // 门板贴在格边、与走的方向平行:从东往西横穿门格不碰门板
        BlockState closed = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        TestWorld world = ground().set(AT, closed).set(AT.above(), closed.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        assertEquals(Step.WALK, step(world, AT.east(), Y, Direction.WEST, Y));
        assertEquals(Step.WALK, step(world, AT, Y, Direction.WEST, Y));
    }

    /**
     * 开着的门:朝北的门(铰链默认在左)开了之后门板转到格子的西边、沿南北方向立着。顺着门板南北穿过门格不碰它;从东往西
     * 横穿过去、从西边走进门格,都要穿过门板,过不去。
     */
    @Test
    void anOpenDoorBlocksWalkingAcrossItsPanelButNotAlongIt() {
        BlockState open = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
                .setValue(DoorBlock.OPEN, true);
        TestWorld world = ground().set(AT, open).set(AT.above(), open.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        assertEquals(Step.WALK, step(world, AT.south(), Y, Direction.NORTH, Y), "从南边顺着门板走进门格");
        assertEquals(Step.WALK, step(world, AT, Y, Direction.NORTH, Y), "顺着门板从北边出去");
        assertEquals(Step.WALK, step(world, AT.east(), Y, Direction.WEST, Y), "从东边进门格,还没碰到西边的门板");
        assertEquals(Step.BLOCKED, step(world, AT, Y, Direction.WEST, Y), "往西出去要穿过门板");
        assertEquals(Step.BLOCKED, step(world, AT.west(), Y, Direction.EAST, Y), "从西边进门格要穿过门板");
    }

    @Test
    void walkingOffAnEdgeTellsWhereTheFeetLand() {
        TestWorld world = ground().set(AT.east().below(), Blocks.AIR.defaultBlockState())
                .set(AT.east().below(3), Blocks.STONE.defaultBlockState());
        assertEquals(Y - 2, Stepping.walkOff(world, SURVIVAL, 0, Y, 0, 1, 0, Y - 4), 1e-9, "落到两格下的石头上");
        assertEquals(Double.NEGATIVE_INFINITY, Stepping.walkOff(world, SURVIVAL, 0, Y, 0, 1, 0, Y - 1),
                "看的范围里脚下没有东西");
        TestWorld wall = ground().set(AT.east(), Blocks.STONE.defaultBlockState());
        assertTrue(Double.isNaN(Stepping.walkOff(wall, SURVIVAL, 0, Y, 0, 1, 0, Y - 4)), "要跳才过得去");
    }

    // ==================== 整块位图与逐个碰撞箱答得一模一样 ====================

    /** 八个方向:四个正方向、四个斜方向。 */
    private static final int[][] DIRECTIONS = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}, {1, -1}, {-1, -1}, {1, 1}, {-1, 1}};
    private static final AABB UNIT = new AABB(0, 0, 0, 1, 1, 1);

    /**
     * 迈步要看的格全是全空或整块时,推导按每列一个整块位图走({@link Stepping.Columns});否则把碰撞箱逐个比
     * ({@link Stepping.Pieces})。同一份几何交给两种记法,推导的每一步(取样点、最后的脚高、最大的坎、最高点与它在哪)与结论
     * (走、跳、过不去,走出边沿落到多高)必须完全相同。起点脚在 Y;八个方向;落到低一格、同一格、高一格的节点,以及不指定终点
     * 地走出边沿。
     *
     * <p>正方向把收进来的两列每一格的全空、整块都穷举。斜方向收四列,全部穷举有两亿多种,改为穷举身体碰得到的格:起点脚在
     * 整数高度、整块的顶也都是整数,途中的脚高只会是整数(起跳最多升 1.25 格,也就最多到 Y + 1),头顶最多到 Y + 2.8,
     * 脚下看到的最低是收进来的最低一格——窗口从那一格到 Y + 2;窗口外的格只影响取样点,各按全空、全整块两种摆。
     */
    @Test
    void wholeBlockColumnsAnswerExactlyLikeTheirBoxes() {
        BodyStats body = SURVIVAL;
        double jump = body.jumpHeight(1.0);
        double height = body.height(net.minecraft.world.entity.Pose.STANDING);
        for (int[] d : DIRECTIONS) {
            int dx = d[0];
            int dz = d[1];
            boolean diagonal = dx != 0 && dz != 0;
            for (int to = Y - 1; to <= Y + 1; to++) {
                int y0 = Footing.cellOf(Math.min(Y, to)) - 1;
                int y1 = net.minecraft.util.Mth.floor(Math.max(Y, to) + jump + height) + 1;
                double toFeet = to;
                exhaust(dx, dz, y0, y1, diagonal ? Y + 2 : y1, (columns, pieces) -> {
                    sameWalk(Stepping.walk(pieces, body, jump, 0, Y, 0, dx, dz), Stepping.walk(columns, body, jump, 0, Y, 0, dx, dz));
                    assertEquals(Stepping.between(pieces, body, jump, 0, Y, 0, dx, dz, toFeet),
                            Stepping.between(columns, body, jump, 0, Y, 0, dx, dz, toFeet));
                });
            }
            for (int lowest : diagonal ? new int[] {Y - 1} : new int[] {Y - 1, Y - 3}) {
                int y0 = Footing.cellOf(Math.min(Y, lowest)) - 1;
                int y1 = net.minecraft.util.Mth.floor(Y + jump + height) + 1;
                exhaust(dx, dz, y0, y1, diagonal ? Y + 2 : y1, (columns, pieces) -> {
                    sameWalk(Stepping.walk(pieces, body, jump, 0, Y, 0, dx, dz), Stepping.walk(columns, body, jump, 0, Y, 0, dx, dz));
                    assertEquals(0, Double.compare(Stepping.walkOff(pieces, body, jump, 0, Y, 0, dx, dz),
                            Stepping.walkOff(columns, body, jump, 0, Y, 0, dx, dz)));
                });
            }
        }
    }

    /**
     * 同上,起点脚在格子中间的高度(0.25、0.5):推导照样一模一样。实际走到整块位图时起点总在整数高度——托着脚的那一块也在
     * 收进来的格里,它是整块,脚就在整数高度;这里把推导本身在任意脚高上核一遍。正方向,收进来的格全部穷举。
     */
    @Test
    void wholeBlockColumnsAnswerExactlyLikeTheirBoxesFromAnyFeetHeight() {
        BodyStats body = SURVIVAL;
        double jump = body.jumpHeight(1.0);
        double height = body.height(net.minecraft.world.entity.Pose.STANDING);
        for (int[] d : Arrays.copyOf(DIRECTIONS, 4)) {
            int dx = d[0];
            int dz = d[1];
            for (double from : new double[] {Y + 0.25, Y + 0.5}) {
                for (double to : new double[] {from - 1, from, from + 1, Y, Y + 1}) {
                    int y0 = Footing.cellOf(Math.min(from, to)) - 1;
                    int y1 = net.minecraft.util.Mth.floor(Math.max(from, to) + jump + height) + 1;
                    exhaust(dx, dz, y0, y1, y1, (columns, pieces) -> {
                        sameWalk(Stepping.walk(pieces, body, jump, 0, from, 0, dx, dz),
                                Stepping.walk(columns, body, jump, 0, from, 0, dx, dz));
                        assertEquals(Stepping.between(pieces, body, jump, 0, from, 0, dx, dz, to),
                                Stepping.between(columns, body, jump, 0, from, 0, dx, dz, to));
                        assertEquals(0, Double.compare(Stepping.walkOff(pieces, body, jump, 0, from, 0, dx, dz),
                                Stepping.walkOff(columns, body, jump, 0, from, 0, dx, dz)));
                    });
                }
            }
        }
    }

    private interface Check {
        void run(Stepping.Obstacles columns, Stepping.Obstacles pieces);
    }

    /**
     * 朝 {@code (dx, dz)} 走一步要收的那几列、{@code y0} 到 {@code y1} 的格:{@code y0} 到 {@code top} 这个窗口里每格的全空、
     * 整块逐一穷举,窗口外的格全空、全整块各摆一遍;每种摆法交给 {@code check},两种记法各一份。
     */
    private static void exhaust(int dx, int dz, int y0, int y1, int top, Check check) {
        int x0 = Math.min(0, dx);
        int z0 = Math.min(0, dz);
        int nx = Math.abs(dx) + 1;
        int nz = Math.abs(dz) + 1;
        int width = top - y0 + 1;
        int bits = width * nx * nz;
        boolean[] backgrounds = top < y1 ? new boolean[] {false, true} : new boolean[] {false};
        for (boolean background : backgrounds) {
            LongStream.range(0, 1L << bits).parallel().forEach(layout -> {
                Stepping.Columns columns = new Stepping.Columns(x0, z0, nx, nz, y0);
                List<AABB> boxes = new ArrayList<>();
                int column = 0;
                for (int cx = x0; cx < x0 + nx; cx++) {
                    for (int cz = z0; cz < z0 + nz; cz++) {
                        for (int cy = y0; cy <= y1; cy++) {
                            boolean whole = cy <= top ? (layout >> (column * width + cy - y0) & 1) != 0 : background;
                            if (whole) {
                                columns.set(cx, cy, cz);
                                boxes.add(UNIT.move(cx, cy, cz));
                            }
                        }
                        column++;
                    }
                }
                try {
                    check.run(columns, new Stepping.Pieces(boxes));
                } catch (AssertionError e) {
                    throw new AssertionError("方向 " + dx + "," + dz + " 摆法 " + Long.toBinaryString(layout)
                            + " 窗口外" + (background ? "整块" : "全空") + ":" + e.getMessage(), e);
                }
            });
        }
    }

    private static void sameWalk(Stepping.Walk expected, Stepping.Walk actual) {
        if (expected == null || actual == null) {
            assertEquals(expected, actual, "一边半路就够不着了,另一边没有");
            return;
        }
        assertArrayEquals(expected.points(), actual.points(), "取样点");
        assertEquals(0, Double.compare(expected.feet(), actual.feet()), "最后的脚高");
        assertEquals(0, Double.compare(expected.biggestStep(), actual.biggestStep()), "最大的坎");
        assertEquals(0, Double.compare(expected.peak(), actual.peak()), "最高的脚高");
        assertEquals(expected.peakAt(), actual.peakAt(), "最高点在第几个取样点");
    }
}
