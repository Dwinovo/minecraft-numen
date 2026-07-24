package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.pathing.moves.ActionCosts;
import com.dwinovo.numen.core.pathing.moves.ChunkLoadedTest;
import com.dwinovo.numen.core.pathing.settings.NavSettings;

import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("mc")
class BuildTaskRecordTest {

    private static boolean booted;
    private static ServerPlayer player;

    private boolean savedConsiderPotions;
    private boolean savedAllowWaterBucketFall;
    private boolean savedAllowBreak;
    private boolean savedAllowPlace;
    private boolean savedBuildIgnoreDirection;
    private boolean savedBreakFromAbove;
    private boolean savedGoalBreakFromAbove;
    private boolean savedSkipFailedLayers;

    @BeforeAll
    static void boot() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            player = allocatePlayer();
            booted = true;
        } catch (Throwable t) {
            booted = false;
        }
    }

    @BeforeEach
    void setUp() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过建造成本钉桩");
        NavSettings settings = NavSettings.get();
        savedConsiderPotions = settings.considerPotionEffects;
        savedAllowWaterBucketFall = settings.allowWaterBucketFall;
        savedAllowBreak = settings.allowBreak;
        savedAllowPlace = settings.allowPlace;
        savedBuildIgnoreDirection = settings.buildIgnoreDirection;
        savedBreakFromAbove = settings.breakFromAbove;
        savedGoalBreakFromAbove = settings.goalBreakFromAbove;
        savedSkipFailedLayers = settings.skipFailedLayers;
        settings.considerPotionEffects = false;
        settings.allowWaterBucketFall = false;
        settings.allowBreak = true;
        settings.allowPlace = true;
        settings.buildIgnoreDirection = false;
        settings.breakFromAbove = false;
        settings.goalBreakFromAbove = false;
        settings.skipFailedLayers = false;
        settings.buildIgnoreProperties().clear();
        settings.buildValidSubstitutes().clear();
    }

    @AfterEach
    void tearDown() {
        if (!booted) {
            return;
        }
        NavSettings settings = NavSettings.get();
        settings.considerPotionEffects = savedConsiderPotions;
        settings.allowWaterBucketFall = savedAllowWaterBucketFall;
        settings.allowBreak = savedAllowBreak;
        settings.allowPlace = savedAllowPlace;
        settings.buildIgnoreDirection = savedBuildIgnoreDirection;
        settings.breakFromAbove = savedBreakFromAbove;
        settings.goalBreakFromAbove = savedGoalBreakFromAbove;
        settings.skipFailedLayers = savedSkipFailedLayers;
        settings.buildIgnoreProperties().clear();
        settings.buildValidSubstitutes().clear();
    }
    private static ServerPlayer allocatePlayer() throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        ServerPlayer p = (ServerPlayer) unsafe.allocateInstance(ServerPlayer.class);
        Field inventory = Player.class.getDeclaredField("inventory");
        inventory.setAccessible(true);
        // 1.21.5: 盔甲/副手迁入 EntityEquipment,Inventory 与实体共享同一实例
        var equipment = new net.minecraft.world.entity.EntityEquipment();
        Field equipmentField = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("equipment");
        equipmentField.setAccessible(true);
        equipmentField.set(p, equipment);
        inventory.set(p, new Inventory(p, equipment));
        Field foodData = Player.class.getDeclaredField("foodData");
        foodData.setAccessible(true);
        foodData.set(p, new FoodData());
        p.getInventory().getNonEquipmentItems().set(0, new net.minecraft.world.item.ItemStack(Blocks.OBSIDIAN.asItem()));
        return p;
    }

    @Test
    void targetMatchesBlockAndOptionalAxis() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过建造规则钉桩");
        BuildTaskRecord.Target target = new BuildTaskRecord.Target(Blocks.OAK_LOG,
                Blocks.OAK_LOG.asItem(), BlockPos.ZERO, "oak_log", null, Direction.Axis.Y, null);

        assertTrue(target.matches(Blocks.OAK_LOG.defaultBlockState()
                .setValue(BlockStateProperties.AXIS, Direction.Axis.Y)));
        assertFalse(target.matches(Blocks.OAK_LOG.defaultBlockState()
                .setValue(BlockStateProperties.AXIS, Direction.Axis.X)));
        assertFalse(target.matches(Blocks.SPRUCE_LOG.defaultBlockState()
                .setValue(BlockStateProperties.AXIS, Direction.Axis.Y)));
    }


    @Test
    void requestedHalfDoesNotAcceptDoubleSlab() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过建造规则钉桩");
        BuildTaskRecord.Target target = new BuildTaskRecord.Target(Blocks.SMOOTH_STONE_SLAB,
                Blocks.SMOOTH_STONE_SLAB.asItem(), BlockPos.ZERO, "smooth_stone_slab", null, null, true);

        assertFalse(target.matches(Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE)));
    }

    @Test
    void targetMatchesEveryRequestedStateProperty() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过建造规则钉桩");
        BlockState desired = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.WATERLOGGED, false);
        BuildTaskRecord.Target target = new BuildTaskRecord.Target(desired,
                Blocks.OAK_STAIRS.asItem(), BlockPos.ZERO, "oak_stairs", Direction.NORTH, null, null);

        assertTrue(target.matches(desired));
        assertFalse(target.matches(desired.setValue(BlockStateProperties.WATERLOGGED, true)));
    }

    @Test
    void buildValidityCanIgnoreConfiguredProperties() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过建造规则钉桩");
        NavSettings.get().buildIgnoreProperties().add("waterlogged");
        BlockState desired = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, false);
        BuildTaskRecord.Target target = new BuildTaskRecord.Target(desired,
                Blocks.OAK_STAIRS.asItem(), BlockPos.ZERO, "oak_stairs", null, null, null);

        assertTrue(target.matches(desired.setValue(BlockStateProperties.WATERLOGGED, true)));
    }

    @Test
    void buildIgnoreDirectionIgnoresKnownOrientationProperties() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过建造规则钉桩");
        NavSettings.get().buildIgnoreDirection = true;
        BlockState desired = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        BuildTaskRecord.Target target = new BuildTaskRecord.Target(desired,
                Blocks.OAK_STAIRS.asItem(), BlockPos.ZERO, "oak_stairs", null, null, null);

        assertTrue(target.matches(desired.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)));
    }

    @Test
    void buildIgnoreDirectionDoesNotIgnoreUnlistedProperties() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过建造规则钉桩");
        NavSettings.get().buildIgnoreDirection = true;
        BlockState desired = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.WATERLOGGED, false);
        BuildTaskRecord.Target target = new BuildTaskRecord.Target(desired,
                Blocks.OAK_STAIRS.asItem(), BlockPos.ZERO, "oak_stairs", null, null, null);

        assertFalse(target.matches(desired.setValue(BlockStateProperties.WATERLOGGED, true)));
    }

    @Test
    void buildValidityAcceptsConfiguredSubstitutesOnlyForExistingBlocks() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过建造规则钉桩");
        NavSettings.get().buildValidSubstitutes().put(Blocks.STONE, List.of(Blocks.COBBLESTONE));
        BuildTaskRecord.Target target = new BuildTaskRecord.Target(Blocks.STONE,
                Blocks.STONE.asItem(), BlockPos.ZERO, "stone", null, null, null);

        assertTrue(target.matches(Blocks.COBBLESTONE.defaultBlockState()));
        assertFalse(target.acceptsPlacedState(Blocks.COBBLESTONE.defaultBlockState()));
    }

    @Test
    void unsupportedOrientationHintIsRejected() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过建造规则钉桩");

        assertThrows(IllegalArgumentException.class, () -> new BuildTaskRecord.Target(Blocks.STONE,
                Blocks.STONE.asItem(), BlockPos.ZERO, "stone", Direction.NORTH, null, null));
    }

    @Test
    void buildContextPricesRequestedCells() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过建造成本钉桩");
        BlockPos pos = new BlockPos(3, 65, 7);
        BuildTaskRecord.Target target = new BuildTaskRecord.Target(Blocks.OBSIDIAN,
                Blocks.OBSIDIAN.asItem(), pos, "obsidian", null, null, null);
        FakeView view = new FakeView();
        view.set(pos, Blocks.AIR.defaultBlockState());
        BuildCalculationContext ctx = new BuildCalculationContext(player, view, ChunkLoadedTest.ALWAYS,
                true, LongSets.emptySet(), LongSets.emptySet(), Map.of(pos.asLong(), target),
                Set.of(Blocks.OBSIDIAN.defaultBlockState()), true);

        assertEquals(0.0, ctx.costOfPlacingAt(pos.getX(), pos.getY(), pos.getZ(),
                Blocks.AIR.defaultBlockState()));
        assertEquals(NavSettings.get().breakCorrectBlockPenaltyMultiplier,
                ctx.breakCostMultiplierAt(pos.getX(), pos.getY(), pos.getZ(),
                        Blocks.OBSIDIAN.defaultBlockState()));
        assertEquals(1.0, ctx.breakCostMultiplierAt(pos.getX(), pos.getY(), pos.getZ(),
                Blocks.DIRT.defaultBlockState()));
    }

    @Test
    void buildContextAllowsTemporaryBlocksInAirTargetCells() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过建造成本钉桩");
        player.getInventory().getNonEquipmentItems().set(1, new net.minecraft.world.item.ItemStack(Items.DIRT));
        BlockPos pos = new BlockPos(3, 65, 7);
        BuildTaskRecord.Target target = new BuildTaskRecord.Target(Blocks.AIR,
                Blocks.AIR.asItem(), pos, "air", null, null, null);
        FakeView view = new FakeView();
        view.set(pos, Blocks.AIR.defaultBlockState());
        BuildCalculationContext ctx = new BuildCalculationContext(player, view, ChunkLoadedTest.ALWAYS,
                true, LongSets.emptySet(), LongSets.emptySet(), Map.of(pos.asLong(), target),
                Set.of(Blocks.DIRT.defaultBlockState()), true);

        assertEquals(NavSettings.get().blockPlacementPenalty
                        * NavSettings.get().placeIncorrectBlockPenaltyMultiplier,
                ctx.costOfPlacingAt(pos.getX(), pos.getY(), pos.getZ(), Blocks.AIR.defaultBlockState()));
    }


    @Test
    void buildContextRespectsBreakSettingForWrongTargetCells() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过建造成本钉桩");
        NavSettings.get().allowBreak = false;
        BlockPos pos = new BlockPos(3, 65, 7);
        BuildTaskRecord.Target target = new BuildTaskRecord.Target(Blocks.OBSIDIAN,
                Blocks.OBSIDIAN.asItem(), pos, "obsidian", null, null, null);
        FakeView view = new FakeView();
        view.set(pos, Blocks.DIRT.defaultBlockState());
        BuildCalculationContext ctx = new BuildCalculationContext(player, view, ChunkLoadedTest.ALWAYS,
                true, LongSets.emptySet(), LongSets.emptySet(), Map.of(pos.asLong(), target),
                Set.of(Blocks.OBSIDIAN.defaultBlockState()), true);

        assertEquals(ActionCosts.COST_INF, ctx.breakCostMultiplierAt(pos.getX(), pos.getY(), pos.getZ(),
                Blocks.DIRT.defaultBlockState()));
    }

    private static final class FakeView implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void set(BlockPos pos, BlockState state) {
            blocks.put(pos.immutable(), state);
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }
        @Override public int getHeight() { return 384; }
        @Override public int getMinY() { return -64; }
    }
}



