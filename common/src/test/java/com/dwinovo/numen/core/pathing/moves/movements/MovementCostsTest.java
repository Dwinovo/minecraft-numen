package com.dwinovo.numen.core.pathing.moves.movements;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import com.dwinovo.numen.core.pathing.moves.ActionCosts;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.ChunkLoadedTest;
import com.dwinovo.numen.core.pathing.moves.MutableMoveResult;
import com.dwinovo.numen.core.pathing.settings.NavSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 移动原语成本函数的关键数值钉桩:在假方块图上验证平移(疾跑/步行)、
 * 上一格、下一格、对角、垫柱、原地下挖的精确成本,以及跑酷开关关闭时
 * 不产出。需要 MC 注册表,无头引导失败时跳过而不失败。
 *
 * <p>玩家用无构造器分配 + 反射注入背包与饥饿数据的空壳:成本函数只经
 * CalculationContext 读取背包/饥饿/附魔快照,不触碰实体其余状态。
 */
@Tag("mc")
class MovementCostsTest {

    private static final double EPS = 1e-3;
    private static final BlockPos SRC = new BlockPos(0, 64, 0);

    private static boolean booted;
    private static ServerPlayer player;

    /** 记录被本测试改写过的设置,逐测恢复。 */
    private boolean savedConsiderPotions;
    private boolean savedAllowWaterBucketFall;
    private boolean savedAllowSprint;
    private boolean savedAllowPlace;
    private boolean savedAllowParkour;

    @BeforeAll
    static void boot() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            player = allocatePlayer();
            booted = true;
        } catch (Throwable t) {
            booted = false; // 无法引导的环境:跳过,不失败
        }
    }

    /** 无构造器分配 ServerPlayer,并注入真实背包与饥饿数据。 */
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
        foodData.set(p, new FoodData()); // 满饥饿:可疾跑
        // 快捷栏放一组泥土:垫路耗材判定与徒手挖掘选材都有明确对象
        p.getInventory().getNonEquipmentItems().set(0, new ItemStack(Items.DIRT));
        return p;
    }

    @BeforeEach
    void setUp() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过成本钉桩");
        NavSettings s = NavSettings.get();
        savedConsiderPotions = s.considerPotionEffects;
        savedAllowWaterBucketFall = s.allowWaterBucketFall;
        savedAllowSprint = s.allowSprint;
        savedAllowPlace = s.allowPlace;
        savedAllowParkour = s.allowParkour;
        // 空壳玩家没有药水效果表与所在维度,绕开会触碰它们的取样路径
        s.considerPotionEffects = false;
        s.allowWaterBucketFall = false;
        s.allowSprint = true;
        s.allowPlace = true;
        s.allowParkour = false;
    }

    @AfterEach
    void tearDown() {
        NavSettings s = NavSettings.get();
        s.considerPotionEffects = savedConsiderPotions;
        s.allowWaterBucketFall = savedAllowWaterBucketFall;
        s.allowSprint = savedAllowSprint;
        s.allowPlace = savedAllowPlace;
        s.allowParkour = savedAllowParkour;
    }

    // ==================== 假世界 ====================

    /** Map 后备的世界视图,缺省全是空气。 */
    private static final class FakeView implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void set(int x, int y, int z, BlockState state) {
            blocks.put(new BlockPos(x, y, z), state);
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

    /** SRC 周围 5×5 的石头地板(脚下 y=63)。 */
    private static FakeView floored() {
        FakeView v = new FakeView();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                v.set(SRC.getX() + dx, 63, SRC.getZ() + dz, Blocks.STONE.defaultBlockState());
            }
        }
        return v;
    }

    private static CalculationContext context(FakeView view) {
        return new CalculationContext(player, view, ChunkLoadedTest.ALWAYS, true);
    }

    // ==================== 平移 ====================

    @Test
    void traverseFlatSprintCosts3_5638() {
        CalculationContext ctx = context(floored());
        double cost = MovementTraverse.cost(ctx, 0, 64, 0, 1, 0);
        assertEquals(3.5638, cost, EPS);
        assertEquals(ActionCosts.SPRINT_ONE_BLOCK_COST, cost, 1e-9);
    }

    @Test
    void traverseFlatWalkCosts4_6329() {
        NavSettings.get().allowSprint = false;
        CalculationContext ctx = context(floored());
        double cost = MovementTraverse.cost(ctx, 0, 64, 0, 1, 0);
        assertEquals(4.6329, cost, EPS);
        assertEquals(ActionCosts.WALK_ONE_BLOCK_COST, cost, 1e-9);
    }

    // ==================== 上一格 ====================

    @Test
    void ascendFlatCostsWalkPlusJumpPenalty() {
        FakeView v = floored();
        v.set(1, 64, 0, Blocks.STONE.defaultBlockState()); // 上一格的落脚台
        CalculationContext ctx = context(v);
        double cost = MovementAscend.cost(ctx, 0, 64, 0, 1, 0);
        // max(起跳耗时, 平走一格) + 跳跃罚金 = 4.63285 + 2
        assertEquals(4.63285 + 2, cost, EPS);
    }

    // ==================== 下一格 ====================

    @Test
    void descendFlatCostsWalkOffPlusFallOne() {
        FakeView v = new FakeView();
        v.set(0, 63, 0, Blocks.STONE.defaultBlockState()); // 出发地板
        v.set(1, 62, 0, Blocks.STONE.defaultBlockState()); // 低一格的落脚地板
        CalculationContext ctx = context(v);
        MutableMoveResult res = new MutableMoveResult();
        MovementDescend.cost(ctx, 0, 64, 0, 1, 0, res);
        assertEquals(63, res.y, "落点应恰低一格(下降而非坠落)");
        assertEquals(3.70628 + 5.6147, res.cost, EPS);
    }

    // ==================== 对角 ====================

    @Test
    void diagonalFlatSprintCosts5_0397() {
        CalculationContext ctx = context(floored());
        MutableMoveResult res = new MutableMoveResult();
        MovementDiagonal.cost(ctx, 0, 64, 0, 1, 1, res);
        assertEquals(64, res.y, "平级对角落点应同高");
        assertEquals(5.0397, res.cost, EPS);
    }

    // ==================== 垫柱 ====================

    @Test
    void pillarFlatCostsJumpPlusPlacePlusPenalty() {
        CalculationContext ctx = context(floored());
        double cost = MovementPillar.cost(ctx, 0, 64, 0);
        // 起跳 3.1634 + 放置罚金 20 + 跳跃罚金 2,头顶无阻挡不含挖掘
        assertEquals(25.1634, cost, EPS);
    }

    @Test
    void pillarWithDirtOverheadAddsMiningTicks() {
        FakeView v = floored();
        v.set(0, 66, 0, Blocks.DIRT.defaultBlockState()); // 头顶两格处有泥土
        CalculationContext ctx = context(v);
        double cost = MovementPillar.cost(ctx, 0, 64, 0);
        // 徒手(泥土物品)挖泥土:硬度 0.5 → 1/(2/30)=15 tick,再加挖掘附加罚金 2
        assertEquals(25.1634 + 15 + 2, cost, EPS);
    }

    // ==================== 原地下挖 ====================

    @Test
    void downwardThroughDirtCostsFallPlusMining() {
        FakeView v = new FakeView();
        v.set(0, 63, 0, Blocks.DIRT.defaultBlockState());  // 脚下泥土
        v.set(0, 62, 0, Blocks.STONE.defaultBlockState()); // 再下一格可站
        CalculationContext ctx = context(v);
        double cost = MovementDownward.cost(ctx, 0, 64, 0);
        assertEquals(5.6147 + 15 + 2, cost, EPS);
    }

    // ==================== 跑酷 ====================

    @Test
    void parkourDisabledProducesNothing() {
        FakeView v = new FakeView();
        v.set(0, 63, 0, Blocks.STONE.defaultBlockState()); // 起跳台
        v.set(2, 63, 0, Blocks.STONE.defaultBlockState()); // 隔一格空隙的落点
        CalculationContext ctx = context(v);
        MutableMoveResult res = new MutableMoveResult();
        MovementParkour.cost(ctx, 0, 64, 0, Direction.EAST, res);
        assertTrue(res.cost >= ActionCosts.COST_INF, "跑酷关闭时不应产出任何落点");
    }

    @Test
    void parkourEnabledPricesOneGapJump() {
        NavSettings.get().allowParkour = true;
        FakeView v = new FakeView();
        v.set(0, 63, 0, Blocks.STONE.defaultBlockState());
        v.set(2, 63, 0, Blocks.STONE.defaultBlockState());
        CalculationContext ctx = context(v);
        MutableMoveResult res = new MutableMoveResult();
        MovementParkour.cost(ctx, 0, 64, 0, Direction.EAST, res);
        assertEquals(2, res.x);
        assertEquals(64, res.y);
        // 两格平跳 4.63285×2 + 跳跃罚金 2
        assertEquals(9.2657 + 2, res.cost, EPS);
    }
}
