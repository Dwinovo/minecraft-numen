package com.dwinovo.numen.core.pathing.moves;

import java.util.List;

import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.core.pathing.settings.ScaffoldMaterials;
import com.dwinovo.numen.core.pathing.spec.CellClass;
import com.dwinovo.numen.core.pathing.spec.RouteSpec;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.Gate;
import com.dwinovo.numen.permission.Verdict;

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
 * 谓词;这次导航能做什么、每样多贵由 {@link RouteSpec} 说了算(能不能改地形、
 * 哪些格禁挖禁放、各项罚金),本类只把它与服主总开关、背包、附魔折成结论。
 * 这一格能不能挖、能不能放,只问权限层的 {@link Gate}——它是主线程取的快照,
 * 工作线程只读。
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
    /** 有没有她认可的垫路料可放({@link ScaffoldMaterials#available}),已与规格和总开关折在一起。 */
    public final boolean hasThrowaway;
    /** 快捷栏有水桶且不在下界。 */
    public final boolean hasWaterBucket;
    public final boolean canSprint;
    public final boolean allowBreak;
    public final List<Block> allowBreakAnyway;
    public final boolean allowJumpAtBuildLimit;
    /** 装备的霜行者附魔等级,0 为无。 */
    public final int frostWalker;
    /** 坠落类移动的最小坠落高度。 */
    public int minFallHeight;
    /** 无水可接受的最大坠落高度:规格给下限,摔不死的高度按当前血量放宽。 */
    public final int maxFallHeightNoWater;
    public final int maxFallHeightBucket;
    public final double fallDamageCostPerPoint;
    /** 水中行走单格成本(水下速附魔按系数折向平走速度)。 */
    public final double waterWalkSpeed;
    public double backtrackCostFavoringCoefficient;
    public final boolean allowPlaceInFluidsSource;
    public final boolean allowPlaceInFluidsFlow;
    public final boolean avoidUpdatingFallingBlocks;

    /** 这次导航的路线规格;{@link #allowBreak}/{@link #hasThrowaway}/{@link #canSprint} 已把它与总开关折在一起。 */
    public final RouteSpec spec;

    /** 权限层的裁决快照:挖某格、放某格先问它。 */
    public final Gate gate;

    /**
     * 需要主人同意的格子在 {@link RouteSpec.Alter#ANY} 下的代价乘数。要有限,ANY 才搜得到
     * 穿过它的路;要贵到任何长度相当的自然路线都胜出——十倍于自然挖掘,约等于绕行几十格。
     */
    public static final double CONSENT_COST_MULTIPLIER = 10.0;

    /** 世界可建高度下界(含)与上界(不含)。 */
    public final int worldBottom;
    public final int worldHeight;

    /**
     * 世界边界快照(构造时在主线程取样)。avoidBreaking 用它的
     * {@code canPlaceAt(x,z)} 拒绝在边界外挖方块。
     */
    public final WorldBorder worldBorder;

    public CalculationContext(ServerPlayer player, BlockGetter view, ChunkLoadedTest loadedTest,
                              boolean safeForThreadedUse, RouteSpec spec, Gate gate) {
        NavSettings settings = NavSettings.get();
        this.safeForThreadedUse = safeForThreadedUse;
        this.player = player;
        this.view = view;
        this.loadedTest = loadedTest;
        this.spec = spec;
        this.gate = gate;
        this.toolSet = new ToolSet(player);
        // 有没有料可垫与执行器切料问同一处(ScaffoldMaterials.available / source):免耗材画像(创造)
        // 清单不空就算有,执行时伸手进创造物品栏取清单里的料——不然空手创造同伴会挖坑出不来。
        // 规格与总开关同折:不改地形的路线没有耗材这回事,放置成本处处 INF
        this.hasThrowaway = spec.alter().mayAlter() && settings.allowPlace
                && ScaffoldMaterials.available(player);
        this.hasWaterBucket = settings.allowWaterBucketFall
                && hotbarHasWaterBucket(player)
                && player.level().dimension() != Level.NETHER;
        // 无饥饿画像(创造)不受饱食度门限——否则 food≤6 时被切创造会永久锁死疾跑
        this.canSprint = spec.sprint() && settings.allowSprint
                && (!com.dwinovo.numen.core.WorkProfile.of(player).hasHunger()
                        || player.getFoodData().getFoodLevel() > 6);
        this.allowBreak = spec.alter().mayAlter() && settings.allowBreak;
        this.allowBreakAnyway = List.copyOf(settings.allowBreakAnyway());
        this.allowJumpAtBuildLimit = settings.allowJumpAtBuildLimit;
        this.frostWalker = equipmentEnchantLevel(player);
        this.minFallHeight = 3;
        // 落差上限不写死:摔不死的高度都可以是路,只是疼。原版摔伤 = 高度-3(半心/格),
        // 按当前血量留 3 颗心(6 点)保命余量反推可承受高度;规格值为下限。
        int survivableFall = 3 + Math.max(0, (int) ((player.getHealth() - 6.0f) / 1.0f));
        this.maxFallHeightNoWater = Math.min(12,
                Math.max(spec.maxFallHeightNoWater(), survivableFall));
        this.maxFallHeightBucket = settings.maxFallHeightBucket;
        this.fallDamageCostPerPoint = settings.fallDamageCostPerPoint;
        this.waterWalkSpeed = computeWaterWalkSpeed(player);
        this.backtrackCostFavoringCoefficient = settings.backtrackCostFavoringCoefficient;
        this.allowPlaceInFluidsSource = settings.allowPlaceInFluidsSource;
        this.allowPlaceInFluidsFlow = settings.allowPlaceInFluidsFlow;
        this.avoidUpdatingFallingBlocks = settings.avoidUpdatingFallingBlocks;
        this.worldBottom = view.getMinBuildHeight();
        this.worldHeight = view.getMaxBuildHeight();
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

    // ==================== 格子判定(本视图 + 本规格) ====================

    public boolean canWalkThrough(int x, int y, int z) {
        return canWalkThrough(x, y, z, get(x, y, z));
    }

    public boolean canWalkThrough(int x, int y, int z, BlockState state) {
        return CellClass.canWalkThrough(view, loadedTest, x, y, z, state, spec);
    }

    public boolean canWalkOn(int x, int y, int z) {
        return canWalkOn(x, y, z, get(x, y, z));
    }

    public boolean canWalkOn(int x, int y, int z, BlockState state) {
        return CellClass.canWalkOn(view, loadedTest, x, y, z, state, spec);
    }

    // ==================== 成本函数 ====================

    /**
     * 在 (x,y,z) 放一个方块的成本。无耗材、规格按位置或按种类禁放、权限层不许、贴着世界边界
     * (边界格无法右键贴放)、流体规则不许 → INF;否则放置罚金加该格的位置代价,需要主人同意
     * 的格(只有 {@link RouteSpec.Alter#ANY} 走得到)再乘 {@link #CONSENT_COST_MULTIPLIER}。
     */
    public double costOfPlacingAt(int x, int y, int z, BlockState current) {
        if (!hasThrowaway) { // 构造时已含规格与 allowPlace 判定
            return COST_INF;
        }
        double positional = spec.positions().place(BlockPos.asLong(x, y, z));
        if (positional >= COST_INF) {
            return COST_INF;
        }
        if (spec.bans().placingInto().contains(current.getBlock())) {
            return COST_INF;
        }
        double permitted = permissionMultiplier(Action.place(new BlockPos(x, y, z), current, null));
        if (permitted >= COST_INF) {
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
        return spec.placeCost() * permitted + positional;
    }

    /**
     * 挖 (x,y,z) 的成本乘数:这次导航的规格允不允许({@link #terrainBreakMultiplierAt}),再乘权限层的
     * 定价({@link #permissionMultiplier}:放行 1;需要主人同意在 {@link RouteSpec.Alter#ANY} 下有限价、
     * 否则 INF;拒绝 INF)。
     */
    public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
        if (terrainBreakMultiplierAt(x, y, z, current) >= COST_INF) {
            return COST_INF;
        }
        return permissionMultiplier(Action.breakBlock(new BlockPos(x, y, z), current));
    }

    /**
     * 挖 (x,y,z) 在这次导航的规格与服主总开关之下做不做得到,不问权限层:规格按位置禁挖(导航自身
     * 目标格、工地格)、按种类禁挖(模型点名的方块)、规格不改地形或总开关 {@code allowBreak} 关闭
     * 且不在例外清单 → INF;否则 1。
     */
    public double terrainBreakMultiplierAt(int x, int y, int z, BlockState current) {
        if (spec.positions().dig(BlockPos.asLong(x, y, z)) >= COST_INF) {
            return COST_INF;
        }
        if (spec.bans().breaking().contains(current.getBlock())) {
            return COST_INF;
        }
        if (!allowBreak && !allowBreakAnyway.contains(current.getBlock())) {
            return COST_INF;
        }
        return 1;
    }

    /**
     * 权限层对一个动作的裁决折成代价乘数:放行 1;拒绝 INF;需要主人同意——规格是
     * {@link RouteSpec.Alter#ANY} 就 {@link #CONSENT_COST_MULTIPLIER}(算进路线,账单里单列),
     * 否则 INF(有别的路就不走这条)。成本模型只读裁决,不自判。
     */
    protected double permissionMultiplier(Action action) {
        Verdict verdict = gate.judge(action, view);
        if (verdict.allowed()) {
            return 1;
        }
        if (verdict.asks() && spec.alter() == RouteSpec.Alter.ANY) {
            return CONSENT_COST_MULTIPLIER;
        }
        return COST_INF;
    }

    /** 坠落中放水桶的成本(与放置罚金同价)。 */
    public double placeBucketCost() {
        return spec.placeCost();
    }
}
