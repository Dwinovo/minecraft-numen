package com.dwinovo.numen.api.entity;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 一具服务端假玩家的视角:往哪儿看、怎么转过去(同伴的是 {@link NumenPlayer#look})。转头照原版鼠标,每移一个像素转一个固定
 * 角度,所以转到的角度落在这个格子上,离算出来的点差不到半个像素——和真人一样只能转到鼠标转得到的朝向。
 *
 * <p>看一格方块时看它身上的哪一点、点它哪一面,也在这里:都是"她此刻的眼睛能看见、够得着的那一点",照 {@link Sight}、{@link Reach}、
 * {@link Faces} 这几份身体事实算,所以转过去之后鼠标({@code Mouse})的准星就落在那一格上。看得见是指从眼睛到那一点的视线
 * (按轮廓、不看流体)第一下就碰上那一格,而且在交互距离之内。
 *
 * <p>只改朝向,不按键:要点下去是 {@code Mouse} 的事。
 */
public final class Look {

    /** 鼠标灵敏度 0.5 时移一个像素视角转的角度(原版 {@code (s·0.6+0.2)³·8·0.15})。 */
    static final double PIXEL = Math.pow(0.5 * 0.6 + 0.2, 3) * 8 * 0.15;

    private final ServerPlayer body;
    /** 上一次 {@link #steer} 之后的颈部。 */
    private Neck lastNeck;

    public Look(ServerPlayer body) {
        this.body = body;
    }

    // ==================== 转头 ====================

    /** 转过去看 {@code point}。 */
    public void at(Vec3 point) {
        rotate(toward(body.getEyePosition(), point));
    }

    /**
     * 转过去看 {@code box}(一只实体的碰撞箱)上离眼睛最近的一点,离棱留一点边。够得着一只实体是眼睛到它碰撞箱的最近距离
     * ({@link Reach}),真人对准它挥拳的那一点就是这一点:沿视线进箱子的位置不比它近,看着箱子中心会把斜着够得着的目标看成够不着。
     */
    public void atNearest(AABB box) {
        Vec3 eye = body.getEyePosition();
        at(new Vec3(Sight.clampInside(eye.x, box.minX, box.maxX), Sight.clampInside(eye.y, box.minY, box.maxY),
                Sight.clampInside(eye.z, box.minZ, box.maxZ)));
    }

    /** 转到朝 {@code yaw}、俯仰 {@code pitch}(按鼠标像素取整)。 */
    public void turn(float yaw, float pitch) {
        rotate(turned(body.getYRot(), body.getXRot(), yaw, pitch));
    }

    /** 走路时朝 {@code (x, z)} 看去,俯仰留在平视。 */
    public void faceToward(double x, double z) {
        turn(yawOf(x - body.getX(), z - body.getZ()), 0);
    }

    /**
     * 朝 {@code yaw}、{@code pitch} 转一刻,按颈部模型({@link Neck}):角速度与角加速度都有上限,两轴一起到位,像真人推鼠标,不瞬间甩过去。
     * 空中的控制器每刻调一次;这里转的就是 {@link #neck} 往前算一刻的结果,控制器推演时复制同一个颈部,看到的朝向与真身体一致。
     * 不取整到鼠标像素:飞行里要的是顺滑的转动,朝向就是这一刻算出的值。
     */
    public void steer(float yaw, float pitch) {
        Neck next = neck().toward(yaw, pitch);
        lastNeck = next;
        rotate(new Rotation(next.yaw(), next.pitch()));
    }

    /**
     * 颈部此刻的样子:朝向是身体此刻的朝向,角速度是上一次 {@link #steer} 留下的——要是朝向被别处转走了(转头去挖、去看)就当停着。
     */
    public Neck neck() {
        if (lastNeck != null && lastNeck.yaw() == body.getYRot() && lastNeck.pitch() == body.getXRot()) {
            return lastNeck;
        }
        return Neck.at(body.getYRot(), body.getXRot());
    }

    /** 水平方向 {@code (dx, dz)} 对应的偏航角(度,原版口径:南是 0)。 */
    public static float yawOf(double dx, double dz) {
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    /** 方向 {@code (dx, dy, dz)} 对应的俯仰角(度,原版口径:向下为正)。 */
    public static float pitchOf(double dx, double dy, double dz) {
        return (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }

    private void rotate(Rotation to) {
        body.setYRot(to.yaw());
        body.setYHeadRot(to.yaw());
        body.setYBodyRot(to.yaw());
        body.setXRot(to.pitch());
    }

    /** 从 {@code eye} 转向 {@code point} 之后身体的朝向(按鼠标像素取整)。 */
    private Rotation toward(Vec3 eye, Vec3 point) {
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        return turned(body.getYRot(), body.getXRot(), yawOf(dx, dz), pitchOf(dx, dy, dz));
    }

    /**
     * 转向 {@code point} 之后视线真正落在的那一点(与 {@code point} 离眼睛一样远):转头取整到鼠标像素,这条线与到 {@code point}
     * 的直线差不到半个像素。视线擦着挡路方块的棱而过时,碰没碰上就由差的这一点点决定,所以判看不看得见要照这条线。
     */
    private Vec3 landing(Vec3 eye, Vec3 point) {
        Rotation to = toward(eye, point);
        return eye.add(Vec3.directionFromRotation(to.pitch(), to.yaw()).scale(point.distanceTo(eye)));
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

    private static float quantize(float degrees) {
        return (float) (Math.round(degrees / PIXEL) * PIXEL);
    }

    // ==================== 挖:看这一格身上的哪一点 ====================

    /**
     * 挖 {@code pos} 这一格时看它身上的哪一点:它的轮廓中心,不行就是轮廓各个朝着眼睛的面的中心,再不行是这些面上离眼睛最近的
     * 一点(离棱留一点边),取第一个看得见、够得着的;都看不见为 null。最后那一档与 {@link Reach} 量的是同一个距离
     * ——眼睛到方块最近的一点——所以 {@code Reach} 说够得着、那一点又没被挡着,这里就交得出瞄点。有没有被挡着照转过去之后视线
     * 实际落的那条线判({@link #landing})。
     */
    public Vec3 point(BlockPos pos) {
        Vec3 eye = body.getEyePosition();
        for (Vec3 candidate : digPoints(pos)) {
            if (Sight.trace(body.level(), eye, landing(eye, candidate), pos).clear(null)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 挖 {@code pos} 时够得着的瞄点,先后照 {@link #point} 说的那三档,不管中间有没有东西挡着。看不见它时从这几点里挑隔着的格都
     * 清得掉的那一条视线({@link Sight#dig}),朝那一点看过去,准星落着的就是先要挖开的那一格。
     */
    public List<Vec3> digPoints(BlockPos pos) {
        return Sight.digPoints(body.level(), body.getEyePosition(), body.blockInteractionRange(), pos);
    }

    // ==================== 用:点它哪一面 ====================

    /**
     * 从眼睛此刻的位置用 {@code pos} 这一格:它哪一面用得上({@link Sight#use}——面朝着眼睛、在交互距离内、视线上没有
     * 硬遮挡),交出那一次视线;没隔着软遮挡的面优先。一面都用不上为 null。与导航"用一格方块"的到达是同一个视线函数。
     */
    public Sight.Trace use(BlockPos pos) {
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
     * 往 {@code target} 放 {@code placing} 时,从眼睛此刻的位置点得中的那个面({@link Faces#inSight},规划判放不放得下
     * 问的是同一个);看不见任何一个为 null。
     */
    public Faces.Face face(BlockPos target, Block placing) {
        return Faces.inSight(body.level(), body.getEyePosition(), body.blockInteractionRange(), target, placing);
    }
}
