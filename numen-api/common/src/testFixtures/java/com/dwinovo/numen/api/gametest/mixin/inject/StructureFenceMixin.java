package com.dwinovo.numen.api.gametest.mixin.inject;

import com.dwinovo.numen.api.gametest.GameTestSuites;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 摆完一块场地之后,照高版本原版的样子围上屏障——这一代的原版钉住了整块场地的区块,但不围栏。
 *
 * <p>围栏让寻路用例把场地当一座孤岛:墙把场地隔开、"没有口"就是没有口。形状照高版本的 {@code StructureUtils.encaseStructure}:
 * 场地包围盒(结构方块之上的模板那一块)四周向外一格,从包围盒底一直到比包围盒顶高一层,四面墙加一层顶,用例标了 {@code SkyAccess} 的不加顶。
 */
@Mixin(GameTestInfo.class)
public abstract class StructureFenceMixin {

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    private StructureBlockEntity structureBlockEntity;

    @Inject(method = "prepareTestStructure", at = @At("RETURN"))
    private void numen$fence(BlockPos pos, CallbackInfo ci) {
        BoundingBox box = StructureUtils.getStructureBoundingBox(structureBlockEntity);
        boolean roof = !GameTestSuites.skyAccess(((GameTestInfo) (Object) this).getTestName());
        int minX = box.minX() - 1;
        int maxX = box.maxX() + 1;
        int minZ = box.minZ() - 1;
        int maxZ = box.maxZ() + 1;
        int top = box.maxY() + 1;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int y = box.minY(); y <= top; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if ((roof && y == top) || x == minX || x == maxX || z == minZ || z == maxZ) {
                        level.setBlock(at.set(x, y, z), Blocks.BARRIER.defaultBlockState(), 2);
                    }
                }
            }
        }
    }
}
