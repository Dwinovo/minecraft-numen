package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.pathing.world.Kinematics;
import com.dwinovo.numen.pathing.world.Kinematics.Keys;
import com.dwinovo.numen.pathing.world.Kinematics.Motion;

/**
 * 一种空中移动方式的物理与它能按的键:控制器({@link Pilot})对所有方式都是同一个,只有这两样随方式换。
 * 物理是 {@link Kinematics} 里对应的逐刻步进({@link #flying}:{@link Kinematics#fly};滑翔与烟花:{@link Kinematics#glide}),
 * 键集是这种方式下玩家会去按的几组键。
 */
interface Dynamics {

    /**
     * 一组候选:这一刻按的键,和要把视角转到的方向({@code yaw}、{@code pitch},度)。视角不是一刻就到位的({@link Pilot#TURN}),
     * 键里的朝向由推演按转头的速度逐刻填。
     */
    record Action(Keys keys, float yaw, float pitch) {}

    /** 走一刻。 */
    Motion step(Motion state, Keys keys);

    /** 这一刻能挑的几组候选;{@code yaw}、{@code pitch} 是路线上要朝的方向。排在前面的在分数相同时优先。 */
    List<Action> actions(float yaw, float pitch);

    /** {@code last} 按过之后接着怎么按:推演的后半程替身体往后"滑"。{@code descend}:降落点就在身边,滑的时候一直往下落到地面。 */
    Action coast(Action last, boolean descend);

    /** {@code last} 按过之后拼命刹:松开键滑出去会撞时,推演换这一程看刹不刹得住(创造飞行是按后退,滑翔是拉起)。 */
    Action brake(Action last);

    /** 往后推演的某一刻真正按的键:{@code doing} 是这一程的候选,{@code state} 是此刻的状态(刹车一程按到停住就松开,不倒着飞走)。 */
    Keys tail(Action doing, Motion state);

    /** 飞着能随时停住(创造飞行)。滑翔停不下来:一组候选都过不去时,要在撞上之前尽量拖久、落得轻({@link Pilot#survive})。 */
    boolean stoppable();

    /** 身体已经停住(往后推演没有意义了)。 */
    boolean rests(Motion state);

    /** 往后推演最多几刻。 */
    int horizon();

    /** 正在按的 {@code held} 是不是一旦按下就得按到底(推刹的承诺锁存):不到这个地步才允许换。 */
    boolean committed(Action held, Motion state);

    /** 一支烟花推多少刻(保底,{@link com.dwinovo.numen.pathing.plan.Mobility#rocketBurn}),候选里放烟花时推演按这么多刻算;不用烟花的方式为 0。 */
    int burn();

    /** 从正在按的 {@code held} 换成 {@code next} 的代价(弧长的格数):不在相邻两组之间来回抖。 */
    double change(Action held, Action next);

    /** 推演滑到头的状态本身的代价(弧长的格数):滑翔慢到快失速(像降落伞一样飘)的状态,离落地和转向都远,扣分。 */
    double endCost(Motion end);

    /** 这一组候选本身的代价(弧长的格数):滑翔里放一支烟花是花钱的。 */
    double cost(Action action);

    /** 以 {@code state} 这样落到地面伤身的扣分(弧长的格数,不伤为 0):原版滑翔里竖直速度高于每刻 -0.5 时坠落距离每刻重置,不掉血,再快就掉。 */
    double impact(Motion state);

    /** 每换一个键扣多少分(弧长的格数)。 */
    double KEY_CHANGE = 0.1;
    /** 滑翔里俯仰目标、偏航目标每差一度扣多少分。 */
    double PITCH_CHANGE = 0.02;
    double YAW_CHANGE = 0.01;
    /** 滑翔的水平速度低于它就算快失速,每低一格每刻扣多少分。 */
    double STALL_SPEED = 0.6;
    double STALL_COST = 40.0;
    /** 放一支烟花扣多少分(弧长的格数):烟花是花钱的,只有真有好处才放。 */
    double ROCKET_COST = 4.0;
    /** 看向前方的点时俯仰打的折扣。 */
    float LOOK_SHARE = 0.5F;
    /** 后退刹车按到水平速度降到这么小才松开({@link #committed})。 */
    double BRAKING = 0.15;
    /** 推演里刹车一程,水平速度降到这么小就松开后退键。 */
    double BRAKED = 0.01;

    /** 两组候选差几个键(前后、疾跑、跳、潜行、烟花各算一个)。 */
    static int difference(Action a, Action b) {
        Keys x = a.keys();
        Keys y = b.keys();
        return (x.forward() != y.forward() ? 1 : 0) + (x.left() != y.left() ? 1 : 0) + (x.sprint() != y.sprint() ? 1 : 0) + (x.jump() != y.jump() ? 1 : 0)
                + (x.sneak() != y.sneak() ? 1 : 0) + (x.rocket() != y.rocket() ? 1 : 0);
    }

    /** 创造飞行:前后(疾飞、平飞、松开、后退)与左右平移乘上跳、松开、潜行,朝着路线飞。松开键飞着就慢慢停下,所以滑出去的后半程是松开。 */
    static Dynamics flying(float flyingSpeed) {
        return new Dynamics() {
            @Override
            public Motion step(Motion state, Keys keys) {
                return Kinematics.fly(state, keys, flyingSpeed);
            }

            @Override
            public List<Action> actions(float yaw, float pitch) {
                // 创造飞行里俯仰不进物理,只决定看哪儿:朝前方的点看,幅度打折,陡爬时不直视天空
                pitch *= LOOK_SHARE;
                // 前后(疾飞、平飞、后退)、松开,和左右平移:贴着目标做细调时不必先把脸转过去
                int[][] drive = {{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
                List<Action> all = new ArrayList<>();
                for (int lift : new int[] {0, 1, -1}) {
                    for (int[] d : drive) {
                        all.add(new Action(new Keys(d[0], d[2], lift > 0, lift < 0, d[1] > 0, yaw, pitch, false), yaw, pitch));
                    }
                }
                return all;
            }

            @Override
            public Action coast(Action last, boolean descend) {
                Keys keys = new Keys(0, 0, false, descend, false, last.yaw(), last.pitch(), false);
                return new Action(keys, last.yaw(), last.pitch());
            }

            @Override
            public Keys tail(Action doing, Motion state) {
                Keys keys = doing.keys();
                if (keys.forward() < 0 && Math.hypot(state.velocity().x, state.velocity().z) < BRAKED) {
                    return new Keys(0, 0, keys.jump(), keys.sneak(), false, keys.yaw(), keys.pitch(), false);
                }
                return keys;
            }

            @Override
            public Action brake(Action last) {
                Keys back = new Keys(-1, 0, false, false, false, last.yaw(), last.pitch(), false);
                return new Action(back, last.yaw(), last.pitch());
            }

            @Override
            public boolean rests(Motion state) {
                return state.velocity().lengthSqr() < Kinematics.REST * Kinematics.REST;
            }

            @Override
            public int horizon() {
                return 70;
            }

            @Override
            public boolean stoppable() {
                return true;
            }

            @Override
            public boolean committed(Action held, Motion state) {
                return held.keys().forward() < 0 && Math.hypot(state.velocity().x, state.velocity().z) > BRAKING;
            }

            @Override
            public int burn() {
                return 0;
            }

            @Override
            public double change(Action held, Action next) {
                return KEY_CHANGE * difference(held, next);
            }

            @Override
            public double endCost(Motion end) {
                return 0;
            }

            @Override
            public double cost(Action action) {
                return 0;
            }

            @Override
            public double impact(Motion state) {
                return 0;
            }
        };
    }

    /**
     * 鞘翅滑翔加烟花:物理是 {@link Kinematics#glide}(有烟花推着的刻,推力在滑翔之后加上)。俯仰是操纵杆,候选取"目标俯仰"(经颈部逐刻逼近,
     * 不在离散的俯仰值之间直接跳)乘上偏航的几个小偏移,放不放烟花是另一维。滑翔停不下来,往后"滑"就是照样滑翔(不再放烟花);刹不住时的补救是拉起。
     *
     * @param gravity 身体的重力
     * @param burn    一支烟花保底推多少刻
     */
    static Dynamics gliding(double gravity, int burn) {
        return new Dynamics() {
            private final float[] pitches = {COLUMN_PITCH, -45, -25, -8, 10, 35};
            private final float[] yaws = {0, -25, 25, -50, 50};

            @Override
            public Motion step(Motion state, Keys keys) {
                return Kinematics.glide(state, keys, gravity);
            }

            @Override
            public List<Action> actions(float yaw, float pitch) {
                List<Action> all = new ArrayList<>();
                for (boolean fire : new boolean[] {false, true}) {
                    for (float turn : yaws) {
                        for (float target : pitches) {
                            all.add(new Action(new Keys(0, 0, false, false, false, yaw + turn, target, fire), yaw + turn, target));
                        }
                    }
                }
                return all;
            }

            @Override
            public Action coast(Action last, boolean descend) {
                return new Action(last.keys().burning(false), last.yaw(), last.pitch());
            }

            @Override
            public Action brake(Action last) {
                // 刹不住的补救:拉起来换成高度
                return new Action(last.keys().burning(false), last.yaw(), PULL_UP);
            }

            @Override
            public Keys tail(Action doing, Motion state) {
                return doing.keys().burning(false);
            }

            @Override
            public boolean rests(Motion state) {
                return false;
            }

            @Override
            public int horizon() {
                return 40;
            }

            @Override
            public boolean stoppable() {
                return false;
            }

            @Override
            public int burn() {
                return burn;
            }

            @Override
            public double change(Action held, Action next) {
                return KEY_CHANGE * (held.keys().rocket() != next.keys().rocket() ? 1 : 0)
                        + PITCH_CHANGE * Math.abs(held.pitch() - next.pitch()) + YAW_CHANGE * Math.abs(held.yaw() - next.yaw());
            }

            @Override
            public boolean committed(Action held, Motion state) {
                return false;
            }

            @Override
            public double endCost(Motion end) {
                return Math.max(0, STALL_SPEED - Math.hypot(end.velocity().x, end.velocity().z)) * STALL_COST;
            }

            @Override
            public double cost(Action action) {
                return action.keys().rocket() ? ROCKET_COST : 0;
            }

            @Override
            public double impact(Motion state) {
                return Math.max(0, SOFT_LANDING - state.velocity().y) * IMPACT_COST;
            }
        };
    }

    /** 起飞的空柱里爬升用的目标俯仰(纯竖直):柱口径只有两格,斜着爬要偏出柱子。只在爬升段里挑({@link Pilot#choose}),飞起来之后不挑。 */
    float COLUMN_PITCH = -90.0F;
    /** 拉起时的目标俯仰。 */
    float PULL_UP = -35.0F;
    /** 落地时竖直速度每比 {@link #SOFT_LANDING} 快一格每刻,扣多少分。 */
    double IMPACT_COST = 3000.0;
    /** 落地时竖直速度高于它,原版滑翔里坠落距离每刻重置,不掉血。 */
    double SOFT_LANDING = -0.5;
}
