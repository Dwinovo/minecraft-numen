package com.dwinovo.numen.pathing.body;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.pathing.world.Faces;
import com.dwinovo.numen.pathing.world.Reach;
import com.dwinovo.numen.pathing.world.Replaceable;
import com.dwinovo.numen.pathing.world.Sight;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 视角:往哪儿看、怎么转过去。瞄点只在这里定——挖一格看它身上哪一点、放一块点哪个面的哪一点;转头照原版鼠标,每移一个
 * 像素转一个固定角度,所以转到的角度落在这个格子上,离算出来的点差不到半个像素。
 *
 * <p>看得见是指从眼睛到那一点的视线(第 0 层 {@link Sight},按轮廓、不看流体)第一下就碰上那一格,而且在交互距离之内——
 * 与准星拾取({@link Crosshair})是同一种射线,所以转过去之后准星就落在那一格上。
 */
public final class Aim {

    /** 鼠标灵敏度 0.5 时移一个像素视角转的角度(原版 {@code (s·0.6+0.2)³·8·0.15})。 */
    static final double PIXEL = Math.pow(0.5 * 0.6 + 0.2, 3) * 8 * 0.15;

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
        Rotation to = turned(body.getYRot(), body.getXRot(), yaw, pitch);
        body.setYRot(to.yaw());
        body.setYHeadRot(to.yaw());
        body.setYBodyRot(to.yaw());
        body.setXRot(to.pitch());
    }

    /** 一个朝向:水平朝向 {@code yaw} 与俯仰 {@code pitch},度。 */
    record Rotation(float yaw, float pitch) {}

    /**
     * 从 {@code (fromYaw, fromPitch)} 照鼠标转向 {@code (yaw, pitch)} 之后落在哪:两个方向各转整数个像素({@link #PIXEL}),
     * 取离要的角度最近的那一格,所以差不到半个像素;水平朝向走近的那一边(不超过半圈,转到的值不回绕到 ±180 之内,与原版
     * 鼠标一样累加);俯仰照原版夹在 ±90 之间。
     */
    static Rotation turned(float fromYaw, float fromPitch, float yaw, float pitch) {
        float newYaw = fromYaw + quantize(Mth.wrapDegrees(yaw - fromYaw));
        float newPitch = Mth.clamp(fromPitch + quantize(pitch - fromPitch), -90.0F, 90.0F);
        return new Rotation(newYaw, newPitch);
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
     * 挖 {@code pos} 这一格时看它身上的哪一点:它的轮廓中心,不行就是轮廓各个朝着眼睛的面的中心,再不行是这些面上离眼睛最近的
     * 一点(离棱留一点边),取第一个看得见、够得着的;都看不见为 null。最后那一档与第 0 层 {@link Reach} 量的是同一个距离
     * ——眼睛到方块最近的一点——所以 {@code Reach} 说够得着、那一点又没被挡着,这里就交得出瞄点。
     */
    public static Vec3 point(ServerPlayer body, BlockPos pos) {
        Vec3 eye = body.getEyePosition();
        for (Vec3 candidate : digPoints(body, pos)) {
            if (hits(body, eye, candidate, pos, null)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 挖 {@code pos} 时够得着的瞄点,先后照 {@link #point} 说的那三档,不管中间有没有东西挡着。看不见它时从这几点里挑隔着的格都
     * 清得掉的那一条视线({@link Sight#dig}),朝那一点看过去,准星落着的就是先要挖开的那一格。
     */
    public static List<Vec3> digPoints(ServerPlayer body, BlockPos pos) {
        Vec3 eye = body.getEyePosition();
        double range = body.blockInteractionRange();
        List<Vec3> out = new ArrayList<>();
        for (Vec3 candidate : candidates(body, pos)) {
            if (candidate.distanceTo(eye) < range) {
                out.add(candidate);
            }
        }
        return out;
    }

    /** 挖 {@code pos} 时的候选瞄点,先后照 {@link #point} 说的那三档。 */
    private static List<Vec3> candidates(ServerPlayer body, BlockPos pos) {
        Level level = body.level();
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        List<AABB> boxes = shape.isEmpty() ? List.of(new AABB(0, 0, 0, 1, 1, 1)) : shape.toAabbs();
        Vec3 eye = body.getEyePosition();
        List<Vec3> candidates = new ArrayList<>();
        AABB bounds = shape.isEmpty() ? boxes.get(0) : shape.bounds();
        candidates.add(bounds.getCenter().add(pos.getX(), pos.getY(), pos.getZ()));
        List<Vec3> nearest = new ArrayList<>();
        for (AABB local : boxes) {
            AABB box = local.move(pos);
            for (Direction side : Direction.values()) {
                Vec3 center = faceCenter(box, side);
                if (Sight.facing(eye, center, side)) {
                    Vec3 inward = Vec3.atLowerCornerOf(side.getNormal()).scale(Sight.INSET);
                    candidates.add(center.subtract(inward));
                    nearest.add(nearestOnFace(box, side, eye).subtract(inward));
                }
            }
        }
        candidates.addAll(nearest);
        return candidates;
    }

    // ==================== 用:点它哪一面 ====================

    /**
     * 从眼睛此刻的位置用 {@code pos} 这一格:它哪一面用得上(第 0 层 {@link Sight#use}——面朝着眼睛、在交互距离内、视线上没有
     * 硬遮挡),交出那一次视线;没隔着软遮挡的面优先。一面都用不上为 null。与导航"用一格方块"的到达是同一个视线函数。
     */
    public static Sight.Trace use(ServerPlayer body, BlockPos pos) {
        Level level = body.level();
        Vec3 eye = body.getEyePosition();
        double range = body.blockInteractionRange();
        Sight.Trace behindSoft = null;
        for (Direction side : Direction.values()) {
            Sight.Trace trace = Sight.use(level, eye, range, pos, side);
            if (trace == null) {
                continue;
            }
            if (trace.soft().isEmpty()) {
                return trace;
            }
            if (behindSoft == null) {
                behindSoft = trace;
            }
        }
        return behindSoft;
    }

    // ==================== 放:点哪个面的哪一点 ====================

    /**
     * 往 {@code target} 放 {@code placing} 时,从眼睛此刻的位置点得中的那个面(第 0 层 {@link Faces#inSight},规划判放不放得下
     * 问的是同一个);看不见任何一个为 null。
     */
    public static Faces.Face face(ServerPlayer body, BlockPos target, Block placing) {
        return Faces.inSight(body.level(), body.getEyePosition(), body.blockInteractionRange(), target, placing);
    }

    // ==================== 射线 ====================

    /** 从眼睛朝 {@code point} 的视线第一下碰上的是 {@code pos}(给了 {@code side} 时还得是那一面):第 0 层 {@link Sight}。 */
    private static boolean hits(ServerPlayer body, Vec3 eye, Vec3 point, BlockPos pos, Direction side) {
        return Sight.trace(body.level(), eye, point, pos).clear(side);
    }

    /** {@code box} 的 {@code side} 面上离 {@code eye} 最近的一点,离面的四条棱各留 {@link Reach#EDGE}。 */
    private static Vec3 nearestOnFace(AABB box, Direction side, Vec3 eye) {
        double x = clampInside(eye.x, box.minX, box.maxX);
        double y = clampInside(eye.y, box.minY, box.maxY);
        double z = clampInside(eye.z, box.minZ, box.maxZ);
        return switch (side) {
            case DOWN -> new Vec3(x, box.minY, z);
            case UP -> new Vec3(x, box.maxY, z);
            case NORTH -> new Vec3(x, y, box.minZ);
            case SOUTH -> new Vec3(x, y, box.maxZ);
            case WEST -> new Vec3(box.minX, y, z);
            case EAST -> new Vec3(box.maxX, y, z);
        };
    }

    private static double clampInside(double v, double min, double max) {
        double margin = Math.min(Reach.EDGE, (max - min) / 2);
        return Mth.clamp(v, min + margin, max - margin);
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
