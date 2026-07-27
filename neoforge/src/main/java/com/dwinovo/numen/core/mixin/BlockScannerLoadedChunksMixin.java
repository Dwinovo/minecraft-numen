package com.dwinovo.numen.core.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "com.dwinovo.numen.core.scan.BlockScanner")
public abstract class BlockScannerLoadedChunksMixin {
    @ModifyConstant(
        method = "captureRings",
        constant = @Constant(intValue = 4096),
        require = 1
    )
    private static int numen$capRingScan(int originalGuardSquared) {
        return Math.min(originalGuardSquared, 256);
    }

    @Redirect(
        method = {"findWithin", "captureLoadedChunks", "captureRings"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;"
        ),
        require = 3
    )
    private static ChunkAccess numen$getLoadedChunkNow(
        Level level,
        int chunkX,
        int chunkZ,
        ChunkStatus ignoredStatus,
        boolean ignoredCreate
    ) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        return serverLevel.getChunkSource().getChunkNow(chunkX, chunkZ);
    }
}
