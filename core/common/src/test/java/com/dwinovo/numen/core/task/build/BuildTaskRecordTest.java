package com.dwinovo.numen.core.task.build;

import com.dwinovo.numen.core.build.BuildSettings;
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
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
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
        assertFalse(target.acceptsPlacedState(Blocks.COBBLESTONE.defaultBlockState()));
    }

    @Test
    void blockSpecCarriesItsOwnStateAndRejectsNonsense() {
        // 与 /setblock 同一套语法,状态由原版解析器认;石头没有朝向就当场拒收
        assertEquals(Blocks.OAK_STAIRS.defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                        .setValue(BlockStateProperties.HALF, Half.TOP),
                com.dwinovo.numen.core.build.BuildPalette
                        .parse("oak_stairs[facing=east,half=top]").first().state());
        assertThrows(IllegalArgumentException.class, () -> com.dwinovo.numen.core.build.BuildPalette
                .parse("stone[facing=north]"));
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
        // 原生格没提朝向,朝向就不是工程量:游戏按玩家规则给什么朝向都算建好
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
     * 施工路上放下的非图纸块收场时都要拆掉,所以走向外圈的路线规格要说"要拆回":放一块的价钱连拆它的那一下一起算,
     * 定价只在寻路的成本模型一处(见模块的 {@code CostModelTest}),施工这边不自己加价。
     */
    @Test
    void theBuildRouteTakesBackWhatItLaysDown() {
        assertTrue(BuildCompanionTask.SPEC.takeBack(), "施工的路线规格要说明路上放下的块事后要拆");
        assertEquals(RouteSpec.Alter.NATURAL, BuildCompanionTask.SPEC.alter());
    }
}
