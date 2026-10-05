package com.dwinovo.numen.core.task.move;

import java.util.List;

import com.dwinovo.numen.sdk.Place;
import com.dwinovo.numen.core.nav.NavText;
import com.dwinovo.numen.permission.Listing;

import net.minecraft.core.BlockPos;

/**
 * 一站这一趟走不通时({@link Destination} 编不成目标)的提醒,只有这里写字:规划把它写进那一段的为什么,{@code numen.route.plan} 的计划
 * 里照抄。每一句说事实,再给能照抄的写法;不替她改成别的意思,也不去搜索。判断由 {@link Destination} 问寻路模块,这里只把结论写成话。
 */
public final class GotoReminders {

    private GotoReminders() {}

    /** 一句能照抄的去那一格({@link NavText#gotoCall}),{@code options} 是描述里的那几项,可以为空。 */
    public static String call(BlockPos pos, String options) {
        return NavText.gotoCall(Place.cell(pos), options);
    }

    /**
     * arrive = "at" 指向一格占着的方块:站不进去。给出用它、站上去、停在附近、挖进去四种写法;{@code top} 是站在它上面时脚所在的那一格
     * (模型拿方块的坐标想站上去,照抄这一格就行),站不上去为 null,那就不给这一种。
     */
    public static String occupied(BlockPos pos, String block, BlockPos top) {
        return Listing.coords(pos) + " is " + block + " — no room to stand in it, and this walk changes nothing."
                + " To use it: " + call(pos, "arrive = \"use\"")
                + (top == null ? "" : "; to stand on top of it: " + call(top, ""))
                + "; to stop close by: " + call(pos, "arrive = \"near\"")
                + "; to dig into it instead, add costs = {dig = true}.";
    }

    /**
     * arrive = "at" 指向半空:脚下没东西托。{@code ground} 是那一列里往下第一个站得住的节点,找不到为 null。
     */
    public static String midAir(BlockPos pos, BlockPos ground) {
        String there = ground == null ? "" : " (the ground in that column is at y=" + ground.getY() + ": "
                + call(ground, "") + ")";
        return Listing.coords(pos) + " is in mid-air — nothing to stand on there" + there
                + ". Give {x = …, z = …} alone to go to that column; to pillar up to it, add costs = {place = true}.";
    }

    /** arrive = "use" 指向没有可点的轮廓的格:空气、流体。 */
    public static String nothingToUse(BlockPos pos, String block) {
        return Listing.coords(pos) + " is " + block + " — nothing there to click. To get close: "
                + call(pos, "arrive = \"near\"") + ".";
    }

    /** arrive = "dig" 指向没有轮廓的格:空气、流体,没有可挖的。 */
    public static String nothingToDig(BlockPos pos, String block) {
        return Listing.coords(pos) + " is " + block + " — nothing there to dig. To get close: "
                + call(pos, "arrive = \"near\"") + ".";
    }

    /** arrive = "dig" 指向的方块站到哪儿都挖不成:物理上挖不动,或规则不许。{@code why} 是挖它被拒的缘由。 */
    public static String cantDig(BlockPos pos, String block, String why) {
        return block + " at " + Listing.coords(pos) + " can't be dug: " + why;
    }

    /** 封着一面的那一格:哪一面、面前是什么、在哪。 */
    public record Cover(String face, String block, BlockPos at) {}

    /**
     * arrive = "use" 指向的方块四面封死:每一面前面是什么;离她最近的那一面排在最前,给出挖开它的写法。
     */
    public static String sealed(BlockPos pos, String block, List<Cover> covers) {
        StringBuilder sb = new StringBuilder(Listing.coords(pos)).append(" (").append(block)
                .append(") is walled in on every side — no face is open to see or click:");
        for (Cover c : covers) {
            sb.append(' ').append(c.face()).append(' ').append(c.block()).append(" at ").append(Listing.coords(c.at()))
                    .append(',');
        }
        sb.setLength(sb.length() - 1);
        Cover nearest = covers.get(0);
        return sb.append(". Dig one of them open — the ").append(nearest.face()).append(" one is nearest me: ")
                .append(call(nearest.at(), "arrive = \"dig\"")).append(", then `numen.work.dig(")
                .append(com.dwinovo.numen.sdk.LuaCodecs.literal(nearest.at())).append(")` — then ").append(call(pos, "arrive = \"use\""))
                .append(" again.").toString();
    }

    /** arrive = "use" 指向的方块有敞开的面,可够得着的地方一处也站不了人。 */
    public static String nowhereToStand(BlockPos pos, String block, List<String> openFaces) {
        return Listing.coords(pos) + " (" + block + ") is open on " + String.join(", ", openFaces)
                + ", but there is nowhere within reach to stand and see one of those faces. To get as close as I can: "
                + call(pos, "arrive = \"near\"") + ".";
    }

    // ==================== 去一堆格子 ====================

    /** arrive = "at" 一堆格子,一格也站不了人,而这一趟不改地形。 */
    public static String cellsNowhereToStand(int cells) {
        return "none of the " + cells + " cell(s) is somewhere to stand, and this walk changes nothing. To stop close "
                + "by: arrive = \"near\"; to dig or pillar into them, add costs = {dig = true, place = true}.";
    }

    /** arrive = "use" 一堆格子,没有一格有可点的轮廓。 */
    public static String cellsNothingToUse(int cells) {
        return "none of the " + cells + " cell(s) holds a block to click — they are air or fluid. To get close: "
                + "arrive = \"near\".";
    }

    /**
     * arrive = "dig" 一堆格子,有方块却一格也挖不成(物理上挖不动、规则不许、每一面都贴着清不掉的方块);{@code why} 是离她最近那一格的
     * 缘由。
     */
    public static String cellsNoneDiggable(int cells, String why) {
        return "none of the " + cells + " cell(s) can be dug; the nearest: " + why + ".";
    }

    /** arrive = "dig" 一堆格子,没有一格有可挖的方块。 */
    public static String cellsNothingToDig(int cells) {
        return "none of the " + cells + " cell(s) holds a block to dig — they are air or fluid. To get close: "
                + "arrive = \"near\".";
    }

    /**
     * arrive = "use" 一堆格子,离她最近的那几个能点的方块一个也用不上:四面封死,或够得着的地方一处也站不了。{@code first} 是其中
     * 离她最近的那一个,单独去用它的那一句会说清是什么挡着。
     */
    public static String cellsNoneUsable(int tried, BlockPos first) {
        return "none of the " + tried + " block(s) nearest me can be used: each is walled in on every side or has "
                + "nowhere within reach to stand and see it. " + call(first, "arrive = \"use\"")
                + " says what is in the way of the nearest one; to get as close as I can: arrive = \"near\".";
    }
}
