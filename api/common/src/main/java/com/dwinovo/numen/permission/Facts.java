package com.dwinovo.numen.permission;

import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;

/**
 * 信号函数读世界的口。
 *
 * @param view   方块视图:主线程是活世界(只读已加载),搜索线程是冻结快照
 * @param placed 这一维度的玩家放置记录(任何线程可读)
 * @param live   只在主线程非空——方块实体内容、领地查询这些活读只从它读;搜索线程拿到 null,
 *               信号按"不知道"的保守值回答(见各信号的说明)
 * @param claims 领地 mod 的裁决口;没有领地 mod 是 {@link TerritoryClaims#NONE}
 * @param actor  要动手的同伴;测试可传 null
 */
public record Facts(BlockGetter view, PlacedBlocks placed, ServerLevel live, TerritoryClaims claims,
                    NumenPlayer actor) {
}
