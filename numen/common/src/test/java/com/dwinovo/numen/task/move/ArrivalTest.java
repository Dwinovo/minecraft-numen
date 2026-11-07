package com.dwinovo.numen.task.move;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 没说距离时走到哪儿:位置能站进去就站进去,方块与实体走到旁边;说了距离就是不超过那么远。 */
class ArrivalTest {

    private static final BlockPos P = new BlockPos(1, 2, 3);

    @Test
    void aPositionIsStoodInAndABlockIsGoneBeside() {
        assertEquals(Arrival.How.AT, Arrival.to(new Where.Cell(P, null), null).how());
        assertEquals(Arrival.How.AT, Arrival.to(new Where.Column(1, 3), null).how());
        assertEquals(Arrival.How.AT, Arrival.to(new Where.Height(12), null).how());
        Arrival block = Arrival.to(new Where.Cell(P, Blocks.CHEST), null);
        assertEquals(Arrival.How.BESIDE, block.how());
        assertEquals(Arrival.BESIDE, block.range());
        assertEquals(Arrival.How.BESIDE, Arrival.to(new Where.Cells(List.of(new Where.Cell(P, Blocks.CHEST))), null).how());
        assertEquals(Arrival.How.AT, Arrival.to(Where.Cells.of(List.of(P)), null).how());
    }

    @Test
    void aDistanceMeansWithinThatManyBlocks() {
        Arrival near = Arrival.to(new Where.Cell(P, null), 6);
        assertEquals(Arrival.How.NEAR, near.how());
        assertEquals(6, near.range());
        assertEquals(Arrival.How.AWAY, Arrival.away(new Where.Cell(P, null), 12).how());
    }

    @Test
    void badShapesAreRefusedWhereTheyAreWritten() {
        assertThrows(IllegalArgumentException.class, () -> Arrival.to(new Where.Cell(P, null), 0));
        assertThrows(IllegalArgumentException.class, () -> Arrival.to(new Where.Cell(P, null), Arrival.MAX_RANGE + 1));
        assertThrows(IllegalArgumentException.class, () -> Arrival.to(new Where.Height(12), 3));
        assertThrows(IllegalArgumentException.class, () -> Arrival.away(new Where.Height(12), 3));
    }
}
