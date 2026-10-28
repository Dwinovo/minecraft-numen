package com.dwinovo.numen.pathing.plan;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.spec.BlockBans;
import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Semantics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 走法的快判断({@link Move#quick})与精确的前提({@link Move#premise})在一批随机地形上逐节点逐走法逐方向核对:
 * 快判断答"不成立"的,精确判断一定不成立;答"成立"的,精确判断一定成立,而且落点、站姿、代价、刻数一模一样(代价按位比);
 * 这两种都不能读到没加载的列(搜索据此把读到没加载的列的步子记成"未加载",快判断断定的步子不能少记)。答"可能"的,精确判断成立时
 * 代价不比快判断给的低(惰性评估的乐观估价),落点不是猜的就一模一样。三种答复都数一数,免得有一种从来没出现过、核对成了空话。
 *
 * <p>地形是平地上有凸起、凹坑、墙和各色各样的方块(半砖、楼梯、水、岩浆、梯子、门、蜘蛛网、蜂蜜……),规格与身体也换着来:
 * 不改地形的、许挖的、带着圆石许挖许放的、许跑酷的、有按位置禁令与加价的、排除会塌的方块的、禁止站上某种方块的、有生物危险的;
 * 生存、创造、血量低的身体。叠了改动的节点只能答"不成立"或"可能"。
 */
class QuickTest {

    private static final int Y = 64;
    private static final int RADIUS = 6;

    private static BlockState[] oddities;
    private static BlockState[] solids;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
        solids = new BlockState[] {
                Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
                Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.SAND.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(),
                Blocks.OAK_LEAVES.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(), Blocks.GLASS.defaultBlockState(),
                Blocks.HAY_BLOCK.defaultBlockState(), Blocks.SLIME_BLOCK.defaultBlockState(), Blocks.ICE.defaultBlockState(),
        };
        oddities = new BlockState[] {
                Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM),
                Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP),
                Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST),
                Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH),
                Blocks.WATER.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState(),
                Blocks.CACTUS.defaultBlockState(), Blocks.FARMLAND.defaultBlockState(), Blocks.OAK_FENCE.defaultBlockState(),
                Blocks.SOUL_SAND.defaultBlockState(), Blocks.HONEY_BLOCK.defaultBlockState(),
                Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN, true),
                Blocks.OAK_DOOR.defaultBlockState(), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3),
                Blocks.COBWEB.defaultBlockState(), Blocks.MAGMA_BLOCK.defaultBlockState(), Blocks.CAMPFIRE.defaultBlockState(),
                Blocks.SWEET_BERRY_BUSH.defaultBlockState(), Blocks.POWDER_SNOW.defaultBlockState(), Blocks.SCAFFOLDING.defaultBlockState(),
                Blocks.WHITE_CARPET.defaultBlockState(), Blocks.OAK_PRESSURE_PLATE.defaultBlockState(), Blocks.TRIPWIRE.defaultBlockState(),
                Blocks.SHORT_GRASS.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState(), Blocks.DANDELION.defaultBlockState(),
                Blocks.TORCH.defaultBlockState(), Blocks.LILY_PAD.defaultBlockState(), Blocks.OAK_FENCE_GATE.defaultBlockState(),
                Blocks.COBBLESTONE_WALL.defaultBlockState(), Blocks.GLASS_PANE.defaultBlockState(), Blocks.CAULDRON.defaultBlockState(),
                Blocks.BAMBOO.defaultBlockState(), Blocks.POINTED_DRIPSTONE.defaultBlockState(), Blocks.BUBBLE_COLUMN.defaultBlockState(),
                Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(),
        };
    }

    // ==================== 世界 ====================

    /**
     * 以 {@code (ox, oz)} 为中心的一块地形:地面在 {@code Y - 1}(往下无边),每列大多平,一些列抬高成墙、挖成坑,
     * 再在四周撒各色各样的方块。
     */
    private static TestWorld terrain(Random random, int ox, int oz, boolean odd) {
        TestWorld world = new TestWorld().ground(Y - 1, Blocks.STONE.defaultBlockState());
        for (int x = ox - RADIUS; x <= ox + RADIUS; x++) {
            for (int z = oz - RADIUS; z <= oz + RADIUS; z++) {
                int roll = random.nextInt(100);
                BlockState block = solids[random.nextInt(solids.length)];
                if (roll < 14) {
                    int height = 1 + random.nextInt(3);
                    for (int y = Y; y < Y + height; y++) {
                        world.set(x, y, z, block);
                    }
                } else if (roll < 24) {
                    int depth = 1 + random.nextInt(7);
                    for (int y = Y - 1; y > Y - 1 - depth; y--) {
                        world.set(x, y, z, Blocks.AIR.defaultBlockState());
                    }
                    if (random.nextInt(3) == 0) {
                        world.set(x, Y - 1 - depth, z, oddities[random.nextInt(oddities.length)]);
                    }
                } else if (roll < 28) {
                    world.set(x, Y - 1, z, block);
                }
            }
        }
        if (odd) {
            for (int x = ox - RADIUS; x <= ox + RADIUS; x++) {
                for (int z = oz - RADIUS; z <= oz + RADIUS; z++) {
                    for (int y = Y - 4; y <= Y + 4; y++) {
                        if (random.nextInt(100) < 3) {
                            world.set(x, y, z, oddities[random.nextInt(oddities.length)]);
                        }
                    }
                }
            }
        }
        return world;
    }

    /** 读世界、同时记下有没有读到没加载的列(搜索据此把这一步记成"未加载")。 */
    private static final class Recording implements WorldView {
        private final TestWorld world;
        boolean touched;

        Recording(TestWorld world) {
            this.world = world;
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
            if (!world.isLoaded(x, z)) {
                touched = true;
            }
            return world.airSection(x, y, z);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            if (!world.isLoaded(pos.getX(), pos.getZ())) {
                touched = true;
            }
            return world.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
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

    // ==================== 规格与身体 ====================

    private record Setup(String name, CostModel model) {}

    private static List<Setup> setups(Random random, int ox, int oz) {
        List<Setup> out = new ArrayList<>();
        RouteSpec none = RouteSpec.defaults();
        RouteSpec natural = Fixtures.natural();
        out.add(new Setup("不改地形", Fixtures.model(none)));
        out.add(new Setup("许挖没有料", Fixtures.model(natural)));
        out.add(new Setup("许挖许放带圆石", Fixtures.withCobble(natural)));
        out.add(new Setup("跑酷", Fixtures.model(none.edit().parkour(true).build())));
        out.add(new Setup("排除会塌的、禁止站上石头", Fixtures.model(none.edit().exclude(Semantics.Kind.FALLING)
                .bans(new BlockBans(Set.of(), Set.of(), Set.of(Blocks.STONE))).build())));
        PositionCosts.Builder positions = PositionCosts.builder();
        for (int i = 0; i < 40; i++) {
            long cell = BlockPos.asLong(ox + random.nextInt(2 * RADIUS) - RADIUS, Y - 2 + random.nextInt(6), oz + random.nextInt(2 * RADIUS) - RADIUS);
            switch (random.nextInt(4)) {
                case 0 -> positions.forbid(Use.PASS, cell);
                case 1 -> positions.forbid(Use.STAND, cell);
                case 2 -> positions.add(Use.PASS, cell, 1 + random.nextInt(5) / 2.0);
                default -> positions.add(Use.STAND, cell, 0.5 + random.nextInt(4));
            }
        }
        out.add(new Setup("按位置的禁令与加价", Fixtures.model(none.edit().positions(positions.build()).build())));
        out.add(new Setup("生物危险", CostModel.of(none, Fixtures.body(), Fixtures.ALLOW_ALL, Fixtures.NO_MATERIALS,
                () -> List.of(new Threat(ox + 0.5, Y + 0.5, oz + 0.5, 3.0)))));
        out.add(new Setup("创造模式", CostModel.of(none, Fixtures.body(Vanilla.CREATIVE, GameType.CREATIVE, 20, List.of()),
                Fixtures.ALLOW_ALL, Fixtures.NO_MATERIALS, com.dwinovo.numen.pathing.plan.Threats.NONE)));
        out.add(new Setup("血量低", CostModel.of(none, Fixtures.body(7), Fixtures.ALLOW_ALL, Fixtures.NO_MATERIALS,
                com.dwinovo.numen.pathing.plan.Threats.NONE)));
        out.add(new Setup("带水桶", CostModel.of(natural, Fixtures.carrying(0, new ItemStack(net.minecraft.world.item.Items.WATER_BUCKET)),
                Fixtures.ALLOW_ALL, Fixtures.NO_MATERIALS, com.dwinovo.numen.pathing.plan.Threats.NONE)));
        out.add(new Setup("饿了不能疾跑", CostModel.of(none, new BodySnapshot(Vanilla.SURVIVAL, GameType.SURVIVAL, 20, 3, 1, 3, 0,
                List.of(), Fixtures.MINING, Fixtures.BREATH), Fixtures.ALLOW_ALL, Fixtures.NO_MATERIALS,
                com.dwinovo.numen.pathing.plan.Threats.NONE)));
        return out;
    }

    // ==================== 核对 ====================

    private static final class Tally {
        final Map<MoveKind, int[]> counts = new EnumMap<>(MoveKind.class);
        int compared;
        /** 答"可能"、精确判断成立的:乐观代价核对过的步数。 */
        int likelyHeld;
        /** 快判断答成立、精确判断说要起跳的斜走:侧面有一格高的坎。 */
        int jumpingDiagonals;

        void add(MoveKind kind, int verdict) {
            counts.computeIfAbsent(kind, k -> new int[3])[verdict]++;
        }

        int count(MoveKind kind, int verdict) {
            return counts.getOrDefault(kind, new int[3])[verdict];
        }
    }

    private static void check(CostModel model, Quick quick, BlockPos from, Stance stance, WorldView here, List<Edit> edits,
                              Recording recording, Tally tally, String where) {
        quick.begin(from.getX(), from.getY(), from.getZ(), stance, here, edits);
        for (Move move : Moves.ALL) {
            for (Heading heading : move.headings()) {
                String at = where + " " + move.kind() + " 从 " + from + " 朝 " + heading + (edits.isEmpty() ? "" : " (叠了改动 " + edits + ")");
                int verdict = move.quick(quick, heading);
                tally.add(move.kind(), verdict);
                if (verdict == Quick.LIKELY) {
                    BlockPos predicted = new BlockPos(quick.toX(), quick.toY(), quick.toZ());
                    double optimistic = quick.cost();
                    boolean guessed = quick.guessed();
                    if (move.premise(model, here, from, stance, heading) instanceof Premise.Holds holds) {
                        Maneuver m = holds.maneuver();
                        double exact = move.cost(model, m);
                        assertTrue(optimistic <= exact + 1e-9, "答可能的代价比精确的高 " + at + ":快 " + optimistic + " 精确 " + exact);
                        if (!guessed) {
                            assertEquals(m.to(), predicted, "答可能的落点 " + at);
                        }
                        tally.likelyHeld++;
                    }
                    continue;
                }
                recording.touched = false;
                Premise premise = move.premise(model, here, from, stance, heading);
                assertFalse(recording.touched, "快判断开了口,精确判断却读到了没加载的列 " + at);
                tally.compared++;
                if (verdict == Quick.REJECT) {
                    assertTrue(premise instanceof Premise.Fails, "快判断说不成立,精确判断成立 " + at + ":" + premise);
                    continue;
                }
                assertTrue(premise instanceof Premise.Holds, "快判断说成立,精确判断不成立 " + at + ":" + premise);
                Maneuver m = ((Premise.Holds) premise).maneuver();
                assertEquals(m.to(), new BlockPos(quick.toX(), quick.toY(), quick.toZ()), "落点 " + at);
                assertEquals(m.landing(), quick.ground(quick.toY()), "落点站姿 " + at);
                assertTrue(m.edits().isEmpty(), "改动 " + at);
                if (move.kind() == MoveKind.DIAGONAL && m.jump()) {
                    tally.jumpingDiagonals++;
                }
                assertFalse(m.submerged(), "憋气 " + at);
                assertEquals(Double.doubleToLongBits(move.cost(model, m)), Double.doubleToLongBits(quick.cost()),
                        "代价 " + at + " 精确 " + move.cost(model, m) + " 快 " + quick.cost());
                assertEquals(Double.doubleToLongBits(move.ticks(model, m)), Double.doubleToLongBits(quick.ticks()),
                        "刻数 " + at + " 精确 " + move.ticks(model, m) + " 快 " + quick.ticks());
            }
        }
    }

    @Test
    void quickAnswersAgreeWithTheExactPremiseOnRandomTerrain() {
        Tally tally = new Tally();
        Random random = new Random(20261012L);
        for (int round = 0; round < 24; round++) {
            // 一半的轮次把加载范围卡在区块边上:节点靠近没加载的列时快判断不能开口去断
            boolean edge = round % 2 == 1;
            int ox = edge ? 13 : 0;
            int oz = edge ? 14 : 0;
            TestWorld world = terrain(random, ox, oz, round % 3 != 0);
            if (edge) {
                world.loadedWithin(0);
            }
            Recording recording = new Recording(world);
            for (Setup setup : setups(random, ox, oz)) {
                CostModel model = setup.model();
                Quick quick = new Quick(model, world);
                BodyStats body = model.body().stats();
                for (int x = ox - 3; x <= ox + 3; x++) {
                    for (int z = oz - 3; z <= oz + 3; z++) {
                        if (!world.isLoaded(x, z)) {
                            continue;
                        }
                        for (int y = Y - 2; y <= Y + 4; y++) {
                            BlockPos from = new BlockPos(x, y, z);
                            Stance stance = Stance.at(world, body, from);
                            if (stance == null) {
                                continue;
                            }
                            check(model, quick, from, stance, recording, List.of(), recording, tally,
                                    "round " + round + " " + setup.name());
                        }
                    }
                }
            }
        }
        for (MoveKind kind : List.of(MoveKind.WALK, MoveKind.DIAGONAL, MoveKind.ASCEND, MoveKind.DESCEND, MoveKind.FALL)) {
            assertTrue(tally.count(kind, Quick.HOLDS) > 200, kind + " 答成立的太少:" + tally.count(kind, Quick.HOLDS));
            assertTrue(tally.count(kind, Quick.REJECT) > 200, kind + " 答不成立的太少:" + tally.count(kind, Quick.REJECT));
            assertTrue(tally.count(kind, Quick.LIKELY) > 20, kind + " 答可能的太少:" + tally.count(kind, Quick.LIKELY));
        }
        for (MoveKind kind : List.of(MoveKind.PILLAR, MoveKind.DOWNWARD, MoveKind.CLIMB, MoveKind.SWIM, MoveKind.PARKOUR)) {
            assertTrue(tally.count(kind, Quick.REJECT) > 200, kind + " 答不成立的太少:" + tally.count(kind, Quick.REJECT));
        }
        assertTrue(tally.compared > 100_000, "比对了 " + tally.compared + " 步");
        assertTrue(tally.likelyHeld > 10_000, "答可能又成立的只有 " + tally.likelyHeld + " 步");
        assertTrue(tally.jumpingDiagonals > 50, "起跳的斜走只有 " + tally.jumpingDiagonals + " 步");
    }

    /** 随机几件改动:挖开、垫石头、开关门、倒水接坠落(世界照旧)。 */
    private static List<Edit> randomEdits(Random random, TestWorld world) {
        List<Edit> out = new ArrayList<>();
        Set<BlockPos> used = new java.util.HashSet<>();
        for (int i = 0; i < 1 + random.nextInt(4); i++) {
            BlockPos pos = new BlockPos(random.nextInt(7) - 3, Y - 2 + random.nextInt(6), random.nextInt(7) - 3);
            if (!used.add(pos)) {
                continue;
            }
            BlockState there = world.getBlockState(pos);
            switch (random.nextInt(4)) {
                case 0 -> out.add(new Edit.Dig(pos, there, Permit.ALLOW, false, true));
                case 1 -> out.add(new Edit.Place(pos, there, Blocks.COBBLESTONE, Permit.ALLOW));
                case 2 -> {
                    if (Semantics.openableByHand(there)) {
                        out.add(new Edit.Door(pos, there));
                    } else {
                        out.add(new Edit.Dig(pos, there, Permit.ALLOW, false, true));
                    }
                }
                default -> out.add(new Edit.Catch(pos, there, Permit.ALLOW));
            }
        }
        return out;
    }

    @Test
    void anEditedNodeJudgesTheSameAsTheExactPremiseOnTheEditedWorld() {
        Tally tally = new Tally();
        Random random = new Random(1012L);
        for (int round = 0; round < 24; round++) {
            TestWorld world = terrain(random, 0, 0, round % 2 == 0);
            Recording recording = new Recording(world);
            for (Setup setup : setups(random, 0, 0)) {
                CostModel model = setup.model();
                Quick quick = new Quick(model, world);
                BodyStats body = model.body().stats();
                for (int pick = 0; pick < 60; pick++) {
                    List<Edit> edits = randomEdits(random, world);
                    // 节点面对的世界:快照加上这些改动
                    WorldView here = EditedView.after(recording, edits);
                    BlockPos from = new BlockPos(random.nextInt(7) - 3, Y - 2 + random.nextInt(6), random.nextInt(7) - 3);
                    Stance stance = Stance.at(here, body, from);
                    if (stance == null) {
                        continue;
                    }
                    check(model, quick, from, stance, here, edits, recording, tally, "round " + round + " " + setup.name());
                }
            }
        }
        for (MoveKind kind : List.of(MoveKind.WALK, MoveKind.DIAGONAL, MoveKind.ASCEND, MoveKind.DESCEND, MoveKind.FALL)) {
            assertTrue(tally.count(kind, Quick.HOLDS) > 50, kind + " 在叠了改动的节点上答成立的太少:" + tally.count(kind, Quick.HOLDS));
            assertTrue(tally.count(kind, Quick.REJECT) > 50, kind + " 在叠了改动的节点上答不成立的太少:" + tally.count(kind, Quick.REJECT));
        }
        assertTrue(tally.compared > 10_000, "比对了 " + tally.compared + " 步");
        assertTrue(tally.likelyHeld > 1_000, "答可能又成立的只有 " + tally.likelyHeld + " 步");
    }

    @Test
    void anOddBodyStaysWithTheExactPremise() {
        // 不是原版玩家的身体:矮一点、窄一点、跳得高一点的,快判断要么照样与精确判断一致,要么答"可能"且不高估
        Random random = new Random(7L);
        EntityDimensions slim = EntityDimensions.scalable(0.4F, 1.5F).withEyeHeight(1.3F);
        BodyStats[] bodies = {
                new BodyStats(slim, 0.6, 0.42F, 0.08, 0.1, 0.3, 4.5, false, false),
                new BodyStats(EntityDimensions.scalable(0.6F, 1.8F).withEyeHeight(1.62F), 1.0, 0.42F, 0.08, 0.1, 0.3, 4.5, false, false),
                new BodyStats(EntityDimensions.scalable(0.6F, 1.8F).withEyeHeight(1.62F), 0.6, 0.6F, 0.08, 0.1, 0.3, 4.5, false, false),
                new BodyStats(EntityDimensions.scalable(0.6F, 2.5F).withEyeHeight(2.2F), 0.6, 0.42F, 0.08, 0.1, 0.3, 4.5, false, false),
                new BodyStats(EntityDimensions.scalable(0.6F, 1.8F).withEyeHeight(1.62F), 0.6, 0.42F, 0.08, 0.1, 0.3, 4.5, true, false),
                new BodyStats(EntityDimensions.scalable(0.6F, 1.8F).withEyeHeight(1.62F), 0.6, 0.42F, 0.08, 0.1, 0.3, 4.5, false, true),
        };
        Tally tally = new Tally();
        for (BodyStats stats : bodies) {
            for (int round = 0; round < 6; round++) {
                TestWorld world = terrain(random, 0, 0, round % 2 == 0);
                CostModel model = CostModel.of(Fixtures.natural(), Fixtures.body(stats, GameType.SURVIVAL, 20, List.of()),
                        Fixtures.ALLOW_ALL, Fixtures.COBBLE, com.dwinovo.numen.pathing.plan.Threats.NONE);
                Recording recording = new Recording(world);
                Quick quick = new Quick(model, world);
                for (int x = -3; x <= 3; x++) {
                    for (int z = -3; z <= 3; z++) {
                        for (int y = Y - 2; y <= Y + 4; y++) {
                            BlockPos from = new BlockPos(x, y, z);
                            Stance stance = Stance.at(world, stats, from);
                            if (stance != null) {
                                check(model, quick, from, stance, recording, List.of(), recording, tally, "body " + stats.standing());
                            }
                        }
                    }
                }
            }
        }
        assertTrue(tally.compared > 5_000, "比对了 " + tally.compared + " 步");
    }
}
