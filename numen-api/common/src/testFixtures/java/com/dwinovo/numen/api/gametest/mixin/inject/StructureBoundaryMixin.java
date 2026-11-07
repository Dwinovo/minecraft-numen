package com.dwinovo.numen.api.gametest.mixin.inject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.world.level.block.entity.TestInstanceBlockEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * 场地的围栏(屏障)只围四周、不垫底:四面墙从内容最低一层起往上,没有天空的场地再加一层顶。原版 1.21.5 起
 * 围栏还在内容底下铺一整层屏障,场地底下就成了一块挖不穿、站得住的板,用例里"挖穿地板钻下去""地板下面空一层"的场地都按没有这层写。
 * 罩着原版的 {@code processStructureBoundary}:围上({@code encaseStructure})与拆掉({@code removeBarriers})走的是同一圈格子。
 */
@Mixin(TestInstanceBlockEntity.class)
public abstract class StructureBoundaryMixin {

    @Shadow
    public abstract AABB getStructureBounds();

    @Shadow
    private Optional<Holder.Reference<GameTestInstance>> getTestHolder() {
        throw new AssertionError();
    }

    @Inject(method = "processStructureBoundary", at = @At("HEAD"), cancellable = true)
    private void numen$wallsWithoutAFloor(Consumer<BlockPos> action, CallbackInfo ci) {
        AABB bounds = getStructureBounds();
        boolean roofed = !getTestHolder().map(test -> test.value().skyAccess()).orElse(false);
        BlockPos from = BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ).offset(-1, 0, -1);
        BlockPos to = BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ);
        BlockPos.betweenClosedStream(from, to).forEach(pos -> {
            boolean wall = pos.getX() == from.getX() || pos.getX() == to.getX()
                    || pos.getZ() == from.getZ() || pos.getZ() == to.getZ();
            if (wall || roofed && pos.getY() == to.getY()) {
                action.accept(pos);
            }
        });
        ci.cancel();
    }
}
