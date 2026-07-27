package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.pathing.moves.movements.BuildPlacementRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BuildPlacementRegistry.class)
public interface BuildPlacementRegistryAccessor {
    @Invoker("hasTarget")
    static boolean numen$hasTarget(ServerPlayer player, BlockPos pos) {
        throw new AssertionError("Mixin invoker was not transformed");
    }
}
