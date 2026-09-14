package com.dwinovo.numen.core.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 服务端挖掘状态机的只读视图。挖掘器照真客户端发 START/STOP,服务端收没收下这一下只记在这几个私有字段里:
 * START 收下了才有 {@code isDestroyingBlock} 对着那一格;STOP 时服务端按自己的钟算进度,不够就挂成
 * {@code hasDelayedDestroy} 过几刻自己挖掉。挖掘器读它们区分"服务端还在挖"与"服务端把这一下退回来了"。
 */
@Mixin(ServerPlayerGameMode.class)
public interface ServerPlayerGameModeAccessor {

    @Accessor("isDestroyingBlock")
    boolean numen$isDestroyingBlock();

    @Accessor("destroyPos")
    BlockPos numen$destroyPos();

    @Accessor("hasDelayedDestroy")
    boolean numen$hasDelayedDestroy();

    @Accessor("delayedDestroyPos")
    BlockPos numen$delayedDestroyPos();
}
