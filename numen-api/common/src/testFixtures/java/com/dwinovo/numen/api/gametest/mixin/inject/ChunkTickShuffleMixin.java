package com.dwinovo.numen.api.gametest.mixin.inject;

import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 原版每刻把在走刻的区块洗一遍牌({@code ServerChunkCache.tickChunks})。这一代用的是 {@code Collections.shuffle(List)}:JDK 里一份
 * 进程级、种子不定的随机数,每次开服洗出的先后都不同,区块谁先走刻(随机刻、刷怪、实体的先后)就跟着变。测试服里洗牌改用这一份
 * 固定种子的随机数:洗牌照洗,先后只看第几次洗、洗的是哪些区块。
 */
@Mixin(ServerChunkCache.class)
public abstract class ChunkTickShuffleMixin {

    @Unique
    private final Random numen$shuffle = new Random(0);

    @Redirect(method = "tickChunks",
            at = @At(value = "INVOKE", target = "Ljava/util/Collections;shuffle(Ljava/util/List;)V"))
    private void numen$ownShuffle(List<?> chunks) {
        Collections.shuffle(chunks, numen$shuffle);
    }
}
