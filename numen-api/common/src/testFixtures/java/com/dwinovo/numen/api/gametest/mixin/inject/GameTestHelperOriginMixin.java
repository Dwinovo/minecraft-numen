package com.dwinovo.numen.api.gametest.mixin.inject;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 用例里的相对坐标从结构方块那一格起算:模板的内容从它上面一格开始,所以模板第一层是相对 y=1,地板底下(相对 y=0)是空的。
 * 1.21.2 起原版改从内容原点起算(模板第一层成了相对 y=0);这里把 {@code GameTestHelper} 换算相对与绝对坐标的四处起点换回结构方块,
 * 用例与模板照写,摆出来的场地与它们写下时一模一样。
 */
@Mixin(GameTestHelper.class)
public abstract class GameTestHelperOriginMixin {

    @Redirect(method = {"absolutePos", "relativePos", "absoluteVec", "relativeVec"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/gametest/framework/GameTestInfo;getTestOrigin()Lnet/minecraft/core/BlockPos;"))
    private BlockPos numen$fromTheStructureBlock(GameTestInfo info) {
        return info.getStructureBlockPos();
    }
}
