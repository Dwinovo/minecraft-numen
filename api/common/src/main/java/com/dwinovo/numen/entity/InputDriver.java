package com.dwinovo.numen.entity;

import com.dwinovo.numen.mixin.BoatAccessor;
import com.dwinovo.numen.pathing.body.Controls;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;

/**
 * 同伴自己的朝向、看向与驾船。走、跳、潜行、停是按她的键盘({@link NumenPlayer#controls},寻路模块的 {@link Controls}),
 * 不在这里;看向一格方块上的哪一点、怎么转过去由寻路模块的瞄准({@code Aim})管,这里管的是她自己的事:看着说话的人、
 * 看着要打要用的实体、转身朝一个方向,以及坐在船里压舵。
 */
public final class InputDriver {

    private InputDriver() {}

    /** Aim the eyes at a point (yaw + pitch) — e.g. the eyes of whoever she talks to or hits. */
    public static void lookAt(ServerPlayer p, Vec3 point) {
        p.lookAt(EntityAnchorArgument.Anchor.EYES, point);
    }

    /** 船的转向死区(度):差角小于它就不压舵。太小会和转向动量打架来回摆头。 */
    private static final float BOAT_TURN_DEADBAND = 5.0f;

    /**
     * 骑乘驾驶:朝 {@code target} 压舵,行进期间每刻调用。
     *
     * <p>船走原版桨物理:按差角给左右键、恒按前进,然后调原版 {@code controlBoat}
     * (见 {@link BoatAccessor})——输入语义和真玩家按 WASD 完全一致,推进常数零复制,
     * 划桨动画照常同步。服务端能动船的前提是载具权威开关(MixinEntityVehicleControl)。
     *
     * <p>马这类生物载具由原版 {@code travelRidden} 读<b>骑手</b>的朝向与前进键,
     * 转过去、按住她的前进键即可,不用碰载具。
     */
    public static void steerVehicle(NumenPlayer p, Vec3 target) {
        Entity vehicle = p.getVehicle();
        if (vehicle instanceof Boat boat) {
            double dx = target.x - boat.getX();
            double dz = target.z - boat.getZ();
            float want = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float diff = Mth.wrapDegrees(want - boat.getYRot());
            boat.setInput(diff < -BOAT_TURN_DEADBAND, diff > BOAT_TURN_DEADBAND, true, false);
            ((BoatAccessor) boat).numen$controlBoat();
            face(p, target);   // 乘员朝向不驱动船,看向去处只是像个人
            return;
        }
        face(p, target);
        p.controls().press(Controls.Key.FORWARD);
    }

    /** 松舵:船停桨,她的走路键松开。离开驾驶状态的每刻收尾。 */
    public static void haltVehicle(NumenPlayer p) {
        if (p.getVehicle() instanceof Boat boat) {
            boat.setInput(false, false, false, false);
        }
        p.controls().stop();
    }

    /** Turn the body (and head) to face {@code target} horizontally — walking goes where yaw points. */
    public static void face(ServerPlayer p, Vec3 target) {
        double dx = target.x - p.getX();
        double dz = target.z - p.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        p.setYRot(yaw);
        p.setYHeadRot(yaw);
    }
}
