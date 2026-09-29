package com.dwinovo.numen.core.task.mine;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import com.dwinovo.numen.core.nav.WorkArea;

import net.minecraft.core.BlockPos;

/**
 * 工作区外还有的目标:mine 只报告、不去。回执里说它们的那一句——还有几个、最近一个在哪、离她多远,以及照着就能做的
 * 下一步——只在这里写;受理时点名的团整个落在区外、当场拒收的那句话也在这里。
 *
 * @param cells   区外的格:找方块的查询带回来的,或点名的团里落在区外的
 * @param atLeast 查询凑够要的个数就停了,区外可能还有更多
 */
public record Beyond(List<BlockPos> cells, boolean atLeast) {

    /** 区外什么都没看见。 */
    public static final Beyond NONE = new Beyond(List.of(), false);

    /**
     * 下一步里 {@code move_goto} 的 {@code near}:走到离那一格这么近就算到了。落脚宽松(挖进石头里的矿不必站到跟前),
     * 到了之后以脚下为中心的工作区照样盖得住它和它身边的一片。
     */
    static final int NEAR = 8;

    public Beyond {
        cells = List.copyOf(cells);
    }

    public boolean isEmpty() {
        return cells.isEmpty();
    }

    /** 只留还满足 {@code still} 的格(说的时候还在那儿的)。 */
    public Beyond keep(Predicate<BlockPos> still) {
        return new Beyond(cells.stream().filter(still).toList(), atLeast);
    }

    /**
     * 找方块的用法:{@code 7 more lie beyond it, the nearest at 80,40,-10, about 69 blocks from me: move_goto there first
     * (x:80 y:40 z:-10 near:8), then work_mine again}。查询提前停了时说"至少"。
     *
     * @param from 她此刻脚下那一格
     */
    public String more(BlockPos from) {
        return (atLeast ? "at least " : "") + cells.size() + " more lie beyond it" + nearest(from);
    }

    /** 点名的用法:{@code 4 of the named cells lie beyond it and were left, the nearest at …: move_goto …}。 */
    public String named(BlockPos from) {
        return cells.size() + " of the named cells lie beyond it and were left" + nearest(from);
    }

    private String nearest(BlockPos from) {
        BlockPos near = cells.stream().min(Comparator.comparingDouble(from::distSqr)).orElseThrow();
        return ", the nearest at " + coords(near) + ", about " + blocks(from, near) + " blocks from me: "
                + goThere(near);
    }

    /**
     * 点名的团整个落在区外:受理当场拒收的那句话。
     *
     * @param ids    整个落在区外的团
     * @param others 还点名了别的团(有落在区里的)
     * @param area   她此刻的工作区
     * @param near   这些团里离她最近的一格
     */
    public static String groupsOutside(List<String> ids, boolean others, WorkArea area, BlockPos near) {
        boolean one = ids.size() == 1;
        return (one ? "group " + ids.get(0) + " lies" : "groups " + String.join(", ", ids) + " lie")
                + " wholly beyond my work area (" + area.describe() + "), so I did not start; the nearest of "
                + (one ? "its" : "their") + " cells is at " + coords(near) + ", about " + blocks(area.center(), near)
                + " blocks away. " + goThere(near) + " (group ids stay good until your next scan_blocks)"
                + (others ? "; or leave " + (one ? "it" : "them") + " out to dig the other groups from here." : ".");
    }

    /** 照着就能做的下一步:先走过去,再挖一次。 */
    static String goThere(BlockPos cell) {
        return "move_goto there first (x:" + cell.getX() + " y:" + cell.getY() + " z:" + cell.getZ() + " near:" + NEAR
                + "), then work_mine again";
    }

    private static String coords(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    private static long blocks(BlockPos from, BlockPos to) {
        return Math.round(Math.sqrt(from.distSqr(to)));
    }
}
