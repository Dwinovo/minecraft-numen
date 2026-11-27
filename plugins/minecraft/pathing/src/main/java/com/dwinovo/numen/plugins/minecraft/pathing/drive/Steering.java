package com.dwinovo.numen.plugins.minecraft.pathing.drive;

import com.dwinovo.numen.api.entity.Controls;
import com.dwinovo.numen.api.entity.Controls.Key;
import com.dwinovo.numen.plugins.minecraft.pathing.world.Kinematics;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * 朝一个水平的点走:停在那儿,或不减速地穿过去。走是按着前进键走的,加速、疾跑、摩擦都是原版的;按键做不到的三处由她自己改这一刻的
 * 水平速度({@link Controls#impulse}):
 * <ul>
 *   <li><b>收脚的最后一刻</b>:再按一下前进就会走过头,改成正好挪到那一点,到了清零——惯性让按键停不准;</li>
 *   <li><b>空中对准落点</b>:身体离了地转不了身,那一点不在它朝着的前方时,直接朝那一点挪;</li>
 *   <li><b>拐弯</b>:转过身的那一刻,把不朝着新方向的那一份速度去掉——否则惯性带着身子往旧方向侧滑一段。</li>
 * </ul>
 * 改成的速度不超过这一刻按着前进能挪的距离(加速度来自 {@link Kinematics},规划定价用的是同一份物理),所以她不比按键走得快,
 * 只是刹得住。每次调用给这一刻的水平速度下一个结论(不改也是结论),最后一次调用算数。
 *
 * <p>只在身体有依托时转身({@link #supported}:脚踏实地、泡在水里、攀着);自由飞行时不回身——空中转过身去,落地就是背对着路。
 */
final class Steering {

    /** 离要穿过的点这么近就不再转身朝它:再近,转身的角度由一点点偏差决定。 */
    private static final double NEAR = 0.05;
    /** 离要停的点这么近就算停在了它上面:原版把小于 {@link Kinematics#REST} 的速度分量当成零,再近就挪不动了。 */
    private static final double EXACT = 0.005;
    /** 转身超过这么多度算拐了弯。 */
    private static final float TURN = 10.0F;

    private Steering() {}

    /**
     * 身体有依托,可以转身朝着要去的方向:脚踏实地,或泡在水里、攀在梯子上(水里与梯子上没有"落地背对着路"的事,转身不会带到落点上)。
     * 自由飞行(跳起来、掉下去)时不转。
     */
    static boolean supported(ServerPlayer body) {
        return body.onGround() || body.isInWater() || body.onClimbable();
    }

    /** 不减速地朝 {@code (x, z)} 走。 */
    static void pass(Rig rig, double x, double z) {
        ServerPlayer body = rig.entity;
        Vec3 motion = body.getDeltaMovement();
        Vec3 kept = motion;
        if (supported(body) && Math.hypot(x - body.getX(), z - body.getZ()) >= NEAR) {
            kept = face(rig, x, z, motion);
        }
        rig.keys.press(Key.FORWARD);
        rig.keys.release(Key.BACK);
        steer(rig.keys, motion, kept.x, kept.z);
    }

    /** 按住前进的下一刻,身体沿它此刻朝着的方向会挪多远:原版先把这一刻的输入加速度加进速度,再移动。 */
    static double nextStride(ServerPlayer body) {
        double yaw = Math.toRadians(body.getYRot());
        Vec3 motion = body.getDeltaMovement();
        double along = motion.x * -Math.sin(yaw) + motion.z * Math.cos(yaw);
        return along + (body.onGround() ? groundAcceleration(body) : Kinematics.airAcceleration(body.isSprinting()));
    }

    /**
     * 停在 {@code (x, z)}。那一点在身体朝着的前方、还有不止一刻的路时按着前进走;最后一刻,或那一点不在前方而身体转不了身时,
     * 把这一刻的水平速度改成正好朝那一点挪。
     *
     * @return 已经停在那一点上
     */
    static boolean stop(Rig rig, double x, double z) {
        ServerPlayer body = rig.entity;
        Controls keys = rig.keys;
        double dx = x - body.getX();
        double dz = z - body.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        Vec3 motion = body.getDeltaMovement();
        keys.release(Key.FORWARD);
        keys.release(Key.BACK);
        if (distance < EXACT) {
            // 到了那一点:把带过来的速度抵掉;速度没了才算停住
            steer(keys, motion, 0, 0);
            return Math.hypot(motion.x, motion.z) < Kinematics.REST;
        }
        Vec3 kept = supported(body) ? face(rig, x, z, motion) : motion;
        double yaw = Math.toRadians(body.getYRot());
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double acceleration = acceleration(body);
        if (dx * fx + dz * fz > kept.x * fx + kept.z * fz + acceleration) {
            keys.press(Key.FORWARD);
            steer(keys, motion, kept.x, kept.z);
            return false;
        }
        double reach = Math.hypot(kept.x, kept.z) + acceleration;
        double scale = distance <= reach ? 1 : reach / distance;
        steer(keys, motion, dx * scale, dz * scale);
        return false;
    }

    /**
     * 转身朝着 {@code (x, z)},返回转过身之后她留着的水平速度:拐了弯(转过 {@link #TURN} 度以上)只留朝着新方向的那一份,
     * 直走时原样留着。
     */
    private static Vec3 face(Rig rig, double x, double z, Vec3 motion) {
        ServerPlayer body = rig.entity;
        float before = body.getYRot();
        rig.look.faceToward(x, z);
        if (Math.abs(Mth.wrapDegrees(body.getYRot() - before)) < TURN) {
            return motion;
        }
        double yaw = Math.toRadians(body.getYRot());
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double along = Math.max(0, motion.x * fx + motion.z * fz);
        return new Vec3(along * fx, motion.y, along * fz);
    }

    /** 这一刻的水平速度要是 {@code (x, z)}:与身体此刻的速度差多少,就让她自己加多少。 */
    private static void steer(Controls keys, Vec3 motion, double x, double z) {
        keys.impulse(x - motion.x, z - motion.z);
    }

    /** 这一刻按着前进加的速度:泡在水里是水里的,踩在地上是地上的,否则是空中的。 */
    private static double acceleration(ServerPlayer body) {
        if (body.isInWater()) {
            // 原版:脚不沾地时水下移动效率减半
            double efficiency = body.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY) * (body.onGround() ? 1 : 0.5);
            return Kinematics.waterAcceleration(body.getSpeed(), efficiency);
        }
        return body.onGround() ? groundAcceleration(body) : Kinematics.airAcceleration(body.isSprinting());
    }

    /** 地上按前进一刻加的速度:身体此刻的移动速度(含疾跑)与脚下的摩擦。 */
    private static double groundAcceleration(ServerPlayer body) {
        return Kinematics.groundAcceleration(body.getSpeed(), friction(body));
    }

    /** 脚下影响移动的那一格的摩擦(原版看脚下半格处)。 */
    private static double friction(ServerPlayer body) {
        BlockPos below = BlockPos.containing(body.getX(), body.getY() - 0.5000001, body.getZ());
        float friction = body.level().getBlockState(below).getBlock().getFriction();
        return friction > 0 ? friction : Kinematics.DEFAULT_FRICTION;
    }
}
