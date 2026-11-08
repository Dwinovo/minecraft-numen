package com.dwinovo.numen.api.gametest.mixin.inject;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 场地的结构模板一律经 {@code StructureTemplateManager} 取。它从测试模板目录({@code StructureUtils.testStructuresDir},见
 * {@code GameTestSuites})读 SNBT 的那个来源,这一代的原版只在 {@code SharedConstants.IS_RUNNING_IN_IDE} 为真时才登记,无头跑批里它是假的,
 * 用例的模板就找不到。测试服里照样登记这个来源;别的来源(存档的 generated、数据包)不变。
 */
@Mixin(StructureTemplateManager.class)
public abstract class StructureTemplateTestDirMixin {

    @Redirect(method = "<init>", at = @At(value = "FIELD", opcode = 178,
            target = "Lnet/minecraft/SharedConstants;IS_RUNNING_IN_IDE:Z"))
    private boolean numen$testStructuresDir() {
        return true;
    }
}
