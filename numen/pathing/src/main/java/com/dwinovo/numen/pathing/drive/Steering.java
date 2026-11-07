package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.api.entity.Controls;
import com.dwinovo.numen.api.entity.Controls.Key;
import com.dwinovo.numen.pathing.world.Kinematics;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * 朝一个水平的点走:停在那儿、不减速地穿过去,或压着速度走过去。停下时身体朝着那一点,每刻在"按前进、松开、按后退"
 * 三者里挑一个:照原版的移动与摩擦往后推算这一刻按下它、之后一直松着,身体最后停在哪,挑停得离那一点最近的;身体在空中
 * 时连同落地前的那几刻一起推算。
 *
 * <p>推算用的量全来自 {@link Kinematics}(规划定价用的是同一份物理):地上的加速度与摩擦、空中的加速度与阻力、落地要几刻。
 *
 * <p>只在身体有依托时转身({@link #supported}:脚踏实地、泡在水里、攀着);自由飞行时不回身,按身体此刻朝着的方向前进或后退来修正——
 * 空中转过身去,落地就是背对着路。
 */
final class Steering {

    /** 推算的最长刻数。 */
    private static final int HORIZON = 80;
    /** 离目标点这么近、速度这么小就算停住了。 */
    static final double AT = 0.05;

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
        if (supported(rig.entity) && Math.hypot(x - rig.entity.getX(), z - rig.entity.getZ()) >= AT) {
            rig.look.faceToward(x, z);
        }
        rig.keys.press(Key.FORWARD);
        rig.keys.release(Key.BACK);
    }

    /**
     * 朝 {@code (x, z)} 走向支撑的边沿、走出去落下 {@code drop} 格:前进键按到"再按这一刻、之后松着滑出去"就会落过 {@code reach} 为止
     * ({@link Kinematics#dropLanding},滑出去后空中不按键),之后松开,借着惯性滑出边沿。不是压一个固定的慢速——能多快走出去就多快,只要落点还在
     * 落点那一列里。离地之后再由 {@link #stop} 在空中修正落点。
     *
     * @param toEdge 身体中心离"身子整个离开支撑"那一点沿前进方向还有多远
     * @param reach  落点最远允许落在身体中心沿前进方向多远
     */
    static void approach(Rig rig, double x, double z, double toEdge, double reach, double drop) {
        ServerPlayer body = rig.entity;
        Controls keys = rig.keys;
        rig.look.faceToward(x, z);
        double yaw = Math.toRadians(body.getYRot());
        Vec3 motion = body.getDeltaMovement();
        double along = motion.x * -Math.sin(yaw) + motion.z * Math.cos(yaw);
        double landing = Kinematics.dropLanding(body.getGravity(), toEdge, along + groundAccel(body), 0, 0, drop);
        keys.release(Key.BACK);
        keys.set(Key.FORWARD, Double.isNaN(landing) || landing <= reach);
    }

    /** 按住前进的下一刻,身体沿它此刻朝着的方向会挪多远:原版先把这一刻的输入加速度加进速度,再移动。 */
    static double nextStride(ServerPlayer body) {
        double yaw = Math.toRadians(body.getYRot());
        Vec3 motion = body.getDeltaMovement();
        double along = motion.x * -Math.sin(yaw) + motion.z * Math.cos(yaw);
        return along + (body.onGround() ? groundAccel(body) : Kinematics.airAcceleration(body.isSprinting()));
    }

    /**
     * 停在 {@code (x, z)};身体在空中时落在脚高 {@code landingY}。
     *
     * @return 已经停在那一点上
     */
    static boolean stop(Rig rig, double x, double z, double landingY) {
        return stop(rig, x, z, landingY, 0, 0, 0, 0);
    }

    /**
     * 停在 {@code (x, z)} 附近;身体在空中时落在脚高 {@code landingY}。照这一刻松开键推算停在哪,只要停在那一点沿前进方向
     * 往后不超过 {@code behind}、往前不超过 {@code beyond} 以内就松着,不为了更靠近那一点再拉回去:落在一列里、停住就行的地方
     * (落点那一列),冲劲带着身子偏到另一侧也不回头走到中心。
     *
     * @param behind 离那一点还没走到时容许差多远(落点比起步低时,身子要整个离开起步那块的边沿,差不能超过半格减半个身宽)
     * @param beyond 已经走过了那一点时容许超过多远
     * @param hx     这一步水平方向的前进方向(东南西北之一):那一点已经在这个方向的身后(冲劲带过了头)就不转身回头去够它,松着、或按后退键刹就是;
     *               容许范围为零时不看
     * @return 已经停在那一点的容许范围内
     */
    static boolean stop(Rig rig, double x, double z, double landingY, double behind, double beyond, int hx, int hz) {
        ServerPlayer body = rig.entity;
        Controls keys = rig.keys;
        double dx = x - body.getX();
        double dz = z - body.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        Vec3 motion = body.getDeltaMovement();
        double speed = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        keys.release(Key.FORWARD);
        keys.release(Key.BACK);
        if (distance < Math.max(AT, Math.min(behind, beyond)) && speed < Kinematics.REST * 10) {
            return true;
        }
        boolean lenient = behind > 0 || beyond > 0;
        boolean passed = lenient && dx * hx + dz * hz <= 0;
        double yaw = Math.toRadians(body.getYRot());
        if (distance >= AT && supported(body) && !passed) {
            rig.look.faceToward(x, z);
            yaw = Math.toRadians(body.getYRot());
        }
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double ahead = dx * fx + dz * fz;
        double along = motion.x * fx + motion.z * fz;
        int airLeft = body.onGround() ? 0 : Kinematics.ticksToLand(body.getY(), motion.y, landingY, body.getGravity(), HORIZON);
        double groundAccel = groundAccel(body);
        double groundFriction = Kinematics.groundDrag(friction(body));
        double airAccel = Kinematics.airAcceleration(body.isSprinting());
        // 松着停在容许范围内就不动;否则挑停得离那一点最近的
        int choice = 0;
        double idle = simulate(along, airLeft, 0, groundAccel, groundFriction, airAccel) - ahead;
        if (idle < -behind || idle > beyond) {
            double best = Double.POSITIVE_INFINITY;
            for (int action : new int[] {1, 0, -1}) {
                double miss = Math.abs(simulate(along, airLeft, action, groundAccel, groundFriction, airAccel) - ahead);
                if (miss < best - 1.0E-4) {
                    best = miss;
                    choice = action;
                }
            }
        }
        if (choice > 0) {
            keys.press(Key.FORWARD);
        } else if (choice < 0) {
            keys.press(Key.BACK);
        }
        return false;
    }

    /**
     * 这一刻按 {@code action}(1 前进、0 松开、-1 后退)、之后一直松着,身体沿直线还会走多远才停住:先在空中飞完
     * {@code airLeft} 刻(在地上为 0),再在地上滑到停。
     */
    private static double simulate(double along, int airLeft, int action,
                                   double groundAccel, double groundFriction, double airAccel) {
        double pos = 0;
        double m = along;
        for (int t = 0; t < HORIZON; t++) {
            int input = t == 0 ? action : 0;
            if (t < airLeft) {
                m += airAccel * input;
                pos += m;
                m *= Kinematics.AIR_DRAG;
            } else {
                m += groundAccel * input;
                pos += m;
                m *= groundFriction;
                if (Math.abs(m) < Kinematics.REST) {
                    break;
                }
            }
        }
        return pos;
    }

    /** 地上按前进一刻加的速度:身体此刻的移动速度(含疾跑)与脚下的摩擦。 */
    private static double groundAccel(ServerPlayer body) {
        return Kinematics.groundAcceleration(body.getSpeed(), friction(body));
    }

    /** 脚下影响移动的那一格的摩擦(原版看脚下半格处)。 */
    private static double friction(ServerPlayer body) {
        BlockPos below = BlockPos.containing(body.getX(), body.getY() - 0.5000001, body.getZ());
        float friction = body.level().getBlockState(below).getBlock().getFriction();
        return friction > 0 ? friction : Kinematics.DEFAULT_FRICTION;
    }
}
