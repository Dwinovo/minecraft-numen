package com.dwinovo.numen.core.pathing.moves;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.core.pathing.util.BlockEntityAware;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
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

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 挖掘/放置保护口径的回归钉,打在真实成本函数上:
 * <ul>
 *   <li>功能方块软惩罚:箱子(在 NavSettings.blocksToAvoidBreaking 默认清单内)
 *       计 ×10 软成本(有限价,无路可走仍会破坏)
 *       (回到泥土一样的有限价);</li>
 *   <li>do_not_break 标签成员(数据包追加)在任何开关下都计 INF,
 *       默认清单为空,本测试通过 NavSettings
 *       .blocksToDisallowBreaking 钉一个方块验证;</li>
 *   <li>sacred(导航自身目标格)必 INF;</li>
 *   <li>同地形普通方块(泥土)有限价——证明是保护在起作用,不是别的
 *       东西把边价推上去的;</li>
 *   <li>deniedPlace 命中格放置计 INF;石头可作放置贴面
 *       (plan/execute 一把尺的共享谓词)。</li>
 * </ul>
 * 需要 MC 注册表,无头引导失败时跳过而不失败。
 */
@Tag("mc")
class ProtectionPinsTest {

    private static final BlockPos SRC = new BlockPos(0, 64, 0);

    private static boolean booted;
    private static ServerPlayer player;

    private boolean savedConsiderPotions;
    private boolean savedAllowWaterBucketFall;

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

    /** 无构造器分配 ServerPlayer,注入真实背包与饥饿数据(同 MovementCostsTest)。 */
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
        // 1.21.x 的 SynchedEntityData 改由 Builder 组装，build() 会校验「每个 id 都已定义」
        // ——只塞血量一项会当场抛。所以照 Entity 构造器的原样把定义表补全：基类那八项
        // 写死在构造器里（不在 defineSynchedData 内），其余交给各层的 defineSynchedData
        // （那几个实现都是纯粹的 builder.define，不碰实例状态）。拿到的就是真实定义表。
        net.minecraft.network.syncher.SynchedEntityData.Builder builder =
                new net.minecraft.network.syncher.SynchedEntityData.Builder(p);
        builder.define(dataKey(net.minecraft.world.entity.Entity.class, "DATA_SHARED_FLAGS_ID"),
                (byte) 0);
        builder.define(dataKey(net.minecraft.world.entity.Entity.class, "DATA_AIR_SUPPLY_ID"),
                net.minecraft.world.entity.Entity.TOTAL_AIR_SUPPLY);
        builder.define(dataKey(net.minecraft.world.entity.Entity.class, "DATA_CUSTOM_NAME_VISIBLE"),
                false);
        builder.define(dataKey(net.minecraft.world.entity.Entity.class, "DATA_CUSTOM_NAME"),
                java.util.Optional.<net.minecraft.network.chat.Component>empty());
        builder.define(dataKey(net.minecraft.world.entity.Entity.class, "DATA_SILENT"), false);
        builder.define(dataKey(net.minecraft.world.entity.Entity.class, "DATA_NO_GRAVITY"), false);
        builder.define(dataKey(net.minecraft.world.entity.Entity.class, "DATA_POSE"),
                net.minecraft.world.entity.Pose.STANDING);
        builder.define(dataKey(net.minecraft.world.entity.Entity.class, "DATA_TICKS_FROZEN"), 0);
        java.lang.reflect.Method defineSynched = net.minecraft.world.entity.Entity.class
                .getDeclaredMethod("defineSynchedData",
                        net.minecraft.network.syncher.SynchedEntityData.Builder.class);
        defineSynched.setAccessible(true);
        defineSynched.invoke(p, builder);
        net.minecraft.network.syncher.SynchedEntityData synched = builder.build();
        entityData.set(p, synched);
        synched.set(dataKey(net.minecraft.world.entity.LivingEntity.class, "DATA_HEALTH_ID"),
                20.0f);   // 满血
        p.getInventory().items.set(0, new ItemStack(Items.DIRT));
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

    @BeforeEach
    void setUp() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过保护钉桩");
        NavSettings s = NavSettings.get();
        savedConsiderPotions = s.considerPotionEffects;
        savedAllowWaterBucketFall = s.allowWaterBucketFall;
        // 空壳玩家没有药水效果表与所在维度,绕开会触碰它们的取样路径
        s.considerPotionEffects = false;
        s.allowWaterBucketFall = false;
    }

    @AfterEach
    void tearDown() {
        NavSettings s = NavSettings.get();
        s.considerPotionEffects = savedConsiderPotions;
        s.allowWaterBucketFall = savedAllowWaterBucketFall;
    }

    // ==================== 假世界 ====================

    /** Map 后备世界视图,自答方块实体存在性(保护检查离线可答)。 */
    private static final class FakeView implements BlockGetter, BlockEntityAware {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final Set<BlockPos> blockEntities = new HashSet<>();

        void set(BlockPos p, BlockState s) {
            blocks.put(p.immutable(), s);
        }

        void setChest(BlockPos p) {
            set(p, Blocks.CHEST.defaultBlockState());
            blockEntities.add(p.immutable());
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public boolean hasBlockEntity(BlockPos pos) { return blockEntities.contains(pos); }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }
        @Override public int getHeight() { return 384; }
        @Override public int getMinY() { return -64; }
    }

    /** SRC 周边 5×5 铺石地板,横向移动都有落脚。 */
    private static FakeView floored() {
        FakeView v = new FakeView();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                v.set(new BlockPos(SRC.getX() + dx, 63, SRC.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
            }
        }
        return v;
    }

    private static CalculationContext context(FakeView view, LongSet sacred) {
        return new CalculationContext(player, view, ChunkLoadedTest.ALWAYS, false,
                sacred, LongSets.emptySet());
    }

    private static LongSet sacredOf(BlockPos pos) {
        LongSet set = new LongOpenHashSet();
        set.add(pos.asLong());
        return set;
    }

    // ==================== 挖掘保护 ====================

    @Test
    void chestBreakIsSoftPenaltyButFinite() {
        BlockPos chest = SRC.north();
        FakeView v = floored();
        v.setChest(chest);
        // 箱子在 NavSettings.blocksToAvoidBreaking 默认清单内 → ×10 软成本(有限价)
        double soft = MovementHelper.getMiningDurationTicks(
                context(v, LongSets.emptySet()),
                chest.getX(), chest.getY(), chest.getZ(), false);
        assertTrue(soft > 0 && soft < COST_INF, "软惩罚箱子应有有限价,实为 " + soft);
        // 对照:普通泥土无软惩罚,应显著更便宜(软惩罚真实生效)
        BlockPos dirt = SRC.south();
        v.set(dirt, Blocks.DIRT.defaultBlockState());
        double plain = MovementHelper.getMiningDurationTicks(
                context(v, LongSets.emptySet()),
                dirt.getX(), dirt.getY(), dirt.getZ(), false);
        assertTrue(plain < soft, "软惩罚应贵于普通方块,soft=" + soft + " plain=" + plain);
        // 端到端:北向平移(要挖穿箱子)产出有限边(不是 INF)
        double cost = Moves.TRAVERSE_NORTH.cost(context(v, LongSets.emptySet()),
                SRC.getX(), SRC.getY(), SRC.getZ());
        assertTrue(cost > 0 && cost < COST_INF, "穿箱平移应有限价,实为 " + cost);
    }

    @Test
    void disallowBreakingHoldsInfinite() {
        // blocksToDisallowBreaking(默认空)硬禁挖。钉一个方块到该清单
        // 验证(用石头,与箱子软清单区分开)。
        BlockPos stone = SRC.north();
        FakeView v = floored();
        v.set(stone, Blocks.STONE.defaultBlockState());
        NavSettings s = NavSettings.get();
        var savedDisallow = new java.util.ArrayList<>(s.blocksToDisallowBreaking());
        var savedAvoid = new java.util.ArrayList<>(s.blocksToAvoidBreaking());
        try {
            s.blocksToDisallowBreaking().add(Blocks.STONE);
            s.blocksToAvoidBreaking().clear();
            assertTrue(MovementHelper.avoidBreaking(
                    context(v, LongSets.emptySet()),
                    stone.getX(), stone.getY(), stone.getZ(), v.getBlockState(stone)));
        } finally {
            s.blocksToDisallowBreaking().clear();
            s.blocksToDisallowBreaking().addAll(savedDisallow);
            s.blocksToAvoidBreaking().clear();
            s.blocksToAvoidBreaking().addAll(savedAvoid);
        }
    }

    @Test
    void sacredCellTurnsARoutableBreakInfinite() {
        BlockPos dirt = SRC.north();
        FakeView v = floored();
        v.set(dirt, Blocks.DIRT.defaultBlockState());
        // 未保护:同地形泥土障碍有限价(证明后面翻成 INF 的是 sacred)
        double open = MovementHelper.getMiningDurationTicks(
                context(v, LongSets.emptySet()),
                dirt.getX(), dirt.getY(), dirt.getZ(), false);
        assertTrue(open > 0 && open < COST_INF, "未保护的泥土应有限价,实为 " + open);
        // sacred:一模一样的地形,该格是导航自身的目标 → INF
        assertTrue(MovementHelper.getMiningDurationTicks(
                context(v, sacredOf(dirt)),
                dirt.getX(), dirt.getY(), dirt.getZ(), false) >= COST_INF);
    }

    // ==================== 放置保护 / 贴面一把尺 ====================

    @Test
    void deniedAndSacredCellsRefusePlacement() {
        FakeView v = floored();
        BlockPos cell = SRC.north();
        // sacred 格不可被埋
        CalculationContext sacredCtx = context(v, sacredOf(cell));
        assertEquals(COST_INF, sacredCtx.costOfPlacingAt(
                cell.getX(), cell.getY(), cell.getZ(), v.getBlockState(cell)));
        // deniedPlace 格(执行层证明无支撑)不可再规划放置
        LongSet denied = new LongOpenHashSet();
        denied.add(cell.asLong());
        CalculationContext deniedCtx = new CalculationContext(player, v, ChunkLoadedTest.ALWAYS,
                false, LongSets.emptySet(), denied);
        assertEquals(COST_INF, deniedCtx.costOfPlacingAt(
                cell.getX(), cell.getY(), cell.getZ(), v.getBlockState(cell)));
    }

    @Test
    void placementFacePredicateSharedRuler() {
        // 箱子不可作放置贴面;流体更不行;完整实心方块可以
        assertFalse(MovementHelper.canPlaceAgainst(Blocks.CHEST.defaultBlockState()));
        assertFalse(MovementHelper.canPlaceAgainst(Blocks.WATER.defaultBlockState()));
        assertTrue(MovementHelper.canPlaceAgainst(Blocks.STONE.defaultBlockState()));
    }
}
