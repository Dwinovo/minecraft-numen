package com.dwinovo.numen.core.mixin;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "com.dwinovo.numen.core.task.LocateStructureTaskGoal")
public abstract class LocateStructureLoadedChunksMixin {
    @Redirect(
        method = "checkCandidate",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/StructureManager;checkStructurePresence(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/levelgen/structure/Structure;Lnet/minecraft/world/level/levelgen/structure/placement/StructurePlacement;Z)Lnet/minecraft/world/level/levelgen/structure/StructureCheckResult;"
        ),
        require = 1
    )
    private StructureCheckResult numen$skipCandidatesThatNeedChunkLoading(
        StructureManager manager,
        ChunkPos chunkPos,
        Structure structure,
        StructurePlacement placement,
        boolean skipKnownStructures
    ) {
        StructureCheckResult result = manager.checkStructurePresence(
            chunkPos,
            structure,
            placement,
            skipKnownStructures
        );
        return result == StructureCheckResult.CHUNK_LOAD_NEEDED
            ? StructureCheckResult.START_NOT_PRESENT
            : result;
    }
}
