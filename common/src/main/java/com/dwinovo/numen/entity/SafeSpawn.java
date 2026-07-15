package com.dwinovo.numen.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Finds a safe landing spot for a companion body near a point (its owner). A raw
 * {@code owner.position()} spawn suffocates the body when the owner is in a tight
 * tunnel / crawling under a low ceiling: the full player hitbox overlaps blocks,
 * takes in-wall damage every tick, dies, and respawns into the same spot — a
 * death loop until the owner happens to move.
 *
 * <p>Search shape: up to 10 random samples in a 7×3×7 box around the anchor
 * (rejecting spots hugging the anchor so the body isn't shoved into a wall by
 * entity pushing in a narrow space), then a deterministic sweep nearest-first
 * that also climbs upward (owner at the bottom of a pit). Every candidate must
 * offer full-block footing that isn't hazardous, feet/head clear of fire, lava
 * and (at head height) any fluid, and room for a standing player hitbox.
 */
@com.dwinovo.numen.api.Internal
final class SafeSpawn {

    private static final int RANDOM_ATTEMPTS = 10;
    private static final int HORIZONTAL_RANGE = 3;
    private static final int UPWARD_SWEEP = 8;
    private static final double HALF_WIDTH = 0.3;
    private static final double HEIGHT = 1.8;

    private SafeSpawn() {}

    /**
     * A safe standing spot near {@code center} (block-bottom-centered), or
     * {@code null} if the whole neighborhood is unusable right now — the caller
     * decides whether to fall back or wait and retry.
     */
    static Vec3 findNear(ServerLevel level, Vec3 center) {
        BlockPos origin = BlockPos.containing(center);
        RandomSource random = level.getRandom();
        for (int i = 0; i < RANDOM_ATTEMPTS; i++) {
            int dx = random.nextInt(HORIZONTAL_RANGE * 2 + 1) - HORIZONTAL_RANGE;
            int dy = random.nextInt(3) - 1;
            int dz = random.nextInt(HORIZONTAL_RANGE * 2 + 1) - HORIZONTAL_RANGE;
            if (Math.abs(dx) < 2 && Math.abs(dz) < 2) continue;   // not on top of the anchor
            BlockPos pos = origin.offset(dx, dy, dz);
            if (isSafe(level, pos)) return Vec3.atBottomCenterOf(pos);
        }
        // Random phase dry — sweep deterministically, closest ring first per layer,
        // climbing from one below the anchor up out of a possible pit.
        for (int dy = -1; dy <= UPWARD_SWEEP; dy++) {
            for (int r = 0; r <= HORIZONTAL_RANGE; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        BlockPos pos = origin.offset(dx, dy, dz);
                        if (isSafe(level, pos)) return Vec3.atBottomCenterOf(pos);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Whether a standing player hitbox fits at this exact (non-grid) point.
     * Fallback check for anchors on non-full-block floors (slabs, carpet) where
     * no grid candidate has sturdy footing: wherever the owner can stand, the
     * body can too.
     */
    static boolean hasStandingRoom(ServerLevel level, Vec3 pos) {
        return level.noCollision(standingBox(pos.x, pos.y, pos.z));
    }

    private static boolean isSafe(ServerLevel level, BlockPos pos) {
        if (!level.getWorldBorder().isWithinBounds(pos)) return false;
        BlockPos belowPos = pos.below();
        BlockState footing = level.getBlockState(belowPos);
        if (!footing.isFaceSturdy(level, belowPos, Direction.UP)) return false;
        if (footing.is(Blocks.MAGMA_BLOCK) || footing.is(Blocks.CACTUS)) return false;
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        if (feet.is(BlockTags.FIRE) || head.is(BlockTags.FIRE)) return false;
        if (feet.getFluidState().is(FluidTags.LAVA)) return false;
        if (!head.getFluidState().isEmpty()) return false;   // head must be breathable
        return level.noCollision(standingBox(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
    }

    private static AABB standingBox(double x, double y, double z) {
        return new AABB(x - HALF_WIDTH, y, z - HALF_WIDTH, x + HALF_WIDTH, y + HEIGHT, z + HALF_WIDTH);
    }
}
