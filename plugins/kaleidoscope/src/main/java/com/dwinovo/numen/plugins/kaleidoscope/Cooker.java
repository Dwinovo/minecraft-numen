package com.dwinovo.numen.plugins.kaleidoscope;

import com.dwinovo.numen.entity.NumenPlayer;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.PotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Map;

/**
 * 世界上的一格炊具。{@code kc_inspect} 读它,{@code kc_cook} 一刻一刻推它。
 *
 * <p>炒锅和汤锅的<b>全部</b>差别只在这条线的两个实现里:上面的工具与任务不知道谁要油谁要盖子。
 */
public interface Cooker {

    Cookware kind();

    BlockPos pos();

    /** {@code kc_inspect} 的答案:这一格此刻是什么样子。 */
    Map<String, Object> report();

    /**
     * 这口锅现在能不能接这道菜。
     *
     * <p>能就返回 null;接不了返回卡在哪那句话——锅里是别人的活、汤底不对、种类对不上。
     * 在动手之前问一次,免得把油倒进别人的锅里。
     */
    String cannotStart(Dish dish);

    /**
     * 往前推一刻:按当前阶段做该做的那一下。
     *
     * @param portions 与 {@link Dish#ingredients()} 一一对应的投料份数
     */
    Step advance(NumenPlayer cook, Dish dish, int[] portions);

    /**
     * 推一刻的结果。
     *
     * @param kind   这一刻走到哪一步
     * @param note   说给模型听的一句;{@link Kind#BLOCKED} 时就是卡在哪
     * @param plated 出锅的东西;没出锅时是空栈
     */
    record Step(Kind kind, String note, ItemStack plated) {

        public enum Kind {
            /** 还在做。 */
            WORKING,
            /** 这道菜出锅了。 */
            DONE,
            /** 出锅了,但不是点的那道菜:糊了,或者投料/翻炒没对上做成了黑暗料理。 */
            RUINED,
            /** 干不下去了:缺料、没火、没锅铲、锅被占。 */
            BLOCKED
        }

        static Step working(String note) {
            return new Step(Kind.WORKING, note, ItemStack.EMPTY);
        }

        static Step blocked(String why) {
            return new Step(Kind.BLOCKED, why, ItemStack.EMPTY);
        }

        static Step done(ItemStack plated, String note) {
            return new Step(Kind.DONE, note, plated);
        }

        static Step ruined(ItemStack plated, String note) {
            return new Step(Kind.RUINED, note, plated);
        }
    }

    /** 这一格上的炊具;不是炒锅也不是汤锅(含蒸笼这些还没接的)就返回 null。 */
    static Cooker at(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof PotBlockEntity pot) {
            return new PotCooker(level, pos, pot);
        }
        if (be instanceof StockpotBlockEntity stockpot) {
            return new StockpotCooker(level, pos, stockpot);
        }
        return null;
    }

    /** 回执里点名这一格用的写法。 */
    static String where(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
