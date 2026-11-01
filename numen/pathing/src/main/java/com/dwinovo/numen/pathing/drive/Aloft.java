package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.api.entity.Controls;
import com.dwinovo.numen.api.entity.Controls.Key;
import com.dwinovo.numen.pathing.world.Kinematics;
import com.dwinovo.numen.pathing.world.Kinematics.Keys;
import com.dwinovo.numen.pathing.world.Kinematics.Motion;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * 飞着的身体每刻按什么:照原版飞行的运动({@link Kinematics#fly})往后推,从几种键里挑一种。只按键、转视角,不碰速度与位置。
 *
 * <p><b>水平</b>:离这一段飞行收在哪里还远,就朝航点一路疾飞;近到巡航的刹停距离({@link Kinematics#flyStop})之内,就朝收处转过去,这一刻在"疾飞、平飞、松开、后退"里挑一种,推算按下它、之后一直松着,身体最终停在哪({@link Kinematics#flyRest}),
 * 挑停得离要停的那一点最近的。要停的那一点({@code rest})是这一段飞行收在哪里;没有(这一段后面还没定)就一直疾飞。远的时候疾飞停得最近,
 * 所以先加速;临近了再按下去会冲过,就松开滑过去、冲过头就按后退刹——与地面上的停车({@link Steering#stop})是同一个办法。
 *
 * <p><b>竖直</b>:跳往上、潜行往下,飞着松开就停在原处(没有重力):这一刻在"跳、松开、潜行"里挑一种,按下它之后脚最终停在哪
 * ({@link Kinematics#flyRise}),挑离目标高度最近的。
 */
final class Aloft {

    /** 脚离目标高度这么近、竖直速度这么小,就算停在了这一格里。 */
    static final double LEVEL = 0.2;
    /** 水平离那一点这么近、速度这么小,就算停住了。 */
    static final double STOPPED = 0.25;
    /** 飞着停在一格里,脚离这一格底的高度:放得下身体的范围是 [0, {@link com.dwinovo.numen.pathing.plan.Stance#HOVER_SPAN}),取中间。 */
    static final double HOVER = 0.2;
    /** 离航点这么近就不再转向(离得太近,朝向会乱跳)。 */
    private static final double FACE = 0.2;
    /** 刹停距离之外再留这么多格就开始刹:一刻的步子随速度大到一格多。 */
    private static final double MARGIN = 1.0;
    /** 竖直速度小于它算不动了。 */
    private static final double STILL = 0.02;

    private Aloft() {}

    /**
     * 这一刻朝 {@code aim}(航点,脚底中心)飞,脚朝着 {@code feetY} 的高度;{@code rest} 是这一段飞行要停在哪一点(水平),没有为 null。
     *
     * @param sprint 能疾飞就疾飞
     */
    static void steer(Rig rig, Vec3 aim, Vec3 rest, double feetY, boolean sprint) {
        ServerPlayer body = rig.entity;
        Controls keys = rig.keys;
        float flyingSpeed = body.getAbilities().getFlyingSpeed();
        // 离这一段飞行的收处不到巡航的刹停距离了:往收处刹停,朝着它;还远就朝航点一路疾飞
        boolean braking = false;
        if (rest != null) {
            Vec3 motion = body.getDeltaMovement();
            double stop = Kinematics.flyStop(flyingSpeed, Math.hypot(motion.x, motion.z)).distance();
            braking = Math.hypot(rest.x - body.getX(), rest.z - body.getZ()) <= stop + MARGIN;
        }
        Vec3 face = braking ? rest : aim;
        if (Math.hypot(face.x - body.getX(), face.z - body.getZ()) >= FACE) {
            rig.look.faceToward(face.x, face.z);
        }
        Motion now = new Motion(body.position(), body.getDeltaMovement());
        float yaw = body.getYRot();
        float pitch = body.getXRot();
        // 候选:松开、平飞、疾飞、后退;松开排第一,不比它好的不换
        Keys[] candidates = {
                new Keys(0, 0, false, false, false, yaw, pitch, false),
                new Keys(1, 0, false, false, false, yaw, pitch, false),
                new Keys(1, 0, false, false, sprint, yaw, pitch, false),
                new Keys(-1, 0, false, false, false, yaw, pitch, false)};
        int pick = Math.hypot(aim.x - body.getX(), aim.z - body.getZ()) < FACE ? 0 : 2;
        if (braking) {
            pick = 0;
            double best = distance(Kinematics.flyRest(now, candidates[0], flyingSpeed), rest);
            for (int i = 1; i < candidates.length; i++) {
                double error = distance(Kinematics.flyRest(now, candidates[i], flyingSpeed), rest);
                if (error < best - 1.0E-6) {
                    best = error;
                    pick = i;
                }
            }
        }
        Keys chosen = candidates[pick];
        keys.set(Key.FORWARD, chosen.forward() > 0);
        keys.set(Key.BACK, chosen.forward() < 0);
        keys.set(Key.SPRINT, chosen.sprint());
        keys.release(Key.LEFT);
        keys.release(Key.RIGHT);
        int lift = 0;
        double best = Math.abs(body.getY() + Kinematics.flyRise(flyingSpeed, body.getDeltaMovement().y, 0) - feetY);
        for (int direction : new int[] {1, -1}) {
            if (direction > 0 && keys.jumpWouldToggleFlight()) {
                continue;
            }
            double error = Math.abs(body.getY() + Kinematics.flyRise(flyingSpeed, body.getDeltaMovement().y, direction) - feetY);
            if (error < best - 1.0E-6) {
                best = error;
                lift = direction;
            }
        }
        keys.set(Key.JUMP, lift > 0);
        keys.set(Key.SNEAK, lift < 0);
    }

    /**
     * 停在 {@code node} 那一格的上空(脚在 {@link #HOVER} 的高度)。停住了(水平不动、落在中心附近、高度对了)返回 true。
     */
    static boolean hold(Rig rig, net.minecraft.core.BlockPos node) {
        ServerPlayer body = rig.entity;
        Vec3 center = new Vec3(node.getX() + 0.5, node.getY() + HOVER, node.getZ() + 0.5);
        steer(rig, center, center, center.y, false);
        Vec3 motion = body.getDeltaMovement();
        return Math.hypot(motion.x, motion.z) < Kinematics.REST * 10 && Math.hypot(center.x - body.getX(), center.z - body.getZ()) < STOPPED
                && Math.abs(body.getY() - center.y) < LEVEL && Math.abs(motion.y) < STILL;
    }

    private static double distance(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }
}
