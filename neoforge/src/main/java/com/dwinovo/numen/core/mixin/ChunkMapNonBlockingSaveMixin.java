package com.dwinovo.numen.core.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public abstract class ChunkMapNonBlockingSaveMixin {
    @Inject(
        method = "isExistingChunkFull",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;readChunk(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;"
        ),
        cancellable = true
    )
    private void numen$assumeUnknownDiskChunkIsFull(
        ChunkPos position,
        CallbackInfoReturnable<Boolean> callback
    ) {
        // A cache miss must not turn chunk unloading into a server-thread disk wait.
        callback.setReturnValue(true);
    }
}
