package com.dwinovo.numen.core.build;

import com.dwinovo.numen.core.CoreCommandsFixture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code building_design} 技能里"方块朝哪"的每一句,对着原版方块自己的形状核一遍(1.21.1 的方块类,不凭记忆)。技能里曾写
 * "南坡的楼梯朝南",和前半句"朝屋顶升高的方向"自相矛盾,她为此在思考里兜了几页——朝向的说法从此有测试守着。
 * 要世界才判得了的(梯子与灯笼挂不挂得住、床头落在哪)在 GameTest 里验。坐标:北是 -z,南是 +z,东是 +x。
 */
class BlockFacingTest {

    @BeforeAll
    static void install() {
        CoreCommandsFixture.install();
    }

    /** 这一格里 (x, y, z) 那一点是不是实心(方块自己的形状,0..1)。 */
    private static boolean solid(BlockState state, double x, double y, double z) {
        return state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs().stream()
                .anyMatch(box -> box.contains(x, y, z));
    }

    private static BlockState parse(String written) {
        return BuildPalette.parse(written).first().state();
    }

    /**
     * 楼梯的高背在 {@code facing} 那一侧,朝着走上去的方向。屋顶的一面坡朝屋脊升高:南坡(往南落下去的那一面)高处在北,
     * 所以南坡的楼梯朝北。
     */
    @Test
    void stairsFaceTheWayYouWalkUpSoASouthSlopeFacesNorth() {
        BlockState southSlope = parse("oak_stairs[facing=north]");
        assertTrue(solid(southSlope, 0.5, 0.25, 0.5), "下半整块");
        assertTrue(solid(southSlope, 0.5, 0.75, 0.25), "上半在北:高处朝北");
        assertFalse(solid(southSlope, 0.5, 0.75, 0.75), "南边低,从南边踩上去");

        BlockState northSlope = parse("oak_stairs[facing=south]");
        assertTrue(solid(northSlope, 0.5, 0.75, 0.75), "北坡的楼梯朝南,高处在南");
        assertFalse(solid(northSlope, 0.5, 0.75, 0.25));

        BlockState upsideDown = parse("oak_stairs[facing=north,half=top]");
        assertEquals(Half.TOP, upsideDown.getValue(BlockStateProperties.HALF));
        assertTrue(solid(upsideDown, 0.5, 0.75, 0.75) && solid(upsideDown, 0.5, 0.75, 0.25), "倒过来:上半整块");
        assertFalse(solid(upsideDown, 0.5, 0.25, 0.75), "下半只剩北边那一截");
    }

    /** 关着的门板贴在格子里与 {@code facing} 相反的那一边:南墙上的门朝北,门板和墙外皮齐平。 */
    @Test
    void aClosedDoorLiesAgainstTheEdgeOppositeItsFacing() {
        BlockState door = parse("oak_door[facing=north]");
        assertEquals(DoubleBlockHalf.LOWER, door.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF), "写的是下半");
        assertTrue(solid(door, 0.5, 0.5, 0.95), "门板在南边");
        assertFalse(solid(door, 0.5, 0.5, 0.05));
        assertTrue(solid(parse("oak_door[facing=south]"), 0.5, 0.5, 0.05), "朝南的门板在北边,南墙的里皮");
    }

    /** 打开的活板门是整格高的一块竖板,贴在与 {@code facing} 相反的那一边;{@code half} 不改它。关着的是顶上或底下一块薄板。 */
    @Test
    void anOpenTrapdoorIsAFullHeightPanelOppositeItsFacingWhateverItsHalf() {
        BlockState open = parse("oak_trapdoor[open=true,facing=south]");
        assertTrue(solid(open, 0.5, 0.05, 0.05) && solid(open, 0.5, 0.95, 0.05), "北边一整块竖板,贴着北墙");
        assertFalse(solid(open, 0.5, 0.5, 0.95));
        assertEquals(open.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs(),
                parse("oak_trapdoor[open=true,facing=south,half=top]")
                        .getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs(),
                "开着的时候 half=top 与 half=bottom 是同一块板");
        assertTrue(solid(parse("oak_trapdoor[open=true,facing=north]"), 0.5, 0.5, 0.95), "朝北的竖在南边");

        BlockState shelf = parse("oak_trapdoor[half=top,open=false]");
        assertTrue(solid(shelf, 0.5, 0.9, 0.5) && !solid(shelf, 0.5, 0.1, 0.5), "关着、half=top:顶上一块,挂在梁下的搁板");
        BlockState ledge = parse("oak_trapdoor[half=bottom,open=false]");
        assertTrue(solid(ledge, 0.5, 0.1, 0.5) && !solid(ledge, 0.5, 0.9, 0.5), "关着、half=bottom:底下一块,矮台");
    }

    /** 梯子贴在与 {@code facing} 相反的那一边,挂在那一边的方块上(挂不挂得住在 GameTest 里验)。 */
    @Test
    void aLadderLiesAgainstTheEdgeOppositeItsFacing() {
        BlockState ladder = parse("ladder[facing=north]");
        assertTrue(solid(ladder, 0.5, 0.5, 0.95));
        assertFalse(solid(ladder, 0.5, 0.5, 0.05));
    }

    /** 原木的 {@code axis} 是它顺着的方向:东西向的梁转 90° 成南北向,转的是同一根木头的走向。 */
    @Test
    void aLogsAxisIsTheWayItRuns() {
        BlockState eastWest = parse("oak_log[axis=x]");
        assertEquals(Direction.Axis.Z, eastWest.rotate(Rotation.CLOCKWISE_90).getValue(BlockStateProperties.AXIS));
        assertEquals(Direction.Axis.Y, parse("oak_log[axis=y]").rotate(Rotation.CLOCKWISE_90)
                .getValue(BlockStateProperties.AXIS), "立柱转了还是立柱");
    }
}
