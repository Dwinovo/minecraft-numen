package com.dwinovo.numen.mixin;

import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 同伴 join 的落点前置。1.21.8 的 {@code placeNewPlayer} 在建网络会话前阻塞等
 * {@code player.chunkPosition()} 的区块<b>连实体存储一起</b>就绪
 * ({@code ServerLevel.waitForChunkAndEntities});没有 .dat 的新召唤在这一步之前
 * 被 vanilla 挪到世界出生点,于是等的是出生点区块。这一等在两种场景下饿死:
 * gametest 服务器与繁忙 tick——{@code managedBlock} 只在 {@code haveTime()} 时轮询
 * 区块任务,tick 预算耗尽后区块加载永不推进,服务器线程从此停摆。
 *
 * <p>治法与 Carpet 假玩家的 {@code fixStartingPosition} 相同:等待发生前把身体
 * 先站到召唤方指定的落点——主人身边 / 测试结构里,区块必然已加载且实体就绪,
 * 等待条件当场满足。只动 {@link NumenPlayer} 且仅当召唤方给了落点;真玩家与
 * 从休眠原位苏醒(落点随 .dat)不受影响。
 */
@Mixin(PlayerList.class)
public abstract class MixinPlayerListCompanionSpawn {

    @Inject(method = "placeNewPlayer",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;waitForChunkAndEntities(Lnet/minecraft/world/level/ChunkPos;I)V"))
    private void numen$standAtIntendedSpawnBeforeWait(Connection connection, ServerPlayer player,
                                                      CommonListenerCookie cookie, CallbackInfo ci) {
        if (player instanceof NumenPlayer companion && companion.intendedSpawnPos() != null) {
            var pos = companion.intendedSpawnPos();
            companion.snapTo(pos.x, pos.y, pos.z, companion.getYRot(), companion.getXRot());
        }
    }
}
