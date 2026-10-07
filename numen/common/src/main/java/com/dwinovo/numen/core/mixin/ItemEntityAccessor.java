package com.dwinovo.numen.core.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to vanilla's private pickup-delay countdown and its "never" marker. */
@Mixin(ItemEntity.class)
public interface ItemEntityAccessor {

    @Accessor("pickupDelay")
    int numen$getPickupDelay();

    @Accessor("INFINITE_PICKUP_DELAY")
    static int numen$infinitePickupDelay() {
        throw new AssertionError();
    }
}
