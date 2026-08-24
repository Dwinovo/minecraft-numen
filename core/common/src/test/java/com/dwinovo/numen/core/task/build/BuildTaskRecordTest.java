package com.dwinovo.numen.core.task.build;

import com.dwinovo.numen.core.pathing.moves.ActionCosts;
import com.dwinovo.numen.core.pathing.moves.ChunkLoadedTest;
import com.dwinovo.numen.core.pathing.moves.TerrainPermit;
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
            com.dwinovo.numen.core.ScaffoldTagTestSupport.bind();
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
        inventory.set(p, new Inventory(p));
        Field foodData = Player.class.getDeclaredField("foodData");
        foodData.setAccessible(true);
        foodData.set(p, new FoodData());
        // 能力位与血量:成本函数现在也读这两样(免耗材画像恒有耗材、无饥饿画像
        // 不受饱食度门限,落差上限按血量反推),空壳里它们都是 null。和背包、饥饿
        // 数据一样按真实对象注进去,断言本身一个字没动。
        Field abilities = Player.class.getDeclaredField("abilities");
        abilities.setAccessible(true);
        abilities.set(p, new net.minecraft.world.entity.player.Abilities());   // 默认生存画像
        Field entityData = net.minecraft.world.entity.Entity.class.getDeclaredField("entityData");
        entityData.setAccessible(true);
        // 1.20.4 的 SynchedEntityData 直接 new + define,没有 Builder,也不校验
        // 「每个 id 都已定义」——只定义要断言的血量一项即可。
        net.minecraft.network.syncher.SynchedEntityData synched =
                new net.minecraft.network.syncher.SynchedEntityData(p);
        synched.define(dataKey(net.minecraft.world.entity.LivingEntity.class, "DATA_HEALTH_ID"),
                20.0f);   // 满血
        entityData.set(p, synched);
        p.getInventory().items.set(0, new net.minecraft.world.item.ItemStack(Blocks.OBSIDIAN.asItem()));
        return p;
    }

    /** 取原版某个同步数据键（全是私有静态字段）。 */
    @SuppressWarnings("unchecked")
    private static <T> net.minecraft.network.syncher.EntityDataAccessor<T> dataKey(
            Class<?> owner, String field) throws Exception {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        return (net.minecraft.network.syncher.EntityDataAccessor<T>) f.get(null);
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
        // 半砖的上下半是作者定的姿态，但不在“朝向”那一组里：buildIgnoreDirection
        // 放过的只有朝向，别的作者属性照比。（此前这里拿含水当反例，而含水本就是
        // 世界算出来的派生属性，对账口径里两边都不看。）
        BlockState desired = Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
        BuildTaskRecord.Target target = new BuildTaskRecord.Target(desired,
                Blocks.SMOOTH_STONE_SLAB.asItem(), BlockPos.ZERO, "smooth_stone_slab",
                null, null, null);

        assertFalse(target.matches(desired.setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP)));
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
    void itemPlaceLaneOnlyForPlaceableItems() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过建造规则钉桩");
        BuildTaskRecord.Target table = new BuildTaskRecord.Target(Blocks.CRAFTING_TABLE,
                Blocks.CRAFTING_TABLE.asItem(), BlockPos.ZERO, "crafting_table", null, null, null);
        assertTrue(table.asItemPlace().itemPlace());

        // 清空格与液体没有"拿在手里放"这回事,原样回落图纸车道
        BuildTaskRecord.Target air = new BuildTaskRecord.Target(Blocks.AIR,
                Blocks.AIR.asItem(), BlockPos.ZERO, "air", null, null, null);
        assertFalse(air.asItemPlace().itemPlace());
        BuildTaskRecord.Target water = new BuildTaskRecord.Target(
                Blocks.WATER.defaultBlockState(), Items.WATER_BUCKET,
                BlockPos.ZERO, "water", null, null, null);
        assertFalse(water.asItemPlace().itemPlace());
    }

    @Test
    void itemPlaceMatchesByBlockIdentityNotState() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过建造规则钉桩");
        // 原生格没提朝向,朝向就不是工程量:游戏按玩家规则给什么朝向都算建好
        BuildTaskRecord.Target chest = new BuildTaskRecord.Target(Blocks.CHEST,
                Blocks.CHEST.asItem(), BlockPos.ZERO, "chest", null, null, null).asItemPlace();
        assertTrue(chest.matches(Blocks.CHEST.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)));
        assertFalse(chest.matches(Blocks.TRAPPED_CHEST.defaultBlockState()), "别的方块不冒充");
        assertFalse(chest.matches(Blocks.AIR.defaultBlockState()), "空格就是还没放");

        // 提了朝向的格子是图纸语义,原判据一分不松
        BuildTaskRecord.Target drafted = new BuildTaskRecord.Target(Blocks.CHEST,
                Blocks.CHEST.asItem(), BlockPos.ZERO, "chest", Direction.NORTH, null, null);
        assertFalse(drafted.matches(Blocks.CHEST.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)));
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
                true, LongSets.emptySet(), LongSets.emptySet(), TerrainPermit.TERRAFORM,
                Map.of(pos.asLong(), target),
                Set.of(Blocks.OBSIDIAN.defaultBlockState()), true);

        assertEquals(0.0, ctx.costOfPlacingAt(pos.getX(), pos.getY(), pos.getZ(),
                Blocks.AIR.defaultBlockState()));
        // 拆已经建对的格子收固定重罚 8×：贵得不会顺手拆，又没贵到“活路等于没有”
        // ——逐层上盖时她本来就站在自己盖的楼板底下，唯一的出路就是拆一块钻出去，
        // 定价过高时 A* 只会搜不出任何出路、原地打转。
        assertEquals(8.0,
                ctx.breakCostMultiplierAt(pos.getX(), pos.getY(), pos.getZ(),
                        Blocks.OBSIDIAN.defaultBlockState()));
        assertEquals(1.0, ctx.breakCostMultiplierAt(pos.getX(), pos.getY(), pos.getZ(),
                Blocks.DIRT.defaultBlockState()));
    }

    @Test
    void buildContextAllowsTemporaryBlocksInAirTargetCells() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过建造成本钉桩");
        player.getInventory().items.set(1, new net.minecraft.world.item.ItemStack(Items.DIRT));
        BlockPos pos = new BlockPos(3, 65, 7);
        BuildTaskRecord.Target target = new BuildTaskRecord.Target(Blocks.AIR,
                Blocks.AIR.asItem(), pos, "air", null, null, null);
        FakeView view = new FakeView();
        view.set(pos, Blocks.AIR.defaultBlockState());
        BuildCalculationContext ctx = new BuildCalculationContext(player, view, ChunkLoadedTest.ALWAYS,
                true, LongSets.emptySet(), LongSets.emptySet(), TerrainPermit.TERRAFORM,
                Map.of(pos.asLong(), target),
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
                true, LongSets.emptySet(), LongSets.emptySet(), TerrainPermit.TERRAFORM,
                Map.of(pos.asLong(), target),
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
        @Override public int getMinBuildHeight() { return -64; }
    }
}



