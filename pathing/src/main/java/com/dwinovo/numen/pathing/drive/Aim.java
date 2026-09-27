package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.pathing.world.Faces;
import com.dwinovo.numen.pathing.world.Replaceable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 视角:往哪儿看、怎么转过去。瞄点只在这里定——挖一格看它身上哪一点、放一块点哪个面的哪一点;转头照原版鼠标,每移一个
 * 像素转一个固定角度,所以转到的角度落在这个格子上,离算出来的点差不到半个像素。
 *
 * <p>看得见是指从眼睛到那一点的方块射线(轮廓,不看流体)第一下就碰上那一格,而且在交互距离之内——与准星拾取
 * ({@link com.dwinovo.numen.pathing.body.Crosshair})是同一套射线,所以转过去之后准星就落在那一格上。
 */
public final class Aim {

    /** 鼠标灵敏度 0.5 时移一个像素视角转的角度(原版 {@code (s·0.6+0.2)³·8·0.15})。 */
    static final double PIXEL = Math.pow(0.5 * 0.6 + 0.2, 3) * 8 * 0.15;
    /** 瞄面上的点时往面里收一点,射线不贴着棱。 */
    private static final double INSET = 0.02;

    private Aim() {}

    // ==================== 转头 ====================

    /** 转过去看 {@code point}。 */
    public static void look(ServerPlayer body, Vec3 point) {
        Vec3 eye = body.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        turn(body, yaw, pitch);
    }

    /** 转到朝 {@code yaw}、俯仰 {@code pitch}(按鼠标像素取整)。 */
    public static void turn(ServerPlayer body, float yaw, float pitch) {
        float fromYaw = body.getYRot();
        float newYaw = fromYaw + quantize(Mth.wrapDegrees(yaw - fromYaw));
        float fromPitch = body.getXRot();
        float newPitch = Mth.clamp(fromPitch + quantize(pitch - fromPitch), -90.0F, 90.0F);
        body.setYRot(newYaw);
        body.setYHeadRot(newYaw);
        body.setYBodyRot(newYaw);
        body.setXRot(newPitch);
    }

    /** 走路时朝 {@code (x, z)} 看去,俯仰留在平视。 */
    public static void faceToward(ServerPlayer body, double x, double z) {
        float yaw = (float) (Math.toDegrees(Math.atan2(z - body.getZ(), x - body.getX())) - 90.0);
        turn(body, yaw, 0);
    }

    private static float quantize(float degrees) {
        return (float) (Math.round(degrees / PIXEL) * PIXEL);
    }

    // ==================== 挖:看这一格身上的哪一点 ====================

    /**
     * 挖 {@code pos} 这一格时看它身上的哪一点:它的轮廓中心,不行就是轮廓各个朝着眼睛的面的中心,取第一个看得见、够得着的;
     * 都看不见为 null。
     */
    public static Vec3 point(ServerPlayer body, BlockPos pos) {
        Level level = body.level();
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        List<AABB> boxes = shape.isEmpty() ? List.of(new AABB(0, 0, 0, 1, 1, 1)) : shape.toAabbs();
        Vec3 eye = body.getEyePosition();
        List<Vec3> candidates = new ArrayList<>();
        AABB bounds = shape.isEmpty() ? boxes.get(0) : shape.bounds();
        candidates.add(bounds.getCenter().add(pos.getX(), pos.getY(), pos.getZ()));
        for (AABB local : boxes) {
            AABB box = local.move(pos);
            for (Direction side : Direction.values()) {
                Vec3 center = faceCenter(box, side);
                if (facing(eye, center, side)) {
                    candidates.add(center.subtract(Vec3.atLowerCornerOf(side.getNormal()).scale(INSET)));
                }
            }
        }
        double range = body.blockInteractionRange();
        for (Vec3 candidate : candidates) {
            if (candidate.distanceTo(eye) < range && hits(body, eye, candidate, pos, null)) {
                return candidate;
            }
        }
        return null;
    }

    // ==================== 放:点哪个面的哪一点 ====================

    /** 放方块时点的那一下:点 {@code clicked} 这一格的 {@code side} 面上的 {@code point}。 */
    public record Face(BlockPos clicked, Direction side, Vec3 point) {}

    /**
     * 往 {@code target} 放 {@code placing} 时,从眼睛此刻的位置点得中的那个面:能贴的面由第 0 层 {@link Faces} 给,点在
     * {@link Faces#hitPoint};眼睛要在那个面朝外的一侧,射线第一下碰上它,够得着,放下去落在 {@code target}。看不见任何一个
     * 为 null。优先点下面那一格的顶面。
     */
    public static Face face(ServerPlayer body, BlockPos target, Block placing) {
        Level level = body.level();
        Vec3 eye = body.getEyePosition();
        double range = body.blockInteractionRange();
        List<Direction> sides = new ArrayList<>(Faces.against(level, target, placing));
        sides.sort((a, b) -> Boolean.compare(b == Direction.DOWN, a == Direction.DOWN));
        for (Direction dir : sides) {
            BlockPos clicked = target.relative(dir);
            Direction side = dir.getOpposite();
            Vec3 onFace = Faces.hitPoint(level, target, dir);
            // 点在面上往面里收一点,从面朝外那一侧射过来才碰得上这一面
            Vec3 point = onFace.subtract(Vec3.atLowerCornerOf(side.getNormal()).scale(INSET));
            if (!facing(eye, onFace, side) || onFace.distanceTo(eye) >= range) {
                continue;
            }
            if (!target.equals(Replaceable.landing(level, clicked, side, placing))) {
                continue;
            }
            if (hits(body, eye, point, clicked, side)) {
                return new Face(clicked, side, point);
            }
        }
        return null;
    }

    // ==================== 射线 ====================

    /** 从眼睛朝 {@code point} 的方块射线第一下碰上的是 {@code pos}(给了 {@code side} 时还得是那一面)。 */
    private static boolean hits(ServerPlayer body, Vec3 eye, Vec3 point, BlockPos pos, Direction side) {
        Vec3 through = point.add(point.subtract(eye).normalize().scale(0.1));
        BlockHitResult hit = body.level().clip(new ClipContext(eye, through, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, body));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)
                && (side == null || hit.getDirection() == side);
    }

    /** 眼睛在这个面朝外的一侧。 */
    private static boolean facing(Vec3 eye, Vec3 onFace, Direction side) {
        Vec3 normal = Vec3.atLowerCornerOf(side.getNormal());
        return eye.subtract(onFace).dot(normal) > 1.0E-4;
    }

    private static Vec3 faceCenter(AABB box, Direction side) {
        Vec3 c = box.getCenter();
        return switch (side) {
            case DOWN -> new Vec3(c.x, box.minY, c.z);
            case UP -> new Vec3(c.x, box.maxY, c.z);
            case NORTH -> new Vec3(c.x, c.y, box.minZ);
            case SOUTH -> new Vec3(c.x, c.y, box.maxZ);
            case WEST -> new Vec3(box.minX, c.y, c.z);
            case EAST -> new Vec3(box.maxX, c.y, c.z);
        };
    }
}
