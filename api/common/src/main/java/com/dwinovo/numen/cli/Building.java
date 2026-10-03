package com.dwinovo.numen.cli;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 要盖成的样子({@link ArgType#building()}):一串格(Cells,每一格是那一格要放的方块,照 {@code /setblock} 的写法),或一份蓝图文件摆在
 * 哪儿({@code numen.build.blueprint} 交回的那张表:文件名、原点、转了多少度)。两样恰好一样。蓝图只记在哪、怎么摆,格子留在文件里,
 * 用到时才读。
 *
 * @param cells     一串格;是蓝图时为 null
 * @param blueprint 一份蓝图;是一串格时为 null
 */
public record Building(List<Cell> cells, Blueprint blueprint) {

    /** 命令行上一份蓝图那一项的开头:{@code blueprint:<文件名>@x,y,z@度数}。 */
    static final String BLUEPRINT = "blueprint:";

    public Building {
        if ((cells == null) == (blueprint == null)) {
            throw new IllegalArgumentException("cells or a blueprint, exactly one of them");
        }
        cells = cells == null ? null : List.copyOf(cells);
    }

    /**
     * 一格:在哪,放什么。
     *
     * @param block 照 {@code /setblock} 写的方块;只给了位置为 null
     */
    public record Cell(BlockPos pos, String block) {

        public Cell {
            pos = pos.immutable();
        }

        /** 命令行上的写法:{@code x,y,z=方块},只有位置的是 {@code x,y,z}。 */
        String written() {
            String at = pos.getX() + "," + pos.getY() + "," + pos.getZ();
            return block == null ? at : at + "=" + block;
        }
    }

    /**
     * 一份蓝图文件摆在哪儿。
     *
     * @param name     文件名(不带扩展名)
     * @param origin   文件里的原点(它的最低西北角)落在哪一格
     * @param rotation 俯视顺时针转了多少度:0、90、180 或 270
     */
    public record Blueprint(String name, BlockPos origin, int rotation) {

        public Blueprint {
            origin = origin.immutable();
        }
    }

    /** 命令行上的写法:一串格空格隔开;一份蓝图是一项 {@code blueprint:名字@x,y,z@度数},名字带空格的整项加引号。 */
    public String written() {
        if (cells != null) {
            return cells.stream().map(Cell::written).collect(Collectors.joining(" "));
        }
        String one = BLUEPRINT + blueprint.name() + "@" + blueprint.origin().getX() + "," + blueprint.origin().getY()
                + "," + blueprint.origin().getZ() + "@" + blueprint.rotation();
        return one.indexOf(' ') < 0 ? one : '"' + one.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    @Override
    public String toString() {
        return written();
    }
}
