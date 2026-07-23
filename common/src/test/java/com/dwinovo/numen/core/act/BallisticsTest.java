package com.dwinovo.numen.core.act;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BallisticsTest {

    @Test
    void measuresAngleBetweenDirections() {
        assertEquals(0.0,
                Ballistics.angleDegrees(new Vec3(1.0, 0.0, 0.0), new Vec3(1.0, 0.0, 0.0)),
                1.0e-6);
        assertEquals(90.0,
                Ballistics.angleDegrees(new Vec3(1.0, 0.0, 0.0), new Vec3(0.0, 0.0, 1.0)),
                1.0e-6);
        assertEquals(180.0,
                Ballistics.angleDegrees(new Vec3(1.0, 0.0, 0.0), new Vec3(-1.0, 0.0, 0.0)),
                1.0e-6);
    }

    @Test
    void startsArrowsBelowTheEye() {
        Vec3 start = Ballistics.projectileStart(1.0, 65.0, 2.0);

        assertEquals(1.0, start.x, 1.0e-6);
        assertEquals(64.89999999850988, start.y, 1.0e-6);
        assertEquals(2.0, start.z, 1.0e-6);
    }

    @Test
    void copiesShooterVelocityOnlyWhenRequested() {
        Vec3 movement = new Vec3(0.2, -0.3, 0.4);

        Vec3 ignored = Ballistics.inheritedVelocity(movement, false, false);
        assertEquals(0.0, ignored.length(), 1.0e-6);

        Vec3 grounded = Ballistics.inheritedVelocity(movement, true, true);
        assertEquals(0.2, grounded.x, 1.0e-6);
        assertEquals(0.0, grounded.y, 1.0e-6);
        assertEquals(0.4, grounded.z, 1.0e-6);

        Vec3 airborne = Ballistics.inheritedVelocity(movement, false, true);
        assertEquals(-0.3, airborne.y, 1.0e-6);
    }

    @Test
    void solvesDragAwareDirectionForReachablePoint() {
        Vec3 direction = Ballistics.directionByTime(
                new Vec3(0.0, 64.0, 0.0),
                new Vec3(8.0, 64.0, 0.0),
                4.0,
                3.0,
                0.05,
                0.99);

        assertNotNull(direction);
        assertTrue(Double.isFinite(direction.x));
        assertTrue(Double.isFinite(direction.y));
        assertTrue(Double.isFinite(direction.z));
    }
    @Test
    void findsHittablePointInsideTargetBox() {
        AABB box = new AABB(9.5, 63.5, -0.5, 10.5, 65.0, 0.5);
        Vec3 point = Ballistics.findHittablePosition(
                new Vec3(0.0, 64.0, 0.0),
                new Vec3(1.0, -0.05, 0.0),
                new Vec3(10.0, 64.0, 0.0),
                box);

        assertNotNull(point);
        assertTrue(box.contains(point));
    }

    @Test
    void computesFiniteImpactVelocity() {
        Vec3 velocity = Ballistics.velocityOnImpact(
                new Vec3(1.0, 0.1, 0.0).normalize(),
                8.0,
                3.0,
                0.05,
                0.99);

        assertNotNull(velocity);
        assertTrue(Double.isFinite(velocity.x));
        assertTrue(Double.isFinite(velocity.y));
        assertTrue(Double.isFinite(velocity.z));
    }
}


