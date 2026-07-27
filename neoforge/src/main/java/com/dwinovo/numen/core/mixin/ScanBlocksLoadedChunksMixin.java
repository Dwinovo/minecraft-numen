package com.dwinovo.numen.core.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "com.dwinovo.numen.core.task.ScanBlocksJob")
public abstract class ScanBlocksLoadedChunksMixin {
    @Redirect(
        method = "nextColumn",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;"
        ),
        require = 1
    )
    private ChunkAccess numen$getLoadedChunkNow(
        ServerLevel level,
        int chunkX,
        int chunkZ,
        ChunkStatus ignoredStatus,
        boolean ignoredCreate
    ) {
        return level.getChunkSource().getChunkNow(chunkX, chunkZ);
    }

    @Redirect(
        method = "nextColumn",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"
        ),
        require = 1
    )
    private LevelChunk numen$doNotForceChunkLoad(ServerLevel level, int chunkX, int chunkZ) {
        return level.getChunkSource().getChunkNow(chunkX, chunkZ);
    }
}
