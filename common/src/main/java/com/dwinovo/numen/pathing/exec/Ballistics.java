package com.dwinovo.numen.pathing.exec;

import net.minecraft.world.phys.Vec3;

/**
 * Projectile-arc aiming, shared by anything that launches a gravity-affected projectile in the
 * look direction (bow arrows, thrown ender pearls / snowballs / eggs). A real archer aims ABOVE a
 * distant target to compensate for the drop; this solves the launch angle that makes the arc pass
 * through the target, so an auto-aiming companion actually hits instead of always falling short.
 */
public final class Ballistics {

    private Ballistics() {}

    /**
     * A point to {@code lookAt} so that a projectile launched from {@code eye} along that look,
     * at launch speed {@code v} (blocks/tick) under per-tick gravity {@code g}, arcs through
     * {@code target}. Returns the flatter (faster-arriving) of the two solutions. Air drag is
     * ignored (a small under-aim at long range, same simplification vanilla mobs make); if the
     * target is out of ballistic range the straight-line {@code target} is returned as a best effort.
     */
    public static Vec3 aimPoint(Vec3 eye, Vec3 target, double v, double g) {
        double dx = target.x - eye.x;
        double dz = target.z - eye.z;
        double d = Math.sqrt(dx * dx + dz * dz);   // horizontal distance
        if (d < 1.0e-6) {
            return target;                          // directly above/below — nothing to compensate
        }
        double y = target.y - eye.y;               // height difference
        double v2 = v * v;
        double root = v2 * v2 - g * (g * d * d + 2.0 * y * v2);
        if (root < 0.0) {
            return target;                          // beyond the arc's reach — aim straight, best effort
        }
        // tan(theta) for the lower (flatter) trajectory: (v² - sqrt(v⁴ - g(g·d² + 2·y·v²))) / (g·d)
        double tanTheta = (v2 - Math.sqrt(root)) / (g * d);
        // A look point at horizontal (target.x, target.z), raised by tan(theta)·d → look pitch = theta.
        return new Vec3(target.x, eye.y + tanTheta * d, target.z);
    }
}
