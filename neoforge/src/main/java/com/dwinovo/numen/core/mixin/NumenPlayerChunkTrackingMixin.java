package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public abstract class NumenPlayerChunkTrackingMixin {
    @Inject(method = "skipPlayer", at = @At("HEAD"), cancellable = true)
    private void numen$skipFakePlayerChunkLoading(
        ServerPlayer player,
        CallbackInfoReturnable<Boolean> callback
    ) {
        if (player instanceof NumenPlayer) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "applyChunkTrackingView", at = @At("HEAD"), cancellable = true)
    private void numen$clearFakePlayerClientView(
        ServerPlayer player,
        ChunkTrackingView requestedView,
        CallbackInfo callback
    ) {
        if (player instanceof NumenPlayer) {
            player.setChunkTrackingView(ChunkTrackingView.EMPTY);
            callback.cancel();
        }
    }
}
