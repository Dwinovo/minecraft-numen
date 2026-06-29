package com.dwinovo.numen.task.tasks;

/**
 * Target-selection economics for {@code auto_mine}. Every bot we surveyed
 * (Baritone MineProcess, Altoclef, mineflayer-collectblock) picks targets by
 * pure nearest-first — which, on a surface made of dirt-over-stone, means
 * "the nearest stone is under my feet", so bulk collection digs a vertical
 * shaft and then spends the yield pillaring back out. The fix is a scoring
 * bias, not a mode: blocks BELOW the pet's feet pay a per-block depth
 * penalty, so lateral deposits (hillsides, cliff faces — walk-out free) win
 * whenever they exist, while genuinely-deep targets (diamonds) still win on
 * necessity because nothing lateral matches.
 */
final class MiningEconomics {

    /**
     * Score added per block of depth below the pet's feet. Calibrated against
     * the real exit cost: climbing back out costs roughly a jump (~5 ticks)
     * or a scaffold block per level — call it 3 distance-equivalents. At 3,
     * stone 4 below loses to stone 12 blocks sideways; ore 30 below with no
     * lateral alternative is barely affected relative to its necessity.
     */
    static final double DEPTH_PENALTY_PER_BLOCK = 3.0;

    private MiningEconomics() {}

    /**
     * Selection score for a candidate target (lower = better): Euclidean
     * distance plus the depth penalty for every block below the feet.
     * Targets at or above feet level score exactly their distance.
     */
    static double score(double distance, int feetY, int targetY) {
        return distance + DEPTH_PENALTY_PER_BLOCK * Math.max(0, feetY - targetY);
    }
}
