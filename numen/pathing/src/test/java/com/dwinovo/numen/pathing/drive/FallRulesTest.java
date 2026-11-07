package com.dwinovo.numen.pathing.drive;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.pathing.world.Kinematics.Motion;

import net.minecraft.world.phys.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 控制器推演里的坠落距离照原版(1.21.1 的 {@code Entity.checkSlowFallDistance}、{@code checkFallDamage},{@code LivingEntity.calculateFallDamage}):
 * 竖直速度高于每刻 -0.5 时压回 1,再快就接着攒;落到地面按落地前的算,减安全摔落高度向上取整;创造飞行不摔。
 */
class FallRulesTest {

    private static Motion at(double y, double vy) {
        return new Motion(new Vec3(0, y, 0), new Vec3(0, vy, 0));
    }

    @Test
    void aSlowFallPressesTheFallDistanceBackToOneAndAFastOneKeepsIt() {
        Dynamics glide = Dynamics.gliding(0.08, 10, 3);
        assertEquals(1, glide.settle(5, at(10, -0.2)), 1e-9);
        assertEquals(5, glide.settle(5, at(10, -0.9)), 1e-9);
        assertEquals(0.5, glide.settle(0.5, at(10, -0.2)), 1e-9);
    }

    @Test
    void theFallDistanceGrowsWithWhatWasFallenInTheAirAndNotWithClimbing() {
        Dynamics glide = Dynamics.gliding(0.08, 10, 3);
        assertEquals(1.9, glide.accrue(1, at(10, -0.9), at(9.1, -0.9)), 1e-9);
        assertEquals(1, glide.accrue(1, at(10, 0.5), at(10.5, 0.5)), 1e-9);
    }

    @Test
    void aLandingHurtsByTheFallDistanceOverTheSafeHeightRoundedUp() {
        Dynamics glide = Dynamics.gliding(0.08, 10, 3);
        assertEquals(0, glide.hurt(3), 1e-9);
        assertEquals(1, glide.hurt(3.01), 1e-9);
        assertEquals(4, glide.hurt(6.2), 1e-9);
    }

    @Test
    void creativeFlightNeverFalls() {
        Dynamics fly = Dynamics.flying(0.05F);
        assertEquals(0, fly.settle(9, at(10, -2)), 1e-9);
        assertEquals(0, fly.accrue(9, at(10, -2), at(8, -2)), 1e-9);
        assertEquals(0, fly.hurt(30), 1e-9);
    }
}
