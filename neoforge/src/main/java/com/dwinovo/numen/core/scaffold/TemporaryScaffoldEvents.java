package com.dwinovo.numen.core.scaffold;

import com.dwinovo.numen.core.mixin.BuildPlacementRegistryAccessor;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.level.BlockEvent;

public final class TemporaryScaffoldEvents {
    private TemporaryScaffoldEvents() {
    }

    public static void onEntityPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof NumenPlayer player)
            || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        BlockPos pos = event.getPos().immutable();
        boolean explicitBuildTarget = BuildPlacementRegistryAccessor.numen$hasTarget(player, pos);
        BlockState placed = event.getPlacedBlock();
        BlockState previous = event.getBlockSnapshot().getState();
        TemporaryScaffoldTracker.recordObservedPlacement(
            player.getUUID(),
            level.dimension().identifier().toString(),
            pos.getX(),
            pos.getY(),
            pos.getZ(),
            BuiltInRegistries.BLOCK.getKey(placed.getBlock()).toString(),
            BuiltInRegistries.BLOCK.getKey(previous.getBlock()).toString(),
            explicitBuildTarget,
            level.getGameTime()
        );
    }
}
