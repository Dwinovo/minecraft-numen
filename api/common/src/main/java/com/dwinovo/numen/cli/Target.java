package com.dwinovo.numen.cli;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

/**
 * 点名要挖(或要用)的一格({@link ArgType#target()}):一个 Pos 只说哪一格,里面是什么算什么;一个 Block(查询交回的、带 {@code name}
 * 的那张表)还说当时那里是什么方块——那一格换成了别的,就不再是她点名的那一个。
 *
 * @param cell  那一格
 * @param block 当时那里的方块;只给了位置为 null
 */
public record Target(BlockPos cell, Block block) {

    public Target {
        cell = cell.immutable();
    }

    /** 脚本里的写法:有方块是 Block 那张表,没有是一个 Pos。 */
    public Object value() {
        if (block == null) {
            return Shapes.value(cell);
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("name", BuiltInRegistries.BLOCK.getKey(block).toString());
        out.put("pos", Shapes.value(cell));
        return out;
    }

    /** 命令行上的写法:那一格的三个数(命令行上只写位置)。 */
    public String written() {
        return cell.getX() + " " + cell.getY() + " " + cell.getZ();
    }

    @Override
    public String toString() {
        return written();
    }
}
