package com.dwinovo.numen.api.combat;

import net.minecraft.world.phys.Vec3;

/**
 * 一发该往哪打:朝哪看({@code lookPoint})、射出的方向、飞多久到({@code travelTicks};命中扫描是 0)。瞄准由攻击任务按提供者说的弹道算好,
 * 交给 {@link RangedWeapon.Shot#tick}。
 */
public record Aim(Vec3 lookPoint, Vec3 direction, double travelTicks) {

    /** 视线与射出方向夹角小于它,才算对准了。 */
    public static final double THRESHOLD_DEGREES = 1.5;

    /** 夹角(度);有一个是零向量(或 null)算不出夹角,是 {@link Double#MAX_VALUE}。 */
    public static double angleDegrees(Vec3 a, Vec3 b) {
        if (a == null || b == null || a.lengthSqr() < 1.0e-6 || b.lengthSqr() < 1.0e-6) {
            return Double.MAX_VALUE;
        }
        double dot = Math.max(-1.0, Math.min(1.0, a.normalize().dot(b.normalize())));
        return Math.toDegrees(Math.acos(dot));
    }

    /** {@code view}(她此刻的视线)对准这个方向了没有。 */
    public boolean aligned(Vec3 view) {
        return angleDegrees(view, direction) <= THRESHOLD_DEGREES;
    }
}
