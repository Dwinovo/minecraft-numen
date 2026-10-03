package com.dwinovo.numen.core.task.dig;

import com.dwinovo.numen.cli.Target;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code numen.work.dig} 够不着的那些格:回执里能照抄的下一步——走到够得着最近那一格的地方,再挖同样的几格。 */
class DigTaskRecordTest {

    private static final BlockPos NEAREST = new BlockPos(10, 1, 11);

    @Test
    void aFewCellsAreReachedAtTheNearestAndDugAgainInFull() {
        assertEquals("`numen.move.goto_({x = 10, y = 1, z = 11}, {arrive = \"dig\"})`, then `numen.work.dig({x = 10, y = 1, z = 11}, {x = 12, y = 1, z = 11})`",
                DigTaskRecord.reachThem(List.of(new Target(NEAREST, null), new Target(new BlockPos(12, 1, 11), null)),
                        NEAREST));
    }

    @Test
    void manyCellsAreReachedAtTheNearestAndDugAgainByName() {
        List<Target> many = List.of(new Target(NEAREST, null), new Target(NEAREST.east(), null),
                new Target(NEAREST.east(2), null), new Target(NEAREST.east(3), null));
        assertEquals("`numen.move.goto_({x = 10, y = 1, z = 11}, {arrive = \"dig\"})`, then dig the same blocks again",
                DigTaskRecord.reachThem(many, NEAREST));
    }
}
