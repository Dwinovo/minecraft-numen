package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.dwinovo.numen.api.entity.Controls.Key;
import com.dwinovo.numen.pathing.api.Snapshots;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Flight;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.world.Kinematics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 空中飞行的执行:路线上连着的几步飞行(起飞、飞、降落)当一整条折线跟({@link Airway}),不逐格停。每一刻让局部控制器({@link Pilot})
 * 挑一组键,视角按颈部模型转过去({@link com.dwinovo.numen.api.entity.Look#steer}),只按键与转视角,不碰速度与位置。
 * 折线拉直时过两道关:扫过去物理上通({@link Airway#sweep}),新进入的格也过路线规格({@link com.dwinovo.numen.pathing.plan.Flight#refused})。
 *
 * <ul>
 *   <li><b>起飞</b>:身体站在地上,连按两下跳——按、松、按({@link Kinematics#TAKEOFF_TAPS} 刻),第二下切成飞行并起跳;切成了就按折线飞;</li>
 *   <li><b>推进</b>:认的是身体沿折线走到了哪(弧长),一刻跨过几格也算走过;每一步的落点投到折线上,身体过了它就算这一步走完;</li>
 *   <li><b>收处</b>:这一段飞行的末尾。降落:脚落到地面,原版就停飞,身体站在落点那一格;飞着收场:停在落点那一格里({@link #hold})。</li>
 * </ul>
 * 一次只管一段飞行;路线换了或这一段走完,{@link #reset}。
 */
final class AirControl {

    /** 飞着停在一格里,脚离这一格底的高度:放得下身体的范围是 [0, {@link Stance#HOVER_SPAN}),取中间。 */
    static final double HOVER = 0.2;
    /** 脚离这一格的上下沿至少这么远、水平不到 {@link #OVER} 远,就算停在了这一格里。 */
    private static final double MARGIN = 0.02;
    private static final double OVER = 0.3;
    /** 水平速度、竖直速度小于它们算停住了。 */
    private static final double STILL = 0.05;
    private static final double STILL_RISE = 0.02;
    /** 身体的弧长过了某一步的落点这么多,这一步就算走完(提前一点,身体正往前冲)。 */
    private static final double CROSS = 0.6;

    private final Rig rig;
    /** 一段飞行的期限:建折线时按这一段几步的最短耗时定。 */
    private final Watchdog watchdog;
    private final Helm helm;
    private Airway way;
    /** 折线从路线的第几步起、到第几步止(不含)、建线时路线有多长。 */
    private int base;
    private int end;
    private int size;
    /** 每一步的落点投在折线上的弧长。 */
    private double[] ends;
    /** 身体沿折线走到了哪。 */
    private double s;
    /** 起飞已经按过几刻键。 */
    private int taps;
    /** 上一次有步走完之后过了几刻。 */
    private int ticks;

    /** 这一趟的成本模型(路线规格在里面):拉直折线时查规格用。 */
    private final Supplier<CostModel> model;

    AirControl(Rig rig, Watchdog watchdog, Supplier<CostModel> model) {
        this.rig = rig;
        this.watchdog = watchdog;
        this.model = model;
        this.helm = new Helm(rig, Dynamics.flying(rig.entity.getAbilities().getFlyingSpeed()));
    }

    /** 这一刻的结果:按键的结果;路线上前 {@code to} 步已经走完,{@code ticks} 是自上一次有步走完起用了几刻(没有新走完的步为 0)。 */
    record Flown(Beat beat, int to, int ticks) {}

    /** 正在跟着一条折线。 */
    boolean active() {
        return way != null;
    }

    /** 路线换了,或这一段走完:折线、起飞的计数与正在按的都忘掉,松开键。 */
    void reset() {
        way = null;
        taps = 0;
        ticks = 0;
        helm.forget();
    }

    /**
     * 飞一刻:路线 {@code course} 从正在走的这一步起到第 {@code last} 步(含)是连着的一段飞行。
     * 折线只在第一次、或路线长了(接续段拼上)、或这一段收在哪变了的时候建。
     */
    Flown fly(Course course, int last) {
        if (way == null || course.size() != size || last + 1 != end) {
            build(course, last + 1);
        }
        int cur = course.cur();
        Maneuver m = course.at(cur);
        ticks++;
        if (m.kind() == MoveKind.TAKEOFF && !Snapshots.flying(rig.entity)) {
            return new Flown(takeoff(), cur, 0);
        }
        s = way.project(rig.entity.position(), s);
        while (cur < last && s >= ends[cur - base] - CROSS) {
            cur++;
        }
        if (cur == last) {
            Maneuver tail = course.at(last);
            if (tail.kind() == MoveKind.LAND ? landed() : hovering(tail.to())) {
                int walked = ticks;
                reset();
                rig.keys.releaseAll();
                return new Flown(Beat.IDLE, last + 1, walked);
            }
        }
        Beat beat = helm.steer(way, s);
        int walked = cur > course.cur() ? ticks : 0;
        if (walked > 0) {
            ticks = 0;
        }
        return new Flown(beat, cur, walked);
    }

    /**
     * 停在 {@code node} 那一格的上空(脚在 {@link #HOVER} 的高度)。停住了返回 true。悬停没有折线可跟,只有一个点;
     * 收场时的悬停与飞行中是同一个控制器。
     */
    boolean hold(BlockPos node) {
        helm.steer(Airway.at(new Vec3(node.getX() + 0.5, node.getY() + HOVER, node.getZ() + 0.5)), 0);
        return hovering(node);
    }

    // ==================== 起飞 ====================

    /** 连按两下跳:按、松、按。三刻之后还没飞起来,按键就停在松开上,等看护超时、重搜。 */
    private Beat takeoff() {
        rig.keys.release(Key.SPRINT);
        rig.keys.release(Key.FORWARD);
        rig.keys.release(Key.BACK);
        rig.keys.release(Key.SNEAK);
        if (taps < Kinematics.TAKEOFF_TAPS) {
            rig.keys.set(Key.JUMP, taps != 1);
            taps++;
        } else {
            rig.keys.release(Key.JUMP);
        }
        return Beat.IDLE;
    }

    // ==================== 飞 ====================

    /** 路线 {@code [cur, end)} 这几步的落点连成折线,从身体此刻的位置起,拉直之后跟。 */
    private void build(Course course, int end) {
        base = course.cur();
        this.end = end;
        size = course.size();
        List<Vec3> raw = new ArrayList<>();
        raw.add(rig.entity.position());
        for (int i = base; i < end; i++) {
            raw.add(point(course.at(i)));
        }
        Maneuver tail = course.at(end - 1);
        Vec3 touchdown = tail.kind() == MoveKind.LAND ? raw.get(raw.size() - 1) : null;
        CostModel spec = model.get();
        way = Airway.straighten(raw, touchdown, (from, to) -> {
            BlockPos hit = helm.sweep(from, to, touchdown);
            return hit != null ? hit : Flight.refused(spec, rig.world(), from, to);
        });
        ends = new double[end - base];
        double along = 0;
        for (int i = base; i < end; i++) {
            along = Math.max(along, way.project(raw.get(i - base + 1), along));
            ends[i - base] = along;
        }
        s = 0;
        watchdog.begin(course.ticksBefore(end), rig.entity.position());
    }

    /** 这一步落点上脚该在哪:飞着停在落点那一格里取 {@link #HOVER},降落是落点上的地面。 */
    private static Vec3 point(Maneuver m) {
        BlockPos to = m.to();
        double feet = m.landing().kind() == Stance.Kind.FLYING ? to.getY() + HOVER : m.landing().feetY();
        return new Vec3(to.getX() + 0.5, feet, to.getZ() + 0.5);
    }

    // ==================== 收处 ====================

    /** 降落走完:脚落了地、飞行停了。身体落在落点那一格里没有,落地后的冲劲会带到哪,由段状态机照走地面的步子认(落在路线外就重搜)。 */
    private boolean landed() {
        return rig.entity.onGround() && !Snapshots.flying(rig.entity);
    }

    /** 悬停:脚落在落点那一格的飞行范围里({@link Stance#HOVER_SPAN})、水平在格子中心附近,速度小到停住了。 */
    private boolean hovering(BlockPos node) {
        var body = rig.entity;
        Vec3 motion = body.getDeltaMovement();
        return Math.hypot(motion.x, motion.z) < STILL && Math.abs(motion.y) < STILL_RISE
                && Math.hypot(node.getX() + 0.5 - body.getX(), node.getZ() + 0.5 - body.getZ()) < OVER
                && body.getY() > node.getY() + MARGIN && body.getY() < node.getY() + Stance.HOVER_SPAN - MARGIN;
    }
}
