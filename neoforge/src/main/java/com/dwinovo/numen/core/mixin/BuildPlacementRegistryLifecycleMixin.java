package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.pathing.moves.movements.BuildPlacementRegistry;
import com.dwinovo.numen.core.scaffold.TemporaryScaffoldLedger;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BuildPlacementRegistry.class)
public abstract class BuildPlacementRegistryLifecycleMixin {
    @Inject(method = "unregister", at = @At("HEAD"))
    private static void numen$adoptCompletedBuildTargets(
        ServerPlayer player,
        BuildPlacementRegistry.Provider provider,
        CallbackInfo callback
    ) {
        if (!(player instanceof NumenPlayer companion) || provider == null) {
            return;
        }

        ServerLevel level = companion.level();
        String dimensionId = level.dimension().identifier().toString();
        for (TemporaryScaffoldLedger.Entry entry
            : TemporaryScaffoldLedger.entries(companion.getUUID())) {
            if (!dimensionId.equals(entry.dimensionId())) {
                continue;
            }

            BlockPos pos = new BlockPos(entry.x(), entry.y(), entry.z());
            if (level.isLoaded(pos)
                && provider.acceptsPlacement(pos, level.getBlockState(pos))) {
                TemporaryScaffoldLedger.markExplicitBuildTarget(
                    companion.getUUID(),
                    dimensionId,
                    entry.x(),
                    entry.y(),
                    entry.z()
                );
            }
        }
    }
}
