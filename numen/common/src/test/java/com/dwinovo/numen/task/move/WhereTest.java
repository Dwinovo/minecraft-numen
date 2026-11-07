package com.dwinovo.numen.task.move;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.dwinovo.numen.NumenApiFixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 去哪儿怎么读:位置与方块分得开(没说距离时一个站进去、一个走到旁边),一列、一个高度、一只实体各是各的。 */
class WhereTest {

    @BeforeAll
    static void install() {
        NumenApiFixture.install();
    }

    private static Map<String, Object> pos(long x, long y, long z) {
        return Map.of("x", x, "y", y, "z", z);
    }

    @Test
    void aPosIsAPositionAndABlockIsABlock() {
        Where.Cell position = assertInstanceOf(Where.Cell.class, Where.CODEC.decode(pos(1, 2, 3)));
        assertTrue(position.block() == null && !position.beside());
        Where.Cell block = assertInstanceOf(Where.Cell.class,
                Where.CODEC.decode(Map.of("name", "minecraft:chest", "pos", pos(1, 2, 3))));
        assertEquals(net.minecraft.world.level.block.Blocks.CHEST, block.block());
        assertTrue(block.beside());
    }

    @Test
    void aListOfBlocksIsBlocksAndAListOfPositionsIsPositions() {
        Where.Cells blocks = assertInstanceOf(Where.Cells.class, Where.CODEC.decode(List.of(
                Map.of("name", "minecraft:chest", "pos", pos(1, 2, 3)),
                Map.of("name", "minecraft:chest", "pos", pos(4, 2, 3)))));
        assertTrue(blocks.blocks());
        Where.Cells positions = assertInstanceOf(Where.Cells.class, Where.CODEC.decode(List.of(pos(1, 2, 3), pos(4, 2, 3))));
        assertTrue(!positions.blocks());
    }

    /** 程序里读到的与服务端再读一遍的是同一个意思:写回去带着方块。 */
    @Test
    void writingItBackKeepsWhetherItIsABlock() {
        Where block = Where.CODEC.decode(Map.of("name", "minecraft:chest", "pos", pos(1, 2, 3)));
        assertEquals(block, Where.CODEC.decode(Where.CODEC.encode(block)));
        Where blocks = Where.CODEC.decode(List.of(Map.of("name", "minecraft:chest", "pos", pos(1, 2, 3)),
                Map.of("name", "minecraft:chest", "pos", pos(4, 2, 3))));
        assertEquals(blocks, Where.CODEC.decode(Where.CODEC.encode(blocks)));
    }

    @Test
    void aColumnAHeightAndAnEntityAreTheirOwnKinds() {
        assertInstanceOf(Where.Column.class, Where.CODEC.decode(Map.of("x", 5L, "z", 6L)));
        assertEquals(new Where.Height(12), Where.CODEC.decode(Map.of("y", 12L)));
        assertInstanceOf(Where.Mob.class, Where.CODEC.decode("zombie@3f9a1c"));
    }
}
