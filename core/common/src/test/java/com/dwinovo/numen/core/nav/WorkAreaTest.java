package com.dwinovo.numen.core.nav;

import com.dwinovo.numen.pathing.search.WorldSnapshot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunkSection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工作区:哪些格在区里,以及它为什么放得进寻路一次看得清的范围。 */
class WorkAreaTest {

    private static final BlockPos CENTER = new BlockPos(0, 64, 0);
    private static final int R = WorkArea.RADIUS;

    @Test
    void theAreaIsABallCountedInWholeCells() {
        WorkArea area = new WorkArea(CENTER, R);
        assertTrue(area.contains(CENTER));
        assertTrue(area.contains(CENTER.east(R)), "半径上的那一格算在区里");
        assertFalse(area.contains(CENTER.east(R + 1)));
        assertTrue(area.contains(CENTER.below(R)), "竖直方向同一个半径");
        assertFalse(area.contains(CENTER.above(R + 1)));
        // 斜着看是球,不是方块:两轴各走 0.71 R 就到边
        int diagonal = (int) Math.floor(R / Math.sqrt(2));
        assertTrue(area.contains(CENTER.offset(diagonal, 0, diagonal)));
        assertFalse(area.contains(CENTER.offset(diagonal + 1, 0, diagonal + 1)));
        assertEquals("within " + R + " blocks of 0,64,0", area.describe());
    }

    @Test
    void aSmallerAreaIsFineButNoneReachesPastWhatOnePlanSees() {
        assertTrue(new WorkArea(CENTER, 16).contains(CENTER.east(16)));
        assertFalse(new WorkArea(CENTER, 16).contains(CENTER.east(17)));
        assertThrows(IllegalArgumentException.class, () -> new WorkArea(CENTER, R + 1));
        assertThrows(IllegalArgumentException.class, () -> new WorkArea(CENTER, 0));
    }

    /**
     * 从区里任何一处出发朝区里任何一处搜,目标都在那一次搜索拷的快照里——一次规划答得清去得了去不了。取区的两个对顶点,
     * 起点落在区块里的各个位置(区块的头、中、尾),快照照 {@link WorldSnapshot#around} 的取法以起点所在区块为中心。
     */
    @Test
    void fromAnywhereInTheAreaTheWholeAreaIsInOneSearchSnapshot() {
        LevelHeightAccessor heights = LevelHeightAccessor.create(-64, 384);
        int[][] directions = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
        for (int offset : new int[]{0, 7, 15}) {
            BlockPos center = new BlockPos(offset, 64, offset);
            WorkArea area = new WorkArea(center, R);
            for (int[] d : directions) {
                double norm = Math.sqrt(d[0] * d[0] + d[1] * d[1]);
                int dx = (int) Math.floor(R * d[0] / norm);
                int dz = (int) Math.floor(R * d[1] / norm);
                for (int sign : new int[]{1, -1}) {
                    BlockPos start = center.offset(sign * dx, 0, sign * dz);
                    BlockPos target = center.offset(-sign * dx, 0, -sign * dz);
                    assertTrue(area.contains(start) && area.contains(target));
                    WorldSnapshot view = WorldSnapshot.capture(heights, new WorldBorder(), false,
                            (cx, cz) -> new LevelChunkSection[0], SectionPos.blockToSectionCoord(start.getX()),
                            SectionPos.blockToSectionCoord(start.getZ()), WorldSnapshot.SEARCH_RADIUS);
                    assertTrue(view.isLoaded(target.getX(), target.getZ()),
                            "从 " + start.toShortString() + " 搜,区的另一头 " + target.toShortString() + " 不在快照里");
                }
            }
        }
    }
}
