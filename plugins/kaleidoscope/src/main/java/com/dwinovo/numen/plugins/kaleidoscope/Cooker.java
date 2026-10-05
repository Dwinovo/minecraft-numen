package com.dwinovo.numen.plugins.kaleidoscope;

import com.dwinovo.numen.entity.NumenPlayer;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.PotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;


/**
 * 世界上的一格炊具。{@code kaleidoscope.pot.inspect} 读它,{@code kaleidoscope.pot} 的几个动作各推它一步。
 *
 * <p>炒锅和汤锅的<b>全部</b>差别只在这条线的两个实现里:一口锅不做的那一步(炒锅不放汤底、汤锅不翻炒)如实说不做。
 * 先做哪一步、做完一步做下一步是 Lua 模块 {@code kaleidoscope.pot.cook} 的事;锅自己走的那几段时间(下料窗口、
 * 翻炒、出锅窗口、慢炖)由锅的方块实体走,翻炒那一段手要跟着,所以 {@link #stir} 一刻一刻地跟到炒好。
 */
public interface Cooker {

    Cookware kind();

    BlockPos pos();

    /** {@code kaleidoscope.pot.inspect} 的答案:这一格此刻是什么样子。 */
    KaleidoscopeApi.PotState report();

    /** 倒油(炒锅):开出下料窗口。 */
    Step oil(NumenPlayer cook);

    /** 放这道菜的汤底(汤锅)。 */
    Step base(NumenPlayer cook, Dish dish);

    /**
     * 下料:一刻放一份,按 {@code portions} 放齐为止。
     *
     * @param portions 与 {@link Dish#ingredients()} 一一对应的投料份数
     */
    Step fill(NumenPlayer cook, Dish dish, int[] portions);

    /** 盖上或揭开盖子(汤锅):盖着就揭,揭着就盖;料在锅里时盖上就开炖。 */
    Step lid(NumenPlayer cook);

    /** 翻炒(炒锅):这一刻该挥锅铲就挥一下,起锅那一下也是它;炒好了是 {@link Step.Kind#DONE}。 */
    Step stir(NumenPlayer cook);

    /** 出锅装盘:点的那道菜是 {@link Step.Kind#DONE},糊了或做砸了照样端出来、是 {@link Step.Kind#RUINED}。 */
    Step plate(NumenPlayer cook, Dish dish);

    /**
     * 推一刻的结果。
     *
     * @param kind   这一刻走到哪一步
     * @param note   说给模型听的一句;{@link Kind#BLOCKED} 时就是卡在哪
     * @param plated 出锅的东西;没出锅时是空栈
     */
    record Step(Kind kind, String note, ItemStack plated) {

        public enum Kind {
            /** 这一步还没做完。 */
            WORKING,
            /** 这一步做完了;出锅那一步是点的那道菜出锅了。 */
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

        static Step done(String note) {
            return new Step(Kind.DONE, note, ItemStack.EMPTY);
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
