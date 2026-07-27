package com.dwinovo.numen.core.mixin;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "de.z0rdak.yawp.handler.flags.WorldFlagHandler", remap = false)
public abstract class YawpDimensionTravelMixin {
    @Redirect(
        method = "onTravelToDim",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;close()V",
            remap = false
        ),
        require = 2,
        remap = false
    )
    private static void numen$keepActiveLevelOpen(Level level) {
    }
}
