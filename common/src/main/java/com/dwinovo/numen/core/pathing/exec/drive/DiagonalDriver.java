package com.dwinovo.numen.core.pathing.exec.drive;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.core.pathing.movement.Movement;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Diagonal walk, cutting between two orthogonal corner cells. */
final class DiagonalDriver extends MoveDriver {

    DiagonalDriver(NumenPlayer player, Movement mv, double speed) {
        super(player, mv, speed);
    }

    @Override
    public void drive() {
        // Only sprint a diagonal when both cut corners are clear.
        InputDriver.stepToward(player, Vec3.atBottomCenterOf(mv.dest),
                sprintBase() && cornersClear());
    }

    @Override
    public boolean arrived() {
        return feet().equals(mv.dest);   // no overshoot tolerance on a diagonal
    }

    /** Live floor check — restores a dest floor dug since planning. */
    @Override
    public BlockPos scaffoldCell() {
        return floorUnderDest();
    }

    /** Diagonal cancel-safety: safe at the start cell, or when both cut
     *  corners have a floor; if we're cornering through an unwalkable corner cell, only
     *  safe when a block actually supports us (one of the four 0.25 offsets below). */
    @Override
    public boolean safeToCancel(boolean scaffoldCommitted, int ticksOnCurrent) {
        Level level = player.level();
        BlockPos feet = feet();
        if (feet.equals(mv.src)) return true;
        BlockPos floorA = new BlockPos(mv.src.getX(), mv.src.getY() - 1, mv.dest.getZ());
        BlockPos floorB = new BlockPos(mv.dest.getX(), mv.src.getY() - 1, mv.src.getZ());
        if (BlockHelper.canWalkOn(level, floorA) && BlockHelper.canWalkOn(level, floorB)) {
            return true;
        }
        BlockPos cornerA = new BlockPos(mv.src.getX(), mv.src.getY(), mv.dest.getZ());
        BlockPos cornerB = new BlockPos(mv.dest.getX(), mv.src.getY(), mv.src.getZ());
        if (feet.equals(cornerA) || feet.equals(cornerB)) {
            double off = 0.25;
            double x = player.getX(), y = player.getY() - 1, z = player.getZ();
            return BlockHelper.canWalkOn(level, BlockPos.containing(x + off, y, z + off))
                    || BlockHelper.canWalkOn(level, BlockPos.containing(x + off, y, z - off))
                    || BlockHelper.canWalkOn(level, BlockPos.containing(x - off, y, z + off))
                    || BlockHelper.canWalkOn(level, BlockPos.containing(x - off, y, z - off));
        }
        return true;
    }

    /** Both diagonal cut-corners clear — the gate for sprinting. */
    private boolean cornersClear() {
        BlockPos cornerA = new BlockPos(mv.src.getX(), mv.src.getY(), mv.dest.getZ());
        BlockPos cornerB = new BlockPos(mv.dest.getX(), mv.src.getY(), mv.src.getZ());
        return BlockHelper.canWalkThrough(player.level(), cornerA)
                && BlockHelper.canWalkThrough(player.level(), cornerA.above())
                && BlockHelper.canWalkThrough(player.level(), cornerB)
                && BlockHelper.canWalkThrough(player.level(), cornerB.above());
    }
}
