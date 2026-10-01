package com.dwinovo.numen.cli;

import com.dwinovo.numen.area.AreaRef;

import net.minecraft.core.BlockPos;

/**
 * 和方块混在一串里点名的一样东西({@link ArgType#blockCellOrArea()}):一种方块({@code minecraft:chest} 或 {@code #minecraft:logs},
 * 写下的原文)、一格坐标,或一块区域({@code area:house}——和方块写在一串里时区域带记号,见 {@link AreaRef#MARK})。三样恰好一样。
 *
 * @param block 方块 id 或标签,写下的原文;不是方块为 null
 * @param cell  一格;不是一格为 null
 * @param area  一块区域;不是区域为 null
 */
public record BlockCellOrArea(String block, BlockPos cell, AreaRef area) {

    public BlockCellOrArea {
        int given = (block != null ? 1 : 0) + (cell != null ? 1 : 0) + (area != null ? 1 : 0);
        if (given != 1) {
            throw new IllegalArgumentException("a block, a cell or an area, exactly one of them");
        }
        cell = cell == null ? null : cell.immutable();
    }

    /** 命令行上的写法:方块原文、{@code x,y,z} 或 {@code area:名字};{@link ArgType#blockCellOrArea()} 读回来是同一样。 */
    public String written() {
        if (block != null) {
            return block;
        }
        if (cell != null) {
            return cell.getX() + "," + cell.getY() + "," + cell.getZ();
        }
        return area.marked();
    }

    @Override
    public String toString() {
        return written();
    }
}
