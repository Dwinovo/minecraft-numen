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
 * <p>只在脚踏实地时转身;腾空时不回身,按身体此刻朝着的方向前进或后退来修正——空中转过身去,落地就是背对着路。
 */
final class Steering {

    /** 推算的最长刻数。 */
    private static final int HORIZON = 80;
    /** 离目标点这么近、速度这么小就算停住了。 */
    static final double AT = 0.05;

    private Steering() {}

    /** 不减速地朝 {@code (x, z)} 走。 */
    static void pass(Rig rig, double x, double z) {
        if (rig.entity.onGround()) {
            rig.look.faceToward(x, z);
        }
        rig.keys.press(Key.FORWARD);
        rig.keys.release(Key.BACK);
    }

    /**
     * 朝 {@code (x, z)} 走,沿那个方向的速度不超过 {@code speed}(格每刻):低于它就按前进,否则松开。走出边沿时用它压住
     * 冲出去的速度,离地之后再由 {@link #stop} 在空中修正落点。
     */
    static void approach(Rig rig, double x, double z, double speed) {
        ServerPlayer body = rig.entity;
        Controls keys = rig.keys;
        rig.look.faceToward(x, z);
        double yaw = Math.toRadians(body.getYRot());
        Vec3 motion = body.getDeltaMovement();
        double along = motion.x * -Math.sin(yaw) + motion.z * Math.cos(yaw);
        keys.release(Key.BACK);
        keys.set(Key.FORWARD, along < speed);
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
        ServerPlayer body = rig.entity;
        Controls keys = rig.keys;
        double dx = x - body.getX();
        double dz = z - body.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        Vec3 motion = body.getDeltaMovement();
        double speed = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        keys.release(Key.FORWARD);
        keys.release(Key.BACK);
        if (distance < AT && speed < Kinematics.REST * 10) {
            return true;
        }
        // 只在地上转身;腾空时不回身,沿身体此刻朝着的方向按前进后退修正
        if (distance >= AT && body.onGround()) {
            rig.look.faceToward(x, z);
        }
        double yaw = Math.toRadians(body.getYRot());
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double ahead = dx * fx + dz * fz;
        double along = motion.x * fx + motion.z * fz;
        int airLeft = body.onGround() ? 0 : Kinematics.ticksToLand(body.getY(), motion.y, landingY, body.getGravity(), HORIZON);
        double groundAccel = groundAccel(body);
        double groundFriction = Kinematics.groundDrag(friction(body));
        double airAccel = Kinematics.airAcceleration(body.isSprinting());
        double best = Double.POSITIVE_INFINITY;
        int choice = 0;
        for (int action : new int[] {1, 0, -1}) {
            double rest = simulate(along, airLeft, action, groundAccel, groundFriction, airAccel);
            double miss = Math.abs(rest - ahead);
            if (miss < best - 1.0E-4) {
                best = miss;
                choice = action;
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
