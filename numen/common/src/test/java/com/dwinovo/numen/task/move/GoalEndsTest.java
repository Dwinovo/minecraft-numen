package com.dwinovo.numen.task.move;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.plan.Threat;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import static com.dwinovo.numen.pathing.Vanilla.SURVIVAL;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每一种目标交代的终点格与高度范围:这里列全仓库里所有 {@link Goal} 的实现,新加一种目标要进这张表。
 * 声明有终点的,交出的终点格覆盖(小世界里枚举)所有 {@code contains} 为真的节点,高度范围覆盖它们的层;
 * 声明没有的,只能是照设计就没有固定终点的那几类:只到某一列或某一高度、范围无边或大到枚举不划算的距离范围、远离这些。
 */
class GoalEndsTest {

    private static final BlockPos C = new BlockPos(3, 64, -2);

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    /**
     * @param ends    交出终点格(false:照设计没有固定终点,交 null)
     * @param heights 交出高度范围(false:没有范围,交 null)
     */
    private record Row(String name, Goal goal, boolean ends, boolean heights) {}

    private static List<Row> table() {
        TestWorld world = new TestWorld().floor(-10, -10, 20, 10, 63).set(C, Blocks.STONE.defaultBlockState());
        AABB mob = new AABB(C.getX() + 0.2, C.getY() - 15, C.getZ() + 0.2, C.getX() + 0.8, C.getY() - 13.2, C.getZ() + 0.8);
        AABB item = new AABB(C.getX() + 0.4, C.getY() - 20, C.getZ() + 0.4, C.getX() + 0.65, C.getY() - 19.75, C.getZ() + 0.65);
        Goals.Position at = Goals.at(C);
        Goals.Position column = Goals.column(C.getX(), C.getZ());
        Goals.Position level = Goals.level(C.getY());
        Goal within = Goals.within(at, 1, 3);
        Goal digAt = Goals.dig(C.below(2), SURVIVAL, Goals.Clearing.ANY);
        Goal threat = Goals.awayFrom(List.of(new Threat(C.getX() + 8.5, C.getY(), C.getZ() + 0.5, 3)));
        return List.of(
                // 有终点
                new Row("at", at, true, true),
                new Row("within(at)", within, true, true),
                new Row("within(at,0..2)", Goals.within(at, 0, 2), true, true),
                new Row("use", Goals.use(world, SURVIVAL, C), true, true),
                new Row("dig", digAt, true, true),
                new Row("dig 几格", Goals.dig(List.of(new Goals.DigTarget(C.below(2), 0), new Goals.DigTarget(C.east(4), 1)),
                        SURVIVAL, Goals.Clearing.ANY), true, true),
                new Row("place", Goals.place(C.below(2), SURVIVAL), true, true),
                new Row("touch", Goals.touch(mob, SURVIVAL, 3), true, true),
                new Row("pickUp", new ItemReach(item), true, true),
                new Row("anyOf(at, dig)", Goals.anyOf(List.of(at, digAt)), true, true),
                new Row("allOf(within, awayFrom)", Goals.allOf(List.of(within, threat)), true, true),
                // 照设计没有固定终点
                new Row("column", column, false, false),
                new Row("level", level, false, true),
                new Row("within(level)", Goals.within(level, 0, 3), false, true),
                new Row("within(column)", Goals.within(column, 0, 3), false, false),
                new Row("within(at,无穷)", Goals.within(at, 0, Double.POSITIVE_INFINITY), false, false),
                new Row("within(at,超出枚举上限)", Goals.within(at, 0, 200), false, true),
                new Row("awayFrom", threat, false, false),
                new Row("anyOf(at, column)", Goals.anyOf(List.of(at, column)), false, false));
    }

    @Test
    void everyGoalCoversTheNodesItContains() {
        for (Row row : table()) {
            Goal goal = row.goal();
            LongSet ends = goal.endCells();
            Goal.Heights heights = goal.heights();
            if (row.ends()) {
                assertNotNull(ends, row.name() + " 应交出终点格");
            } else {
                assertNull(ends, row.name() + " 照设计没有固定终点");
            }
            if (row.heights()) {
                assertNotNull(heights, row.name() + " 应交出高度范围");
            } else {
                assertNull(heights, row.name() + " 没有高度范围");
            }
            // 目标在 C 附近,正下方二十格也在枚举里
            for (int x = -12; x <= 12; x++) {
                for (int y = -24; y <= 8; y++) {
                    for (int z = -12; z <= 12; z++) {
                        BlockPos node = C.offset(x, y, z);
                        for (double lift : new double[] {0, 0.5, 0.9375}) {
                            Stance stance = new Stance(Stance.Kind.GROUND, node.getY() + lift, node.getY() - 1);
                            if (!goal.contains(node.getX(), node.getY(), node.getZ(), stance)) {
                                continue;
                            }
                            if (row.ends()) {
                                assertTrue(ends.contains(node.asLong()), row.name() + " 到了却不在终点格里:" + node);
                            }
                            if (row.heights()) {
                                assertTrue(node.getY() >= heights.lo() && node.getY() <= heights.hi(),
                                        row.name() + " 到了却在高度范围外:" + node);
                            }
                        }
                    }
                }
            }
        }
    }
}
