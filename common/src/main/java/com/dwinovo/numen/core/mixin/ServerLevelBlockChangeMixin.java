package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.scan.TargetIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feed every successful server-side block state change into {@link TargetIndex}.
 *
 * <p>{@code ServerLevel.updatePOIOnBlockStateChange} is the exact hook vanilla's own POI index
 * consumes: it fires for every change routed through {@code Level.setBlock} (players, pistons,
 * explosions, commands, growth, fluids) and for worldgen writes via {@code WorldGenRegion.setBlock}.
 * The handler is O(1) for irrelevant blocks and a single volatile read when no task has any target
 * registered, so this costs effectively nothing while no companion is mining.
 *
 * <p>1.21.5 只把这个钩子改了名({@code onBlockStateChange} → {@code updatePOIOnBlockStateChange}),
 * 签名与两处调用点一字未动,语义因此完全一致。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelBlockChangeMixin {

    @Inject(method = "updatePOIOnBlockStateChange", at = @At("HEAD"))
    private void numen$feedTargetIndex(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        TargetIndex.onBlockChange((ServerLevel) (Object) this, pos, oldState, newState);
    }
}
