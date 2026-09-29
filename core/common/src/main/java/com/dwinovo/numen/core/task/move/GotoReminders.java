package com.dwinovo.numen.core.task.move;

import java.util.List;

import com.dwinovo.numen.area.AreaRef;
import com.dwinovo.numen.core.nav.NavText;
import com.dwinovo.numen.permission.Listing;

import net.minecraft.core.BlockPos;

/**
 * {@code move_goto} 与路线的去处写错时受理当场给的提醒,只有这里写字。每一句说事实,再给能照抄的写法;不替她改成别的意思,也不去搜索。
 * 判断是不是写错由 {@link Destination} 问寻路模块,这里只把结论写成话。
 */
public final class GotoReminders {

    private GotoReminders() {}

    /** 一句能照抄的 move_goto({@link NavText#gotoCall})。 */
    public static String call(BlockPos pos, String rest) {
        return NavText.gotoCall(pos, rest);
    }

    /** 给了 near 却没写 arrive:near。 */
    public static String nearWithoutArriveNear(int near) {
        return "near:" + near + " only goes with arrive:near — write arrive:near near:" + near
                + " to stop within " + near + " blocks; without it arrival is exact.";
    }

    /** 写了 arrive:near 却没给 near。 */
    public static String arriveNearWithoutNear() {
        return "arrive:near needs near:<blocks> — how close counts as there (1-16).";
    }

    /** arrive:use 只给了 x、z。 */
    public static String blockNeedsY(String arrive) {
        return "arrive:" + arrive + " names one block — give its y too (x, y and z).";
    }

    /** 只给了高度,却写了别的到达方式。 */
    public static String heightTakesNoArrive(String arrive) {
        return "y alone means a height to climb or descend to; arrive:" + arrive
                + " needs a place (x and z, or x, y and z).";
    }

    /**
     * arrive:at 指向一格占着的方块:站不进去。给出用它、站上去、停在附近、挖进去四种写法;{@code top} 是站在它上面时脚所在的那一格
     * (模型拿方块的坐标想站上去,照抄这一格就行),站不上去为 null,那就不给这一种。
     */
    public static String occupied(BlockPos pos, String block, BlockPos top) {
        return Listing.coords(pos) + " is " + block + " — no room to stand in it, and this walk changes nothing."
                + " To use it: " + call(pos, "arrive:use")
                + (top == null ? "" : "; to stand on top of it: " + call(top, ""))
                + "; to stop close by: " + call(pos, "arrive:near near:<blocks>")
                + "; to dig into it instead, add alter:natural.";
    }

    /**
     * arrive:at 指向半空:脚下没东西托。{@code ground} 是那一列里往下第一个站得住的节点,找不到为 null。
     */
    public static String midAir(BlockPos pos, BlockPos ground) {
        String there = ground == null ? "" : " (the ground in that column is at y=" + ground.getY() + ": "
                + call(ground, "") + ")";
        return Listing.coords(pos) + " is in mid-air — nothing to stand on there" + there
                + ". Omit y to go to that column; to pillar up to it, add alter:natural.";
    }

    /** arrive:use 指向没有可点的轮廓的格:空气、流体。 */
    public static String nothingToUse(BlockPos pos, String block) {
        return Listing.coords(pos) + " is " + block + " — nothing there to click. To get close: "
                + call(pos, "arrive:near near:<blocks>") + ".";
    }

    /** 封着一面的那一格:哪一面、面前是什么、在哪。 */
    public record Cover(String face, String block, BlockPos at) {}

    /**
     * arrive:use 指向的方块四面封死:每一面前面是什么;离她最近的那一面排在最前,给出挖开它的写法。
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
                .append("`use block left ").append(nearest.at().getX()).append(' ').append(nearest.at().getY())
                .append(' ').append(nearest.at().getZ()).append("` once in reach — then ").append(call(pos, "arrive:use"))
                .append(" again.").toString();
    }

    /** arrive:use 指向的方块有敞开的面,可够得着的地方一处也站不了人。 */
    public static String nowhereToStand(BlockPos pos, String block, List<String> openFaces) {
        return Listing.coords(pos) + " (" + block + ") is open on " + String.join(", ", openFaces)
                + ", but there is nowhere within reach to stand and see one of those faces. To get as close as I can: "
                + call(pos, "arrive:near near:<blocks>") + ".";
    }

    // ==================== 去一块区域 ====================

    /** 去一块区域的那一句 move_goto({@link NavText#gotoCall})。 */
    public static String call(AreaRef area, String rest) {
        return NavText.gotoCall(area, rest);
    }

    /** 区域里还没有一格。 */
    public static String emptyArea(AreaRef area) {
        return "area " + area + " has no cells yet, so there is nowhere in it to go; `area add " + area.name()
                + " --box <x1,y1,z1..x2,y2,z2>` or `scan blocks <radius> <block ids> --into " + area.name()
                + "` fills it.";
    }

    /** "它离我最近的那几格":看了整块就说整块。 */
    private static String nearest(AreaRef area, int looked, long cells) {
        return looked == cells ? "the " + cells + " cell(s) of area " + area
                : "the " + looked + " cells of area " + area + " nearest me (of " + cells + ")";
    }

    /** arrive:at 一块区域,离她最近的那一部分里一格也站不了人,而这一趟不改地形。 */
    public static String areaNowhereToStand(AreaRef area, int looked, long cells) {
        return "none of " + nearest(area, looked, cells) + " is somewhere to stand, and this walk changes nothing. "
                + "To stop close by: " + call(area, "arrive:near near:<blocks>")
                + "; to dig or pillar into it, add alter:natural.";
    }

    /** arrive:use 一块区域,离她最近的那一部分里没有一格有可点的轮廓。 */
    public static String areaNothingToUse(AreaRef area, int looked, long cells) {
        return "none of " + nearest(area, looked, cells) + " holds a block to click — they are air or fluid. "
                + "To get close: " + call(area, "arrive:near near:<blocks>") + ".";
    }

    /**
     * arrive:use 一块区域,离她最近的那几个能点的方块一个也用不上:四面封死,或够得着的地方一处也站不了。{@code first} 是其中
     * 离她最近的那一个,单独去用它的那一句会说清是什么挡着。
     */
    public static String areaNoneUsable(AreaRef area, int tried, BlockPos first) {
        return "none of the " + tried + " block(s) of area " + area + " nearest me can be used: each is walled in on "
                + "every side or has nowhere within reach to stand and see it. " + call(first, "arrive:use")
                + " says what is in the way of the nearest one; to get as close as I can: "
                + call(area, "arrive:near near:<blocks>") + ".";
    }
}
