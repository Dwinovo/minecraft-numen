package com.dwinovo.numen.api.entity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 一具服务端假玩家的视角:往哪儿看、怎么转过去(同伴的是 {@link NumenPlayer#look})。转头直接转到位。
 *
 * <p>朝向起作用的地方只有服务端真读它的那几处:走路的按键按朝向落成位移、弓与掷出去的东西沿朝向出手、物品自己沿视线打的那一条
 * (桶,{@link Mouse#itemRayOn})、空中飞行。点一格、点一只实体不看朝向({@code Mouse} 只看够不够得着):按之前朝它转一下,是给
 * 看着她的人看的。
 *
 * <p>只改朝向,不按键:要点下去是 {@code Mouse} 的事。
 */
public final class Look {

    private final ServerPlayer body;
    /** 上一次 {@link #steer} 之后的颈部。 */
    private Neck lastNeck;

    public Look(ServerPlayer body) {
        this.body = body;
    }

    /** 转过去看 {@code point}。 */
    public void at(Vec3 point) {
        Vec3 eye = body.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        turn(yawOf(dx, dz), pitchOf(dx, dy, dz));
    }

    /** 转过去看 {@code box}(一只实体的碰撞箱)上离眼睛最近的一点:够得着它量的就是眼睛到这一点的距离({@link Reach})。 */
    public void atNearest(AABB box) {
        Vec3 eye = body.getEyePosition();
        at(new Vec3(Mth.clamp(eye.x, box.minX, box.maxX), Mth.clamp(eye.y, box.minY, box.maxY),
                Mth.clamp(eye.z, box.minZ, box.maxZ)));
    }

    /** 转到朝 {@code yaw}、俯仰 {@code pitch}(俯仰照原版夹在 ±90 之间)。 */
    public void turn(float yaw, float pitch) {
        rotate(yaw, Mth.clamp(pitch, -90.0F, 90.0F));
    }

    /** 走路时朝 {@code (x, z)} 看去,俯仰留在平视。 */
    public void faceToward(double x, double z) {
        turn(yawOf(x - body.getX(), z - body.getZ()), 0);
    }

    /**
     * 朝 {@code yaw}、{@code pitch} 转一刻,按颈部模型({@link Neck}):角速度与角加速度都有上限,两轴一起到位,不瞬间甩过去。
     * 空中的控制器每刻调一次;这里转的就是 {@link #neck} 往前算一刻的结果,控制器推演时复制同一个颈部,看到的朝向与真身体一致。
     */
    public void steer(float yaw, float pitch) {
        Neck next = neck().toward(yaw, pitch);
        lastNeck = next;
        rotate(next.yaw(), next.pitch());
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

    private void rotate(float yaw, float pitch) {
        body.setYRot(yaw);
        body.setYHeadRot(yaw);
        body.setYBodyRot(yaw);
        body.setXRot(pitch);
    }
}
