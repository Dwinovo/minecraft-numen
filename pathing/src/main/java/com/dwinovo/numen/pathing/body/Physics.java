package com.dwinovo.numen.pathing.body;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * 一具服务端假玩家的物理步进。真玩家的移动由客户端算、经移动包交给服务端;假玩家没有客户端,也没有移动包,
 * 原版服务端在收包时替玩家做的几件事就都不会发生。这里在服务端补上同一趟:
 * <ol>
 *   <li>{@code doTick()}——玩家自己的一刻(原版由网络层驱动):按 {@link Controls} 落下的输入走路、起跳、游泳、攀爬,
 *       碰撞与 0.6 格迈步都在原版的 {@code travel} 里;</li>
 *   <li>{@code doCheckFallDamage}——摔伤结算,原版在收到移动包时做;</li>
 *   <li>{@code checkMovementStatistics}——走、跑、游消耗饱食度与统计,原版同样在收包时做;</li>
 *   <li>区块跟着身体走({@code ChunkMap} 的玩家位置),原版同样在收包时做。</li>
 * </ol>
 * 宿主的假玩家每刻在自己的实体刻里调一次 {@link #step},在执行层落下按键之后。
 */
public final class Physics {

    private Physics() {}

    /** 走这一刻。 */
    public static void step(ServerPlayer body) {
        Vec3 before = body.position();
        body.doTick();
        Vec3 moved = body.position().subtract(before);
        body.doCheckFallDamage(moved.x, moved.y, moved.z, body.onGround());
        body.checkMovementStatistics(moved.x, moved.y, moved.z);
        body.serverLevel().getChunkSource().move(body);
    }
}
