package com.dwinovo.numen.cli;

import net.minecraft.core.BlockPos;

/**
 * 命令里点名的一处:坐标给几个算几个——三个是一格({@code 120 64 -35} 或 {@code 120,64,-35}),两个是一列({@code x z},那一列上
 * 站得住的高度),一个是一个高度。写法只在 {@link ArgType#place()} 读;收不收一列、一个高度由用它的动作判(挖只收一格)。一片格子是
 * 一串 {@code Place}。
 *
 * @param x 没给为 null;与 {@code z} 同给同不给
 * @param y 没给为 null
 * @param z 没给为 null
 */
public record Place(Integer x, Integer y, Integer z) {

    public Place {
        if ((x == null) != (z == null) || (x == null && y == null)) {
            throw new IllegalArgumentException("coordinates are x y z (a cell), x z (a column) or y (a height)");
        }
    }

    /** 一格。 */
    public static Place cell(BlockPos pos) {
        return new Place(pos.getX(), pos.getY(), pos.getZ());
    }

    /** 那一格(三个数都给了时);否则为 null。 */
    public BlockPos cell() {
        return x != null && y != null ? new BlockPos(x, y, z) : null;
    }

    /** 脚本里的写法:带键的表(给了几个写几个);{@link ArgType#place()} 读回来是同一处。 */
    public Object value() {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (x != null) {
            out.put("x", (long) x);
        }
        if (y != null) {
            out.put("y", (long) y);
        }
        if (z != null) {
            out.put("z", (long) z);
        }
        return out;
    }

    /** 在回执与提示里写成的那一段程序:{@code {x = 1, y = 2, z = 3}}。 */
    public String literal() {
        return com.dwinovo.numen.agent.script.ScriptEngine.IN_USE.value(value());
    }

    /** 命令行上的写法:{@code 120 64 -35}、{@code 120 -35} 或 {@code 64};{@link ArgType#place()} 读回来是同一处。 */
    public String written() {
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
