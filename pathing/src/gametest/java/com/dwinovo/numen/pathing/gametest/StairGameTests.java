package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Scenes.slab;
import static com.dwinovo.numen.pathing.gametest.Scenes.stair;
import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Semantics;

import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** 楼梯、半砖与矮方块:走上走下、背面侧面、转角、上半楼梯当地面、螺旋楼梯、半砖台阶、各种矮方块上走与站。 */
@GameTestHolder("numen")
@PrefixGameTestTemplate(false)
public class StairGameTests {

    private static final String BATCH = "pathing_stairs";

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    /** 一道朝东、三格宽的楼梯:从下面的空地 {@code (x0-1)} 走上去,顶上是平台。返回平台顶上的脚高。 */
    private static int staircase(Trial t, int x0, int z0, int z1, int steps) {
        for (int i = 0; i < steps; i++) {
            if (i > 0) {
                t.fill(x0 + i, 1, z0, x0 + i, i, z1, Blocks.STONE);
            }
            t.fill(x0 + i, 1 + i, z0, x0 + i, 1 + i, z1, stair(Direction.EAST));
        }
        t.fill(x0 + steps, 1, z0, x0 + steps + 3, steps, z1, Blocks.STONE);
        return steps + 1;
    }

    /** 从正面走上楼梯:一路不起跳。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void walks_up_stairs_from_the_front(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        int top = staircase(t, 6, 4, 6, 4);
        TestBody body = t.body(2, 1, 5);
        t.go(body, Goals.at(t.at(11, top, 5)), RouteSpec.defaults()).arrives().then(Scenes::noJump);
    }

    /** 从平台上顺着楼梯走下来:一路不起跳。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void walks_down_stairs(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        int top = staircase(t, 6, 4, 6, 4);
        TestBody body = t.body(11, top, 5);
        t.go(body, Goals.at(t.at(2, 1, 5)), RouteSpec.defaults()).arrives().then(Scenes::noJump);
    }

    /** 一级朝东的楼梯,从它背后(东边)来:跳上去,或绕到正面走上去,站在它高的那半上。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void gets_onto_a_stair_from_its_back(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.set(8, 1, 5, stair(Direction.EAST));
        TestBody body = t.body(12, 1, 5);
        t.go(body, Goals.at(t.at(8, 2, 5)), RouteSpec.defaults()).arrives();
    }

    /** 一级朝东的楼梯,从它侧面(南边)来。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void gets_onto_a_stair_from_its_side(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.set(8, 1, 5, stair(Direction.EAST));
        TestBody body = t.body(8, 1, 9);
        t.go(body, Goals.at(t.at(8, 2, 5)), RouteSpec.defaults()).arrives();
    }

    /** 转角楼梯:两段楼梯在拐角处由原版拼成内角、外角的形状,沿着拐上去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void climbs_corner_stairs(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        // 朝北的一段:z 从 9 往 6 升;拐角处朝东接上平台
        t.set(6, 1, 9, stair(Direction.NORTH));
        t.fill(6, 1, 8, 6, 1, 8, Blocks.STONE);
        t.set(6, 2, 8, stair(Direction.NORTH));
        t.set(7, 2, 8, stair(Direction.EAST));
        t.fill(7, 1, 8, 7, 1, 8, Blocks.STONE);
        t.fill(6, 1, 5, 9, 2, 7, Blocks.STONE);
        t.fill(8, 1, 8, 9, 2, 8, Blocks.STONE);
        TestBody body = t.body(6, 1, 13);
        t.go(body, Goals.at(t.at(8, 3, 6)), RouteSpec.defaults()).arrives();
    }

    /** 一排倒扣的(上半)楼梯当地面走过去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void walks_on_upside_down_stairs(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.set(4, 1, 5, Blocks.STONE);
        t.fill(5, 1, 5, 10, 1, 5, stair(Direction.EAST, Half.TOP));
        t.set(11, 1, 5, Blocks.STONE);
        TestBody body = t.body(4, 2, 5);
        t.go(body, Goals.at(t.at(11, 2, 5)), RouteSpec.defaults()).arrives().then(Scenes::noJump);
    }

    /** 绕着一根柱子的螺旋楼梯连上十二级(悬空的楼梯,一圈八级):一路走上去,不停在原地来回认步,四百刻内到顶。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void climbs_a_spiral_staircase(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        int cx = 12;
        int cz = 12;
        int[][] ring = {{-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}};
        int steps = 12;
        t.fill(cx, 1, cz, cx, steps + 4, cz, Blocks.STONE);
        for (int i = 0; i < steps; i++) {
            // 每一级朝着走进它的方向,拐角那一级也从正面踏上去
            int[] here = ring[i % ring.length];
            int[] prev = ring[(i + ring.length - 1) % ring.length];
            Direction facing = Direction.getNearest(here[0] - prev[0], 0, here[1] - prev[1]);
            t.set(cx + here[0], 1 + i, cz + here[1], stair(facing));
        }
        int[] last = ring[steps % ring.length];
        t.set(cx + last[0], steps, cz + last[1], Blocks.STONE);
        TestBody body = t.body(cx - 1, 1, cz + 4);
        t.go(body, Goals.at(t.at(cx + last[0], steps + 1, cz + last[1])), RouteSpec.defaults())
                .within(400).arrives().then(Scenes::noJump);
    }

    /** 楼梯走到顶,顶上那一格是一扇关着的木门:开门出去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void opens_a_door_at_the_top_of_stairs(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        int top = staircase(t, 6, 5, 5, 3);
        // 平台边上立一堵墙,中间是门
        t.fill(9, top, 3, 9, top + 2, 7, Blocks.STONE);
        t.fill(9, 1, 3, 12, top - 1, 7, Blocks.STONE);
        Scenes.door(t, 9, top, 5, Blocks.OAK_DOOR, Direction.EAST, false);
        TestBody body = t.body(2, 1, 5);
        t.go(body, Goals.at(t.at(11, top, 5)), RouteSpec.defaults()).arrives();
    }

    // ==================== 半砖与矮方块 ====================

    /** 下半砖与整块交替,半格半格走上去,一路不起跳。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void walks_up_half_steps_of_slabs(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(6, 1, 4, 6, 1, 6, slab(SlabType.BOTTOM));
        t.fill(7, 1, 4, 7, 1, 6, Blocks.STONE);
        t.fill(8, 1, 4, 8, 1, 6, Blocks.STONE);
        t.fill(8, 2, 4, 8, 2, 6, slab(SlabType.BOTTOM));
        t.fill(9, 1, 4, 11, 2, 6, Blocks.STONE);
        TestBody body = t.body(2, 1, 5);
        t.go(body, Goals.at(t.at(10, 3, 5)), RouteSpec.defaults()).arrives().then(Scenes::noJump);
    }

    /** 上半砖、双层半砖垒成的台子与整块一样:要跳上去,站在顶上。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void treats_top_and_double_slabs_as_full_blocks(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(7, 1, 4, 9, 1, 6, slab(SlabType.TOP));
        t.fill(10, 1, 4, 12, 1, 6, slab(SlabType.DOUBLE));
        TestBody body = t.body(3, 1, 5);
        t.go(body, Goals.at(t.at(11, 2, 5)), RouteSpec.defaults()).arrives();
    }

    /** 半砖接半砖:下半、上半、下半、上半,一级半格,一路走上去不起跳。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void walks_slab_after_slab(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(6, 1, 4, 6, 1, 6, slab(SlabType.BOTTOM));
        t.fill(7, 1, 4, 7, 1, 6, slab(SlabType.TOP));
        t.fill(8, 1, 4, 8, 1, 6, Blocks.STONE);
        t.fill(8, 2, 4, 8, 2, 6, slab(SlabType.BOTTOM));
        t.fill(9, 1, 4, 9, 1, 6, Blocks.STONE);
        t.fill(9, 2, 4, 9, 2, 6, slab(SlabType.TOP));
        t.fill(10, 1, 4, 12, 2, 6, Blocks.STONE);
        TestBody body = t.body(2, 1, 5);
        t.go(body, Goals.at(t.at(11, 3, 5)), RouteSpec.defaults()).arrives().then(Scenes::noJump);
    }

    /** 灵魂沙、耕地、土径上走过去,再从土径上跳上一格。规格放开耕地(走上去不踩坏,只是从高处落上去才坏)。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void walks_and_jumps_on_soul_sand_farmland_and_paths(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(5, 0, 4, 7, 0, 6, Blocks.SOUL_SAND);
        t.fill(8, 0, 4, 10, 0, 6, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE));
        t.fill(11, 0, 4, 13, 0, 6, Blocks.DIRT_PATH);
        t.fill(14, 1, 4, 16, 1, 6, Blocks.STONE);
        TestBody body = t.body(2, 1, 5);
        RouteSpec spec = RouteSpec.defaults().edit().allow(Semantics.Kind.FRAGILE).build();
        t.go(body, Goals.at(t.at(15, 2, 5)), spec).arrives();
    }

    /** 一层到八层的雪一格一格排着:一路走上去,再走回地面。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void walks_over_snow_layers_one_to_eight(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        for (int layers = 1; layers <= 8; layers++) {
            t.fill(4 + layers, 1, 4, 4 + layers, 1, 6,
                    Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers));
        }
        TestBody body = t.body(2, 1, 5);
        t.go(body, Goals.at(t.at(16, 1, 5)), RouteSpec.defaults()).arrives();
    }

    /** 一排地毯上走过去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void walks_over_carpets(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(5, 1, 4, 12, 1, 6, Blocks.WHITE_CARPET);
        TestBody body = t.body(2, 1, 5);
        t.go(body, Goals.at(t.at(10, 1, 5)), RouteSpec.defaults()).arrives().then(Scenes::noJump);
    }

    /** 箱子、附魔台这类不满一格的方块排成一道:走上去、走过去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void walks_over_chests_and_enchanting_tables(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(5, 1, 3, 7, 1, 7, Blocks.CHEST);
        t.fill(8, 1, 3, 10, 1, 7, Blocks.ENCHANTING_TABLE);
        TestBody body = t.body(2, 1, 5);
        t.go(body, Goals.at(t.at(13, 1, 5)), RouteSpec.defaults()).arrives();
    }

    /** 站到床、附魔台、炼药锅、灯笼、栅栏顶、四到七层的雪上:六具身体各去一处。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void stands_on_beds_tables_cauldrons_lanterns_fences_and_snow(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.set(4, 1, 4, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.FOOT)
                .setValue(BedBlock.FACING, Direction.NORTH));
        t.set(4, 1, 3, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD)
                .setValue(BedBlock.FACING, Direction.NORTH));
        t.set(10, 1, 4, Blocks.ENCHANTING_TABLE);
        t.set(16, 1, 4, Blocks.CAULDRON);
        t.set(22, 1, 4, Blocks.LANTERN);
        t.set(28, 1, 4, Blocks.OAK_FENCE);
        // 栅栏一格半高,从地上跳不上去:旁边垫一块,从它顶上走上栅栏顶
        t.set(27, 1, 4, Blocks.STONE);
        t.set(34, 1, 4, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 4));
        t.set(34, 1, 8, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 7));
        t.go(t.body(4, 1, 9), Goals.at(t.at(4, 1, 4)), RouteSpec.defaults()).arrives();
        t.go(t.body(10, 1, 9), Goals.at(t.at(10, 1, 4)), RouteSpec.defaults()).arrives();
        t.go(t.body(16, 1, 9), Goals.at(t.at(16, 1, 4)), RouteSpec.defaults()).arrives();
        t.go(t.body(22, 1, 9), Goals.at(t.at(22, 1, 4)), RouteSpec.defaults()).arrives();
        t.go(t.body(28, 1, 9), Goals.at(t.at(28, 2, 4)), RouteSpec.defaults()).arrives();
        t.go(t.body(34, 1, 1), Goals.at(t.at(34, 1, 4)), RouteSpec.defaults()).arrives();
        t.go(t.body(34, 1, 11), Goals.at(t.at(34, 1, 8)), RouteSpec.defaults()).arrives();
    }
}
