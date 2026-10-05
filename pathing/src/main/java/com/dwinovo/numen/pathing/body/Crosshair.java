package com.dwinovo.numen.pathing.body;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 准星此刻落在哪:照原版客户端每刻算准星的那一次拾取({@code GameRenderer.pick})——沿视线先找方块轮廓(不看流体),
 * 再找挡在它前面、能被点中的实体,各按自己的交互距离截断。左键挖、右键放与开门都只对准星落着的东西起作用,
 * 所以准星被实体挡住时,挖掘停下、放置不按,与真玩家一样。
 */
public final class Crosshair {

    private Crosshair() {}

    /** 准星落着的东西:方块、实体,或什么也没有({@link HitResult.Type#MISS})。 */
    public static HitResult pick(ServerPlayer body) {
        double blockRange = body.blockInteractionRange();
        double entityRange = body.entityInteractionRange();
        double range = Math.max(blockRange, entityRange);
        double rangeSqr = Mth.square(range);
        Vec3 eye = body.getEyePosition();
        HitResult block = body.pick(range, 1.0F, false);
        double blockSqr = block.getLocation().distanceToSqr(eye);
        if (block.getType() != HitResult.Type.MISS) {
            rangeSqr = blockSqr;
            range = Math.sqrt(blockSqr);
        }
        Vec3 view = body.getViewVector(1.0F);
        Vec3 end = eye.add(view.x * range, view.y * range, view.z * range);
        AABB swept = body.getBoundingBox().expandTowards(view.scale(range)).inflate(1.0, 1.0, 1.0);
        EntityHitResult entity = ProjectileUtil.getEntityHitResult(body, eye, end, swept,
                e -> !e.isSpectator() && e.isPickable(), rangeSqr);
        return entity != null && entity.getLocation().distanceToSqr(eye) < blockSqr
                ? within(entity, eye, entityRange)
                : within(block, eye, blockRange);
    }

    /** 准星正落在 {@code pos} 这一格上时交出那一下;否则为 null。 */
    public static BlockHitResult on(ServerPlayer body, BlockPos pos) {
        return pick(body) instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(pos) ? hit : null;
    }

    /**
     * 手里的东西在空中右键时自己沿视线打的那一条,照原版 {@code Item.getPlayerPOVHitResult}(桶倒水、舀水就用它):从眼睛沿视线
     * 打方块交互距离,按方块轮廓,液体按 {@code fluid} 的规矩算不算;不看实体。和准星那一次拾取不是同一条:桶瞄的是它自己这条。
     */
    public static BlockHitResult itemRay(ServerPlayer body, ClipContext.Fluid fluid) {
        Vec3 eye = body.getEyePosition();
        Vec3 end = eye.add(body.calculateViewVector(body.getXRot(), body.getYRot()).scale(body.blockInteractionRange()));
        return body.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, fluid, body));
    }

    private static HitResult within(HitResult hit, Vec3 eye, double range) {
        Vec3 at = hit.getLocation();
        if (at.closerThan(eye, range)) {
            return hit;
        }
        Direction direction = Direction.getNearest(at.x - eye.x, at.y - eye.y, at.z - eye.z);
        return BlockHitResult.miss(at, direction, BlockPos.containing(at));
    }
}
