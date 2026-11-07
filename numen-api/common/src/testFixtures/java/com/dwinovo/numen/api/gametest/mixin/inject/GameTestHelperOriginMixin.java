package com.dwinovo.numen.api.gametest.mixin.inject;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 用例里的场地坐标从场地内容底下那一层算起:{@code (0, 0, 0)} 是内容最低一层的正下方,内容的第一层是 {@code y = 1}。
 * 原版从内容原点算(内容第一层是 {@code y = 0});用例、夹具与结构模板都按"第一层是 y = 1"写,
 * 这里把相对与绝对坐标互换时的基准点放回内容原点的正下方,场地与世界里真正摆出来的样子不变。
 */
@Mixin(GameTestHelper.class)
public abstract class GameTestHelperOriginMixin {

    @Redirect(method = {"absolutePos", "relativePos", "absoluteVec", "relativeVec"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/gametest/framework/GameTestInfo;getTestOrigin()Lnet/minecraft/core/BlockPos;"))
    private BlockPos numen$belowTheContent(GameTestInfo info) {
        return info.getTestOrigin().below();
    }
}
