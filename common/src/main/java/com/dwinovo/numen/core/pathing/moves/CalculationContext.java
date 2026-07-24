package com.dwinovo.numen.core.pathing.moves;

import java.util.List;

import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.core.pathing.util.BlockHelper;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;

/**
 * 一次成本计算/搜索的世界视图与能力快照。构造时把设置、背包、附魔
 * 状态全部取样为 final 字段:同一次搜索里每条边用同一把尺,不会
 * 因中途改设置得到自相矛盾的路径。
 *
 * <p>世界读取走注入的 {@link BlockGetter} 视图 + {@link ChunkLoadedTest}
 * 谓词;两个语义开关:{@code sacred}(自身目标格,不可挖不可埋,
 * 不可穿透)、{@code deniedPlace}(执行层证明放不上的格)。
 */
public class CalculationContext {

    private static final ItemStack STACK_BUCKET_WATER = new ItemStack(Items.WATER_BUCKET);

    /** 视图是否可在 worker 线程安全读取(冻结快照 true,活世界 false)。 */
    public final boolean safeForThreadedUse;
    /**
     * 仅供主线程侧使用(执行期状态机、装配移动时存进 Movement 备用)。
     * 线程审计结论:成本计算路径(cost/apply/装配)不得解引用它读活
     * 状态——背包/附魔/饥饿/药水已在构造时折进本类与 {@link ToolSet}
     * 的 final 字段,世界边界由搜索器自行在构造时取样。
     */
    public final ServerPlayer player;
    public final BlockGetter view;
    public final ChunkLoadedTest loadedTest;
    public final ToolSet toolSet;
    /** 背包里是否有可垫路耗材(泥土/圆石/下界岩/石头)。 */
    public final boolean hasThrowaway;
    /** 快捷栏有水桶且不在下界。 */
    public final boolean hasWaterBucket;
    public final boolean canSprint;
    /** 放置一格的成本;经 {@link #costOfPlacingAt} 取用,勿直接读。 */
    protected final double placeBlockCost;
    public final boolean allowBreak;
    public final List<Block> allowBreakAnyway;
    public final boolean allowParkour;
    public final boolean allowParkourPlace;
    public final boolean allowJumpAtBuildLimit;
    public final boolean allowParkourAscend;
    public final boolean assumeWalkOnWater;
    /** 恒 false,占位保留(落岩浆永不可接受)。 */
    public final boolean allowFallIntoLava;
    /** 装备的霜行者附魔等级,0 为无。 */
    public final int frostWalker;
    public final boolean allowDiagonalDescend;
    public final boolean allowDiagonalAscend;
    public final boolean allowDownward;
    /** 坠落类移动的最小坠落高度。 */
    public int minFallHeight;
    public int maxFallHeightNoWater;
    public final int maxFallHeightBucket;
    /** 水中行走单格成本(水下速附魔按系数折向平走速度)。 */
    public final double waterWalkSpeed;
    public final double breakBlockAdditionalCost;
    public double backtrackCostFavoringCoefficient;
    public double jumpPenalty;
    public final double walkOnWaterOnePenalty;
    public final boolean allowPlaceInFluidsSource;
    public final boolean allowPlaceInFluidsFlow;

    /** 不可挖不可埋的自身目标格(BlockPos.asLong 键),不可穿透。 */
    public final LongSet sacred;
    /** 执行层证明无支撑放不上的格:放置成本直接 INF。 */
    public final LongSet deniedPlace;

    /** 世界可建高度下界(含)与上界(不含)。 */
    public final int worldBottom;
    public final int worldHeight;

    /**
     * 世界边界快照(构造时在主线程取样)。avoidBreaking 用它的
     * {@code canPlaceAt(x,z)} 拒绝在边界外挖方块。
     */
    public final WorldBorder worldBorder;

    /** 便捷构造:无语义开关的活世界上下文。 */
    public CalculationContext(ServerPlayer player, BlockGetter view, ChunkLoadedTest loadedTest,
                              boolean safeForThreadedUse) {
        this(player, view, loadedTest, safeForThreadedUse,
                LongSets.emptySet(), LongSets.emptySet());
    }

    public CalculationContext(ServerPlayer player, BlockGetter view, ChunkLoadedTest loadedTest,
                              boolean safeForThreadedUse,
                              LongSet sacred, LongSet deniedPlace) {
        NavSettings settings = NavSettings.get();
        this.safeForThreadedUse = safeForThreadedUse;
        this.player = player;
        this.view = view;
        this.loadedTest = loadedTest;
        this.sacred = sacred;
        this.deniedPlace = deniedPlace;
        this.toolSet = new ToolSet(player);
        this.hasThrowaway = settings.allowPlace && hasGenericThrowaway(player, settings);
        this.hasWaterBucket = settings.allowWaterBucketFall
                && hotbarHasWaterBucket(player)
                && player.level().dimension() != Level.NETHER;
        this.canSprint = settings.allowSprint && player.getFoodData().getFoodLevel() > 6;
        this.placeBlockCost = settings.blockPlacementPenalty;
        this.allowBreak = settings.allowBreak;
        this.allowBreakAnyway = List.copyOf(settings.allowBreakAnyway());
        this.allowParkour = settings.allowParkour;
        this.allowParkourPlace = settings.allowParkourPlace;
        this.allowJumpAtBuildLimit = settings.allowJumpAtBuildLimit;
        this.allowParkourAscend = settings.allowParkourAscend;
        this.assumeWalkOnWater = settings.assumeWalkOnWater;
        this.allowFallIntoLava = false;
        this.frostWalker = equipmentEnchantLevel(player);
        this.allowDiagonalDescend = settings.allowDiagonalDescend;
        this.allowDiagonalAscend = settings.allowDiagonalAscend;
        this.allowDownward = settings.allowDownward;
        this.minFallHeight = 3;
        this.maxFallHeightNoWater = settings.maxFallHeightNoWater;
        this.maxFallHeightBucket = settings.maxFallHeightBucket;
        this.waterWalkSpeed = computeWaterWalkSpeed(player);
        this.breakBlockAdditionalCost = settings.blockBreakAdditionalPenalty;
        this.backtrackCostFavoringCoefficient = settings.backtrackCostFavoringCoefficient;
        this.jumpPenalty = settings.jumpPenalty;
        this.walkOnWaterOnePenalty = settings.walkOnWaterOnePenalty;
        this.allowPlaceInFluidsSource = settings.allowPlaceInFluidsSource;
        this.allowPlaceInFluidsFlow = settings.allowPlaceInFluidsFlow;
        this.worldBottom = view.getMinY();
        this.worldHeight = view.getMaxY();
        WorldBorder border = null;
        if (player != null) {
            try {
                border = player.level() != null ? player.level().getWorldBorder() : null;
            } catch (NullPointerException ignored) {
                // 测试壳玩家无 level 字段:无世界边界,按"不限制"处理
            }
        }
        this.worldBorder = border;
    }

    /**
     * 是否持有可垫路耗材。查快捷栏(0-8)与副手;仅当
     * {@code allowInventory} 开启才查背包深处(9-35)。
     */
    private static boolean hasGenericThrowaway(ServerPlayer player, NavSettings settings) {
        List<net.minecraft.world.item.Item> acceptable = settings.acceptableThrowawayItems();
        var inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && acceptable.contains(stack.getItem())) {
                return true;
            }
        }
        ItemStack offhand = player.getItemBySlot(EquipmentSlot.OFFHAND);
        if (!offhand.isEmpty() && acceptable.contains(offhand.getItem())) {
            // 副手耗材要真能用出来,主手须能切到"右键无消费"的槽
            // (空手或带 TOOL 组件的挖掘工具),否则右键走主手放不出副手方块
            for (int i = 0; i < 9; i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty() || stack.getItem().components()
                        .has(net.minecraft.core.component.DataComponents.TOOL)) {
                    return true;
                }
            }
        }
        if (settings.allowInventory) {
            for (int i = 9; i < 36; i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && acceptable.contains(stack.getItem())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 快捷栏里是否有(物品与组件都相同的)水桶。 */
    private static boolean hotbarHasWaterBucket(ServerPlayer player) {
        return net.minecraft.world.entity.player.Inventory.isHotbarSlot(
                player.getInventory().findSlotMatchingItem(STACK_BUCKET_WATER));
    }

    /** 装备槽遍历顺序中最后一件带霜行者附魔的等级。 */
    private static int equipmentEnchantLevel(ServerPlayer player) {
        int level = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemEnchantments itemEnchantments = player.getItemBySlot(slot).getEnchantments();
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                if (enchant.is(Enchantments.FROST_WALKER)) {
                    level = itemEnchantments.getLevel(enchant);
                }
            }
        }
        return level;
    }

    /** 按装备的水下移动效率附魔,把水中步速在水速与平走速之间插值。 */
    private static double computeWaterWalkSpeed(ServerPlayer player) {
        float waterSpeedMultiplier = 1.0f;
        OUTER:
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemEnchantments itemEnchantments = player.getItemBySlot(slot).getEnchantments();
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                List<EnchantmentAttributeEffect> effects =
                        enchant.value().getEffects(EnchantmentEffectComponents.ATTRIBUTES);
                for (EnchantmentAttributeEffect effect : effects) {
                    if (effect.attribute().is(Attributes.WATER_MOVEMENT_EFFICIENCY.unwrapKey().orElseThrow())) {
                        waterSpeedMultiplier = effect.amount().calculate(itemEnchantments.getLevel(enchant));
                        break OUTER;
                    }
                }
            }
        }
        return ActionCosts.WALK_ONE_IN_WATER_COST * (1 - waterSpeedMultiplier)
                + ActionCosts.WALK_ONE_BLOCK_COST * waterSpeedMultiplier;
    }

    // ==================== 世界读取 ====================

    /** 单线程游标,省去每次读取的 BlockPos 分配(域回调只在一个线程跑)。 */
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public BlockState get(int x, int y, int z) {
        return view.getBlockState(cursor.set(x, y, z));
    }

    public BlockState get(BlockPos pos) {
        return view.getBlockState(pos);
    }

    public Block getBlock(int x, int y, int z) {
        return get(x, y, z).getBlock();
    }

    public boolean isLoaded(int x, int z) {
        return loadedTest.isLoaded(x, z);
    }

    // ==================== 成本函数 ====================

    /**
     * 在 (x,y,z) 放一个方块的成本。无耗材、sacred/denied 命中、
     * 贴着世界边界(边界格无法右键贴放)、流体规则不许 → INF;
     * 否则放置罚金。
     */
    public double costOfPlacingAt(int x, int y, int z, BlockState current) {
        if (!hasThrowaway) { // 构造时已含 allowPlace 判定
            return COST_INF;
        }
        long key = BlockPos.asLong(x, y, z);
        if (sacred.contains(key) || deniedPlace.contains(key)) {
            return COST_INF;
        }
        if (!MovementHelper.placeableWithinBorder(worldBorder, x, z)) {
            return COST_INF;
        }
        if (!allowPlaceInFluidsSource && current.getFluidState().isSource()) {
            return COST_INF;
        }
        if (!allowPlaceInFluidsFlow && !current.getFluidState().isEmpty()
                && !current.getFluidState().isSource()) {
            return COST_INF;
        }
        return placeBlockCost;
    }

    /** 挖掘保护判定的专用游标(与 {@link #cursor} 分开,免得互相踩)。 */
    private final BlockPos.MutableBlockPos protectionCursor = new BlockPos.MutableBlockPos();

    /**
     * 挖 (x,y,z) 的成本乘数。两层禁令,从严到宽:
     * <ol>
     *   <li>sacred(自身目标格)永远 INF,任何开关都不可穿透;</li>
     *   <li>do_not_break 标签成员(数据包追加的硬禁挖方块)直接 INF,
     *       任何开关都不可解除;</li>
     *   <li>总开关 {@code allowBreak} 关闭且不在例外清单 → INF。</li>
     * </ol>
     * 功能方块(工作台/熔炉/箱子等)的 ×10 软惩罚由 {@link ToolSet}
     * 的 {@code avoidanceMultiplier}(NavSettings.blocksToAvoidBreaking)
     * 在 {@code getStrVsBlock} 里实现,此处不参与。
     */
    public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
        if (sacred.contains(BlockPos.asLong(x, y, z))) {
            return COST_INF;
        }
        if (BlockHelper.shouldAvoidBreaking(view, protectionCursor.set(x, y, z))) {
            return COST_INF;
        }
        if (!allowBreak && !allowBreakAnyway.contains(current.getBlock())) {
            return COST_INF;
        }
        return 1;
    }

    /** 坠落中放水桶的成本(与放置罚金同价)。 */
    public double placeBucketCost() {
        return placeBlockCost;
    }
}
