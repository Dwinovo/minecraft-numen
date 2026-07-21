package com.dwinovo.numen.core.act;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * One-shot placement resolver: from where the body stands right now, compute either a
 * line-of-sight-verified {@link BlockHitResult} for placing at {@code placeAt}, or a
 * structured diagnosis of why none exists ({@link PlaceResolution}). Cheap checks run
 * first and short-circuit; rays are spent last, best candidate first, under a hard
 * budget ({@value #RAY_BUDGET} clips per resolution):
 *
 * <ol>
 *   <li><b>Entity pre-check</b> — a building-blocking entity overlapping the target cell
 *       makes vanilla refuse every press, so it is diagnosed up front
 *       ({@link PlaceResolution.Reason#BLOCKED_BY_ENTITY}) without burning a single ray.
 *       The resolving player itself is exempt: self-overlap is the maneuver's business
 *       (it backs the body off, which needs a resolved hit to aim at).</li>
 *   <li><b>Support enumeration</b> — all six neighbours (UP included: placing against a
 *       ceiling is legal) that expose a usable shape or known sturdy shared face. An empty
 *       set with nothing clickable in the cell is {@link PlaceResolution.Reason#NO_SUPPORT},
 *       immediately.</li>
 *   <li><b>Face ranking</b> — back-facing planes are dropped (geometrically unhittable)
 *       and the rest sorted most-facing-first, so the first ray is the likeliest
 *       ({@link PlaceGeometry#rankVisible}). Zero rays spent so far.</li>
 *   <li><b>Budgeted raycast</b> — per face: try the biased shared-face centre,
 *       a sight-line inset point, and fixed face samples. Up/down support faces use
 *       centre plus four edge-safe offsets; side faces use low/high samples so slab and
 *       stair halves can be selected. A hit must land on the support block, on the face
 *       pointing back at the target (that face selects the relative cell vanilla places
 *       into), and pass the caller's optional hit verifier.</li>
 *   <li><b>Direct click-replace</b> — a block already sitting in the target cell (grass,
 *       snow layer, litter) is aimed at directly and replaced; its own shape is sampled
 *       (centre + face centres) within the same ray budget.</li>
 * </ol>
 *
 * <p>When everything fails, the diagnosis distinguishes {@code OUT_OF_REACH} (support
 * exists, all of it beyond arm's length) from {@code NO_LINE_OF_SIGHT} (support in reach
 * but occluded), and — when support exists — carries a {@code suggestedStance}: a standable
 * cell 2–3 blocks out from the best face, from which that face should be visible, so the
 * task layer repositions to a computed spot instead of sampling blind stances.
 *
 * <p>The eye is the crouching eye when {@code wouldSneak} (the body sneaks while placing,
 * so that IS the real eye) — the "lean over a ledge" reach. Clip is {@code OUTLINE}.
 * A press is never fabricated: no ray, no hit.
 *
 * <p>Shared by the {@code place_block} task and the pathfinder's bridge/pillar scaffold
 * placement, so both place identically and natively.
 */
public final class Placement {

    private Placement() {}

    /** Hard cap on {@code level.clip} calls per resolution — the search is a computation,
     *  never a grind. Typical resolutions still commit on the first few rays. */
    static final int RAY_BUDGET = 32;

    /** Reach slack for the face-centre pre-filter: the nearest hittable point of a face can
     *  be up to about half a cell diagonal nearer than its centre. */
    private static final double REACH_MARGIN = 0.7;

    /** Down/up/N/S/W/E face-centre offsets, resolved against the target's actual shape
     *  bounds for the direct click-replace sampling. */
    private static final Vec3[] BLOCK_SIDES = {
            new Vec3(0.5, 0, 0.5),   // Down
            new Vec3(0.5, 1, 0.5),   // Up
            new Vec3(0.5, 0.5, 0),   // North
            new Vec3(0.5, 0.5, 1),   // South
            new Vec3(0, 0.5, 0.5),   // West
            new Vec3(1, 0.5, 0.5)};  // East

    /**
     * A line-of-sight-verified support hit for placing at {@code placeAt}, or {@code null}
     * when no angle reaches a support face nor the (replaceable) target itself from where
     * the body stands. Hit-or-null view of {@link #resolveDetailed}.
     */
    public static BlockHitResult resolve(NumenPlayer player, BlockPos placeAt, boolean wouldSneak) {
        return resolveDetailed(player, placeAt, wouldSneak, null).hit();
    }

    /**
     * As {@link #resolve(NumenPlayer, BlockPos, boolean)}, with an optional absolute
     * {@code aimY} overriding the default support-face aim height — the click height on a
     * face is what selects a slab/stair half, so a caller with a half preference biases it
     * (e.g. {@code y+0.72} for top).
     */
    public static BlockHitResult resolve(NumenPlayer player, BlockPos placeAt, boolean wouldSneak, Double aimY) {
        return resolveDetailed(player, placeAt, wouldSneak, aimY).hit();
    }

    /**
     * Full resolution: a verified hit, or the structured reason none exists — see the
     * class doc for the pipeline. Never returns {@code null}.
     */
    public static PlaceResolution resolveDetailed(NumenPlayer player, BlockPos placeAt,
                                                  boolean wouldSneak, Double aimY) {
        return resolveDetailed(player, placeAt, wouldSneak, aimY, null);
    }

    public static PlaceResolution resolveDetailed(NumenPlayer player, BlockPos placeAt,
                                                  boolean wouldSneak, Double aimY,
                                                  Predicate<BlockHitResult> hitVerifier) {
        Level level = player.level();
        double reach = player.blockInteractionRange();
        Vec3 eye = eye(player, wouldSneak);

        // ---- 1. entity pre-check (no rays): vanilla refuses any placement whose block
        // would overlap a building-blocking entity, so every press is doomed until it
        // moves. The full-cell box is the exact test for full cubes and conservative for
        // sub-cube blocks — acceptable, the task layer retries on a diagnosis. Our own
        // body is exempt: the maneuver handles self-overlap by backing off.
        if (hitVerifier == null) {
            Entity blocker = obstructingEntity(level, player, placeAt);
            if (blocker != null) {
                return PlaceResolution.failure(PlaceResolution.Reason.BLOCKED_BY_ENTITY,
                        "can't place at " + placeAt.toShortString() + " — "
                                + blocker.getName().getString() + " is standing in that cell and a block"
                                + " can't be placed inside a creature; wait for it to move or lead it away");
            }
        }

        // ---- 2. support candidates: all six neighbours with a sturdy shared face.
        List<Direction> supports = new ArrayList<>(6);
        for (Direction dir : Direction.values()) {
            if (supportCandidate(level, placeAt, dir)) {
                supports.add(dir);
            }
        }
        // A block already sitting in the cell is a click-replace candidate (stage 5).
        boolean clickableTarget = !level.getBlockState(placeAt).isAir();
        if (supports.isEmpty() && !clickableTarget) {
            return PlaceResolution.failure(PlaceResolution.Reason.NO_SUPPORT,
                    "can't place at " + placeAt.toShortString() + " — nothing solid beside, above or"
                            + " below it to place against (it's floating in air)."
                            + " Pick a cell that touches solid ground.");
        }

        // ---- 3./4. rank the visible faces, then raycast best-first under the budget.
        List<Direction> ranked = PlaceGeometry.rankVisible(eye, placeAt, supports);
        Vec3 look = player.getLookAngle();
        double reachSqr = (reach + REACH_MARGIN) * (reach + REACH_MARGIN);
        int rays = 0;
        boolean triedAny = false;        // at least one ray was actually spent
        boolean skippedForReach = false; // at least one candidate face was beyond reach
        BlockPos[] occluder = {null};    // first non-face block a sample ray struck
        for (Direction dir : ranked) {
            if (rays >= RAY_BUDGET) break;
            if (eye.distanceToSqr(PlaceGeometry.sharedFaceCenter(placeAt, dir)) > reachSqr) {
                skippedForReach = true;
                continue;
            }
            BlockPos against = placeAt.relative(dir);
            Predicate<BlockHitResult> onFace = res -> res.getBlockPos().equals(against)
                    && against.relative(res.getDirection()).equals(placeAt);
            for (Vec3 point : supportSamples(level, placeAt, dir, aimY, eye, look)) {
                if (rays >= RAY_BUDGET) break;
                triedAny = true;
                BlockHitResult hit = castFromEye(player, eye, point, reach, onFace, occluder);
                rays++;
                PlaceResolution accepted = acceptedHit(player, hit, hitVerifier, eye, point);
                if (accepted != null) return accepted;
            }
        }

        // ---- 5. direct click-replace of whatever sits in the cell, same ray budget.
        if (clickableTarget && rays < RAY_BUDGET) {
            BlockState state = level.getBlockState(placeAt);
            VoxelShape shape = state.getShape(level, placeAt);
            if (shape.isEmpty()) shape = Shapes.block();
            Predicate<BlockHitResult> onTarget = res -> res.getBlockPos().equals(placeAt);
            triedAny = true;
            Vec3 targetCenter = blockCenter(level, placeAt);
            BlockHitResult hit = castFromEye(player, eye, targetCenter, reach, onTarget, occluder);
            rays++;
            PlaceResolution accepted = acceptedHit(player, hit, hitVerifier, eye, targetCenter);
            if (accepted != null) return accepted;
            for (int i = 0; i < BLOCK_SIDES.length && rays < RAY_BUDGET; i++) {
                Vec3 m = BLOCK_SIDES[i];
                Vec3 point = new Vec3(
                        placeAt.getX() + shape.min(Direction.Axis.X) * m.x + shape.max(Direction.Axis.X) * (1 - m.x),
                        placeAt.getY() + shape.min(Direction.Axis.Y) * m.y + shape.max(Direction.Axis.Y) * (1 - m.y),
                        placeAt.getZ() + shape.min(Direction.Axis.Z) * m.z + shape.max(Direction.Axis.Z) * (1 - m.z));
                hit = castFromEye(player, eye, point, reach, onTarget, occluder);
                rays++;
                PlaceResolution sideAccepted = acceptedHit(player, hit, hitVerifier, eye, point);
                if (sideAccepted != null) return sideAccepted;
            }
        }

        // ---- 6. diagnosis. Best face = the most-facing visible one, else any sturdy one
        // (the stance suggestion doesn't need the current eye to like it).
        Direction best = !ranked.isEmpty() ? ranked.get(0)
                : (!supports.isEmpty() ? supports.get(0) : null);
        Vec3 stance = best == null ? null : suggestStance(level, placeAt, best);
        if (!triedAny && skippedForReach) {
            return PlaceResolution.failure(PlaceResolution.Reason.OUT_OF_REACH,
                    "every support face at " + placeAt.toShortString()
                            + " is beyond my arm's reach from where I stand — I need to get closer",
                    stance);
        }
        return PlaceResolution.occludedBy(
                "a support face exists at " + placeAt.toShortString() + " but my view of it is"
                        + " blocked from here — something solid sits between my eyes and the face",
                stance, occluder[0]);
    }

    private static PlaceResolution acceptedHit(NumenPlayer player, BlockHitResult hit,
                                               Predicate<BlockHitResult> hitVerifier,
                                               Vec3 eye, Vec3 point) {
        if (hit == null) {
            return null;
        }
        float yaw = MovementHelper.yawTo(eye, point);
        float pitch = MovementHelper.pitchTo(eye, point);
        if (hitVerifier == null) {
            return PlaceResolution.success(hit, yaw, pitch);
        }
        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        try {
            player.setYRot(yaw);
            player.setXRot(pitch);
            return hitVerifier.test(hit) ? PlaceResolution.success(hit, yaw, pitch) : null;
        } finally {
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
        }
    }

    private static List<Vec3> supportSamples(Level level, BlockPos placeAt, Direction support,
                                             Double aimY, Vec3 eye, Vec3 look) {
        List<Vec3> samples = new ArrayList<>(8);
        Vec3 centre = supportAim(placeAt, placeAt.relative(support), aimY);
        addDistinct(samples, centre);
        Vec3 alt = PlaceGeometry.insetFacePoint(eye, look, placeAt, support);
        if (alt.distanceToSqr(centre) < 0.01) {
            alt = PlaceGeometry.insetFacePoint(eye, null, placeAt, support);
        }
        addDistinct(samples, alt);

        BlockPos against = placeAt.relative(support);
        VoxelShape shape = level.getBlockState(against).getShape(level, against);
        if (shape.isEmpty()) {
            return samples;
        }
        switch (support) {
            case DOWN -> {
                addShapePoint(samples, against, shape, 0.5, 1.0, 0.5, null);
                addShapePoint(samples, against, shape, 0.1, 1.0, 0.5, null);
                addShapePoint(samples, against, shape, 0.9, 1.0, 0.5, null);
                addShapePoint(samples, against, shape, 0.5, 1.0, 0.1, null);
                addShapePoint(samples, against, shape, 0.5, 1.0, 0.9, null);
            }
            case UP -> {
                addShapePoint(samples, against, shape, 0.5, 0.0, 0.5, null);
                addShapePoint(samples, against, shape, 0.1, 0.0, 0.5, null);
                addShapePoint(samples, against, shape, 0.9, 0.0, 0.5, null);
                addShapePoint(samples, against, shape, 0.5, 0.0, 0.1, null);
                addShapePoint(samples, against, shape, 0.5, 0.0, 0.9, null);
            }
            case NORTH -> {
                addShapePoint(samples, against, shape, 0.5, 0.25, 1.0, aimY);
                addShapePoint(samples, against, shape, 0.5, 0.75, 1.0, aimY);
            }
            case SOUTH -> {
                addShapePoint(samples, against, shape, 0.5, 0.25, 0.0, aimY);
                addShapePoint(samples, against, shape, 0.5, 0.75, 0.0, aimY);
            }
            case WEST -> {
                addShapePoint(samples, against, shape, 1.0, 0.25, 0.5, aimY);
                addShapePoint(samples, against, shape, 1.0, 0.75, 0.5, aimY);
            }
            case EAST -> {
                addShapePoint(samples, against, shape, 0.0, 0.25, 0.5, aimY);
                addShapePoint(samples, against, shape, 0.0, 0.75, 0.5, aimY);
            }
        }
        return samples;
    }

    private static void addShapePoint(List<Vec3> samples, BlockPos pos, VoxelShape shape,
                                      double fx, double fy, double fz, Double absoluteY) {
        double y = absoluteY == null || absoluteY.isNaN()
                ? pos.getY() + lerp(shape.min(Direction.Axis.Y), shape.max(Direction.Axis.Y), fy)
                : absoluteY;
        addDistinct(samples, new Vec3(
                pos.getX() + lerp(shape.min(Direction.Axis.X), shape.max(Direction.Axis.X), fx),
                y,
                pos.getZ() + lerp(shape.min(Direction.Axis.Z), shape.max(Direction.Axis.Z), fz)));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static void addDistinct(List<Vec3> samples, Vec3 point) {
        for (Vec3 existing : samples) {
            if (existing.distanceToSqr(point) < 0.0001) {
                return;
            }
        }
        samples.add(point);
    }

    private static boolean supportCandidate(Level level, BlockPos placeAt, Direction dir) {
        BlockPos against = placeAt.relative(dir);
        if (canPlaceAgainst(level, against, dir.getOpposite())) {
            return true;
        }
        BlockState state = level.getBlockState(against);
        return !state.canBeReplaced() && !state.getShape(level, against).isEmpty();
    }

    /** Legacy support-face aim point: shared-face centre with Y biased 0.25 toward the
     *  support (lets the ray clear the block's own top when leaning over an edge);
     *  {@code aimY} — the slab/stair half bias — overrides the Y outright. */
    private static Vec3 supportAim(BlockPos placeAt, BlockPos against, Double aimY) {
        return new Vec3(
                (placeAt.getX() + against.getX() + 1.0) * 0.5,
                aimY != null ? aimY : (placeAt.getY() + against.getY() + 0.5) * 0.5,
                (placeAt.getZ() + against.getZ() + 1.0) * 0.5);
    }

    /** The first building-blocking entity (other than {@code self}) overlapping the target
     *  cell, or {@code null}. Mirrors the entity test vanilla runs on every placement. */
    private static Entity obstructingEntity(Level level, NumenPlayer self, BlockPos placeAt) {
        List<Entity> hits = level.getEntities(self, new AABB(placeAt),
                e -> !e.isRemoved() && e.blocksBuilding && !e.isSpectator());
        return hits.isEmpty() ? null : hits.get(0);
    }

    /** The (crouch-when-sneaking) eye position the resolution reasons and casts from. */
    private static Vec3 eye(NumenPlayer player, boolean wouldSneak) {
        return wouldSneak
                ? new Vec3(player.getX(), player.getY() + player.getEyeHeight(Pose.CROUCHING), player.getZ())
                : player.getEyePosition();
    }

    /** Raytrace from the eye toward {@code point}; return the hit if {@code ok}. A BLOCK
     *  hit that fails the test is the ray's OCCLUDER — the first one seen is recorded in
     *  {@code occluderSink[0]} so the diagnosis can name (and the maneuver maybe punch) it. */
    private static BlockHitResult castFromEye(NumenPlayer player, Vec3 eye, Vec3 point, double reach,
                                              Predicate<BlockHitResult> ok, BlockPos[] occluderSink) {
        Vec3 toPoint = point.subtract(eye);
        if (toPoint.lengthSqr() < 1.0e-6) return null;
        Vec3 end = eye.add(toPoint.normalize().scale(reach));
        BlockHitResult res = player.level().clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (res.getType() != HitResult.Type.BLOCK) return null;
        if (ok.test(res)) return res;
        if (occluderSink[0] == null) {
            occluderSink[0] = res.getBlockPos().immutable();
        }
        return null;
    }

    /** Centre of the collision shape (geometric centre if empty). */
    private static Vec3 blockCenter(Level level, BlockPos pos) {
        VoxelShape s = level.getBlockState(pos).getCollisionShape(level, pos);
        if (s.isEmpty()) {
            return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        }
        return new Vec3(
                pos.getX() + (s.min(Direction.Axis.X) + s.max(Direction.Axis.X)) / 2,
                pos.getY() + (s.min(Direction.Axis.Y) + s.max(Direction.Axis.Y)) / 2,
                pos.getZ() + (s.min(Direction.Axis.Z) + s.max(Direction.Axis.Z)) / 2);
    }

    /**
     * A standable cell from which the best support face should come into view: 2–3 blocks
     * straight out from the face (along its outward normal when horizontal; any cardinal
     * when the face is a floor/ceiling), at the target's height ±1. A simple heuristic —
     * the task layer's own navigation validates reachability.
     */
    private static Vec3 suggestStance(Level level, BlockPos placeAt, Direction support) {
        Direction outward = support.getOpposite();
        List<Direction> outs = outward.getAxis().isHorizontal()
                ? List.of(outward)
                : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
        for (Direction out : outs) {
            for (int d = 2; d <= 3; d++) {
                BlockPos base = placeAt.relative(out, d);
                for (int dy : new int[]{0, 1, -1}) {
                    BlockPos feet = base.above(dy);
                    if (BlockHelper.isStandable(level, feet)) {
                        return Vec3.atBottomCenterOf(feet);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Is the {@code face} of the block at {@code pos} a face we can aim at and place
     * against — sturdy per the same face test vanilla uses to judge support (full cubes,
     * glass, a top slab's top, a stair's solid back all qualify; a bottom slab's top does
     * not). See {@link BlockHelper#canPlaceAgainst} for the behaviour-special refusals.
     */
    public static boolean canPlaceAgainst(Level level, BlockPos pos, Direction face) {
        return BlockHelper.canPlaceAgainst(level, pos, face);
    }
}
