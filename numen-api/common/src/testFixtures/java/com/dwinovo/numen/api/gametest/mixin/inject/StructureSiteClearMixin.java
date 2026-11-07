package com.dwinovo.numen.api.gametest.mixin.inject;

import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.world.level.block.entity.TestInstanceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 每块场地摆结构之前先把它的地清一遍({@code StructureUtils.clearSpaceForStructure}):包围盒外扩一圈,
 * 内容最低一层的下一层是空气,再往下填石头,往上全是空气。原版 1.21.5 起铺场地只放测试方块和结构、不再清地,
 * 场地底下露出的是平坦世界本来的地层(地板下面两格空、再下面是草方块)。用例的寻路、挖掘与扫描都按
 * "地板下一层是空气、再往下是石头"的场地写。清地的范围盖住测试方块所在的那一格,所以在放测试方块之前清。
 */
@Mixin(GameTestInfo.class)
public abstract class StructureSiteClearMixin {

    @Inject(method = "prepareTestStructure", at = @At("HEAD"))
    private void numen$clearTheSite(CallbackInfoReturnable<GameTestInfo> cir) {
        GameTestInfo info = (GameTestInfo) (Object) this;
        Vec3i size = TestInstanceBlockEntity.getStructureSize(info.getLevel(), info.getTestHolder().key()).orElse(new Vec3i(1, 1, 1));
        StructureUtils.clearSpaceForStructure(StructureUtils.getStructureBoundingBox(
                TestInstanceBlockEntity.getStructurePos(info.getTestBlockPos()), size, info.getRotation()), info.getLevel());
    }
}
