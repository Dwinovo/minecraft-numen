package com.dwinovo.numen.core.act;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.DoubleUnaryOperator;

/** Projectile aiming helpers for gravity-affected arrows. */
public final class Ballistics {

    public record Aim(Vec3 lookPoint, Vec3 direction, double travelTicks) {}

    private record SimulationHit(double ticks, double centerDistance) {}

    private static final int MAX_SIMULATION_TICKS = 80;
    private static final double PROJECTILE_EYE_OFFSET = 0.10000000149011612;
    private static final double EPS = 1.0e-6;

    private Ballistics() {}

    public static Aim findArrowShot(Level level, Entity shooter, Entity target,
                                    double velocity, double gravity, double drag,
                                    double hitboxRadius, double maxRange,
                                    boolean copiesShooterVelocity) {
        if (velocity <= EPS || gravity < 0.0 || drag <= 0.0 || target.isRemoved()) {
            return null;
        }
        Vec3 eye = projectileStart(shooter);
        Vec3 lookOrigin = shooter.getEyePosition();
        AABB box = target.getBoundingBox();
        Vec3 center = boxCenter(box);
        if (eye.distanceToSqr(center) > maxRange * maxRange) {
            return null;
        }

        Vec3 currentLook = shooter.getViewVector(1.0f).normalize();
        Vec3 targetVelocity = target.getDeltaMovement();
        Vec3 shooterVelocity = inheritedVelocity(shooter.getDeltaMovement(), shooter.onGround(), copiesShooterVelocity);
        List<Vec3> candidates = new ArrayList<>();

        addDragAwareCandidates(candidates, eye, box, targetVelocity, velocity, gravity, drag, hitboxRadius);
        addParabolicFallbackCandidates(candidates, eye, box, targetVelocity, velocity, gravity);
        candidates.sort(Comparator.comparingDouble(direction -> angleDegrees(currentLook, direction)));

        Aim best = null;
        double bestScore = Double.MAX_VALUE;
        for (Vec3 direction : candidates) {
            if (direction == null || direction.lengthSqr() < EPS) continue;
            direction = direction.normalize();
            SimulationHit hit = simulate(level, shooter, target, targetVelocity, eye,
                    direction.scale(velocity).add(shooterVelocity), gravity, drag, hitboxRadius);
            if (hit == null) continue;
            double score = hit.centerDistance + angleDegrees(currentLook, direction) * 0.03 + hit.ticks * 0.01;
            if (score < bestScore) {
                bestScore = score;
                best = new Aim(lookOrigin.add(direction.scale(64.0)), direction, hit.ticks);
            }
        }
        return best;
    }

    /** A simple no-drag fallback retained for callers that only need a look point. */
    public static Vec3 aimPoint(Vec3 eye, Vec3 target, double v, double g) {
        Vec3 direction = solveDirection(eye, target, v, g);
        return direction == null ? target : eye.add(direction.scale(64.0));
    }

    static Vec3 projectileStart(double x, double eyeY, double z) {
        return new Vec3(x, eyeY - PROJECTILE_EYE_OFFSET, z);
    }

    static Vec3 inheritedVelocity(Vec3 movement, boolean onGround, boolean copiesShooterVelocity) {
        if (!copiesShooterVelocity) {
            return new Vec3(0.0, 0.0, 0.0);
        }
        return new Vec3(movement.x, onGround ? 0.0 : movement.y, movement.z);
    }

    static Vec3 solveDirection(Vec3 eye, Vec3 target, double velocity, double gravity) {
        double dx = target.x - eye.x;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < EPS) {
            return target.subtract(eye).normalize();
        }
        double y = target.y - eye.y;
        double v2 = velocity * velocity;
        double root = v2 * v2 - gravity * (gravity * horizontal * horizontal + 2.0 * y * v2);
        if (root < 0.0) {
            return null;
        }
        double tanTheta = (v2 - Math.sqrt(root)) / (gravity * horizontal);
        Vec3 lookPoint = new Vec3(target.x, eye.y + tanTheta * horizontal, target.z);
        Vec3 direction = lookPoint.subtract(eye);
        return direction.lengthSqr() < EPS ? null : direction.normalize();
    }

    static Vec3 directionByTime(Vec3 eye, Vec3 target, double ticks,
                                double velocity, double gravity, double drag) {
        if (ticks <= EPS || velocity <= EPS || drag <= 0.0) {
            return null;
        }
        double rPow = Math.pow(drag, ticks);
        double resistance = drag - 1.0;
        double distanceTerm = rPow - 1.0;
        if (Math.abs(resistance) < EPS || Math.abs(distanceTerm) < EPS) {
            return null;
        }
        double horizontalFactor = resistance / (velocity * distanceTerm);
        double verticalGravity = gravity * (rPow - drag * ticks + ticks - 1.0)
                / (velocity * resistance * distanceTerm);
        Vec3 direction = new Vec3(
                (target.x - eye.x) * horizontalFactor,
                (target.y - eye.y) * horizontalFactor + verticalGravity,
                (target.z - eye.z) * horizontalFactor);
        return allFinite(direction) ? direction : null;
    }

    static Vec3 velocityOnImpact(Vec3 initialDirection, double ticks,
                                 double velocity, double gravity, double drag) {
        if (initialDirection == null || ticks <= EPS || velocity <= EPS || drag <= 0.0) {
            return null;
        }
        double rPow = Math.pow(drag, ticks);
        double resistance = drag - 1.0;
        if (Math.abs(resistance) < EPS) {
            return null;
        }
        double logDrag = Math.log(drag);
        Vec3 impact = new Vec3(
                (initialDirection.x * rPow * logDrag * velocity) / resistance,
                (initialDirection.y * resistance * rPow * logDrag * velocity
                        - gravity * (rPow * logDrag - drag + 1.0)) / (resistance * resistance),
                (initialDirection.z * rPow * logDrag * velocity) / resistance);
        return allFinite(impact) && impact.lengthSqr() >= EPS ? impact : null;
    }

    public static double angleDegrees(Vec3 a, Vec3 b) {
        if (a == null || b == null || a.lengthSqr() < EPS || b.lengthSqr() < EPS) {
            return Double.MAX_VALUE;
        }
        double dot = a.normalize().dot(b.normalize());
        dot = clamp(dot, -1.0, 1.0);
        return Math.toDegrees(Math.acos(dot));
    }

    private static void addDragAwareCandidates(List<Vec3> out, Vec3 eye, AABB box, Vec3 targetVelocity,
                                               double velocity, double gravity, double drag,
                                               double hitboxRadius) {
        Vec3 center = boxCenter(box);
        double distance = eye.distanceTo(center);
        double maxTravelTime = clamp(distance / velocity * 1.75, 1.0, MAX_SIMULATION_TICKS);
        TimeSearchResult result = findMinimum(0.05, maxTravelTime, ticks -> {
            Vec3 direction = directionByTime(eye, center.add(targetVelocity.scale(ticks)),
                    ticks, velocity, gravity, drag);
            return direction == null ? Double.MAX_VALUE : Math.abs(direction.length() - 1.0);
        });
        if (result.delta > 0.1) {
            return;
        }

        double preciseTicks = result.ticks;
        double roundedTicks = Math.max(1.0, Math.rint(preciseTicks));
        Vec3 impactCenter = center.add(targetVelocity.scale(preciseTicks));
        AABB predictedBox = box.move(targetVelocity.scale(preciseTicks));
        addHittablePointCandidate(out, eye, impactCenter, predictedBox.inflate(hitboxRadius),
                preciseTicks, roundedTicks, velocity, gravity, drag);
        for (Vec3 point : sampleBox(predictedBox.inflate(hitboxRadius))) {
            addDirectionForTime(out, eye, point, preciseTicks, velocity, gravity, drag);
            addDirectionForTime(out, eye, point, roundedTicks, velocity, gravity, drag);
        }
    }

    private static void addHittablePointCandidate(List<Vec3> out, Vec3 eye, Vec3 impactCenter,
                                                  AABB targetBox, double preciseTicks, double roundedTicks,
                                                  double velocity, double gravity, double drag) {
        Vec3 centerDirection = directionByTime(eye, impactCenter, preciseTicks, velocity, gravity, drag);
        if (centerDirection == null) return;
        Vec3 inbound = velocityOnImpact(centerDirection.normalize(), preciseTicks, velocity, gravity, drag);
        if (inbound == null) return;
        Vec3 point = findHittablePosition(eye, inbound.normalize(), impactCenter, targetBox);
        if (point == null) return;
        addDirectionForTime(out, eye, point, preciseTicks, velocity, gravity, drag);
        addDirectionForTime(out, eye, point, roundedTicks, velocity, gravity, drag);
    }

    static Vec3 findHittablePosition(Vec3 eye, Vec3 directionOnImpact,
                                     Vec3 entityPositionOnImpact, AABB targetBox) {
        if (directionOnImpact == null || directionOnImpact.lengthSqr() < EPS) return null;
        Vec3 virtualEye = eye.add(0.0,
                directionOnImpact.y * -eye.distanceTo(entityPositionOnImpact),
                0.0);
        Vec3 center = boxCenter(targetBox);
        Vec3 best = null;
        double bestScore = Double.MAX_VALUE;

        Optional<Vec3> entry = targetBox.clip(virtualEye, center);
        if (entry.isPresent()) {
            Vec3 point = entry.get().add(center.subtract(entry.get()).scale(0.35));
            best = point;
            bestScore = point.distanceToSqr(center) * 0.5;
        }

        for (Vec3 point : sampleBox(targetBox)) {
            if (targetBox.clip(virtualEye, point).isEmpty()) continue;
            double score = point.distanceToSqr(center);
            if (score < bestScore) {
                bestScore = score;
                best = point;
            }
        }
        return best;
    }

    private static void addDirectionForTime(List<Vec3> out, Vec3 eye, Vec3 point, double ticks,
                                            double velocity, double gravity, double drag) {
        Vec3 direction = directionByTime(eye, point, ticks, velocity, gravity, drag);
        if (direction == null) {
            return;
        }
        double length = direction.length();
        if (length < EPS || Math.abs(length - 1.0) > 0.15) {
            return;
        }
        addCandidate(out, direction.normalize());
    }

    private static void addParabolicFallbackCandidates(List<Vec3> out, Vec3 eye, AABB box,
                                                       Vec3 targetVelocity, double velocity,
                                                       double gravity) {
        double estimateTicks = clamp(eye.distanceTo(boxCenter(box)) / velocity, 0.0, 30.0);
        AABB predictedBox = box.move(targetVelocity.scale(estimateTicks));
        for (Vec3 point : sampleBox(predictedBox)) {
            addCandidate(out, solveDirection(eye, point, velocity, gravity));
        }
    }

    private static void addCandidate(List<Vec3> out, Vec3 direction) {
        if (direction == null || direction.lengthSqr() < EPS || !allFinite(direction)) {
            return;
        }
        Vec3 normalized = direction.normalize();
        for (Vec3 existing : out) {
            if (angleDegrees(existing, normalized) < 0.05) {
                return;
            }
        }
        out.add(normalized);
    }

    private static SimulationHit simulate(Level level, Entity shooter, Entity target, Vec3 targetVelocity,
                                          Vec3 start, Vec3 initialVelocity, double gravity,
                                          double drag, double hitboxRadius) {
        Vec3 pos = start;
        Vec3 velocity = initialVelocity;
        for (int tick = 1; tick <= MAX_SIMULATION_TICKS; tick++) {
            Vec3 next = pos.add(velocity);
            AABB baseTargetBox = target.getBoundingBox().move(targetVelocity.scale(tick));
            AABB targetBox = baseTargetBox.inflate(hitboxRadius);
            Vec3 segmentStart = pos;
            Optional<Vec3> targetHit = targetBox.clip(segmentStart, next);
            double targetDistance = targetHit.map(hit -> hit.distanceToSqr(segmentStart)).orElse(Double.MAX_VALUE);

            BlockHitResult blockHit = level.clip(new ClipContext(
                    pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
            double blockDistance = blockHit.getType() == HitResult.Type.MISS
                    ? Double.MAX_VALUE : blockHit.getLocation().distanceToSqr(pos);
            double entityDistance = firstOtherEntityDistance(shooter, target, pos, next, hitboxRadius);

            if (targetHit.isPresent() && targetDistance <= blockDistance + EPS
                    && targetDistance <= entityDistance + EPS) {
                return new SimulationHit(tick, distanceToSegment(boxCenter(baseTargetBox), segmentStart, next));
            }
            if (blockDistance < Double.MAX_VALUE || entityDistance < Double.MAX_VALUE) {
                return null;
            }

            pos = next;
            velocity = velocity.scale(drag).add(0.0, -gravity, 0.0);
        }
        return null;
    }

    private static double firstOtherEntityDistance(Entity shooter, Entity target, Vec3 from, Vec3 to,
                                                   double hitboxRadius) {
        AABB hitbox = new AABB(
                from.x - hitboxRadius, from.y - hitboxRadius, from.z - hitboxRadius,
                from.x + hitboxRadius, from.y + hitboxRadius, from.z + hitboxRadius);
        AABB sweep = hitbox.expandTowards(to.subtract(from)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                shooter, from, to, sweep,
                e -> e != shooter && e != target && !e.isSpectator() && e.isPickable() && e.isAlive(),
                Double.MAX_VALUE);
        return hit == null ? Double.MAX_VALUE : hit.getLocation().distanceToSqr(from);
    }

    private static double distanceToSegment(Vec3 point, Vec3 from, Vec3 to) {
        Vec3 segment = to.subtract(from);
        double lengthSqr = segment.lengthSqr();
        if (lengthSqr < EPS) {
            return point.distanceTo(from);
        }
        double t = clamp(point.subtract(from).dot(segment) / lengthSqr, 0.0, 1.0);
        return point.distanceTo(from.add(segment.scale(t)));
    }

    private static List<Vec3> sampleBox(AABB box) {
        double[] xs = axisSamples(box.minX, box.maxX);
        double[] ys = axisSamples(box.minY, box.maxY);
        double[] zs = axisSamples(box.minZ, box.maxZ);
        List<Vec3> out = new ArrayList<>(xs.length * ys.length * zs.length);
        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    out.add(new Vec3(x, y, z));
                }
            }
        }
        return out;
    }

    private static double[] axisSamples(double min, double max) {
        double size = max - min;
        if (size <= 0.2) {
            return new double[] { (min + max) * 0.5 };
        }
        double inset = Math.min(0.15, size * 0.25);
        return new double[] { (min + max) * 0.5, min + inset, max - inset };
    }

    private static Vec3 boxCenter(AABB box) {
        return new Vec3((box.minX + box.maxX) * 0.5,
                (box.minY + box.maxY) * 0.5,
                (box.minZ + box.maxZ) * 0.5);
    }

    private record TimeSearchResult(double ticks, double delta) {}

    private static TimeSearchResult findMinimum(double from, double to, DoubleUnaryOperator function) {
        double lower = from;
        double upper = to;
        while (upper - lower > 1.0e-4) {
            double mid = (lower + upper) * 0.5;
            double leftValue = function.applyAsDouble((lower + mid) * 0.5);
            double rightValue = function.applyAsDouble((mid + upper) * 0.5);
            if (leftValue < rightValue) {
                upper = mid;
            } else {
                lower = mid;
            }
        }
        double ticks = (lower + upper) * 0.5;
        return new TimeSearchResult(ticks, function.applyAsDouble(ticks));
    }

    private static boolean allFinite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private static Vec3 projectileStart(Entity shooter) {
        return projectileStart(shooter.getX(), shooter.getEyeY(), shooter.getZ());
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
