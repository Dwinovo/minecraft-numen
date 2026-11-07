package com.dwinovo.numen.api.gametest.mixin.inject;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原版给每块场地清空地时({@code StructureUtils.clearSpaceForStructure})对包围盒外扩后的每一格都下一遍"放这个方块"外加一遍邻格更新,
 * 不管那格本来是不是已经是了。场地铺在没人用过的新区块里,绝大多数格子本来就是目标状态(空气,或脚下那层石头),这几万格的空更新
 * 占了批与批之间的空档的大头(NeoForge 默认整套里约六成)。本来就是目标状态的格子跳过:不变的格子没有可通知的变化,
 * 真正要改的格子照旧放方块、照旧通知邻格。
 */
@Mixin(StructureUtils.class)
public abstract class StructureClearMixin {

    /** 原版 {@code clearBlock}:{@code floorY} 以下放石头,以上放空气。 */
    @Inject(method = "clearBlock", at = @At("HEAD"), cancellable = true)
    private static void numen$skipUnchanged(int floorY, BlockPos pos, ServerLevel level, CallbackInfo ci) {
        BlockState wanted = (pos.getY() < floorY ? Blocks.STONE : Blocks.AIR).defaultBlockState();
        if (level.getBlockState(pos) == wanted) {
            ci.cancel();
        }
    }
}
