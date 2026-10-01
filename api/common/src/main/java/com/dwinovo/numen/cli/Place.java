package com.dwinovo.numen.cli;

import com.dwinovo.numen.area.AreaRef;

import net.minecraft.core.BlockPos;

/**
 * 命令里点名的一处:坐标给几个算几个——三个是一格({@code 120 64 -35} 或 {@code 120,64,-35}),两个是一列({@code x z},那一列上
 * 站得住的高度),一个是一个高度——或主人名下的一块区域({@code ores}、{@code ores/g3})。写法只在 {@link ArgType#place()} 读;
 * 收不收一列、一个高度由用它的动作判(挖只收一格与区域)。
 *
 * @param x    没给为 null;与 {@code z} 同给同不给
 * @param y    没给为 null
 * @param z    没给为 null
 * @param area 一块区域;给了坐标为 null
 */
public record Place(Integer x, Integer y, Integer z, AreaRef area) {

    public Place {
        boolean coords = x != null || y != null || z != null;
        if (coords == (area != null)) {
            throw new IllegalArgumentException("a place is coordinates or an area, exactly one of them");
        }
        if ((x == null) != (z == null) || (x == null && y == null && area == null)) {
            throw new IllegalArgumentException("coordinates are x y z (a cell), x z (a column) or y (a height)");
        }
    }

    /** 一格。 */
    public static Place cell(BlockPos pos) {
        return new Place(pos.getX(), pos.getY(), pos.getZ(), null);
    }

    /** 一块区域,或它的一部分。 */
    public static Place area(AreaRef area) {
        return new Place(null, null, null, area);
    }

    /** 那一格(三个数都给了时);否则为 null。 */
    public BlockPos cell() {
        return x != null && y != null ? new BlockPos(x, y, z) : null;
    }

    /** 命令行上的写法:{@code 120 64 -35}、{@code 120 -35}、{@code 64} 或区域名;{@link ArgType#place()} 读回来是同一处。 */
    public String written() {
        if (area != null) {
            return area.toString();
        }
        if (x == null) {
            return String.valueOf(y);
        }
        return y == null ? x + " " + z : x + " " + y + " " + z;
    }

    @Override
    public String toString() {
        return written();
    }
}
