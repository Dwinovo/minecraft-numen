package com.dwinovo.numen.api.sdk;

/**
 * 8-point compass label from a block delta (+X east, +Z south), shared by the
 * locate tools' result messages and the entity events and nearby list. A diagonal collapses to a cardinal when the
 * dominant axis is at least twice the other — "north-east" only when the
 * direction is meaningfully diagonal.
 */
public final class CompassUtil {

    private CompassUtil() {}

    /** {@code to} 在 {@code from} 的哪个方向。 */
    public static String compass(net.minecraft.world.entity.Entity from, net.minecraft.world.entity.Entity to) {
        return compass((int) Math.round(to.getX() - from.getX()), (int) Math.round(to.getZ() - from.getZ()));
    }

    public static String compass(int dx, int dz) {
        if (dx == 0 && dz == 0) return "here";
        String ns = dz < 0 ? "north" : (dz > 0 ? "south" : "");
        String ew = dx < 0 ? "west" : (dx > 0 ? "east" : "");
        if (ns.isEmpty()) return ew;
        if (ew.isEmpty()) return ns;
        if (Math.abs(dz) >= 2 * Math.abs(dx)) return ns;
        if (Math.abs(dx) >= 2 * Math.abs(dz)) return ew;
        return ns + "-" + ew;
    }
}
