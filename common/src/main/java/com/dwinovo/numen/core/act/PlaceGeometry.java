package com.dwinovo.numen.core.act;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure placement geometry — the raycast-free half of {@link Placement}, kept free of any
 * {@code Level}/world dependency so it is unit-testable headless.
 *
 * <p>Terminology: a placement at {@code placeAt} clicks a neighbouring <b>support</b> block
 * {@code placeAt.relative(support)}; the clicked face is the <b>shared face</b> between the
 * two cells, and its outward normal is {@code support.getOpposite()} (it points from the
 * support back into the target cell).
 */
public final class PlaceGeometry {

    private PlaceGeometry() {}

    /** Inset applied to each edge of a face rectangle before sampling a click point on it —
     *  keeps the hit safely inside the face (a point on the very rim resolves ambiguously). */
    public static final double FACE_INSET = 0.15;

    /** Below this facing score a face plane is treated as unseeable (the eye is on, or
     *  behind, the plane — no ray from the eye can land on the face from that side). */
    public static final double VISIBLE_EPS = 1.0e-6;

    /** Centre of the shared face between {@code placeAt} and its {@code support} neighbour. */
    public static Vec3 sharedFaceCenter(BlockPos placeAt, Direction support) {
        return Vec3.atCenterOf(placeAt)
                .add(support.getStepX() * 0.5, support.getStepY() * 0.5, support.getStepZ() * 0.5);
    }

    /**
     * How head-on the eye sees the shared face: the cosine of the angle between the face's
     * outward normal and the face-centre→eye direction. {@code 1.0} = looking at it dead-on,
     * {@code 0} = edge-on, negative = the eye is behind the face plane. The sign depends only
     * on which side of the plane the eye is, so a non-positive score means NO point of the
     * face is hittable — such faces can be dropped without spending a ray.
     */
    public static double facingScore(Vec3 eye, BlockPos placeAt, Direction support) {
        Vec3 toEye = eye.subtract(sharedFaceCenter(placeAt, support));
        double len = toEye.length();
        if (len < 1.0e-9) return 1.0;   // eye ON the face centre — trivially visible
        Direction normal = support.getOpposite();
        return (normal.getStepX() * toEye.x + normal.getStepY() * toEye.y + normal.getStepZ() * toEye.z)
                / len;
    }

    /**
     * The candidate support directions whose shared face the eye is on the visible side of,
     * sorted most-facing-first ({@link #facingScore} descending) — the cheap ordering that
     * lets the raycaster try the likeliest face first and stay within its ray budget.
     */
    public static List<Direction> rankVisible(Vec3 eye, BlockPos placeAt, List<Direction> candidates) {
        List<Direction> visible = new ArrayList<>(candidates.size());
        for (Direction d : candidates) {
            if (facingScore(eye, placeAt, d) > VISIBLE_EPS) visible.add(d);
        }
        visible.sort((a, b) -> Double.compare(
                facingScore(eye, placeAt, b), facingScore(eye, placeAt, a)));
        return visible;
    }

    /** The shared-face plane's coordinate along the support axis (the cell boundary). */
    public static double planeCoord(BlockPos placeAt, Direction support) {
        int base = support.getAxis().choose(placeAt.getX(), placeAt.getY(), placeAt.getZ());
        return base + (support.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0 : 0.0);
    }

    /**
     * A click point on the shared face, inside the {@link #FACE_INSET}-inset rectangle,
     * as close as possible to where the given sight ray crosses the face plane:
     * <ul>
     *   <li>{@code look != null} and the ray crosses the plane ahead of the eye — the
     *       crossing point, clamped into the inset rectangle;</li>
     *   <li>otherwise (no look ray, ray parallel to the plane, or plane behind the eye) —
     *       the point of the inset rectangle nearest the eye (the eye's projection onto
     *       the plane, clamped), which is the spot most likely to peek around an occluding
     *       edge when the face centre is blocked.</li>
     * </ul>
     */
    public static Vec3 insetFacePoint(Vec3 eye, Vec3 look, BlockPos placeAt, Direction support) {
        Direction.Axis axis = support.getAxis();
        double plane = planeCoord(placeAt, support);
        Vec3 raw = eye;
        if (look != null) {
            double la = axis.choose(look.x, look.y, look.z);
            if (Math.abs(la) > 1.0e-9) {
                double t = (plane - axis.choose(eye.x, eye.y, eye.z)) / la;
                if (t > 0) raw = eye.add(look.scale(t));
            }
        }
        double x = axis == Direction.Axis.X ? plane : clampToCell(raw.x, placeAt.getX());
        double y = axis == Direction.Axis.Y ? plane : clampToCell(raw.y, placeAt.getY());
        double z = axis == Direction.Axis.Z ? plane : clampToCell(raw.z, placeAt.getZ());
        return new Vec3(x, y, z);
    }

    private static double clampToCell(double v, int cellMin) {
        return Math.min(cellMin + 1.0 - FACE_INSET, Math.max(cellMin + FACE_INSET, v));
    }
}
