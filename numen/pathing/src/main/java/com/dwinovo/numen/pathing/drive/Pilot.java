package com.dwinovo.numen.pathing.drive;

import java.util.List;

import com.dwinovo.numen.api.entity.Look;
import com.dwinovo.numen.api.entity.Neck;
import com.dwinovo.numen.pathing.drive.Dynamics.Action;
import com.dwinovo.numen.pathing.world.Kinematics.Keys;
import com.dwinovo.numen.pathing.world.Kinematics.Motion;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 空中的局部控制器:身体沿一条折线({@link Airway})飞,每一刻从一组候选({@link Dynamics#actions})里挑一组去按。只管挑,不碰身体与世界——
 * 状态进、候选出,物理({@link Dynamics})与扫路({@link Airway.Sweep})都是传进来的,所以创造飞行与滑翔共用它。
 *
 * <p>候选是宏动作:每一组按下 {@link #HOLD} 刻(玩家按键不会每刻换),之后替身体往后"滑"({@link Dynamics#coast},创造飞行是松开键,滑翔是照样滑翔)到停住或推演够长。
 * 颈部({@link Neck})和身体一起往前算,推演里的朝向与真身体转头一模一样。挑的次序是字典式的:
 * <ol>
 *   <li><b>硬约束</b>:推演里任何一刻身体盒碰到方块(飞着往下多留一层皮,擦着地面就是落了地,{@link Airway.Sweep}),或这一组按下去之后滑到停不下来
 *       (滑出去的那一程撞方块),整组不要;一组都过不去({@link #blocker} 交出碰到的那一格)就是被围住了;</li>
 *   <li><b>软代价</b>(都是弧长的格数):推演终点在折线上走到了哪(越远越好),离折线多远(平方扣分),
 *       折线末尾之外的重扣——末尾是收处,滑出去的终点不过收处,就是"现在松键还停得在收处",所以远的时候一路疾飞,近了才松开、冲过头才按后退;
 *       离收处不到 {@link #NEAR} 格时只看滑停的点离收处那一点多远(悬停与落点要停在一个点上);
 *       与正在按的那一组差多少({@link Dynamics#change}),放烟花要花钱({@link Dynamics#cost});</li>
 * </ol>
 * 节奏:选中的一组至少按满 {@link #HOLD} 刻才换,被否决了才例外;离收处不到 {@link #NEAR} 格时每刻都可以换(悬停与落点要细调);
 * 后退刹车一旦按下就按到速度降到 {@link Dynamics#committed} 以下(推刹承诺锁存),不在"后退"与"松开"之间来回抖。
 */
final class Pilot {

    /** 一组按键至少按多少刻才换。 */
    static final int HOLD = 3;
    /** 离折线末尾不到这么远,按键每刻都可以换。 */
    static final double NEAR = 4.0;
    /** 推演的前几刻逐刻扫路,之后一次扫完剩下的。 */
    private static final int WATCH = 10;
    /** 离折线的距离每格平方扣多少分(弧长的格数)。 */
    private static final double LATERAL = 1.0;
    /** 离收处不到 {@link #NEAR} 格时,滑停的点离收处每格平方扣多少分。 */
    private static final double NEAR_WEIGHT = 40.0;
    /** 终点滑出折线末尾,每格扣多少倍。 */
    private static final double OVERSHOOT = 3.0;
    /** 视线朝折线前方多远的点:至少这么多格,水平速度越快越远。 */
    private static final double LOOK = 3.0;
    private static final double LOOK_PER_SPEED = 6.0;

    private final Dynamics physics;
    private final Airway.Sweep sweep;
    /** 眼睛离脚底的高度:朝路线上一点看的俯仰,从眼睛算。 */
    private final double eye;
    private Action held;
    private int age;
    private BlockPos blocker;
    /** 挑中的那一组要靠刹车才不撞({@link #warned})。 */
    private boolean warned;
    /** 这一次推演用了刹车一程。 */
    private boolean braked;
    /** 这一次推演里落到地面时的伤害扣分({@link Dynamics#impact})。 */
    private double impact;

    Pilot(Dynamics physics, Airway.Sweep sweep, double eye) {
        this.physics = physics;
        this.sweep = sweep;
        this.eye = eye;
    }

    /** 换了一段路线或收场:正在按的忘掉。 */
    void forget() {
        held = null;
        age = 0;
    }

    /** 这一刻挑中的那一组,松开键滑出去会撞、要靠刹车才停得住(创造飞行)或拉起(滑翔):前方有东西,宜早做打算。 */
    boolean warned() {
        return warned;
    }

    /** 上一次所有候选都被否决时,被撞的那一格;这一刻没被围住为 null。 */
    BlockPos blocker() {
        return blocker;
    }

    /**
     * 这一刻按哪一组。
     *
     * @param now        身体此刻的位置与速度
     * @param neck       颈部此刻的样子({@link Look#neck})
     * @param s          身体已经走到的弧长
     * @param jumpLocked 此刻按下跳会被当成双击切换飞行({@link com.dwinovo.numen.api.entity.Controls#jumpWouldToggleFlight}),带跳的候选不挑
     * @param supply     烟花:还能放几支、正烧着的这一支还推几刻
     * @return 要按的一组;所有候选都被否决为 null
     */
    Action choose(Motion now, Neck neck, Airway way, double s, boolean jumpLocked, Supply supply) {
        Vec3 speed = now.velocity();
        Vec3 aim = way.at(Math.min(way.length(), s + LOOK + LOOK_PER_SPEED * Math.hypot(speed.x, speed.z)));
        Vec3 from = now.pos().add(0, eye, 0);
        double dx = aim.x - from.x;
        double dz = aim.z - from.z;
        float aimYaw = Math.hypot(dx, dz) < 0.3 ? neck.yaw() : Look.yawOf(dx, dz);
        float aimPitch = Look.pitchOf(dx, aim.y + eye - from.y, dz);
        int hold = way.length() - s < NEAR ? 1 : HOLD;
        blocker = null;
        if (held != null && (age < hold || physics.committed(held, now)) && !(jumpLocked && held.keys().jump())
                && !(held.keys().rocket() && supply.rockets <= 0)
                && rollout(now, neck, held, way, s, hold, supply) > Double.NEGATIVE_INFINITY) {
            age++;
            return held;
        }
        Action best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        List<Action> actions = physics.actions(aimYaw, aimPitch);
        for (Action action : actions) {
            if (jumpLocked && action.keys().jump() || action.keys().rocket() && supply.rockets <= 0) {
                continue;
            }
            double score = rollout(now, neck, action, way, s, hold, supply);
            if (score == Double.NEGATIVE_INFINITY) {
                continue;
            }
            if (held != null) {
                score -= physics.change(held, action);
            }
            if (best == null || score > bestScore) {
                best = action;
                bestScore = score;
            }
        }
        if (best == null && !physics.stoppable()) {
            best = survive(now, neck, way, supply);
        }
        warned = best != null && rollout(now, neck, best, way, s, hold, supply) > Double.NEGATIVE_INFINITY && braked;
        age = best != null && best.equals(held) ? age + 1 : 1;
        held = best;
        return best;
    }

    /**
     * 一组候选都过不去,而身体停不下来(滑翔):不是放弃,是挑撞上之前能多飞几刻的那一组,拖得越久越有机会(转向、拉起、烟花、等落点进了射程),
     * 一样久挑落地伤得轻的;一刻都飞不了的才算被围住。
     */
    private Action survive(Motion now, Neck neck, Airway way, Supply supply) {
        Action best = null;
        int bestTicks = 0;
        for (Action action : physics.actions(neck.yaw(), neck.pitch())) {
            if (action.keys().rocket() && supply.rockets <= 0) {
                continue;
            }
            Motion m = now;
            Neck n = neck;
            int burning = action.keys().rocket() ? physics.burn() : supply.burning;
            int t = 0;
            for (; t < physics.horizon(); t++) {
                n = n.toward(action.yaw(), action.pitch());
                Step step = advance(m, n, action.keys().burning(burning-- > 0), way.touchdown());
                if (sweepBlocked(m.pos(), step.motion().pos())) {
                    break;
                }
                m = step.motion();
            }
            if (t > bestTicks) {
                best = action;
                bestTicks = t;
            }
        }
        return best;
    }

    /**
     * 按 {@code action} {@code hold} 刻、之后替身体往后滑,终点的分数;沿途碰到方块是负无穷。
     * 颈部从 {@code neck} 起每刻往 {@code action} 要的方向转一刻。滑出去的那一程碰了方块,再试一程刹车({@link Dynamics#brake}):
     * 刹得住就还算数,只是要扣分——下一刻就得刹;刹也刹不住才整组不要。
     */
    private double rollout(Motion now, Neck neck, Action action, Airway way, double s, int hold, Supply supply) {
        Motion m = now;
        Neck n = neck;
        Vec3 down = way.touchdown();
        impact = 0;
        braked = false;
        // 烟花:候选里放,这一刻起推 burn 刻;已经在烧的接着烧
        int burning = action.keys().rocket() ? physics.burn() : supply.burning;
        for (int t = 0; t < hold; t++) {
            n = n.toward(action.yaw(), action.pitch());
            Step step = advance(m, n, action.keys().burning(burning-- > 0), down);
            if (step == null || sweepBlocked(m.pos(), step.motion().pos())) {
                return Double.NEGATIVE_INFINITY;
            }
            m = step.motion();
            if (step.touched()) {
                return score(m, way, s, action, 0);
            }
        }
        boolean landing = down != null && m.pos().y >= down.y && Math.hypot(m.pos().x - down.x, m.pos().z - down.z) < DESCEND;
        Motion tail = coast(m, n, physics.coast(action, landing), down, Math.max(0, burning));
        double penalty = 0;
        if (tail == null) {
            tail = coast(m, n, physics.brake(action), down, Math.max(0, burning));
            if (tail == null) {
                return Double.NEGATIVE_INFINITY;
            }
            penalty = BRAKING;
            braked = true;
        }
        return score(tail, way, s, action, penalty);
    }

    /** 烟花的存量:还能放几支,和正烧着的这一支还推几刻。 */
    record Supply(int rockets, int burning) {
        static final Supply NONE = new Supply(0, 0);
    }

    /** 一刻的结果:身体的新状态,和这一刻有没有落到降落点的地面上。 */
    private record Step(Motion motion, boolean touched) {}

    private Step advance(Motion m, Neck n, Keys keys, Vec3 down) {
        Motion next = physics.step(m, keys.facing(n.yaw(), n.pitch()));
        if (down != null && m.pos().y >= down.y && next.pos().y < down.y
                && Math.hypot(next.pos().x - down.x, next.pos().z - down.z) <= TOUCHDOWN) {
            // 落到降落点的地面上:原版落地就停飞(滑翔也一样),身体在地上再滑一小段;落得重要扣分(伤身),别无选择时伤害最小的那一组胜出
            impact = physics.impact(next);
            Vec3 on = new Vec3(next.pos().x, down.y, next.pos().z);
            return new Step(new Motion(on.add(next.velocity().x * SLIDE, 0, next.velocity().z * SLIDE), Vec3.ZERO), true);
        }
        return new Step(next, false);
    }

    /** 从 {@code m} 起一直按 {@code doing} 往后滑到停住或推演够长;沿途碰了方块为 null。 */
    private Motion coast(Motion from, Neck neck, Action doing, Vec3 down, int burning) {
        Motion m = from;
        Neck n = neck;
        Vec3 mark = m.pos();
        for (int t = 0; t < physics.horizon(); t++) {
            if (physics.rests(m)) {
                break;
            }
            n = n.toward(doing.yaw(), doing.pitch());
            Step step = advance(m, n, physics.tail(doing, m).burning(burning-- > 0), down);
            if (step == null || t < WATCH && sweepBlocked(m.pos(), step.motion().pos())) {
                return null;
            }
            if (t == WATCH) {
                mark = m.pos();
            }
            m = step.motion();
            if (step.touched()) {
                break;
            }
        }
        return sweepBlocked(mark, m.pos()) ? null : m;
    }

    private double score(Motion end, Airway way, double s, Action action, double penalty) {
        double length = way.length();
        if (length - s < NEAR) {
            // 收处就在眼前:滑停的点离收处那一点多远,就是全部的分——悬停与落点要停在一个点上,沿线与离线不再分开算
            return -NEAR_WEIGHT * end.pos().distanceToSqr(way.end()) - physics.cost(action) - penalty - impact;
        }
        double along = way.project(end.pos(), s);
        double offset = way.offset(end.pos(), along);
        double reach = along <= length ? along : length - OVERSHOOT * (along - length);
        return reach - LATERAL * offset * offset - physics.cost(action) - penalty - impact;
    }

    /** 松开键滑出去会撞、要靠刹车才停得住的那一组,扣多少分(弧长的格数):能选别的就别选它。 */
    private static final double BRAKING = 6.0;

    /** 离降落点水平不到这么远,滑的那一程一直往下落到地面。 */
    private static final double DESCEND = 2.5;
    /** 与降落点水平离这么近,脚落到地面就算落在了降落点上。 */
    private static final double TOUCHDOWN = 1.0;
    /** 落地之后水平速度每刻乘地上的阻力,滑出去的总距离是速度的这么多倍(几何级数 1 / (1 − 0.546))。 */
    private static final double SLIDE = 2.2;

    private boolean sweepBlocked(Vec3 from, Vec3 to) {
        BlockPos hit = sweep.blocker(from, to);
        if (hit != null) {
            blocker = hit;
        }
        return hit != null;
    }
}
