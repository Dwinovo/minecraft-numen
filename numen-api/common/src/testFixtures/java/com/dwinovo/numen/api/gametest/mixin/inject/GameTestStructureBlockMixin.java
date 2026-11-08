package com.dwinovo.numen.api.gametest.mixin.inject;

import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 场地的范围与大小({@code getStructureBounds}/{@code getStructureSize})从场地的结构方块算。这一代原版每次都按坐标去世界里取那个方块实体,
 * 用例在场地原点那一格放了别的方块(铺地面、砌墙),范围就成了 null,助手的范围、收场时清场都跟着崩;1.21.1 原版用的是摆场地时
 * 记下的那个方块实体({@code prepareTestStructure} 存进的 {@code structureBlockEntity}),测试服里照它取。
 */
@Mixin(GameTestInfo.class)
public abstract class GameTestStructureBlockMixin {

    @Shadow
    private StructureBlockEntity structureBlockEntity;

    @Inject(method = "getStructureBlockEntity", at = @At("HEAD"), cancellable = true)
    private void numen$theSpawnedStructureBlock(CallbackInfoReturnable<StructureBlockEntity> cir) {
        cir.setReturnValue(structureBlockEntity);
    }
}
