package com.dwinovo.numen.core.task.dig;

import com.dwinovo.numen.area.AreaRef;
import com.dwinovo.numen.cli.Place;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code work.dig} 够不着的那些格:回执里能照抄的下一步——走到够得着的地方,再挖同样的几处。 */
class DigTaskRecordTest {

    private static final BlockPos NEAREST = new BlockPos(10, 1, 11);

    @Test
    void oneAreaIsReachedByItsName() {
        assertEquals("`move.goto_(\"ores/g3\", {arrive = \"dig\"})`, then `work.dig(\"ores/g3\")`",
                DigTaskRecord.reachThem(List.of(Place.area(AreaRef.parse("ores/g3"))), NEAREST));
    }

    @Test
    void cellsOrSeveralPlacesAreReachedAtTheNearestCell() {
        assertEquals("`move.goto_({x = 10, y = 1, z = 11}, {arrive = \"dig\"})`, then `work.dig({x = 10, y = 1, z = 11}, {x = 12, y = 1, z = 11})`",
                DigTaskRecord.reachThem(List.of(Place.cell(NEAREST), Place.cell(new BlockPos(12, 1, 11))), NEAREST));
        assertEquals("`move.goto_({x = 10, y = 1, z = 11}, {arrive = \"dig\"})`, then `work.dig(\"ores\", \"gold\")`",
                DigTaskRecord.reachThem(List.of(Place.area(AreaRef.parse("ores")), Place.area(AreaRef.parse("gold"))),
                        NEAREST));
    }
}
