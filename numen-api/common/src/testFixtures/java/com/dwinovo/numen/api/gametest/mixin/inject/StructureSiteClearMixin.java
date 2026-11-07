package com.dwinovo.numen.api.gametest.mixin.inject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.TestInstanceBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 每块场地摆结构之前先把它的地清一遍:内容的包围盒四周外扩(左右各 2 到 3 格、往下 3 格、往上 20 格),内容最低一层的下一层
 * 是空气,再往下填石头,往上全是空气,盒里不是玩家的实体移走。原版 1.21.5 起铺场地不再清这一圈,原版的清地
 * ({@code StructureUtils.clearSpaceForStructure})也只清给它的那个盒子、不往外扩,场地底下露出的是平坦世界本来的地层
 * (地板下面两格空、再下面是草方块)。用例的寻路、挖掘与扫描都按"地板下一层是空气、再往下是石头"的场地写。
 * 每一格经原版的 {@code clearBlock} 放({@link StructureUtilsInvoker}),清地的范围盖住测试方块所在的那一格,所以在放测试方块之前清。
 */
@Mixin(GameTestInfo.class)
public abstract class StructureSiteClearMixin {

    @Inject(method = "prepareTestStructure", at = @At("HEAD"))
    private void numen$clearTheSite(CallbackInfoReturnable<GameTestInfo> cir) {
        GameTestInfo info = (GameTestInfo) (Object) this;
        ServerLevel level = info.getLevel();
        Vec3i size = TestInstanceBlockEntity.getStructureSize(level, info.getTestHolder().key()).orElse(new Vec3i(1, 1, 1));
        BoundingBox content = StructureUtils.getStructureBoundingBox(
                TestInstanceBlockEntity.getStructurePos(info.getTestBlockPos()), size, info.getRotation());
        int floorY = content.minY() - 1;
        BoundingBox site = new BoundingBox(content.minX() - 2, content.minY() - 3, content.minZ() - 3,
                content.maxX() + 3, content.maxY() + 20, content.maxZ() + 3);
        BlockPos.betweenClosedStream(site).forEach(pos -> StructureUtilsInvoker.numen$clearBlock(floorY, pos, level));
        level.getBlockTicks().clearArea(site);
        level.clearBlockEvents(site);
        level.getEntitiesOfClass(Entity.class, AABB.of(site), entity -> !(entity instanceof Player)).forEach(Entity::discard);
    }
}
