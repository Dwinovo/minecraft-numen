package com.dwinovo.numen.core.pathing.hier;

import com.dwinovo.numen.core.pathing.util.PathSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The coarse layer's front door for {@code PlayerNav}: build the (frozen,
 * per-dispatch) {@link CoarseField} for a long-range approach goal, backed by
 * a per-dimension {@link SummaryCache}. Returns null for short hops (the fine
 * search alone is cheapest there) — every consumer must treat null as "no
 * coarse information", never as failure. Tick-thread only.
 */
public final class CoarsePlanner {

    private static final ConcurrentHashMap<ResourceKey<Level>, SummaryCache> CACHES =
            new ConcurrentHashMap<>();

    private CoarsePlanner() {}

    /** Build the field toward {@code goalCenter}, or null when out of scope. */
    public static CoarseField fieldFor(Level level, BlockGetter view,
                                       BlockPos start, BlockPos goalCenter) {
        double minDist = PathSettings.COARSE_MIN_DISTANCE;
        if (start.distSqr(goalCenter) < minDist * minDist) {
            return null;
        }
        SummaryCache cache = CACHES.computeIfAbsent(level.dimension(),
                k -> new SummaryCache(PathSettings.COARSE_SUMMARY_TTL_TICKS));
        cache.tick(level.getGameTime());
        long t0 = System.nanoTime();
        CoarseField field = CoarseField.build(new McSampler(view), cache,
                start.getX(), start.getY(), start.getZ(),
                goalCenter.getX(), goalCenter.getY(), goalCenter.getZ(),
                16 * PathSettings.COST_HEURISTIC,
                PathSettings.COARSE_SOFT_CROSS_PENALTY,
                PathSettings.COARSE_SECTION_CAP,
                PathSettings.COARSE_EXACT_SCAN_CAP);
        com.dwinovo.numen.Constants.LOG.info(
                "[numen-path] coarse field start={} goal={} {} in {}ms",
                start.toShortString(), goalCenter.toShortString(), field.summary(),
                (System.nanoTime() - t0) / 1_000_000);
        return field;
    }

    /** Drop all cached summaries (dimension unload / tests). */
    public static void dropAll() {
        CACHES.clear();
    }
}
