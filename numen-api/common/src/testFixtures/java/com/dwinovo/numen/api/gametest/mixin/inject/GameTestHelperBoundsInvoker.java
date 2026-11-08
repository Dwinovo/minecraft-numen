package com.dwinovo.numen.api.gametest.mixin.inject;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 场地的范围:这一代原版的 {@code GameTestHelper.getBounds} 是私有的。只经 {@link com.dwinovo.numen.api.gametest.TestBounds} 用。 */
@Mixin(GameTestHelper.class)
public interface GameTestHelperBoundsInvoker {

    @Invoker("getBounds")
    AABB numen$bounds();
}
