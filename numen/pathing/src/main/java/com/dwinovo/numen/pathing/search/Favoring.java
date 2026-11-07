package com.dwinovo.numen.pathing.search;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;

/**
 * 旧路打折:重新规划时,落在上一条路线节点上的步子价钱打个折,新路倾向沿旧路走,不为一点点差价来回换道。全模块只此一处。
 */
public final class Favoring {

    /** 旧路上的步子按这个比例计价。 */
    static final double DISCOUNT = 0.5;

    /** 没有旧路。 */
    public static final Favoring NONE = new Favoring(LongSets.EMPTY_SET);

    private final LongSet cells;

    private Favoring(LongSet cells) {
        this.cells = cells;
    }

    /** 沿着 {@code previous} 这条路打折。 */
    public static Favoring along(Route previous) {
        LongOpenHashSet cells = new LongOpenHashSet();
        for (BlockPos node : previous.nodes()) {
            cells.add(node.asLong());
        }
        return new Favoring(cells);
    }

    /**
     * 任何一步的价钱至少乘多少:估价是对没折扣的价钱算的下界,沿旧路打折之后要同乘这个数才还是下界(没有旧路是 1)。
     */
    double floor() {
        return cells.isEmpty() ? 1 : DISCOUNT;
    }

    /** 落到 {@code to}({@link BlockPos#asLong} 编码)的一步的价钱乘多少。 */
    double factor(long to) {
        return cells.contains(to) ? DISCOUNT : 1;
    }
}
