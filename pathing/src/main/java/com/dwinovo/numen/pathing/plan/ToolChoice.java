package com.dwinovo.numen.pathing.plan;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 挖一种方块用哪件工具:看整个主背包,挑挖得最快的那件;一样快时空手优先,其次是不耗耐久的,再其次槽位靠前的——不为了
 * 一样的速度磨损工具。规划按它定价,执行按它换工具,两边是同一个选择。
 *
 * <p>比较时按脚踏实地、眼睛不在水里算:水下与离地的减速对每件工具是同一个倍数,不改变谁最快。选择按方块状态缓存,
 * 多个线程同时查是安全的。
 */
public final class ToolChoice {

    /**
     * 一个选择。
     *
     * @param slot 主背包的槽位;{@link #BARE_HAND} 是空手
     */
    public record Pick(int slot, ItemStack tool) {

        public static final int BARE_HAND = -1;
    }

    private static final Pick HAND = new Pick(Pick.BARE_HAND, ItemStack.EMPTY);

    private final BodySnapshot body;
    private final List<ItemStack> inventory;
    /** 每个槽位的工具拿在手上时的挖掘效率属性值,下标同槽位。 */
    private final double[] efficiency;
    private final double handEfficiency;
    private final ConcurrentHashMap<BlockState, Pick> picks = new ConcurrentHashMap<>();

    public ToolChoice(BodySnapshot body) {
        this.body = body;
        this.inventory = body.inventory();
        this.efficiency = new double[inventory.size()];
        for (int slot = 0; slot < inventory.size(); slot++) {
            efficiency[slot] = DigTime.efficiency(body, inventory.get(slot));
        }
        this.handEfficiency = DigTime.efficiency(body, ItemStack.EMPTY);
    }

    /** 挖 {@code state} 用哪件。 */
    public Pick best(BlockState state) {
        return picks.computeIfAbsent(state, this::choose);
    }

    /** 用挑中的那件挖 {@code state} 要几刻,见 {@link DigTime#ticks}。 */
    public int ticks(BlockState state, boolean eyeInWater, boolean grounded) {
        Pick pick = best(state);
        double eff = pick.slot() == Pick.BARE_HAND ? handEfficiency : efficiency[pick.slot()];
        return DigTime.ticks(body, pick.tool(), eff, state, eyeInWater, grounded);
    }

    /**
     * 用挑中的那件挖掉 {@code state},手上一共要花几刻:挖到碎({@link #ticks}),加上碎了之后缓手的那几刻
     * ({@link DigTime#cooldown};生存模式一下就碎的不缓)。规划给挖一格定价按它。
     */
    public double handTicks(BlockState state, boolean eyeInWater, boolean grounded) {
        int ticks = ticks(state, eyeInWater, grounded);
        return (double) ticks + DigTime.cooldown(body.creative(), ticks == 1);
    }

    private Pick choose(BlockState state) {
        Pick best = HAND;
        int bestTicks = DigTime.ticks(body, ItemStack.EMPTY, handEfficiency, state, false, true);
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack tool = inventory.get(slot);
            if (tool.isEmpty()) {
                continue;
            }
            int ticks = DigTime.ticks(body, tool, efficiency[slot], state, false, true);
            // 严格更快才换;一样快时保留先到的,而先到的是空手,再是不耗耐久的物品
            if (ticks < bestTicks || (ticks == bestTicks && best.tool().isDamageableItem() && !tool.isDamageableItem())) {
                best = new Pick(slot, tool);
                bestTicks = ticks;
            }
        }
        return best;
    }
}
