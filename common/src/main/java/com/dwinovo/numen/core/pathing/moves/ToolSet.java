package com.dwinovo.numen.core.pathing.moves;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.dwinovo.numen.core.pathing.settings.NavSettings;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 快捷栏工具评估:对任意方块给出"最优工具挖掘速度"(1/挖掘tick数)
 * 与最优槽位。结果按 Block 缓存,一次成本计算全程复用。
 */
public class ToolSet {

    /** 每种方块 → 最优工具挖掘速度的缓存。 */
    private final Map<Block, Double> breakStrengthCache;

    /** 缓存缺失时的计算函数(构造时折入药水修正,避免逐次判断)。 */
    private final Function<Block, Double> backendCalculation;

    private final ServerPlayer player;

    /** true 时功能方块保护乘数失效(强制破坏语义)。 */
    private final boolean ignoreBreakingProtection;

    public ToolSet(ServerPlayer player) {
        this(player, false);
    }

    public ToolSet(ServerPlayer player, boolean ignoreBreakingProtection) {
        this.breakStrengthCache = new HashMap<>();
        this.player = player;
        this.ignoreBreakingProtection = ignoreBreakingProtection;

        if (NavSettings.get().considerPotionEffects) {
            double amplifier = potionAmplifier();
            Function<Double, Double> amplify = x -> amplifier * x;
            backendCalculation = amplify.compose(this::getBestDestructionTime);
        } else {
            backendCalculation = this::getBestDestructionTime;
        }
    }

    /**
     * 用快捷栏最优工具挖该方块的速度(1/挖掘tick数)。
     * 不可破坏时为负值。
     */
    public double getStrVsBlock(BlockState state) {
        return breakStrengthCache.computeIfAbsent(state.getBlock(), backendCalculation);
    }

    /**
     * 工具材质的廉价度序:木0 石1 铁2 金3 钻4 下界合金5;
     * 非分级工具返回 -1。平速时优先用更廉价材质,省好工具。
     */
    private static int getMaterialCost(ItemStack itemStack) {
        if (!(itemStack.getItem() instanceof TieredItem tiered)) {
            return -1;
        }
        Tier tier = tiered.getTier();
        if (tier == Tiers.WOOD) return 0;
        if (tier == Tiers.STONE) return 1;
        if (tier == Tiers.IRON) return 2;
        if (tier == Tiers.GOLD) return 3;
        if (tier == Tiers.DIAMOND) return 4;
        if (tier == Tiers.NETHERITE) return 5;
        return -1;
    }

    /** 该物品是否带精准采集附魔。 */
    public boolean hasSilkTouch(ItemStack stack) {
        ItemEnchantments enchantments = stack.getEnchantments();
        for (Holder<Enchantment> enchant : enchantments.keySet()) {
            if (enchant.is(Enchantments.SILK_TOUCH) && enchantments.getLevel(enchant) > 0) {
                return true;
            }
        }
        return false;
    }

    public int getBestSlot(Block b, boolean preferSilkTouch) {
        return getBestSlot(b, preferSilkTouch, false);
    }

    /**
     * 快捷栏 9 格中挖该方块的最优槽位。速度最高者胜;平速时优先
     * 更廉价材质,{@code preferSilkTouch} 时优先精准采集。
     * autoTool 关闭且用于成本计算时直接返回当前手持槽
     * (让路径成本反映真实会用的那件工具)。
     */
    public int getBestSlot(Block b, boolean preferSilkTouch, boolean pathingCalculation) {
        NavSettings settings = NavSettings.get();
        if (!settings.autoTool && pathingCalculation) {
            return player.getInventory().selected;
        }

        int best = 0;
        double highestSpeed = Double.NEGATIVE_INFINITY;
        int lowestCost = Integer.MIN_VALUE;
        boolean bestSilkTouch = false;
        BlockState blockState = b.defaultBlockState();
        for (int i = 0; i < 9; i++) {
            ItemStack itemStack = player.getInventory().getItem(i);
            if (!settings.useSwordToMine && itemStack.getItem() instanceof SwordItem) {
                continue;
            }
            if (settings.itemSaver
                    && itemStack.getDamageValue() + settings.itemSaverThreshold >= itemStack.getMaxDamage()
                    && itemStack.getMaxDamage() > 1) {
                continue;
            }
            double speed = calculateSpeedVsBlock(itemStack, blockState);
            boolean silkTouch = hasSilkTouch(itemStack);
            if (speed > highestSpeed) {
                highestSpeed = speed;
                best = i;
                lowestCost = getMaterialCost(itemStack);
                bestSilkTouch = silkTouch;
            } else if (speed == highestSpeed) {
                int cost = getMaterialCost(itemStack);
                if ((cost < lowestCost && (silkTouch || !bestSilkTouch))
                        || (preferSilkTouch && !bestSilkTouch && silkTouch)) {
                    highestSpeed = speed;
                    best = i;
                    lowestCost = cost;
                    bestSilkTouch = silkTouch;
                }
            }
        }
        return best;
    }

    /** 用最优槽位工具挖该方块的速度(已乘保护方块修正)。 */
    private double getBestDestructionTime(Block b) {
        ItemStack stack = player.getInventory().getItem(getBestSlot(b, false, true));
        return calculateSpeedVsBlock(stack, b.defaultBlockState()) * avoidanceMultiplier(b);
    }

    /**
     * 受保护功能方块(工作台/熔炉/箱子等)速度乘 0.1,
     * 即挖掘成本 ×10;强制破坏语义下失效。
     */
    private double avoidanceMultiplier(Block b) {
        if (ignoreBreakingProtection) {
            return 1;
        }
        return NavSettings.get().blocksToAvoidBreaking().contains(b)
                ? NavSettings.get().avoidBreakingMultiplier : 1;
    }

    /**
     * 用指定物品挖指定方块的速度(1/挖掘tick数)。
     * hardness&lt;0(基岩类)返回 -1 表示不可破坏。
     * speed&gt;1 时叠加效率附魔的挖掘效率加成;除以 hardness 后,
     * 正确工具(或不需要正确工具)除以 30,错误工具除以 100。
     */
    public static double calculateSpeedVsBlock(ItemStack item, BlockState state) {
        float hardness;
        try {
            hardness = state.getDestroySpeed(null, null);
        } catch (NullPointerException npe) {
            // 取不到硬度的异类方块按不可破坏处理
            return -1;
        }
        if (hardness < 0) {
            return -1;
        }

        float speed = item.getDestroySpeed(state);
        if (speed > 1) {
            ItemEnchantments itemEnchantments = item.getEnchantments();
            OUTER:
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                List<EnchantmentAttributeEffect> effects =
                        enchant.value().getEffects(EnchantmentEffectComponents.ATTRIBUTES);
                for (EnchantmentAttributeEffect e : effects) {
                    if (e.attribute().is(Attributes.MINING_EFFICIENCY.unwrapKey().orElseThrow())) {
                        speed += e.amount().calculate(itemEnchantments.getLevel(enchant));
                        break OUTER;
                    }
                }
            }
        }

        speed /= hardness;
        if (!state.requiresCorrectToolForDrops() || (!item.isEmpty() && item.isCorrectToolForDrops(state))) {
            return speed / 30;
        } else {
            return speed / 100;
        }
    }

    /** 急迫 / 挖掘疲劳药水对挖掘速度的修正倍率。 */
    private double potionAmplifier() {
        double speed = 1;
        if (player.hasEffect(MobEffects.DIG_SPEED)) {
            speed *= 1 + (player.getEffect(MobEffects.DIG_SPEED).getAmplifier() + 1) * 0.2;
        }
        if (player.hasEffect(MobEffects.DIG_SLOWDOWN)) {
            switch (player.getEffect(MobEffects.DIG_SLOWDOWN).getAmplifier()) {
                case 0:
                    speed *= 0.3;
                    break;
                case 1:
                    speed *= 0.09;
                    break;
                case 2:
                    speed *= 0.0027; // 原版就是 0.0027,不是 0.027
                    break;
                default:
                    speed *= 0.00081;
                    break;
            }
        }
        return speed;
    }
}
