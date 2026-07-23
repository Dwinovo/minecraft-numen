package com.dwinovo.numen.core.act;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for the raycast-free half of the placement resolver — no {@code Level},
 * no bootstrapped game (same headless style as {@code FoodPolicyTest}): shared-face
 * geometry, the facing-score face ordering, and the inset-rectangle click-point
 * selection. The ray/world half of {@link Placement} is verified in-game.
 */
class PlaceGeometryTest {

    private static final BlockPos TARGET = new BlockPos(0, 0, 0);
    private static final double EPS = 1.0e-9;

    // ---- shared-face geometry ----

    @Test
    void sharedFaceCenterSitsOnTheCellBoundary() {
        // Support below: the shared face is the target's bottom plane.
        assertEquals(new Vec3(0.5, 0.0, 0.5), PlaceGeometry.sharedFaceCenter(TARGET, Direction.DOWN));
        // Support to the east: the shared face is the x = 1 plane.
        assertEquals(new Vec3(1.0, 0.5, 0.5), PlaceGeometry.sharedFaceCenter(TARGET, Direction.EAST));
        // Support above: the shared face is the y = 1 plane.
        assertEquals(new Vec3(0.5, 1.0, 0.5), PlaceGeometry.sharedFaceCenter(TARGET, Direction.UP));
    }

    @Test
    void planeCoordPicksTheBoundaryOnTheSupportSide() {
        assertEquals(0.0, PlaceGeometry.planeCoord(TARGET, Direction.DOWN), EPS);
        assertEquals(1.0, PlaceGeometry.planeCoord(TARGET, Direction.UP), EPS);
        assertEquals(0.0, PlaceGeometry.planeCoord(TARGET, Direction.NORTH), EPS);
        assertEquals(1.0, PlaceGeometry.planeCoord(TARGET, Direction.SOUTH), EPS);
        assertEquals(0.0, PlaceGeometry.planeCoord(TARGET, Direction.WEST), EPS);
        assertEquals(1.0, PlaceGeometry.planeCoord(TARGET, Direction.EAST), EPS);
    }

    // ---- facing score: sign = which side of the plane, magnitude = how head-on ----

    @Test
    void facingScoreIsPositiveOnlyOnTheVisibleSide() {
        // Support below → clicked face normal is UP: an eye above the plane sees it...
        assertTrue(PlaceGeometry.facingScore(new Vec3(0.5, 2, 0.5), TARGET, Direction.DOWN) > 0);
        // ... an eye below the plane cannot, no matter what point on the face is aimed at.
        assertTrue(PlaceGeometry.facingScore(new Vec3(0.5, -2, 0.5), TARGET, Direction.DOWN) < 0);
        // Dead-on view scores the full cosine of 1.
        assertEquals(1.0, PlaceGeometry.facingScore(new Vec3(0.5, 3, 0.5), TARGET, Direction.DOWN), EPS);
    }

    @Test
    void facingScoreIsZeroEdgeOn() {
        // Eye exactly in the bottom-face plane: edge-on, geometrically unhittable.
        assertEquals(0.0, PlaceGeometry.facingScore(new Vec3(0.5, 0.0, 4.0), TARGET, Direction.DOWN), EPS);
    }

    // ---- ranking: most-facing first, unhittable planes dropped ----

    @Test
    void rankVisiblePutsTheMostFacingFaceFirstAndDropsBackFaces() {
        // Eye straight above the target: the bottom support's UP face is dead-on (score 1),
        // side faces are seen at a slant, the ceiling support's DOWN face points away.
        Vec3 eye = new Vec3(0.5, 3, 0.5);
        List<Direction> ranked = PlaceGeometry.rankVisible(eye, TARGET, List.of(
                Direction.NORTH, Direction.UP, Direction.DOWN, Direction.EAST));
        assertEquals(Direction.DOWN, ranked.get(0));
        assertFalse(ranked.contains(Direction.UP));
    }

    @Test
    void rankVisibleOrdersSideFacesByHowHeadOnTheyAre() {
        // Eye due north of the target: the south support's north-pointing face is dead-on
        // (score 1), the east support's west-pointing face is seen at a slant — both
        // visible, dead-on wins.
        Vec3 eye = new Vec3(0.5, 0.5, -3.0);
        List<Direction> ranked = PlaceGeometry.rankVisible(eye, TARGET,
                List.of(Direction.EAST, Direction.SOUTH));
        assertEquals(List.of(Direction.SOUTH, Direction.EAST), ranked);
    }

    @Test
    void rankVisibleDropsEdgeOnFaces() {
        // Eye exactly in the bottom plane: DOWN scores 0 → dropped; nothing else offered.
        Vec3 eye = new Vec3(0.5, 0.0, 4.0);
        assertTrue(PlaceGeometry.rankVisible(eye, TARGET, List.of(Direction.DOWN)).isEmpty());
    }

    // ---- inset face point: ray crossing, clamping, fallbacks ----

    @Test
    void insetFacePointTakesTheRayCrossingWhenItLandsInsideTheFace() {
        // Looking straight down from above the face centre: the crossing IS the centre.
        Vec3 p = PlaceGeometry.insetFacePoint(
                new Vec3(0.5, 2, 0.5), new Vec3(0, -1, 0), TARGET, Direction.DOWN);
        assertEquals(new Vec3(0.5, 0.0, 0.5), p);
    }

    @Test
    void insetFacePointClampsACrossingOutsideTheInsetRectangle() {
        // A shallow ray crosses the bottom plane at x = 3 — outside the cell; the point
        // is pulled back to the inset rim (1 − 0.15).
        Vec3 eye = new Vec3(2, 1, 0.5);
        Vec3 look = new Vec3(1, -1, 0).normalize();
        Vec3 p = PlaceGeometry.insetFacePoint(eye, look, TARGET, Direction.DOWN);
        assertEquals(0.85, p.x, EPS);
        assertEquals(0.0, p.y, EPS);
        assertEquals(0.5, p.z, EPS);
    }

    @Test
    void insetFacePointFallsBackToTheEyeNearestPointWhenTheRayMissesThePlane() {
        // Ray parallel to the bottom plane: fall back to the eye's projection, clamped.
        Vec3 p = PlaceGeometry.insetFacePoint(
                new Vec3(0.3, 5, 0.05), new Vec3(1, 0, 0), TARGET, Direction.DOWN);
        assertEquals(new Vec3(0.3, 0.0, 0.15), p);
        // No look ray at all: same eye-nearest fallback.
        Vec3 q = PlaceGeometry.insetFacePoint(new Vec3(-2, 5, 0.5), null, TARGET, Direction.DOWN);
        assertEquals(new Vec3(0.15, 0.0, 0.5), q);
    }

    @Test
    void insetFacePointPinsTheResultOntoTheFacePlane() {
        // Vertical face (east support, x = 1 plane): the crossing keeps y/z, pins x.
        Vec3 p = PlaceGeometry.insetFacePoint(
                new Vec3(4, 0.5, 0.5), new Vec3(-1, 0, 0), TARGET, Direction.EAST);
        assertEquals(new Vec3(1.0, 0.5, 0.5), p);
    }

    @Test
    void insetFacePointNeverLeavesTheInsetRectangle() {
        // Whatever the ray does, the free coordinates stay in [0.15, 0.85] of the cell.
        Vec3 p = PlaceGeometry.insetFacePoint(
                new Vec3(9, 3, -7), new Vec3(0.2, -0.3, 0.93).normalize(), TARGET, Direction.DOWN);
        assertTrue(p.x >= 0.15 && p.x <= 0.85);
        assertEquals(0.0, p.y, EPS);
        assertTrue(p.z >= 0.15 && p.z <= 0.85);
    }
}
