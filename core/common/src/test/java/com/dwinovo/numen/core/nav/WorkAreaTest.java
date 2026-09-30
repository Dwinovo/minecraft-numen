package com.dwinovo.numen.core.nav;

import com.dwinovo.numen.pathing.search.WorldSnapshot;
import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunkSection;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工作区:哪些格在区里、走动怎么关在里面,以及它放得进寻路一次看得清的范围。 */
class WorkAreaTest {

    private static final BlockPos CENTER = new BlockPos(0, 64, 0);
    private static final int R = WorkArea.RADIUS;

    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void theAreaIsABallCountedInWholeCells() {
        WorkArea area = WorkArea.around(Level.OVERWORLD, CENTER);
        assertTrue(area.contains(Level.OVERWORLD, CENTER));
        assertTrue(area.contains(Level.OVERWORLD, CENTER.east(R)), "半径上的那一格算在区里");
        assertFalse(area.contains(Level.OVERWORLD, CENTER.east(R + 1)));
        assertTrue(area.contains(Level.OVERWORLD, CENTER.below(R)), "竖直方向同一个半径");
        assertFalse(area.contains(Level.OVERWORLD, CENTER.above(R + 1)));
        // 斜着看是球,不是方块:两轴各走 0.71 R 就到边
        int diagonal = (int) Math.floor(R / Math.sqrt(2));
        assertTrue(area.contains(Level.OVERWORLD, CENTER.offset(diagonal, 0, diagonal)));
        assertFalse(area.contains(Level.OVERWORLD, CENTER.offset(diagonal + 1, 0, diagonal + 1)));
        assertEquals("within " + R + " blocks of 0,64,0", area.describe());
        assertFalse(area.contains(Level.NETHER, CENTER), "区在受理时的那个维度里");
    }

    @Test
    void aSiteIsItsBoxAndAMarginAroundIt() {
        WorkArea site = WorkArea.site(Level.OVERWORLD, new BlockPos(0, 64, 0), new BlockPos(4, 66, 2));
        int m = WorkArea.SITE_MARGIN;
        assertTrue(site.contains(Level.OVERWORLD, new BlockPos(-m, 64 - m, -m)));
        assertTrue(site.contains(Level.OVERWORLD, new BlockPos(4 + m, 66 + m, 2 + m)));
        assertFalse(site.contains(Level.OVERWORLD, new BlockPos(5 + m, 65, 1)));
        assertEquals(new BlockPos(2, 65, 1), site.center());
        assertEquals("the site 0,64,0..4,66,2 and " + m + " blocks around it", site.describe());
    }

    /** 关进区里的规格:站、过、挖、放在区里的格都不禁,区外的一律禁;规格原有的按位置禁令照旧。 */
    @Test
    void aConfinedSpecForbidsEveryUseOutsideTheArea() {
        WorkArea area = WorkArea.around(Level.OVERWORLD, CENTER);
        BlockPos kept = CENTER.north();
        RouteSpec spec = area.confine(RouteSpec.defaults().edit().positions(PositionCosts.builder()
                .forbid(PositionCosts.Use.DIG, kept.asLong()).build()).build());
        for (PositionCosts.Use use : PositionCosts.Use.values()) {
            assertFalse(spec.positions().forbids(use, CENTER.east(R).asLong()), use + " 区边上那一格");
            assertTrue(spec.positions().forbids(use, CENTER.east(R + 1).asLong()), use + " 区外那一格");
        }
        assertTrue(spec.positions().forbids(PositionCosts.Use.DIG, kept.asLong()), "原有的禁令还在");
        assertFalse(spec.positions().forbids(PositionCosts.Use.STAND, kept.asLong()));
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
            WorkArea area = WorkArea.around(Level.OVERWORLD, center);
            for (int[] d : directions) {
                double norm = Math.sqrt(d[0] * d[0] + d[1] * d[1]);
                // 朝零取整:负方向往下取整会多出一格,落到球外
                int dx = (int) (R * d[0] / norm);
                int dz = (int) (R * d[1] / norm);
                for (int sign : new int[]{1, -1}) {
                    BlockPos start = center.offset(sign * dx, 0, sign * dz);
                    BlockPos target = center.offset(-sign * dx, 0, -sign * dz);
                    assertTrue(area.contains(Level.OVERWORLD, start) && area.contains(Level.OVERWORLD, target));
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
