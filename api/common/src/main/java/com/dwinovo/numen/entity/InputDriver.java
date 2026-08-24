package com.dwinovo.numen.entity;

import com.dwinovo.numen.mixin.BoatAccessor;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractBoat;
import net.minecraft.world.phys.Vec3;

/**
 * Drives a companion {@link ServerPlayer} body the way a client's key presses
 * would: by setting the player's
 * movement INPUTS ({@code zza}/{@code xxa}, sprint, sneak, jump) and aim, then
 * letting vanilla player physics ({@code LivingEntity.travel}) do the actual
 * stepping, collision and 0.6-block step-up. Replaces the old {@code BodyMotor}
 * which wrote velocity directly onto a Mob's MoveControl.
 *
 * <p>A fake player has no client to send movement packets, so the server's own
 * player tick runs {@code travel} against these inputs and nothing overrides the
 * resulting position — that is what makes input-driving a server-side body work.
 * Inputs are momentary: set them every tick while moving, and {@link #halt} every
 * tick while stopped (otherwise the last forward input keeps it walking).
 */
public final class InputDriver {

    private InputDriver() {}

    /** Face {@code target} and push full forward. Call each tick while travelling. */
    public static void stepToward(ServerPlayer p, Vec3 target, boolean sprint) {
        faceYaw(p, target);
        // 看路:行走时视线落在前方地面,不残留上一次 lookAt 的仰角(挖矿抬头后
        // 一路走一路望天的病根)。当 tick 需要瞄准的动作(挖/放)在 stepToward
        // 之后自会 lookAt 覆盖,互不打架。
        p.setXRot(12.0f);
        p.zza = 1.0f;
        p.xxa = 0.0f;
        p.setSprinting(sprint && !p.isShiftKeyDown());
    }

    /** Aim the eyes at a point (yaw + pitch) — e.g. a block being mined or placed. */
    public static void lookAt(ServerPlayer p, Vec3 point) {
        p.lookAt(EntityAnchorArgument.Anchor.EYES, point);
    }

    /**
     * Upward impulse, routed the way vanilla routes pressing the jump key: a ground hop
     * on land ({@code jumpFromGround}), or a swim-up stroke in water/lava (vanilla
     * {@code jumpInLiquid} = +0.04/tick). Holding jump whenever the feet sink below the
     * lane is what keeps a body riding the water surface — a fake player has no
     * client to translate a key into the liquid case, so we do it here. Call every tick
     * you want to keep rising; in water it's the per-tick stroke, not a one-shot.
     */
    public static void jump(ServerPlayer p) {
        if (p.onGround()) {
            p.jumpFromGround();
        } else if (p.isInWater() || p.isInLava()) {
            p.setDeltaMovement(p.getDeltaMovement().add(0.0, 0.04, 0.0));
        }
    }

    public static void sneak(ServerPlayer p, boolean on) {
        p.setShiftKeyDown(on);
    }

    /** 船的转向死区(度):差角小于它就不压舵。太小会和转向动量打架来回摆头。 */
    private static final float BOAT_TURN_DEADBAND = 5.0f;

    /**
     * 骑乘驾驶:朝 {@code target} 压舵,行进期间每刻调用(与 {@link #stepToward} 同节拍)。
     *
     * <p>船走原版桨物理:按差角给左右键、恒按前进,然后调原版 {@code controlBoat}
     * (见 {@link BoatAccessor})——输入语义和真玩家按 WASD 完全一致,推进常数零复制,
     * 划桨动画照常同步。服务端能动船的前提是载具权威开关(MixinEntityVehicleControl)。
     *
     * <p>马这类生物载具由原版 {@code travelRidden} 读<b>骑手</b>的朝向与前进键,
     * 写她自己的输入即可,不用碰载具。
     */
    public static void steerVehicle(ServerPlayer p, Vec3 target) {
        Entity vehicle = p.getVehicle();
        if (vehicle instanceof AbstractBoat boat) {
            double dx = target.x - boat.getX();
            double dz = target.z - boat.getZ();
            float want = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float diff = Mth.wrapDegrees(want - boat.getYRot());
            boat.setInput(diff < -BOAT_TURN_DEADBAND, diff > BOAT_TURN_DEADBAND, true, false);
            ((BoatAccessor) boat).numen$controlBoat();
            faceYaw(p, target);   // 乘员朝向不驱动船,看向去处只是像个人
            return;
        }
        faceYaw(p, target);
        p.zza = 1.0f;
        p.xxa = 0.0f;
    }

    /** 松舵:船停桨,骑手输入清零。离开驾驶状态的每刻收尾。 */
    public static void haltVehicle(ServerPlayer p) {
        if (p.getVehicle() instanceof AbstractBoat boat) {
            boat.setInput(false, false, false, false);
        }
        halt(p);
    }

    /** Zero all locomotion input. Call each tick while idle/arrived. */
    public static void halt(ServerPlayer p) {
        p.zza = 0.0f;
        p.xxa = 0.0f;
        p.setSprinting(false);
    }

    /** Turn the body (and head) to face {@code target} horizontally — travel goes where yaw points. */
    private static void faceYaw(ServerPlayer p, Vec3 target) {
        double dx = target.x - p.getX();
        double dz = target.z - p.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        p.setYRot(yaw);
        p.setYHeadRot(yaw);
    }
}
