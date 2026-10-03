package com.dwinovo.numen.cli;

import net.minecraft.core.BlockPos;

/**
 * 和几个固定值混在一串里的一样东西({@link ArgType#oneOfOrCell}):几个固定值之一({@code water}),或一格坐标。两样恰好一样。
 *
 * @param choice 固定值之一;不是它为 null
 * @param cell   一格;不是一格为 null
 */
public record ChoiceOrCell(String choice, BlockPos cell) {

    public ChoiceOrCell {
        if ((choice == null) == (cell == null)) {
            throw new IllegalArgumentException("a choice or a cell, exactly one of them");
        }
        cell = cell == null ? null : cell.immutable();
    }

    /** 命令行上的写法:固定值原文或 {@code x,y,z};{@link ArgType#oneOfOrCell} 读回来是同一样。 */
    public String written() {
        return choice != null ? choice : cell.getX() + "," + cell.getY() + "," + cell.getZ();
    }

    @Override
    public String toString() {
        return written();
    }
}
