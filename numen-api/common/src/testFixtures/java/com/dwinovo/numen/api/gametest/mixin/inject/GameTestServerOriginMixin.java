package com.dwinovo.numen.api.gametest.mixin.inject;

import com.dwinovo.numen.api.gametest.GameTestOrigin;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 场地起点用 {@link GameTestOrigin} 给的那一处(默认固定,{@code -PgametestOrigin} 可换,失败日志里写着它)。这一代原版把起点写死成
 * {@code (0, -60, 0)} 交给 {@code GameTestRunner.runTestBatches},这里换掉的就是这个参数。
 */
@Mixin(GameTestServer.class)
public abstract class GameTestServerOriginMixin {

    @ModifyArg(method = "startTests", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/gametest/framework/GameTestRunner;runTestBatches(Ljava/util/Collection;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Rotation;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/gametest/framework/GameTestTicker;I)Ljava/util/Collection;"),
            index = 1)
    private BlockPos numen$fixedOrigin(BlockPos vanilla) {
        return GameTestOrigin.get();
    }
}
