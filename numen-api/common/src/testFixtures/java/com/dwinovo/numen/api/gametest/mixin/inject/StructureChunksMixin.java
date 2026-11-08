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
 * 摆完一块场地之后,照高版本原版的样子把整块场地所在的区块钉住、围上屏障——这一代(1.20.2)的原版只钉场地起点周围固定的几格区块,
 * 也不围栏。
 *
 * <p>钉住的区块连同周围两圈就地阻塞着生成完(和 {@code GameTestKit.generateChunks} 同一个做法):那几圈本来由工作线程按墙钟生成,
 * 同一条用例这次在第几刻开始、和同批别的用例差几刻都看机器快慢,差出来的几刻又牵动全服共用的东西(世界的随机数、每刻的扫描额度)。
 * 在这里生成完,等多久都不占刻数,之后升到实体刻只经主线程上按刻推进的票据,哪一刻开始就与机器快慢无关。一百多格长的场地后半截
 * 也因此有人加载,走到头寻路不会停在"没加载"上。
 *
 * <p>围栏让寻路用例把场地当一座孤岛:墙把场地隔开、"没有口"就是没有口。形状照高版本:场地包围盒向外一格,从场地第二层一直到
 * 比包围盒顶高两层,四面墙加一层顶,用例标了 {@code SkyAccess} 的不加顶;最底下一层不围(那一层是地面的高度)。
 */
@Mixin(GameTestInfo.class)
public abstract class StructureChunksMixin {

    /** 进实体刻要求周围生成完的圈数。 */
    private static final int ENTITY_TICKING_RING = 2;

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    private BlockPos structureBlockPos;

    @Inject(method = "spawnStructure", at = @At("RETURN"))
    private void numen$pinAndFence(BlockPos pos, int padding, CallbackInfo ci) {
        BoundingBox box = StructureUtils.getStructureBoundingBox((StructureBlockEntity) level.getBlockEntity(structureBlockPos));
        boolean roof = !GameTestSuites.skyAccess(((GameTestInfo) (Object) this).getTestName());
        int minX = box.minX() - 1;
        int maxX = box.maxX() + 1;
        int minZ = box.minZ() - 1;
        int maxZ = box.maxZ() + 1;
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                level.setChunkForced(chunkX, chunkZ, true);
                for (int dx = -ENTITY_TICKING_RING; dx <= ENTITY_TICKING_RING; dx++) {
                    for (int dz = -ENTITY_TICKING_RING; dz <= ENTITY_TICKING_RING; dz++) {
                        level.getChunk(chunkX + dx, chunkZ + dz);
                    }
                }
            }
        }
        int top = box.maxY() + 2;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int y = box.minY() + 1; y <= top; y++) {
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
