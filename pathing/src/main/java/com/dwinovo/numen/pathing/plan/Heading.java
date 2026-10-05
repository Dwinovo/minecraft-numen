package com.dwinovo.numen.pathing.plan;

import java.util.List;

/**
 * 一步朝哪儿走:水平两轴各取 -1、0、1,竖直同样。落点随地形而定的走法(下落、跑酷)只用它的水平方向,落点由前提函数找。
 */
public record Heading(int dx, int dy, int dz) {

    public static final Heading UP = new Heading(0, 1, 0);
    public static final Heading DOWN = new Heading(0, -1, 0);

    /** 东南西北四个正方向,不升不降。 */
    public static final List<Heading> CARDINAL = List.of(
            new Heading(0, 0, -1), new Heading(0, 0, 1), new Heading(1, 0, 0), new Heading(-1, 0, 0));

    /** 四个斜方向。 */
    public static final List<Heading> DIAGONAL = List.of(
            new Heading(1, 0, -1), new Heading(-1, 0, -1), new Heading(1, 0, 1), new Heading(-1, 0, 1));

    public Heading {
        if (dx < -1 || dx > 1 || dy < -1 || dy > 1 || dz < -1 || dz > 1 || (dx == 0 && dy == 0 && dz == 0)) {
            throw new IllegalArgumentException("方向各轴只能取 -1、0、1,且不能全为 0:" + dx + "," + dy + "," + dz);
        }
    }

    /** 同一水平方向,竖直换成 {@code dy}。 */
    public Heading withDy(int dy) {
        return new Heading(dx, dy, dz);
    }

    public boolean horizontal() {
        return dx != 0 || dz != 0;
    }
}
