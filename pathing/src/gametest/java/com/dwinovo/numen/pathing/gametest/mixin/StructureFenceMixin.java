package com.dwinovo.numen.pathing.gametest.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 给每块测试场地围上一圈屏障方块,并把整块场地所在的区块钉住——高版本的原版 GameTest 摆完场地就这么做,这一代(1.20.2)的原版没有。
 *
 * <p>寻路用例把场地当一座<b>孤岛</b>:墙把场地隔开、"没有口"就是没有口,搜索只在场地里转一圈就收场。没有围栏时,场地
 * 边上一两格的落差走得下去,外面是整片天然平地,搜索一路探到没加载的区块去,收场成"没加载"而不是"要改地形"。
 *
 * <p>围栏的形状照高版本的样子:场地包围盒向外一格,从场地第二层一直到比包围盒顶高两层,四面墙加一层顶;最底下一层
 * 不围(那一层是天然地面的高度)。只在 GameTest 的运行里挂({@code numen_gametest.mixins.json} 由运行配置的
 * {@code --mixin.config} 带入),不进发行物。
 */
@Mixin(StructureUtils.class)
public abstract class StructureFenceMixin {

    @Inject(method = "spawnStructure", at = @At("RETURN"))
    private static void numen$fence(String name, BlockPos pos, Rotation rotation, int padding, ServerLevel level,
                                    boolean clearBlocks, CallbackInfoReturnable<StructureBlockEntity> cir) {
        BoundingBox box = StructureUtils.getStructureBoundingBox(cir.getReturnValue());
        int minX = box.minX() - 1;
        int maxX = box.maxX() + 1;
        int minZ = box.minZ() - 1;
        int maxZ = box.maxZ() + 1;
        int top = box.maxY() + 2;
        // 整块场地(围栏在内)所在的区块都钉住:这一代的原版只钉场地起点周围固定的几格区块,一百多格长的场地后半截没人加载,
        // 走到头寻路就停在"没加载"上
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                level.setChunkForced(chunkX, chunkZ, true);
            }
        }
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int y = box.minY() + 1; y <= top; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (y == top || x == minX || x == maxX || z == minZ || z == maxZ) {
                        level.setBlock(at.set(x, y, z), Blocks.BARRIER.defaultBlockState(), 2);
                    }
                }
            }
        }
    }
}
