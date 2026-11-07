package com.dwinovo.numen.task.move;

import java.util.List;

import com.dwinovo.numen.api.sdk.LuaCodecs;
import com.dwinovo.numen.api.sdk.Place;
import com.dwinovo.numen.nav.NavText;
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

    /** 指向没有可点的轮廓的格:空气、流体。 */
    public static String nothingToUse(BlockPos pos, String block) {
        return Listing.coords(pos) + " is " + block + " — nothing there to click. To get close: " + close(pos) + ".";
    }

    /** 指向没有轮廓的格:空气、流体,没有可挖的。 */
    public static String nothingToDig(BlockPos pos, String block) {
        return Listing.coords(pos) + " is " + block + " — nothing there to dig. To get close: " + close(pos) + ".";
    }

    /** 指向的方块站到哪儿都挖不成:物理上挖不动,或规则不许。{@code why} 是挖它被拒的缘由。 */
    public static String cantDig(BlockPos pos, String block, String why) {
        return block + " at " + Listing.coords(pos) + " can't be dug: " + why;
    }

    /** 封着一面的那一格:哪一面、面前是什么、在哪。 */
    public record Cover(String face, String block, BlockPos at) {}

    /** 指向的方块四面封死:每一面前面是什么;离她最近的那一面排在最前,给出挖开它的写法。 */
    public static String sealed(BlockPos pos, String block, List<Cover> covers) {
        StringBuilder sb = new StringBuilder(Listing.coords(pos)).append(" (").append(block)
                .append(") is walled in on every side — no face is open to see or click:");
        for (Cover c : covers) {
            sb.append(' ').append(c.face()).append(' ').append(c.block()).append(" at ").append(Listing.coords(c.at()))
                    .append(',');
        }
        sb.setLength(sb.length() - 1);
        Cover nearest = covers.get(0);
        return sb.append(". Dig one of them open — the ").append(nearest.face()).append(" one is nearest me: `numen.work.dig(")
                .append(LuaCodecs.literal(nearest.at())).append(")` — then use it again.").toString();
    }

    /** 指向的方块有敞开的面,可够得着的地方一处也站不了人。 */
    public static String nowhereToStand(BlockPos pos, String block, List<String> openFaces) {
        return Listing.coords(pos) + " (" + block + ") is open on " + String.join(", ", openFaces)
                + ", but there is nowhere within reach to stand and see one of those faces. To get as close as I can: "
                + close(pos) + ".";
    }

    // ==================== 去一堆格子 ====================

    /** 一堆格子,没有一格有可点的轮廓。 */
    public static String cellsNothingToUse(int cells) {
        return "none of the " + cells + " cell(s) holds a block to click — they are air or fluid. To get close, give "
                + "`numen.move.walk` a distance.";
    }

    /**
     * 一堆格子,有方块却一格也挖不成(物理上挖不动、规则不许、每一面都贴着清不掉的方块);{@code why} 是离她最近那一格的
     * 缘由。
     */
    public static String cellsNoneDiggable(int cells, String why) {
        return "none of the " + cells + " cell(s) can be dug; the nearest: " + why + ".";
    }

    /** 一堆格子,没有一格有可挖的方块。 */
    public static String cellsNothingToDig(int cells) {
        return "none of the " + cells + " cell(s) holds a block to dig — they are air or fluid. To get close, give "
                + "`numen.move.walk` a distance.";
    }

    /**
     * 一堆格子,离她最近的那几个能点的方块一个也用不上:四面封死,或够得着的地方一处也站不了。{@code first} 是其中
     * 离她最近的那一个,单独去用它的那一句会说清是什么挡着。
     */
    public static String cellsNoneUsable(int tried, BlockPos first) {
        return "none of the " + tried + " block(s) nearest me can be used: each is walled in on every side or has "
                + "nowhere within reach to stand and see it. `numen.use.block(" + LuaCodecs.literal(first)
                + ")` says what is in the way of the nearest one; to get as close as I can: `numen.move.walk` with a "
                + "distance.";
    }
}
