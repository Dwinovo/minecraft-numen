package com.dwinovo.numen.tools.shape;

import com.dwinovo.numen.NumenApiFixture;
import com.dwinovo.numen.build.BuildStates;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 方块状态跟着变换转:水平 90° 倍数与水平镜像以原版 {@code BlockState.rotate}、{@code mirror} 的结果为准(逐个方块比),原版不覆盖的
 * (竖直翻转、绕 X 与 Z 转、不是 90° 倍数)照 WorldEdit 的规则,期望值按它的规则手算;带 NBT 的写法不丢、没变的写法原样。
 */
class BlockTurnsTest {

    @BeforeAll
    static void install() {
        NumenApiFixture.install();
    }

    // 线性变换,作用在 (x, y, z) 上。+x 东,+z 南:东转到南是从上看顺时针
    private static final double[][] CLOCKWISE_90 = {{0, 0, -1}, {0, 1, 0}, {1, 0, 0}};
    private static final double[][] CLOCKWISE_180 = {{-1, 0, 0}, {0, 1, 0}, {0, 0, -1}};
    private static final double[][] COUNTERCLOCKWISE_90 = {{0, 0, 1}, {0, 1, 0}, {-1, 0, 0}};
    private static final double[][] FLIP_EAST_WEST = {{-1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    private static final double[][] FLIP_NORTH_SOUTH = {{1, 0, 0}, {0, 1, 0}, {0, 0, -1}};
    private static final double[][] FLIP_UP_DOWN = {{1, 0, 0}, {0, -1, 0}, {0, 0, 1}};
    private static final double[][] ABOUT_X_90 = {{1, 0, 0}, {0, 0, 1}, {0, -1, 0}};
    private static final double[][] ABOUT_X_180 = {{1, 0, 0}, {0, -1, 0}, {0, 0, -1}};
    private static final double[][] YAW_45 = {{Math.sqrt(0.5), 0, -Math.sqrt(0.5)}, {0, 1, 0}, {Math.sqrt(0.5), 0, Math.sqrt(0.5)}};

    private static final List<String> BLOCKS = List.of(
            "oak_stairs[facing=north,half=top,shape=straight]", "oak_stairs[facing=east,half=bottom,shape=inner_left]",
            "oak_stairs[facing=south,half=bottom,shape=outer_right]", "oak_stairs[facing=west,half=top,shape=inner_right]",
            "rail[shape=north_south]", "rail[shape=south_east]", "rail[shape=north_west]", "rail[shape=ascending_east]",
            "powered_rail[shape=ascending_south,powered=true]", "detector_rail[shape=east_west]",
            "oak_door[facing=east,half=lower,hinge=left,open=true]", "oak_door[facing=north,half=upper,hinge=right,open=false]",
            "crafter[orientation=north_up]", "crafter[orientation=up_east]", "crafter[orientation=west_up]",
            "oak_sign[rotation=0]", "oak_sign[rotation=3]", "oak_sign[rotation=13]", "oak_wall_sign[facing=south]",
            "oak_log[axis=x]", "oak_log[axis=y]", "oak_log[axis=z]", "oak_fence[north=true,east=true,south=false,west=false]",
            "glass_pane[north=false,east=true,south=true,west=false]", "vine[north=true,west=true,up=true]",
            "redstone_wire[north=side,east=up,south=none,west=none]", "stone_brick_wall[north=tall,east=low,south=none,west=none]",
            "lever[face=wall,facing=south,powered=false]", "chest[facing=west,type=left]", "red_bed[facing=east,part=head]",
            "furnace[facing=south,lit=true]", "piston[facing=up,extended=false]", "observer[facing=east]",
            "oak_trapdoor[facing=north,half=top,open=false]", "wall_torch[facing=east]", "barrel[facing=down]",
            "oak_slab[type=top]", "white_banner[rotation=7]", "skeleton_skull[rotation=2]", "stone");

    private static BlockState state(String written) {
        return BuildStates.parse(written, true).blockState();
    }

    private static BlockState turned(String written, double[][] m) {
        return state(BlockTurns.turn(List.of(written), m).get(0));
    }

    @Test
    void quarterTurnsAboutTheVerticalAxisAreVanillasRotate() {
        for (String written : BLOCKS) {
            assertEquals(state(written).rotate(Rotation.CLOCKWISE_90), turned(written, CLOCKWISE_90), written + " clockwise 90");
            assertEquals(state(written).rotate(Rotation.CLOCKWISE_180), turned(written, CLOCKWISE_180), written + " 180");
            assertEquals(state(written).rotate(Rotation.COUNTERCLOCKWISE_90), turned(written, COUNTERCLOCKWISE_90), written + " counter-clockwise 90");
        }
        // clockwise seen from above: north turns to east
        assertEquals("oak_stairs[facing=east]", BlockTurns.turn(List.of("oak_stairs[facing=north]"), CLOCKWISE_90).get(0));
    }

    @Test
    void sidewaysMirrorsAreVanillasMirror() {
        for (String written : BLOCKS) {
            assertEquals(state(written).mirror(Mirror.FRONT_BACK), turned(written, FLIP_EAST_WEST), written + " east-west");
            assertEquals(state(written).mirror(Mirror.LEFT_RIGHT), turned(written, FLIP_NORTH_SOUTH), written + " north-south");
        }
        assertEquals(Direction.WEST, Mirror.FRONT_BACK.mirror(Direction.EAST));
        assertEquals(Direction.SOUTH, Mirror.LEFT_RIGHT.mirror(Direction.NORTH));
    }

    /** 原版与 WorldEdit 都管的四分之一转,两套规则给同一个答案:把同一个转动写成不是恰好 1 的竖直分量,让它走 WorldEdit 的那一路。 */
    @Test
    void worldEditsRulesAgreeWithVanillaOnTheQuarterTurnsBothCover() {
        double[][] nearly = {{0, 0, -1}, {0, 1.0000001, 0}, {1, 0, 0}};
        for (String written : BLOCKS) {
            assertEquals(state(written).rotate(Rotation.CLOCKWISE_90), turned(written, nearly), written);
        }
    }

    @Test
    void anUpsideDownFlipSwapsHalvesTypesAndSlopes() {
        assertEquals("oak_stairs[facing=east,half=top]", BlockTurns.turn(List.of("oak_stairs[facing=east,half=bottom]"), FLIP_UP_DOWN).get(0));
        assertEquals("oak_stairs[half=top]", BlockTurns.turn(List.of("oak_stairs"), FLIP_UP_DOWN).get(0));
        assertEquals("oak_slab[type=top]", BlockTurns.turn(List.of("oak_slab[type=bottom]"), FLIP_UP_DOWN).get(0));
        assertEquals("oak_slab[type=double]", BlockTurns.turn(List.of("oak_slab[type=double]"), FLIP_UP_DOWN).get(0));
        assertEquals("oak_trapdoor[half=bottom]", BlockTurns.turn(List.of("oak_trapdoor[half=top]"), FLIP_UP_DOWN).get(0));
        assertEquals("piston[facing=down]", BlockTurns.turn(List.of("piston[facing=up]"), FLIP_UP_DOWN).get(0));
        assertEquals("piston[facing=up]", BlockTurns.turn(List.of("piston[facing=down]"), FLIP_UP_DOWN).get(0));
        assertEquals("piston[facing=east]", BlockTurns.turn(List.of("piston[facing=east]"), FLIP_UP_DOWN).get(0));
        assertEquals("rail[shape=ascending_west]", BlockTurns.turn(List.of("rail[shape=ascending_east]"), FLIP_UP_DOWN).get(0));
        assertEquals("powered_rail[shape=ascending_north]", BlockTurns.turn(List.of("powered_rail[shape=ascending_south]"), FLIP_UP_DOWN).get(0));
        assertEquals("rail[shape=north_south]", BlockTurns.turn(List.of("rail[shape=north_south]"), FLIP_UP_DOWN).get(0));
        // a log standing up stays standing up
        assertEquals("oak_log[axis=y]", BlockTurns.turn(List.of("oak_log[axis=y]"), FLIP_UP_DOWN).get(0));
        // a crafter's orientation: facing up becomes facing down, its top is never down
        assertEquals("crafter[orientation=down_east]", BlockTurns.turn(List.of("crafter[orientation=up_east]"), FLIP_UP_DOWN).get(0));
    }

    @Test
    void aTurnAboutTheXAxisTurnsUpToNorthAndHalfTurnsFlipHandedness() {
        // about x, up goes to north and north goes down
        assertEquals("piston[facing=north]", BlockTurns.turn(List.of("piston[facing=up]"), ABOUT_X_90).get(0));
        assertEquals("piston[facing=down]", BlockTurns.turn(List.of("piston[facing=north]"), ABOUT_X_90).get(0));
        assertEquals("piston[facing=east]", BlockTurns.turn(List.of("piston[facing=east]"), ABOUT_X_90).get(0));
        assertEquals("oak_log[axis=z]", BlockTurns.turn(List.of("oak_log[axis=y]"), ABOUT_X_90).get(0));
        assertEquals("oak_log[axis=y]", BlockTurns.turn(List.of("oak_log[axis=z]"), ABOUT_X_90).get(0));
        assertEquals("oak_log[axis=x]", BlockTurns.turn(List.of("oak_log[axis=x]"), ABOUT_X_90).get(0));
        // half a turn about x: upside down and back to front, so a chest's sides swap, a stair's corners too
        assertEquals("oak_stairs[facing=south,half=top,shape=inner_right]",
                BlockTurns.turn(List.of("oak_stairs[facing=north,half=bottom,shape=inner_left]"), ABOUT_X_180).get(0));
        assertEquals("chest[type=right,facing=south]", BlockTurns.turn(List.of("chest[type=left]"), ABOUT_X_180).get(0));
        assertEquals("oak_slab[type=bottom]", BlockTurns.turn(List.of("oak_slab[type=top]"), ABOUT_X_180).get(0));
        // a block that only lies along x or z has no way to stand up: its axis stays
        assertEquals("nether_portal[axis=z]", BlockTurns.turn(List.of("nether_portal[axis=z]"), ABOUT_X_90).get(0));
    }

    @Test
    void anAngleOfOtherThanAQuarterPicksTheNearestSignRotation() {
        // 45 degrees clockwise is two sixteenths; the 16-step rotation of signs, banners and heads goes by two
        assertEquals("oak_sign[rotation=2]", BlockTurns.turn(List.of("oak_sign[rotation=0]"), YAW_45).get(0));
        assertEquals("oak_sign[rotation=15]", BlockTurns.turn(List.of("oak_sign[rotation=13]"), YAW_45).get(0));
        assertEquals("oak_sign[rotation=6]", BlockTurns.turn(List.of("oak_sign[rotation=4]"), YAW_45).get(0));
    }

    @Test
    void theSidesOfAFenceFollowATurnAboutX() {
        // about x by 180: north and south swap, east and west stay (a fence's up and down are not properties)
        assertEquals("oak_fence[north=false,east=true,south=true]",
                BlockTurns.turn(List.of("oak_fence[north=true,east=true]"), ABOUT_X_180).get(0));
    }

    @Test
    void whatTheTurnLeavesIsReturnedAsWritten() {
        assertEquals("stone", BlockTurns.turn(List.of("stone"), CLOCKWISE_90).get(0));
        assertEquals("  oak_log[axis=y] ", BlockTurns.turn(List.of("  oak_log[axis=y] "), CLOCKWISE_90).get(0));
        // a block turned keeps its name as written and the order of the properties it wrote
        assertEquals("minecraft:oak_stairs[half=top,facing=east]",
                BlockTurns.turn(List.of("minecraft:oak_stairs[half=top,facing=north]"), CLOCKWISE_90).get(0));
        // a property it did not write comes along when the turn changes it
        assertEquals("oak_stairs[facing=east]", BlockTurns.turn(List.of("oak_stairs"), CLOCKWISE_90).get(0));
    }

    @Test
    void aBlockWithNbtKeepsItsNbtThroughATurn() {
        String chest = "chest[facing=north]{Items:[{Slot:0b,id:\"minecraft:stone\",count:3}]}";
        assertEquals("chest[facing=east]{Items:[{Slot:0b,id:\"minecraft:stone\",count:3}]}",
                BlockTurns.turn(List.of(chest), CLOCKWISE_90).get(0));
        assertEquals("chest{Items:[]}", BlockTurns.turn(List.of("chest{Items:[]}"), FLIP_UP_DOWN).get(0));
        assertEquals("chest[facing=east]{Items:[]}", BlockTurns.turn(List.of("chest{Items:[]}"), CLOCKWISE_90).get(0));
    }
}
