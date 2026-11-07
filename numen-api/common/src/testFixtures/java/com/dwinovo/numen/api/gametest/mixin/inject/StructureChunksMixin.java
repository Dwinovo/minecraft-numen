package com.dwinovo.numen.api.gametest.mixin.inject;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.TestInstanceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原版给每块场地强制加载它压着的区块(场地的测试方块 {@code TestInstanceBlockEntity.forceLoadChunks}),然后等它们进实体刻才开始这条用例
 * ({@code GameTestInfo.tick})。进实体刻要周围两圈的区块都生成完,那几圈由工作线程按墙钟生成:同一条用例这次在第几刻开始、和同批别的
 * 用例差几刻,都看机器快慢。差出来的几刻又牵动全服共用的东西(世界的随机数、每刻的扫描额度),结论跟着变。
 *
 * <p>这里在强制加载之后就地把这些区块连同周围两圈阻塞着生成完(和 {@code GameTestKit.generateChunks} 同一个做法):等多久都不占刻数,
 * 之后升到实体刻只经主线程上按刻推进的票据,哪一刻开始就与机器快慢无关。
 */
@Mixin(TestInstanceBlockEntity.class)
public abstract class StructureChunksMixin {

    /** 进实体刻要求周围生成完的圈数。 */
    private static final int ENTITY_TICKING_RING = 2;

    @Inject(method = "forceLoadChunks", at = @At("TAIL"))
    private void numen$generateAround(CallbackInfo ci) {
        TestInstanceBlockEntity test = (TestInstanceBlockEntity) (Object) this;
        if (!(test.getLevel() instanceof ServerLevel level)) {
            return;
        }
        test.getStructureBoundingBox().intersectingChunks().forEach(chunk -> {
            for (int dx = -ENTITY_TICKING_RING; dx <= ENTITY_TICKING_RING; dx++) {
                for (int dz = -ENTITY_TICKING_RING; dz <= ENTITY_TICKING_RING; dz++) {
                    level.getChunk(chunk.x + dx, chunk.z + dz);
                }
            }
        });
    }
}
