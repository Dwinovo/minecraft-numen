package com.dwinovo.numen.api.gametest.mixin.inject;

import java.util.List;
import net.minecraft.util.Util;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 原版每刻用世界的随机数把在走刻的区块洗一遍牌({@code ServerChunkCache.tickChunks}),走刻的有几块就掷几次骰子。区块由工作线程按墙钟
 * 加载,哪一刻多一块在走刻看机器快慢,世界随机数后面的点数(掉落物的初速、战利品)就跟着错开。测试服里洗牌改用这一份自己的随机数:
 * 洗牌照洗,世界随机数只给真正掷骰子的东西用。测试服的随机刻速是 0、不刷怪,区块谁先谁后走刻不改变任何结论。
 */
@Mixin(ServerChunkCache.class)
public abstract class ChunkTickShuffleMixin {

    @Unique
    private final RandomSource numen$shuffle = RandomSource.create(0);

    @Redirect(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;shuffle(Ljava/util/List;Lnet/minecraft/util/RandomSource;)V"))
    private void numen$ownShuffle(List<?> chunks, RandomSource levelRandom) {
        Util.shuffle(chunks, numen$shuffle);
    }
}
