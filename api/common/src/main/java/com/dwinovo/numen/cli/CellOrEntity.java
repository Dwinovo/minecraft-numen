package com.dwinovo.numen.cli;

import net.minecraft.core.BlockPos;

/**
 * 一格或一只实体({@link ArgType#cellOrEntity()}):看它、左键点它的动作两样都收。两样恰好一样。
 *
 * @param cell   一格;是实体时为 null
 * @param entity 一只实体;是一格时为 null
 */
public record CellOrEntity(BlockPos cell, EntityRef entity) {

    public CellOrEntity {
        if ((cell == null) == (entity == null)) {
            throw new IllegalArgumentException("a cell or an entity, exactly one of them");
        }
        cell = cell == null ? null : cell.immutable();
    }

    /** 命令行上的写法:一格是三个数,一只实体是它的编号或 UUID。 */
    public String written() {
        return cell != null ? cell.getX() + " " + cell.getY() + " " + cell.getZ() : entity.written();
    }

    @Override
    public String toString() {
        return written();
    }
}
