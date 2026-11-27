package com.dwinovo.numen.plugins.minecraft.task.move;

import com.dwinovo.numen.api.sdk.Place;
import com.dwinovo.numen.plugins.minecraft.nav.NavText;
import com.dwinovo.numen.api.permission.Listing;

import net.minecraft.core.BlockPos;

/**
 * 一个到达目标编不成去处时({@link Destination})的提醒,只有这里写字。每一句说事实,再给能照抄的写法;不替她改成别的意思,也不去
 * 搜索。判断由 {@link Destination} 问寻路模块,这里只把结论写成话。
 */
public final class GotoReminders {

    private GotoReminders() {}

    /** 靠近一格的写法:走到离它不超过两格的地方。 */
    private static String close(BlockPos pos) {
        return NavText.gotoCall(Place.cell(pos), Arrival.BESIDE);
    }

    /** 指向没有轮廓的格:空气、流体,没有可挖的。 */
    public static String nothingToDig(BlockPos pos, String block) {
        return Listing.coords(pos) + " is " + block + " — nothing there to dig. To get close: " + close(pos) + ".";
    }

    /** 指向的方块站到哪儿都挖不成:物理上挖不动,或规则不许。{@code why} 是挖它被拒的缘由。 */
    public static String cantDig(BlockPos pos, String block, String why) {
        return block + " at " + Listing.coords(pos) + " can't be dug: " + why;
    }

    // ==================== 去一堆格子 ====================

    /** 一堆格子,有方块却一格也挖不成(物理上挖不动、规则不许);{@code why} 是离她最近那一格的缘由。 */
    public static String cellsNoneDiggable(int cells, String why) {
        return "none of the " + cells + " cell(s) can be dug; the nearest: " + why + ".";
    }

    /** 一堆格子,没有一格有可挖的方块。 */
    public static String cellsNothingToDig(int cells) {
        return "none of the " + cells + " cell(s) holds a block to dig — they are air or fluid. To get close, give "
                + "`minecraft.move.walk` a distance.";
    }
}
