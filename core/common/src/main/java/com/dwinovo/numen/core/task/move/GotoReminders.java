package com.dwinovo.numen.core.task.move;

import java.util.List;

import com.dwinovo.numen.core.nav.NavText;
import com.dwinovo.numen.permission.Listing;

import net.minecraft.core.BlockPos;

/**
 * {@code move_goto} 写错时受理当场给的提醒,只有这里写字。每一句说事实,再给能照抄的写法;不替她改成别的意思,也不去搜索。
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

    /** arrive:on、arrive:use 只给了 x、z。 */
    public static String blockNeedsY(String arrive) {
        return "arrive:" + arrive + " names one block — give its y too (x, y and z).";
    }

    /** 只给了高度,却写了别的到达方式。 */
    public static String heightTakesNoArrive(String arrive) {
        return "y alone means a height to climb or descend to; arrive:" + arrive
                + " needs a place (x and z, or x, y and z).";
    }

    /**
     * arrive:at 指向一格占着的方块:站不进去。给出用它、站上去、停在附近、挖进去四种写法。
     */
    public static String occupied(BlockPos pos, String block) {
        return Listing.coords(pos) + " is " + block + " — no room to stand in it, and this walk changes nothing."
                + " To use it: " + call(pos, "arrive:use") + "; to stand on top of it: " + call(pos, "arrive:on")
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

    /** arrive:on 指向没有碰撞箱的格:空气、草、水。 */
    public static String nothingToStandOn(BlockPos pos, String block) {
        return Listing.coords(pos) + " is " + block + " — nothing to stand on. To go to that cell: " + call(pos, "")
                + ".";
    }

    /** arrive:on 指向的方块站不上去:上面压着东西。 */
    public static String noRoomOnTop(BlockPos pos, String block) {
        return "there is no room to stand on the " + block + " at " + Listing.coords(pos)
                + " (something above it is in the way) and this walk changes nothing. To clear it, add alter:natural;"
                + " to stop close by: " + call(pos, "arrive:near near:<blocks>") + ".";
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
}
