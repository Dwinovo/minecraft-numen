package com.dwinovo.numen.api.platform.services;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayerGameMode;

/**
 * 服务端对一个玩家挖掘的记账。{@link ServerPlayerGameMode} 把"正在破坏哪一格""挂着哪一格的延迟破坏"锁成私有:
 * 挖到一半松手(进度不够 0.7)时,它把这一格记成延迟破坏,自己再走一会儿进度才破坏,所以送了 STOP 之后方块没马上变不等于没让挖。
 * 私有成员怎么够得着是平台的事(模组用 Mixin 访问器,Paper 按 Mojang 名取)。
 */
public interface IBreakingState {

    /** 服务端此刻还在破坏 {@code pos} 这一格:正在按进度破坏它,或把它挂成了延迟破坏。 */
    boolean breaking(ServerPlayerGameMode mode, BlockPos pos);
}
