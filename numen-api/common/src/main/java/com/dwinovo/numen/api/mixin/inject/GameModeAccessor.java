package com.dwinovo.numen.api.mixin.inject;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayerGameMode;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 服务端对玩家挖掘的记账(见 {@code IBreakingState}):正在破坏的那一格与挂着的延迟破坏。 */
@Mixin(ServerPlayerGameMode.class)
public interface GameModeAccessor {

    @Accessor("isDestroyingBlock")
    boolean numen$isDestroyingBlock();

    @Accessor("destroyPos")
    BlockPos numen$destroyPos();

    @Accessor("hasDelayedDestroy")
    boolean numen$hasDelayedDestroy();

    @Accessor("delayedDestroyPos")
    BlockPos numen$delayedDestroyPos();
}
