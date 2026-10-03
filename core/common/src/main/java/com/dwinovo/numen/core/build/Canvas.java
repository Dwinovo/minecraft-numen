package com.dwinovo.numen.core.build;

import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.dwinovo.numen.permission.PlacedBlocks;
import net.minecraft.core.BlockPos;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一串格要成的样子:每一格最终要成什么。后写覆盖先写——同一格写两次,留下后一笔。
 */
public final class Canvas {

    /**
     * 一串格至多几格。一栋正常房子五千到八千格;生存模式的盘料只盘交来的这一串,拆成几串交就是墙砌好了才发现屋顶的料不够,所以
     * 一栋房子要装得下。
     */
    public static final int MAX_CELLS = 16384;

    private final Map<Long, BuildTaskRecord.Target> cells = new LinkedHashMap<>();

    /**
     * 画一格:这一格原来画过的被盖掉。双格方块(门的下半、床脚)占两格,另一半那格原来画过的也被它盖掉——另一半由放置
     * 回调自己补出来,不进目标集;留着先前那一笔,施工时就会为了那一笔把刚装好的门拆掉。
     */
    public void put(BuildTaskRecord.Target target) {
        cells.remove(target.pos().asLong());
        BlockPos other = PlacedBlocks.otherHalfOf(target.pos(), target.desiredState());
        if (other != null) {
            cells.remove(other.asLong());
        }
        cells.put(target.pos().asLong(), target);
        if (cells.size() > MAX_CELLS) {
            throw new IllegalArgumentException("this comes to more than " + MAX_CELLS
                    + " cells; build it as several pieces");
        }
    }

    /** 画完的每一格,按最后一次画它的先后,摆在它们自己的世界坐标上。 */
    public Layout layout() {
        return Layout.of(List.copyOf(cells.values()), 0);
    }
}
