package com.dwinovo.numen.api.gametest.mixin.inject;

import com.dwinovo.numen.api.gametest.GameTestOrigin;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 原版取场地起点用的是不定种子的世界随机数,每次开服一处。这里把那个局部变量换成 {@link GameTestOrigin} 给的固定一处:
 * 铺场地的和"tests are now running at position"那行日志用的都是它。
 */
@Mixin(GameTestServer.class)
public abstract class GameTestServerOriginMixin {

    @ModifyVariable(method = "startTests", at = @At("STORE"), ordinal = 0)
    private BlockPos numen$fixedOrigin(BlockPos random) {
        return GameTestOrigin.get();
    }
}
