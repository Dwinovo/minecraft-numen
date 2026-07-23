package com.dwinovo.numen.core.mixin;

import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to vanilla's private successful-bite countdown. */
@Mixin(FishingHook.class)
public interface FishingHookAccessor {

    @Accessor("nibble")
    int numen$getNibble();
}
