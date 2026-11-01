package com.dwinovo.numen.pathing.plan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Footing;
import com.dwinovo.numen.pathing.world.Recall;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 叠了改动的世界上用搜索的记事本({@link Recall}):改动落在一个结论读的格子({@link Recall.Reach})之外,就照用记事本里快照上的结论,
 * 之内才现算。这里在一批随机的世界与随机的改动上证明两条路的结论一模一样:
 * <ul>
 *   <li>每种按格的结论(站姿、落脚高度)——记事本先在没叠改动的快照上记满,再在叠了改动的视图上问,对着不带记事本的同一份视图现算的;</li>
 *   <li>每种走法每个方向的前提——整个 {@link Moves#forBody} 的走法集,挖放都许,对着不带记事本的同一份视图判的。</li>
 * </ul>
 * 一种结论说少了它读的格子,就会在这里出现不一样。
 */
class RecallOverlayTest {

    private static final int Y = 64;
    /** 世界与改动都落在 x、z 各 -4 到 4 之内。 */
    private static final int RADIUS = 4;

    private static BodyStats body;
    private static BlockState[] palette;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
        body = Vanilla.SURVIVAL;
        palette = new BlockState[] {
                Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(),
                Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(),
                Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
                Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM),
                Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST),
                Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH),
                Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.CACTUS.defaultBlockState(),
                Blocks.FARMLAND.defaultBlockState(), Blocks.OAK_FENCE.defaultBlockState(), Blocks.SOUL_SAND.defaultBlockState(),
                Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN, true),
                Blocks.OAK_DOOR.defaultBlockState(), Blocks.SNOW.defaultBlockState(), Blocks.COBWEB.defaultBlockState(),
        };
    }

    /** 带着这次搜索的记事本、没叠改动的世界:搜索里的快照那一层。 */
    private static final class Cached implements WorldView, Recall.Source {
        private final TestWorld world;
        private final Recall recall = new Recall(body);

        Cached(TestWorld world) {
            this.world = world;
        }

        @Override
        public Recall recall() {
            return recall;
        }

        @Override
        public boolean changed(int x, int yMin, int yMax, int z) {
            return false;
        }

        @Override
        public WorldBorder border() {
            return world.border();
        }

        @Override
        public boolean ultraWarm() {
            return false;
        }

        @Override
        public boolean airSection(int x, int y, int z) {
            return world.airSection(x, y, z);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return world.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return world.getFluidState(pos);
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public int getHeight() {
            return world.getHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return world.getMinBuildHeight();
        }
    }

    /** 同一份世界,不带记事本:每个结论现算。 */
    private record Plain(WorldView under) implements WorldView {
        @Override
        public WorldBorder border() {
            return under.border();
        }

        @Override
        public boolean ultraWarm() {
            return under.ultraWarm();
        }

        @Override
        public boolean airSection(int x, int y, int z) {
            return under.airSection(x, y, z);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return under.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return under.getFluidState(pos);
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return under.getBlockEntity(pos);
        }

        @Override
        public int getHeight() {
            return under.getHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return under.getMinBuildHeight();
        }
    }

    /** 表从小起步、顶掉的次数多了才翻倍:问过的格比起手的槽数多得多,答出来的仍是现算的数。 */
    @Test
    void aTableThatOutgrowsItsFirstSlotsStillAnswersWhatTheMeasureSays() {
        Cached world = new Cached(new TestWorld());
        int[] measured = {0};
        Recall.HeightFact fact = new Recall.HeightFact((level, stats, x, y, z) -> {
            measured[0]++;
            return x * 31.0 + y * 7.0 + z;
        }, new Recall.Reach(0, 0));
        for (int round = 0; round < 3; round++) {
            for (int x = 0; x < 64; x++) {
                for (int z = 0; z < 64; z++) {
                    assertEquals(x * 31.0 + Y * 7.0 + z, fact.at(world, body, x, Y, z));
                }
            }
        }
        assertTrue(measured[0] < 3 * 64 * 64, "问过的格有一部分取回了记下的结论,不是次次现算:" + measured[0]);
    }

    private static TestWorld randomWorld(Random random) {
        TestWorld world = new TestWorld().floor(-RADIUS - 2, -RADIUS - 2, RADIUS + 2, RADIUS + 2, Y - 1);
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                for (int y = Y - 1; y <= Y + 4; y++) {
                    if (random.nextInt(3) == 0) {
                        world.set(x, y, z, palette[random.nextInt(palette.length)]);
                    }
                }
            }
        }
        return world;
    }

    /** 一件改动:种类(挖开、放石头、放梯子、倒水)与哪一格。 */
    private record Change(int kind, BlockPos pos) {

        void applyTo(EditedView view) {
            switch (kind) {
                case 0 -> view.dig(pos);
                case 1 -> view.place(pos, Blocks.STONE);
                case 2 -> view.place(pos, Blocks.LADDER);
                default -> view.pour(pos);
            }
        }
    }

    /** 随机改几格:挖开,或放下一块石头、一道梯子、一格水。 */
    private static List<Change> randomChanges(Random random) {
        List<Change> changes = new ArrayList<>();
        int count = 1 + random.nextInt(4);
        for (int i = 0; i < count; i++) {
            changes.add(new Change(random.nextInt(4), new BlockPos(random.nextInt(2 * RADIUS - 1) - RADIUS + 1,
                    Y - 1 + random.nextInt(5), random.nextInt(2 * RADIUS - 1) - RADIUS + 1)));
        }
        return changes;
    }

    /** 叠上这些改动的视图。 */
    private static EditedView edited(WorldView base, List<Change> changes) {
        EditedView view = new EditedView(base);
        changes.forEach(change -> change.applyTo(view));
        return view;
    }

    @Test
    void perCellFactsOnAnEditedViewAreWhatTheyAreWithoutTheNotebook() {
        Random random = new Random(20261012L);
        int compared = 0;
        for (int round = 0; round < 60; round++) {
            Cached base = new Cached(randomWorld(random));
            // 记事本先在没叠改动的快照上记满:改动之外的结论就是从这里取
            for (int x = -RADIUS; x <= RADIUS; x++) {
                for (int z = -RADIUS; z <= RADIUS; z++) {
                    for (int y = Y - 2; y <= Y + 5; y++) {
                        Stance.at(base, body, x, y, z);
                        Footing.height(base, body, x, y, z);
                    }
                }
            }
            for (int edit = 0; edit < 3; edit++) {
                List<Change> changes = randomChanges(random);
                // 对照的那份叠同样的改动,底下的世界不带记事本:每个结论现算
                EditedView view = edited(base, changes);
                EditedView fresh = edited(new Plain(base), changes);
                for (int x = -RADIUS; x <= RADIUS; x++) {
                    for (int z = -RADIUS; z <= RADIUS; z++) {
                        for (int y = Y - 2; y <= Y + 5; y++) {
                            String at = "round " + round + " edit " + edit + " 格 " + x + "," + y + "," + z;
                            assertEquals(Stance.at(fresh, body, x, y, z), Stance.at(view, body, x, y, z), "站姿 " + at);
                            assertEquals(Double.doubleToLongBits(Footing.height(fresh, body, x, y, z)),
                                    Double.doubleToLongBits(Footing.height(view, body, x, y, z)), "落脚高度 " + at);
                            compared++;
                        }
                    }
                }
            }
        }
        assertTrue(compared > 100_000, "比对了 " + compared + " 格");
    }

    @Test
    void everyMoveAndHeadingOnAnEditedViewJudgesTheSameWithAndWithoutTheNotebook() {
        Random random = new Random(1012L);
        List<CostModel> models = List.of(Fixtures.withCobble(Fixtures.natural()), Fixtures.model(com.dwinovo.numen.pathing.spec.RouteSpec.defaults()));
        int compared = 0;
        int holding = 0;
        for (int round = 0; round < 40; round++) {
            Cached base = new Cached(randomWorld(random));
            for (int x = -RADIUS; x <= RADIUS; x++) {
                for (int z = -RADIUS; z <= RADIUS; z++) {
                    for (int y = Y - 2; y <= Y + 5; y++) {
                        Stance.at(base, body, x, y, z);
                    }
                }
            }
            for (int edit = 0; edit < 2; edit++) {
                List<Change> changes = randomChanges(random);
                EditedView view = edited(base, changes);
                EditedView fresh = edited(new Plain(base), changes);
                for (CostModel model : models) {
                    for (int x = -2; x <= 2; x++) {
                        for (int z = -2; z <= 2; z++) {
                            for (int y = Y - 1; y <= Y + 3; y++) {
                                BlockPos from = new BlockPos(x, y, z);
                                Stance stance = Stance.at(fresh, body, from);
                                if (stance == null) {
                                    continue;
                                }
                                for (Move move : Moves.forBody(model.body())) {
                                    for (Heading heading : move.headings()) {
                                        String expected = describe(move.premise(model, fresh, from, stance, heading));
                                        String actual = describe(move.premise(model, view, from, stance, heading));
                                        assertEquals(expected, actual, "round " + round + " edit " + edit + " " + move.kind() + " 从 " + from
                                                + " 朝 " + heading + " 改动 " + changes);
                                        compared++;
                                        if (expected.startsWith("holds")) {
                                            holding++;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(compared > 50_000 && holding > 5_000, "比对了 " + compared + " 个前提,其中成立 " + holding + " 个");
    }

    private static String describe(Premise premise) {
        return switch (premise) {
            case Premise.Fails f -> "fails " + f.cell() + " " + f.reason() + " " + f.detail();
            case Premise.Holds h -> {
                Maneuver m = h.maneuver();
                List<String> parts = new ArrayList<>();
                parts.add(m.kind() + " " + m.heading() + " " + m.from() + "->" + m.to());
                parts.add(m.start() + " " + m.landing());
                parts.add(m.jump() + " " + m.runUp() + " " + m.sneak() + " " + m.wading() + " " + m.submerged());
                parts.add(m.speedFactor() + " " + m.drop() + " " + m.fallDamage() + " " + m.span());
                parts.add(m.edits().toString());
                parts.add(Arrays.toString(m.cells()) + " " + m.exposure() + " " + m.support() + " " + m.reversible());
                yield "holds " + String.join(" | ", parts);
            }
        };
    }
}
