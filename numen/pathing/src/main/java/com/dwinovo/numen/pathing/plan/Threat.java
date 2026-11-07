package com.dwinovo.numen.pathing.plan;

/**
 * 一只生物的危险:它此刻的位置与危险半径(身体离它多近就会挨打)。半径由宿主按生物给出,模块不认生物。
 *
 * @param radius 危险半径,格
 */
public record Threat(double x, double y, double z, double radius) {

    public Threat {
        if (!(radius >= 0) || Double.isInfinite(radius)) {
            throw new IllegalArgumentException("危险半径要是非负的有限数:" + radius);
        }
    }

    /** 格 {@code (cx, cy, cz)} 的中心在不在它的危险半径里。 */
    public boolean covers(int cx, int cy, int cz) {
        double dx = cx + 0.5 - x;
        double dy = cy + 0.5 - y;
        double dz = cz + 0.5 - z;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }
}
