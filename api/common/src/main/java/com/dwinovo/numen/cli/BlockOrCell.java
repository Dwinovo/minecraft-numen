package com.dwinovo.numen.cli;

import net.minecraft.core.BlockPos;

/**
 * 和方块混在一串里点名的一样东西({@link ArgType#blockOrCell()}):一种方块({@code minecraft:chest} 或 {@code #minecraft:logs},
 * 写下的原文),或一格坐标。两样恰好一样。
 *
 * @param block 方块 id 或标签,写下的原文;不是方块为 null
 * @param cell  一格;不是一格为 null
 */
public record BlockOrCell(String block, BlockPos cell) {

    public BlockOrCell {
        if ((block == null) == (cell == null)) {
            throw new IllegalArgumentException("a block or a cell, exactly one of them");
        }
        cell = cell == null ? null : cell.immutable();
    }

    /** 命令行上的写法:方块原文或 {@code x,y,z};{@link ArgType#blockOrCell()} 读回来是同一样。 */
    public String written() {
        return block != null ? block : cell.getX() + "," + cell.getY() + "," + cell.getZ();
    }

    @Override
    public String toString() {
        return written();
    }
}
