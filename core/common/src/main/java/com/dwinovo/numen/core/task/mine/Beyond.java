package com.dwinovo.numen.core.task.mine;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import com.dwinovo.numen.core.nav.WorkArea;

import net.minecraft.core.BlockPos;

/**
 * 要挖的区域里落在工作区外的格:mine 只报告、不去。回执里说它们的那一句——还有几格、最近一格在哪、离她多远,以及照着就能做的
 * 下一步——只在这里写;受理时点名的区域整个落在区外、当场拒收的那句话也在这里。
 *
 * @param cells 区外的格:要挖的区域里落在工作区外、扫描过的格
 */
public record Beyond(List<BlockPos> cells) {

    /** 区外什么都没有。 */
    public static final Beyond NONE = new Beyond(List.of());

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
        return new Beyond(cells.stream().filter(still).toList());
    }

    /**
     * {@code 4 scanned cells of ores/g3 lie beyond it and were left, the nearest at 80,40,-10, about 69 blocks from me:
     * move_goto there first (x:80 y:40 z:-10 arrive:near near:8), then work_mine again}。
     *
     * @param area 点名的区域({@code ores/g3})
     * @param from 她此刻脚下那一格
     */
    public String told(String area, BlockPos from) {
        return cells.size() + " scanned cells of " + area + " lie beyond it and were left" + nearest(from);
    }

    private String nearest(BlockPos from) {
        BlockPos near = cells.stream().min(Comparator.comparingDouble(from::distSqr)).orElseThrow();
        return ", the nearest at " + coords(near) + ", about " + blocks(from, near) + " blocks from me: "
                + goThere(near);
    }

    /**
     * 点名的区域整个落在区外:受理当场拒收的那句话。
     *
     * @param name  点名的区域({@code ores/g3})
     * @param cells 它扫描过的格数
     * @param work  她此刻的工作区
     * @param near  区域里离工作区中心最近的一格
     */
    public static String areaOutside(String name, long cells, WorkArea work, BlockPos near) {
        return "all " + cells + " scanned cells of " + name + " lie wholly beyond my work area (" + work.describe()
                + "), so I did not start; the nearest is at " + coords(near) + ", about " + blocks(work.center(), near)
                + " blocks away. " + goThere(near) + ".";
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
