package com.dwinovo.numen.core.pathing.moves;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.core.pathing.spec.PositionCosts;
import com.dwinovo.numen.core.pathing.spec.RouteSpec;
import com.dwinovo.numen.permission.Gate;
import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.permission.PlacedBlocks;
import com.dwinovo.numen.permission.RuleSet;

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
 *   <li>权限层的 ask(带方块实体的箱子、玩家放的格、出厂表按标签点名的门)在 NATURAL 下
 *       计 INF——有别的路就不走这条;在 ANY 下有限但贵于自然方块,并且账单说得出为什么
 *       需要同意;</li>
 *   <li>sacred(导航自身目标格,经规格的按位置代价)必 INF;</li>
 *   <li>同地形普通方块(泥土)有限价——证明是保护在起作用,不是别的
 *       东西把边价推上去的;</li>
 *   <li>规格按位置禁放的格放置计 INF;石头可作放置贴面
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
            com.dwinovo.numen.core.ThrowawayTagTestSupport.bind();
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

    /** Map 后备世界视图。 */
    private static final class FakeView implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void set(BlockPos p, BlockState s) {
            blocks.put(p.immutable(), s);
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

    /** 可改地形的规格,sacred 格作为按位置的禁挖禁放并进去。 */
    private static final RouteSpec NATURAL = RouteSpec.defaults().withAlter(RouteSpec.Alter.NATURAL);
    /** 连需要主人同意的格也算进路线的规格。 */
    private static final RouteSpec ANY = RouteSpec.defaults().withAlter(RouteSpec.Alter.ANY);

    private static CalculationContext context(FakeView view, LongSet sacred) {
        return context(view, sacred, NATURAL, new PlacedBlocks());
    }

    private static CalculationContext context(FakeView view, LongSet sacred, RouteSpec spec, PlacedBlocks placed) {
        return new CalculationContext(player, view, ChunkLoadedTest.ALWAYS, false,
                spec.withPositions(PositionCosts.protect(sacred)),
                new Gate(null, Mode.ASK, RuleSet.EMPTY, RuleSet.factory(), placed, java.util.List.of()));
    }

    private static LongSet sacredOf(BlockPos pos) {
        LongSet set = new LongOpenHashSet();
        set.add(pos.asLong());
        return set;
    }

    // ==================== 挖掘保护:权限层的裁决折成代价 ====================

    @Test
    void chestNeedsConsentSoNaturalRoutesAroundAndAnyPaysDearly() {
        BlockPos chest = SRC.north();
        FakeView v = floored();
        v.set(chest, Blocks.CHEST.defaultBlockState());
        // 出厂 ask 表 break(block_entity):NATURAL 下 INF——有别的路就不走这条
        assertTrue(MovementHelper.getMiningDurationTicks(
                context(v, LongSets.emptySet()),
                chest.getX(), chest.getY(), chest.getZ(), false) >= COST_INF,
                "带方块实体的箱子在 NATURAL 下应计 INF");
        assertTrue(Moves.TRAVERSE_NORTH.cost(context(v, LongSets.emptySet()),
                SRC.getX(), SRC.getY(), SRC.getZ()) >= COST_INF, "穿箱平移在 NATURAL 下应计 INF");
        // ANY 下有限但贵:同一块箱子对比泥土
        CalculationContext any = context(v, LongSets.emptySet(), ANY, new PlacedBlocks());
        double consent = MovementHelper.getMiningDurationTicks(any,
                chest.getX(), chest.getY(), chest.getZ(), false);
        assertTrue(consent > 0 && consent < COST_INF, "ANY 下箱子应有限价,实为 " + consent);
        BlockPos dirt = SRC.south();
        v.set(dirt, Blocks.DIRT.defaultBlockState());
        double plain = MovementHelper.getMiningDurationTicks(any,
                dirt.getX(), dirt.getY(), dirt.getZ(), false);
        assertTrue(plain < consent, "需要同意的格应贵于自然方块,consent=" + consent + " plain=" + plain);
        // 账单的挖掘条目由同一个裁决填上那一条征询:箱子要问,泥土不用
        var chestBill = com.dwinovo.numen.core.pathing.execute.TerrainBill.planned(throughCell(chest), v, any.gate);
        assertEquals(1, chestBill.consentItems().size());
        assertTrue(chestBill.consentItems().get(0).cause().contains("has a block entity"));
        assertTrue(chestBill.summary().contains("needing consent"), chestBill.summary());
        assertTrue(com.dwinovo.numen.core.pathing.execute.TerrainBill.planned(throughCell(dirt), v, any.gate)
                .consentItems().isEmpty());
    }

    /** 一步挖穿 {@code cell} 的假路径:只为出账,不执行。 */
    private static com.dwinovo.numen.core.pathing.astar.NavPath throughCell(BlockPos cell) {
        Movement step = new Movement(null, ANY, SRC, cell, new BlockPos[]{cell}) {
            {
                override(1);
            }

            @Override
            public double calculateCost(CalculationContext context, MutableMoveResult result) {
                return 1;
            }

            @Override
            protected java.util.Set<BlockPos> calculateValidPositions() {
                return java.util.Set.of(SRC, cell);
            }
        };
        return new com.dwinovo.numen.core.pathing.astar.PathBase() {
            @Override public java.util.List<Movement> movements() { return java.util.List.of(step); }
            @Override public java.util.List<BlockPos> positions() { return java.util.List.of(SRC, cell); }
            @Override public com.dwinovo.numen.core.pathing.goals.Goal getGoal() { return null; }
            @Override public int getNumNodesConsidered() { return 0; }
        };
    }

    @Test
    void playerPlacedCellIsInfiniteUnlessAny() {
        BlockPos wall = SRC.north();
        FakeView v = floored();
        v.set(wall, Blocks.DIRT.defaultBlockState());
        PlacedBlocks placed = new PlacedBlocks();
        placed.record(wall, new PlacedBlocks.Placer(java.util.UUID.randomUUID(), "Steve"));
        // 同一块泥土:没记号有限价,记了"玩家放的"就 INF——翻成 INF 的只是记号
        assertTrue(MovementHelper.getMiningDurationTicks(
                context(v, LongSets.emptySet(), NATURAL, new PlacedBlocks()),
                wall.getX(), wall.getY(), wall.getZ(), false) < COST_INF);
        assertTrue(MovementHelper.getMiningDurationTicks(
                context(v, LongSets.emptySet(), NATURAL, placed),
                wall.getX(), wall.getY(), wall.getZ(), false) >= COST_INF,
                "玩家放的格在 NATURAL 下应计 INF");
        CalculationContext any = context(v, LongSets.emptySet(), ANY, placed);
        double consent = MovementHelper.getMiningDurationTicks(any,
                wall.getX(), wall.getY(), wall.getZ(), false);
        assertTrue(consent > 0 && consent < COST_INF, "ANY 下玩家放的格应有限价,实为 " + consent);
        assertTrue(com.dwinovo.numen.core.pathing.execute.TerrainBill.planned(throughCell(wall), v, any.gate)
                .consentItems().get(0).cause().contains("placed by a player"));
        // 挑挖什么的剪枝不问权限:同一块玩家放的泥土挖得动
        assertTrue(MovementHelper.getUnpricedMiningDurationTicks(context(v, LongSets.emptySet(), NATURAL, placed),
                wall.getX(), wall.getY(), wall.getZ(), v.getBlockState(wall), false) < COST_INF);
    }

    @Test
    void factoryDoorRuleHoldsInfinite() {
        // 出厂表 break(#minecraft:doors)。标签内容运行时来自数据包,无头引导不加载数据包,
        // 所以手动把石头绑进门标签——同 ThrowawayTagTestSupport 的路子,钉的是"标签行生效"。
        BlockPos stone = SRC.north();
        FakeView v = floored();
        v.set(stone, Blocks.STONE.defaultBlockState());
        bindBlockTags(java.util.Map.of(net.minecraft.tags.BlockTags.DOORS,
                java.util.List.of(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .wrapAsHolder(Blocks.STONE))));
        try {
            assertTrue(MovementHelper.getMiningDurationTicks(
                    context(v, LongSets.emptySet()),
                    stone.getX(), stone.getY(), stone.getZ(), false) >= COST_INF,
                    "出厂表点名的标签成员应计 INF");
        } finally {
            bindBlockTags(java.util.Map.of());   // 回到无头引导的原态:方块标签全空
        }
    }

    /** 原版数据包加载走的同一条 bindTags 路,生产代码不为测试开口子。 */
    @SuppressWarnings("unchecked")
    private static void bindBlockTags(java.util.Map<net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block>,
            java.util.List<net.minecraft.core.Holder<net.minecraft.world.level.block.Block>>> tags) {
        ((net.minecraft.core.MappedRegistry<net.minecraft.world.level.block.Block>)
                net.minecraft.core.registries.BuiltInRegistries.BLOCK).bindTags(tags);
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

    // ==================== 路线规格:不改地形时挖与放处处 INF ====================

    @Test
    void preservingSpecMakesEveryBreakAndPlaceInfinite() {
        BlockPos dirt = SRC.north();
        FakeView v = floored();
        v.set(dirt, Blocks.DIRT.defaultBlockState());
        CalculationContext preserve = new CalculationContext(player, v, ChunkLoadedTest.ALWAYS,
                false, RouteSpec.defaults(), new Gate(null, Mode.ASK, RuleSet.EMPTY, RuleSet.factory(), new PlacedBlocks(), java.util.List.of()));
        // 同一块泥土,NATURAL 有限价(见上),NONE 无限价——翻成 INF 的只是规格的 alter
        assertTrue(MovementHelper.getMiningDurationTicks(preserve,
                dirt.getX(), dirt.getY(), dirt.getZ(), false) >= COST_INF);
        BlockPos cell = SRC.north().above();
        assertEquals(COST_INF, preserve.costOfPlacingAt(
                cell.getX(), cell.getY(), cell.getZ(), v.getBlockState(cell)));
        assertFalse(preserve.allowBreak);
        assertFalse(preserve.hasThrowaway);
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
        // 规格按位置只禁放的格不可再规划放置
        CalculationContext deniedCtx = new CalculationContext(player, v, ChunkLoadedTest.ALWAYS,
                false, NATURAL.withPositions(PositionCosts.builder()
                        .place(cell.asLong(), COST_INF).build()),
                new Gate(null, Mode.ASK, RuleSet.EMPTY, RuleSet.factory(), new PlacedBlocks(), java.util.List.of()));
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
