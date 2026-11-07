package com.dwinovo.numen.task.build;

import com.dwinovo.numen.build.BuildSettings;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("mc")
class BuildTaskRecordTest {

    private boolean savedBuildIgnoreDirection;

    @BeforeAll
    static void boot() {
        // 原版标签也要绑上:药水锅归一靠 #minecraft:cauldrons
        com.dwinovo.numen.pathing.Vanilla.boot();
    }

    @BeforeEach
    void setUp() {
        BuildSettings settings = BuildSettings.get();
        savedBuildIgnoreDirection = settings.buildIgnoreDirection;
        settings.buildIgnoreDirection = false;
        settings.buildIgnoreProperties().clear();
        settings.buildValidSubstitutes().clear();
    }

    @AfterEach
    void tearDown() {
        BuildSettings settings = BuildSettings.get();
        settings.buildIgnoreDirection = savedBuildIgnoreDirection;
        settings.buildIgnoreProperties().clear();
        settings.buildValidSubstitutes().clear();
    }

    @Test
    void targetMatchesBlockAndOptionalAxis() {
        BuildTaskRecord.Target target = new BuildTaskRecord.Target(
                Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y),
                Blocks.OAK_LOG.asItem(), BlockPos.ZERO, "oak_log");

        assertTrue(target.matches(Blocks.OAK_LOG.defaultBlockState()
                .setValue(BlockStateProperties.AXIS, Direction.Axis.Y)));
        assertFalse(target.matches(Blocks.OAK_LOG.defaultBlockState()
                .setValue(BlockStateProperties.AXIS, Direction.Axis.X)));
        assertFalse(target.matches(Blocks.SPRUCE_LOG.defaultBlockState()
                .setValue(BlockStateProperties.AXIS, Direction.Axis.Y)));
    }


    @Test
    void requestedHalfDoesNotAcceptDoubleSlab() {
        BuildTaskRecord.Target target = new BuildTaskRecord.Target(
                Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                        .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP),
                Blocks.SMOOTH_STONE_SLAB.asItem(), BlockPos.ZERO, "smooth_stone_slab");

        assertFalse(target.matches(Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE)));
    }

    @Test
    void targetMatchesEveryRequestedStateProperty() {
        BlockState desired = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.WATERLOGGED, false);
        BuildTaskRecord.Target target = new BuildTaskRecord.Target(desired,
                Blocks.OAK_STAIRS.asItem(), BlockPos.ZERO, "oak_stairs");

        assertTrue(target.matches(desired));
        // 朝向是作者定的姿态，逐项比对
        assertFalse(target.matches(
                desired.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)));
        // 含水不是：它由周围的水推出来，对账口径里属于世界自己算的那一类。
        // 楼梯放好后旁边淹了水就判“没建对”，只会拆了重放、再被淹，来回死转。
        assertTrue(target.matches(desired.setValue(BlockStateProperties.WATERLOGGED, true)),
                "waterlogged is derived, not authored — it must not fail the reconciliation");
    }

    @Test
    void buildValidityCanIgnoreConfiguredProperties() {
        BuildSettings.get().buildIgnoreProperties().add("waterlogged");
        BlockState desired = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, false);
        BuildTaskRecord.Target target = new BuildTaskRecord.Target(desired,
                Blocks.OAK_STAIRS.asItem(), BlockPos.ZERO, "oak_stairs");

        assertTrue(target.matches(desired.setValue(BlockStateProperties.WATERLOGGED, true)));
    }

    @Test
    void buildIgnoreDirectionIgnoresKnownOrientationProperties() {
        BuildSettings.get().buildIgnoreDirection = true;
        BlockState desired = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        BuildTaskRecord.Target target = new BuildTaskRecord.Target(desired,
                Blocks.OAK_STAIRS.asItem(), BlockPos.ZERO, "oak_stairs");

        assertTrue(target.matches(desired.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)));
    }

    @Test
    void buildIgnoreDirectionDoesNotIgnoreUnlistedProperties() {
        BuildSettings.get().buildIgnoreDirection = true;
        // 半砖的上下半是作者定的姿态，但不在“朝向”那一组里：buildIgnoreDirection
        // 放过的只有朝向，别的作者属性照比。（此前这里拿含水当反例，而含水本就是
        // 世界算出来的派生属性，对账口径里两边都不看。）
        BlockState desired = Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
        BuildTaskRecord.Target target = new BuildTaskRecord.Target(desired,
                Blocks.SMOOTH_STONE_SLAB.asItem(), BlockPos.ZERO, "smooth_stone_slab");

        assertFalse(target.matches(desired.setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP)));
    }

    @Test
    void buildValidityAcceptsConfiguredSubstitutesOnlyForExistingBlocks() {
        BuildSettings.get().buildValidSubstitutes().put(Blocks.STONE, List.of(Blocks.COBBLESTONE));
        BuildTaskRecord.Target target = new BuildTaskRecord.Target(Blocks.STONE,
                Blocks.STONE.asItem(), BlockPos.ZERO, "stone");

        assertTrue(target.matches(Blocks.COBBLESTONE.defaultBlockState()));
    }

    @Test
    void blockSpecCarriesItsOwnStateAndRejectsNonsense() {
        // 与 /setblock 同一套语法,状态由原版解析器认;石头没有朝向就当场拒收
        assertEquals(Blocks.OAK_STAIRS.defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                        .setValue(BlockStateProperties.HALF, Half.TOP),
                com.dwinovo.numen.build.BuildStates.resolve("oak_stairs[facing=east,half=top]").state());
        assertThrows(IllegalArgumentException.class, () -> com.dwinovo.numen.build.BuildStates
                .resolve("stone[facing=north]"));
    }

    @Test
    void itemPlaceLaneOnlyForPlaceableItems() {
        BuildTaskRecord.Target table = new BuildTaskRecord.Target(Blocks.CRAFTING_TABLE,
                Blocks.CRAFTING_TABLE.asItem(), BlockPos.ZERO, "crafting_table");
        assertTrue(table.asItemPlace().itemPlace());

        // 清空格与液体没有"拿在手里放"这回事,原样回落图纸车道
        BuildTaskRecord.Target air = new BuildTaskRecord.Target(Blocks.AIR,
                Blocks.AIR.asItem(), BlockPos.ZERO, "air");
        assertFalse(air.asItemPlace().itemPlace());
        BuildTaskRecord.Target water = new BuildTaskRecord.Target(
                Blocks.WATER.defaultBlockState(), Items.WATER_BUCKET,
                BlockPos.ZERO, "water");
        assertFalse(water.asItemPlace().itemPlace());
    }

    @Test
    void itemPlaceMatchesByBlockIdentityNotState() {
        // 素面格没提朝向,朝向就不是工程量:游戏按玩家规则给什么朝向都算建好
        BuildTaskRecord.Target chest = new BuildTaskRecord.Target(Blocks.CHEST,
                Blocks.CHEST.asItem(), BlockPos.ZERO, "chest").asItemPlace();
        assertTrue(chest.matches(Blocks.CHEST.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)));
        assertFalse(chest.matches(Blocks.TRAPPED_CHEST.defaultBlockState()), "别的方块不冒充");
        assertFalse(chest.matches(Blocks.AIR.defaultBlockState()), "空格就是还没放");

        // 提了朝向的格子是图纸语义,原判据一分不松
        BuildTaskRecord.Target drafted = new BuildTaskRecord.Target(
                Blocks.CHEST.defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH),
                Blocks.CHEST.asItem(), BlockPos.ZERO, "chest");
        assertFalse(drafted.matches(Blocks.CHEST.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)));
    }

    /**
     * block_id 的读法:air 与 dirt_path、farmland、tall_grass 读得通,液体读成液体方块(她只是不放);方块状态跟在方块名后面,
     * 和 /setblock 同一套语法,方块没有的属性按名字拒绝;没有自己物品的方块拿替代料算账(dirt_path 按土)。
     */
    @Test
    void blockIdsReadLikeSetblockAndTheMaterialIsWhatAHandWouldUse() {
        for (String id : new String[]{"air", "minecraft:air", "dirt_path", "farmland", "tall_grass"}) {
            com.dwinovo.numen.build.BuildStates.resolve(id);
        }
        for (String liquid : new String[]{"water", "minecraft:water", "lava"}) {
            var resolved = com.dwinovo.numen.build.BuildStates.resolve(liquid);
            assertTrue(resolved.state().getBlock() instanceof net.minecraft.world.level.block.LiquidBlock,
                    liquid + " reads as the liquid block; got " + resolved.state());
        }
        var stairs = com.dwinovo.numen.build.BuildStates.resolve("spruce_stairs[facing=south,half=top]").state();
        assertTrue(stairs.is(Blocks.SPRUCE_STAIRS)
                        && stairs.getValue(net.minecraft.world.level.block.StairBlock.FACING) == Direction.SOUTH
                        && stairs.getValue(net.minecraft.world.level.block.StairBlock.HALF) == Half.TOP,
                "the block state must come through the block_id, got " + stairs);
        String stateMsg = "";
        try {
            com.dwinovo.numen.build.BuildStates.resolve("stone[facing=north]");
        } catch (IllegalArgumentException e) {
            stateMsg = String.valueOf(e.getMessage());
        }
        assertTrue(stateMsg.contains("facing"),
                "a property the block does not have must be refused by name; got \"" + stateMsg + "\"");

        // 没有自己物品的方块要拿替代料算账,否则文档里教的 dirt_path 根本放不下去
        assertTrue(com.dwinovo.numen.build.BuildStates.resolve("dirt_path").item() == Items.DIRT,
                "dirt_path has no item of its own; it must be billed as dirt");
    }

    /** 一格几件料:双层砖是两块半砖摞出来的,雪按层,液体与清空不要料。 */
    @Test
    void aCellCostsWhatItTakesToBuildIt() {
        record Case(BlockState state, int want, String why) {}
        var slab = Blocks.STONE_BRICK_SLAB;
        for (Case c : List.of(
                new Case(slab.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE), 2,
                        "a double slab is two slabs"),
                new Case(slab.defaultBlockState(), 1, "a single slab is one"),
                new Case(Blocks.STONE.defaultBlockState(), 1, "a plain block is one"),
                new Case(Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, 5), 5,
                        "snow is billed per layer"),
                // 高草一格一件:tall_grass 自己就是物品。按"两株矮的"算两件会让玩家照清单备双份。
                new Case(Blocks.TALL_GRASS.defaultBlockState(), 1, "tall grass is one item of its own now"),
                new Case(Blocks.WATER.defaultBlockState(), 0, "liquids cost nothing"),
                new Case(Blocks.AIR.defaultBlockState(), 0, "clearing costs nothing"))) {
            var t = new BuildTaskRecord.Target(c.state(), Items.STONE, BlockPos.ZERO, "x");
            assertEquals(c.want(), t.materialCount(), c.why());
        }
    }

    /** 贴附件整体推到第二趟:骨架先立完,再回头挂灯摆花。 */
    @Test
    void whatStandsOnItsOwnIsBuiltBeforeWhatHangsOnSomething() {
        List<BuildTaskRecord.Target> mixed = new java.util.ArrayList<>(List.of(
                new BuildTaskRecord.Target(Blocks.TORCH.defaultBlockState(), Items.TORCH,
                        new BlockPos(1, 1, 0), "torch"),
                new BuildTaskRecord.Target(Blocks.RED_CARPET.defaultBlockState(), Items.RED_CARPET,
                        new BlockPos(2, 1, 0), "carpet"),
                new BuildTaskRecord.Target(Blocks.STONE, Items.STONE, new BlockPos(0, 9, 0), "stone")));
        mixed.sort(BuildOrder.BUILD_ORDER);
        assertTrue(mixed.get(0).desiredState().getBlock() == Blocks.STONE,
                "everything that stands on its own goes first, even nine layers up; got "
                        + mixed.stream().map(BuildTaskRecord.Target::label).toList());
    }

    /**
     * 图纸里的运行态不能照抄进世界:作物长到第几节、含不含水、堆肥桶攒了多少、锅里装的什么、蜂巢的蜜、藤蔓的果。照字面摆下去就会凭空长出
     * 熟麦子、凭空造出水、摆一口装着岩浆的锅。归一在每个目标格都要过的那道口(Target)上做一次。
     */
    @Test
    void runtimeStateIsNormalized() {
        BlockState ripe = Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7);
        BlockState wet = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        BlockState full = Blocks.COMPOSTER.defaultBlockState().setValue(BlockStateProperties.LEVEL_COMPOSTER, 5);

        var wheat = new BuildTaskRecord.Target(ripe, Items.WHEAT_SEEDS, BlockPos.ZERO, "wheat");
        assertTrue(wheat.desiredState().getValue(BlockStateProperties.AGE_7) == 0,
                "ripe wheat must be planted as a seedling, not conjured fully grown");

        var stair = new BuildTaskRecord.Target(wet, Items.OAK_STAIRS, BlockPos.ZERO, "stair");
        assertTrue(!stair.desiredState().getValue(BlockStateProperties.WATERLOGGED),
                "waterlogging is derived from the water around a block; copying it conjures water"
                        + " out of nothing, and she does not place water at all");

        var composter = new BuildTaskRecord.Target(full, Items.COMPOSTER, BlockPos.ZERO, "composter");
        assertTrue(composter.desiredState().getValue(BlockStateProperties.LEVEL_COMPOSTER) == 0,
                "how full a composter is, is runtime state — it must be placed empty");

        var cauldron = new BuildTaskRecord.Target(Blocks.LAVA_CAULDRON.defaultBlockState(),
                Items.CAULDRON, BlockPos.ZERO, "cauldron");
        assertTrue(cauldron.desiredState().is(Blocks.CAULDRON),
                "a cauldron's contents are runtime state; a cell must not hand out free lava");

        var leaves = new BuildTaskRecord.Target(Blocks.OAK_LEAVES.defaultBlockState(),
                Items.OAK_LEAVES, BlockPos.ZERO, "leaves");
        assertTrue(leaves.desiredState().getValue(BlockStateProperties.PERSISTENT),
                "placed leaves are hand-placed leaves; without this they rot as fast as she builds");

        BlockState honeyed = Blocks.BEE_NEST.defaultBlockState().setValue(BlockStateProperties.LEVEL_HONEY, 5);
        var nest = new BuildTaskRecord.Target(honeyed, Items.BEE_NEST, BlockPos.ZERO, "nest");
        assertTrue(nest.desiredState().getValue(BlockStateProperties.LEVEL_HONEY) == 0,
                "stored honey is runtime state; copying it lets the player shear free honeycomb");

        BlockState berried = Blocks.CAVE_VINES.defaultBlockState().setValue(BlockStateProperties.BERRIES, true);
        var vine = new BuildTaskRecord.Target(berried, Items.GLOW_BERRIES, BlockPos.ZERO, "vine");
        assertTrue(!vine.desiredState().getValue(BlockStateProperties.BERRIES),
                "a berried cave vine hands the berry straight back — the whole wall would be free");
    }

    /** 派发层与施工层共用同一个速率公式:时限必须比施工本身多;生存是人手的固定速度,一格五刻,工程再大也不压缩。 */
    @Test
    void theDeadlineCoversTheBuildAndSurvivalIsFiveTicksACell() {
        for (int cells : new int[]{50, 500, 1440, 5859, 16384}) {
            for (boolean survival : new boolean[]{true, false}) {
                long need = BuildOrder.estimatedTicks(cells, survival);
                long budget = BuildOrder.deadlineTicks(cells, survival);
                assertTrue(budget > need, "deadline must exceed the build itself: " + cells + " cells, survival="
                        + survival + ", needs " + need + " ticks but budget is " + budget);
            }
        }
        for (int cells : new int[]{1, 500, 16384}) {
            assertEquals(cells * 5L, BuildOrder.estimatedTicks(cells, true),
                    "survival builds " + cells + " cells at four a second");
        }
    }
}
