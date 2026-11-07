package com.dwinovo.numen.api.gametest.mixin.inject;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 原版清场地放每一格用的私有 {@code clearBlock}:{@code floorY} 以下放石头,以上放空气(见 {@link StructureSiteClearMixin})。 */
@Mixin(StructureUtils.class)
public interface StructureUtilsInvoker {

    @Invoker("clearBlock")
    static void numen$clearBlock(int floorY, BlockPos pos, ServerLevel level) {
        throw new AssertionError();
    }
}
