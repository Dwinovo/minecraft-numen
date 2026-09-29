package com.dwinovo.numen.core.task.mine;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import com.dwinovo.numen.core.nav.WorkArea;

import net.minecraft.core.BlockPos;

/**
 * 工作区外还有的目标:mine 只报告、不去。回执里说它们的那一句——还有几个、最近一个在哪、离她多远,以及照着就能做的
 * 下一步——只在这里写;受理时点名的区域整个落在区外、当场拒收的那句话也在这里。
 *
 * @param cells   区外的格:要挖的区域里落在工作区外、扫描过的格
 * @param atLeast 简写先看的那一次没看全(节数或收集上限截断),区外可能还有更多
 */
public record Beyond(List<BlockPos> cells, boolean atLeast) {

    /** 区外什么都没看见。 */
    public static final Beyond NONE = new Beyond(List.of(), false);

    /**
     * 下一步里 {@code move_goto} 的 {@code arrive:near} 用的 {@code near}:走到离那一格这么近就算到了。落脚宽松(挖进石头里的矿不必站到跟前),
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
     * 简写的用法:{@code 7 more lie beyond it, the nearest at 80,40,-10, about 69 blocks from me: move_goto there first
     * (x:80 y:40 z:-10 arrive:near near:8), then work_mine again}。查询提前停了时说"至少"。
     *
     * @param from 她此刻脚下那一格
     */
    public String more(BlockPos from) {
        return (atLeast ? "at least " : "") + cells.size() + " more lie beyond it" + nearest(from);
    }

    /** 点名区域的用法:{@code 4 of the named cells lie beyond it and were left, the nearest at …: move_goto …}。 */
    public String named(BlockPos from) {
        return cells.size() + " of the named cells lie beyond it and were left" + nearest(from);
    }

    private String nearest(BlockPos from) {
        BlockPos near = cells.stream().min(Comparator.comparingDouble(from::distSqr)).orElseThrow();
        return ", the nearest at " + coords(near) + ", about " + blocks(from, near) + " blocks from me: "
                + goThere(near);
    }

    /**
     * 点名的区域整个落在区外:受理当场拒收的那句话。
     *
     * @param name 点名的区域({@code ores/g3})
     * @param work 她此刻的工作区
     * @param near 区域里离工作区中心最近的一格
     */
    public static String areaOutside(String name, WorkArea work, BlockPos near) {
        return name + " lies wholly beyond my work area (" + work.describe() + "), so I did not start; the nearest of "
                + "its scanned cells is at " + coords(near) + ", about " + blocks(work.center(), near) + " blocks away. "
                + goThere(near) + ".";
    }

    /** 照着就能做的下一步:先走过去,再挖一次。 */
    static String goThere(BlockPos cell) {
        return "move_goto there first (x:" + cell.getX() + " y:" + cell.getY() + " z:" + cell.getZ() + " arrive:near near:"
                + NEAR + "), then work_mine again";
    }

    private static String coords(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    private static long blocks(BlockPos from, BlockPos to) {
        return Math.round(Math.sqrt(from.distSqr(to)));
    }
}
