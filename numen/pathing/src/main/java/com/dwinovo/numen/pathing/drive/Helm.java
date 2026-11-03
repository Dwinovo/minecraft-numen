package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.api.entity.Controls.Key;
import com.dwinovo.numen.api.entity.Hotbar;
import com.dwinovo.numen.api.entity.Mouse;
import com.dwinovo.numen.pathing.api.Snapshots;
import com.dwinovo.numen.pathing.drive.Dynamics.Action;
import com.dwinovo.numen.pathing.drive.Pilot.Supply;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Kinematics.Motion;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * 操舵:局部控制器({@link Pilot})挑出一组候选之后,身体怎么做——转视角(颈部模型)、落键、放烟花(右键)。创造飞行与滑翔共用这一处,
 * 只有物理与键集({@link Dynamics})不同。扫路的判据({@link #sweep})也在这里:往前推演、拉直折线读的是同一个。
 *
 * <p>烟花:放之前先把它拿在手上(换手经 {@link Hotbar}、记进身体动作);右键有间隔({@link #COOLDOWN}),冷却在<b>尝试之前</b>就锁存,
 * 这一下没放成也不在下一刻再试;烧着的这一支还推几刻自己数,身上还剩几支每刻读背包。
 */
final class Helm {

    /** 飞行的线往下多留的一层(格):飞着的身体脚一碰到地面原版就停飞。 */
    private static final double SKIN = 0.05;
    /** 离降落点那一圈再往外这么近以内,允许擦着地面(落地就是要碰到它)。 */
    private static final double LANDING = 10.0;
    /** 右键放烟花的间隔(刻):原版连续使用的间隔。 */
    static final int COOLDOWN = 4;

    private final Rig rig;
    private final Pilot pilot;
    private BodyStats stats;
    /** 此刻正在跟的那条线:折线,或悬停的一点。局部控制器的扫路读它的降落点。 */
    private Airway steering;
    /** 烧着的这一支还推几刻,和放下一支还要等几刻。 */
    private int burning;
    private int cooldown;
    /** 这一趟放了几支。 */
    private int fired;

    Helm(Rig rig, Dynamics physics) {
        this.rig = rig;
        this.stats = rig.snapshot().stats();
        this.pilot = new Pilot(physics,
                (from, to) -> steering == null ? sweep(from, to, null, 0) : sweep(from, to, steering.touchdown(), steering.zone()),
                rig.entity.getEyeHeight());
    }

    /**
     * 照 {@code track} 从 {@code along}(线头的爬升段 {@link Airway#climb} 之后)起一直飞下去,第一个挡路的方块;没有为 null。线上每隔 {@link #LOOK_STEP} 格取一点,相邻两点之间按飞行的扫掠查
     * (降落点附近允许擦着地面)。控制器只看近处的推演,绕得开就绕,线本身通不通是这里问的:线被堵了(飞到一半前面多了一堵墙),就该改降,不是在墙前打转。
     */
    BlockPos blocked(Airway track, double along) {
        double start = Math.max(along, track.climb());
        Vec3 from = track.at(start);
        for (double s = start; s < track.length(); ) {
            s = Math.min(track.length(), s + LOOK_STEP);
            Vec3 to = track.at(s);
            BlockPos hit = sweep(from, to, track.touchdown(), track.zone());
            if (hit != null) {
                return hit;
            }
            from = to;
        }
        return null;
    }

    /** {@link #blocked} 沿线取点的间距(格)。 */
    private static final double LOOK_STEP = 8.0;

    /** 这一趟放了几支烟花。 */
    int fired() {
        return fired;
    }

    /** 换了一段路线或收场:正在按的忘掉。 */
    void forget() {
        pilot.forget();
    }

    /** 飞过这一段碰不碰方块;离降落点近处允许擦着地面,别处都往下多留 {@link #SKIN}。 */
    BlockPos sweep(Vec3 from, Vec3 to, Vec3 touchdown, double zone) {
        boolean landing = touchdown != null && Math.hypot(to.x - touchdown.x, to.z - touchdown.z) < zone + LANDING;
        return Airway.sweep(rig.world(), stats, landing ? 0 : SKIN).blocker(from, to);
    }

    /** 这一刻按什么、视角转到哪、放不放烟花。被围住(每一组都撞方块)时松开所有键,交出被撞的那一格。 */
    Beat steer(Airway track, double along) {
        var body = rig.entity;
        steering = track;
        stats = rig.snapshot().stats();
        if (burning > 0) {
            burning--;
        }
        if (cooldown > 0) {
            cooldown--;
        }
        int rockets = cooldown == 0 && burning == 0 ? Snapshots.mobility(body).fireworks() : 0;
        Motion now = new Motion(body.position(), body.getDeltaMovement());
        Action pick = pilot.choose(now, rig.look.neck(), track, along, rig.keys.jumpWouldToggleFlight(), new Supply(rockets, burning));
        if (pick == null) {
            rig.keys.releaseAll();
            BlockPos cell = pilot.blocker() == null ? body.blockPosition() : pilot.blocker();
            return new Beat.Blocked(new Blockage(cell, rig.world().getBlockState(cell), MoveKind.FLY, null, Blockage.Hitch.BOXED));
        }
        rig.look.steer(pick.yaw(), pick.pitch());
        var keys = pick.keys();
        rig.keys.set(Key.FORWARD, keys.forward() > 0);
        rig.keys.set(Key.BACK, keys.forward() < 0);
        rig.keys.set(Key.SPRINT, keys.sprint());
        rig.keys.set(Key.LEFT, keys.left() > 0);
        rig.keys.set(Key.RIGHT, keys.left() < 0);
        rig.keys.set(Key.JUMP, keys.jump());
        rig.keys.set(Key.SNEAK, keys.sneak());
        if (keys.rocket() && rockets > 0) {
            fire();
        }
        return Beat.IDLE;
    }

    /** 右键放一支烟花:先拿在手上;冷却先锁存。 */
    private void fire() {
        cooldown = COOLDOWN;
        Hotbar.Grip grip = rig.hotbar.grip(Items.FIREWORK_ROCKET);
        rig.act(grip.action());
        if (grip.ready()) {
            int burn = Snapshots.mobility(rig.entity).rocketBurn();
            if (rig.mouse.useItem() instanceof Mouse.Use.Pressed) {
                burning = burn;
                fired++;
            }
        }
    }
}
