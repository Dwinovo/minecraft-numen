package com.dwinovo.numen.pathing.body;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * 一具服务端假玩家的物理步进。真玩家的移动由客户端算、经移动包交给服务端;假玩家没有客户端,也没有移动包,
 * 原版客户端把按键变成输入的那一段、原版服务端在收包时替玩家做的几件事就都不会发生。这里在服务端补上同一趟:
 * <ol>
 *   <li>把这具身体的键盘({@link Body#controls})此刻按着的键落成身体输入——每刻只在这里落一次;</li>
 *   <li>{@code doTick()}——玩家自己的一刻(原版由网络层驱动):按上面落下的输入走路、起跳、游泳、攀爬,
 *       碰撞与 0.6 格迈步都在原版的 {@code travel} 里;</li>
 *   <li>{@code doCheckFallDamage}——摔伤结算,原版在收到移动包时做;</li>
 *   <li>{@code checkMovementStatistics}——走、跑、游消耗饱食度与统计,原版同样在收包时做;</li>
 *   <li>区块跟着身体走({@code ChunkMap} 的玩家位置),原版同样在收包时做。</li>
 * </ol>
 * 宿主的假玩家每刻在自己的实体刻里调一次 {@link #step}。
 */
public final class Physics {

    private Physics() {}

    /** 走这一刻。 */
    public static void step(Body body) {
        ServerPlayer entity = body.entity();
        body.controls().apply(entity);
        Vec3 before = entity.position();
        entity.doTick();
        Vec3 moved = entity.position().subtract(before);
        entity.doCheckFallDamage(moved.x, moved.y, moved.z, entity.onGround());
        entity.checkMovementStatistics(moved.x, moved.y, moved.z);
        entity.serverLevel().getChunkSource().move(entity);
    }
}
