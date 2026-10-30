package com.dwinovo.numen.pathing.search;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Heading;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.Move;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.plan.Moves;
import com.dwinovo.numen.pathing.plan.Premise;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.pathing.Vanilla.SURVIVAL;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 估价处处一致,每一种走法逐一核对:任何一步,起点的估价都不高过这一步的真实代价加落点的估价,即这一步真实代价
 * 不低于估价的下降——疾跑底价的下降加上它跨过的升降的额外代价下界({@link Estimate}、{@link Vertical})。
 * 场景把 {@link Moves} 里每一类走法(平走、斜走、上一级、下一级、下落、跑酷、垫柱、向下挖、攀爬、游)都摆进来,
 * 台阶、半砖、楼梯、土径、水池也各摆一处,对一批高低不同的终点,在场景里每个站得住的节点上试每个方向。
 * 找到任何一步违反,就是这一项的下界多估了。
 */
class EstimateConsistencyTest {

    private static final int Y = 64;
    private static final BlockPos START = new BlockPos(0, Y, 0);
    private static BlockState STONE;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
        STONE = Blocks.STONE.defaultBlockState();
    }

    /**
     * 一处场景。
     *
     * @param goals 终点:估价读它的高度范围与水平位置,是不是站得住的格无所谓
     */
    private record Scene(String name, TestWorld world, CostModel model, List<Goal> goals) {}

    private static TestWorld field() {
        return new TestWorld().floor(-4, -8, 24, 8, Y - 1);
    }

    private static BlockState slab() {
        return Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
    }

    private static BlockState stair(Direction facing) {
        return Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, Half.BOTTOM);
    }

    /** 对每个高度各一个终点:同层、上面、下面,近的远的。 */
    private static List<Goal> goals(int... heights) {
        List<Goal> out = new ArrayList<>();
        for (int dy : heights) {
            out.add(Goals.at(new BlockPos(9, Y + dy, 2)));
            out.add(Goals.at(new BlockPos(1, Y + dy, -1)));
        }
        return out;
    }

    private static List<Scene> scenes() {
        List<Scene> scenes = new ArrayList<>();
        CostModel plain = Fixtures.model(RouteSpec.defaults());
        List<Goal> around = goals(0, 3, -3, 6);

        scenes.add(new Scene("平地与斜走", field(), plain, around));

        TestWorld blocks = field();
        for (int i = 1; i <= 5; i++) {
            blocks.fill(i, Y, -8, i, Y + i - 1, 8, STONE);
        }
        scenes.add(new Scene("整块台阶", blocks, plain, around));

        // 散落的矮块:孤立的一块,从旁边斜着也能跳上去
        TestWorld scattered = field();
        for (int x = 1; x <= 10; x++) {
            for (int z = -3; z <= 3; z++) {
                if ((x * 7 + z * 3) % 5 == 0) {
                    scattered.set(x, Y, z, STONE);
                }
            }
        }
        scenes.add(new Scene("散落的矮块", scattered, plain, around));

        TestWorld slabs = field();
        for (int i = 0; i < 8; i++) {
            int level = i / 2;
            slabs.fill(i + 1, Y, -8, i + 1, Y + level - 1, 8, STONE);
            slabs.fill(i + 1, Y + level, -8, i + 1, Y + level, 8, i % 2 == 0 ? slab() : STONE);
        }
        scenes.add(new Scene("半砖台阶", slabs, plain, around));

        TestWorld stairs = field();
        for (int i = 1; i <= 6; i++) {
            stairs.fill(i, Y, -8, i, Y + i - 2, 8, STONE);
            stairs.fill(i, Y + i - 1, -8, i, Y + i - 1, 8, stair(Direction.EAST));
        }
        scenes.add(new Scene("楼梯", stairs, plain, around));

        scenes.add(new Scene("三格高的台", field().fill(-1, Y, -8, 0, Y + 2, 8, STONE), plain, around));
        scenes.add(new Scene("一级台", field().fill(-1, Y, -8, 0, Y, 8, STONE), plain, around));
        TestWorld tall = field().fill(-1, Y, -8, 1, Y + 7, 8, STONE);
        scenes.add(new Scene("八格高的台(摔不起,下不去)", tall, plain, around));
        scenes.add(new Scene("八格高的台(愿受 7 点伤)", tall, Fixtures.model(RouteSpec.defaults().edit().maxFallDamage(7).build()), around));

        // 土径与耕地:顶面离格底不到半格的矮块,整块与它相邻时落差只有几分之一格
        TestWorld paths = field().fill(4, Y - 1, -8, 24, Y - 1, 8, Blocks.DIRT_PATH.defaultBlockState());
        scenes.add(new Scene("土径", paths, plain, around));

        CostModel leaping = Fixtures.model(RouteSpec.defaults().edit().parkour(true).build());
        scenes.add(new Scene("一格空隙", field().fill(3, Y - 1, -8, 3, Y - 1, 8, Blocks.AIR.defaultBlockState()), leaping, around));
        scenes.add(new Scene("三格空隙", field().fill(3, Y - 1, -8, 5, Y - 1, 8, Blocks.AIR.defaultBlockState()), leaping, around));

        TestWorld ladder = field().fill(1, Y, -1, 1, Y + 2, 1, STONE);
        for (int y = Y; y < Y + 3; y++) {
            ladder.set(0, y, 0, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST));
        }
        scenes.add(new Scene("梯子", ladder, plain, around));

        TestWorld pool = new TestWorld().floor(-6, -6, 12, 6, Y - 5)
                .fill(-6, Y - 4, -6, -5, Y - 1, 6, STONE).fill(5, Y - 4, -6, 6, Y - 1, 6, STONE)
                .fill(-4, Y - 4, -6, 4, Y - 1, 6, Blocks.WATER.defaultBlockState());
        scenes.add(new Scene("水池", pool, plain, goals(0, -4, -2, 3)));

        // 湖:一池很宽的水,对岸的终点。岸上、水面、水里、水底各站位;下水是下落或平走,水里是游或涉水。估价取直线疾跑价,游水不比它快,不能高估。
        BlockState water = Blocks.WATER.defaultBlockState();
        List<Goal> acrossLake = List.of(Goals.at(new BlockPos(14, Y, 0)), Goals.at(new BlockPos(14, Y, 2)), Goals.at(new BlockPos(7, Y - 2, 0)),
                Goals.at(new BlockPos(-2, Y, 1)));
        TestWorld lake = new TestWorld().floor(-6, -8, 20, 8, Y - 4).fill(-6, Y - 3, -8, 1, Y - 1, 8, STONE).fill(13, Y - 3, -8, 20, Y - 1, 8, STONE)
                .fill(2, Y - 3, -8, 12, Y - 1, 8, water);
        scenes.add(new Scene("湖", lake, plain, acrossLake));
        // 浅水:一格深,水底站得住也涉得过去
        TestWorld shallows = new TestWorld().floor(-6, -8, 20, 8, Y - 2).fill(2, Y - 1, -8, 12, Y - 1, 8, water);
        scenes.add(new Scene("浅水", shallows, plain, acrossLake));
        // 冰面:水面上盖着整块的冰,站得住,按陆地走
        TestWorld ice = new TestWorld().floor(-6, -8, 20, 8, Y - 4).fill(-6, Y - 3, -8, 1, Y - 1, 8, STONE).fill(13, Y - 3, -8, 20, Y - 1, 8, STONE)
                .fill(2, Y - 3, -8, 12, Y - 2, 8, water).fill(2, Y - 1, -8, 12, Y - 1, 8, Blocks.ICE.defaultBlockState());
        scenes.add(new Scene("冰面", ice, plain, acrossLake));
        // 睡莲:一条睡莲踩得过去的路(其余是水)
        TestWorld lilies = new TestWorld().floor(-6, -8, 20, 8, Y - 4).fill(-6, Y - 3, -8, 1, Y - 1, 8, STONE).fill(13, Y - 3, -8, 20, Y - 1, 8, STONE)
                .fill(2, Y - 3, -8, 12, Y - 2, 8, water).fill(2, Y - 1, 0, 12, Y - 1, 0, Blocks.LILY_PAD.defaultBlockState());
        scenes.add(new Scene("睡莲", lilies, plain, acrossLake));
        // 含水的半砖:水面上的这一格脚踩得上去
        TestWorld logged = new TestWorld().floor(-6, -8, 20, 8, Y - 4).fill(-6, Y - 3, -8, 1, Y - 1, 8, STONE).fill(13, Y - 3, -8, 20, Y - 1, 8, STONE)
                .fill(2, Y - 3, -8, 12, Y - 1, 8, water)
                .fill(2, Y - 1, 0, 12, Y - 1, 0, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP)
                        .setValue(SlabBlock.WATERLOGGED, true));
        scenes.add(new Scene("含水的半砖", logged, plain, acrossLake));
        // 桥:同一个湖,规格许放、身上有圆石;不许放就是上面的湖
        CostModel bridging = Fixtures.withCobble(RouteSpec.defaults().edit().place(true).consent(false).build());
        scenes.add(new Scene("湖(许放)", lake, bridging, acrossLake));
        // 目标在快照之外:只加载一圈区块,终点在更远处
        scenes.add(new Scene("目标在快照外", field().floor(25, -12, 60, 12, Y - 1).loadedWithin(1), plain,
                List.of(Goals.at(new BlockPos(60, Y, 0)), Goals.at(new BlockPos(48, Y + 3, 5)))));

        // 十格高的台旁边一池水:落进水里不摔,落多高都行
        TestWorld plunge = new TestWorld().floor(-6, -8, 12, 8, Y - 4).fill(-6, Y - 3, -8, 0, Y - 1, 8, STONE)
                .fill(1, Y - 3, -8, 8, Y - 1, 8, Blocks.WATER.defaultBlockState()).fill(-1, Y, -8, 0, Y + 9, 8, STONE);
        scenes.add(new Scene("高台落进水里", plunge, Fixtures.model(RouteSpec.defaults()), goals(-4, -2, 3, 9)));

        // 垫柱:许放不许挖,身上带着圆石
        CostModel pillaring = Fixtures.withCobble(RouteSpec.defaults().edit().place(true).consent(false).build());
        scenes.add(new Scene("垫柱", field(), pillaring, around));

        // 向下挖:许挖许放,终点只给高度(挖的额外代价由埋深另算,这里只核对升降)
        CostModel digging = Fixtures.withCobble(Fixtures.natural());
        TestWorld ground = new TestWorld().ground(Y - 1, STONE);
        scenes.add(new Scene("向下挖", ground, digging, List.of(Goals.level(Y - 3), Goals.level(Y), Goals.level(Y + 3),
                Goals.within(Goals.at(new BlockPos(0, Y - 6, 0)), 0, 2), Goals.within(Goals.at(new BlockPos(4, Y - 3, 1)), 0, 2),
                Goals.within(Goals.at(new BlockPos(1, Y + 3, 0)), 1, 3),
                Goals.touch(new net.minecraft.world.phys.AABB(0.2, Y - 7, 0.2, 0.8, Y - 5.2, 0.8), SURVIVAL, 3))));
        return scenes;
    }

    @Test
    void everyKindOfMoveCostsAtLeastTheDropInTheEstimate() {
        Set<MoveKind> seen = EnumSet.noneOf(MoveKind.class);
        for (Scene scene : scenes()) {
            for (Goal goal : scene.goals()) {
                Estimate estimate = Estimate.of(scene.world(), scene.model(), goal, START, Fixtures.BUDGET, TripMemory.Frozen.NONE, 1);
                for (int x = -3; x <= 12; x++) {
                    for (int y = Y - 5; y <= Y + 12; y++) {
                        for (int z = -3; z <= 3; z++) {
                            BlockPos from = new BlockPos(x, y, z);
                            Stance stance = Stance.at(scene.world(), SURVIVAL, from);
                            if (stance == null) {
                                continue;
                            }
                            for (Move move : Moves.ALL) {
                                for (Heading heading : move.headings()) {
                                    Premise premise = move.premise(scene.model(), scene.world(), from, stance, heading);
                                    if (!(premise instanceof Premise.Holds holds)) {
                                        continue;
                                    }
                                    Maneuver m = holds.maneuver();
                                    double cost = move.cost(scene.model(), m);
                                    double drop = estimate.at(from) - estimate.at(m.to());
                                    assertTrue(drop <= cost + 1e-9, scene.name() + ":" + m.kind() + " " + from + "→" + m.to() + " 去 " + goal
                                            + " 估价降了 " + drop + ",这一步真实代价只有 " + cost);
                                    seen.add(m.kind());
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(seen.containsAll(EnumSet.allOf(MoveKind.class)), "没试到的走法:" + EnumSet.complementOf(EnumSet.copyOf(seen)));
    }
}
