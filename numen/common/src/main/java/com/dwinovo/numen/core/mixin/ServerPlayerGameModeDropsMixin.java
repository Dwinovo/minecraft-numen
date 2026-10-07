package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.act.Drops;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 玩家的手挖掉一格的那一段:原版不论秒破、累着进度挖碎还是服务端延迟落地(在她自己的玩家刻里,不在挖掘任务的刻里),都经
 * {@code destroyBlock};这一段里生成的掉落物是这一挖的,由 {@link Drops} 认领。原版没有"谁的手挖掉了这一格、掉了什么"的事件,
 * NeoForge 的掉落事件 Fabric 没有,common 不按加载器分支,所以挂在两边共有的这一处。
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeDropsMixin {

    @Shadow
    @Final
    protected ServerPlayer player;

    @WrapMethod(method = "destroyBlock")
    private boolean numen$breaking(BlockPos pos, Operation<Boolean> original) {
        Drops.breakStarts(player);
        try {
            return original.call(pos);
        } finally {
            Drops.breakEnds();
        }
    }
}
