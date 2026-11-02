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

    /** 身体已经停住(往后推演没有意义了)。 */
    boolean rests(Motion state);

    /** 往后推演最多几刻。 */
    int horizon();

    /** 正在按的 {@code held} 是不是一旦按下就得按到底(推刹的承诺锁存):不到这个地步才允许换。 */
    boolean committed(Action held, Motion state);

    /** 这一组候选本身的代价(弧长的格数):滑翔里放一支烟花是花钱的。 */
    double cost(Action action);

    /** 以 {@code state} 这样落到地面,身体不伤(原版滑翔里竖直速度高于每刻 -0.5 时坠落距离每刻重置,不掉血)。 */
    boolean softLanding(Motion state);

    /** 看向前方的点时俯仰打的折扣。 */
    float LOOK_SHARE = 0.5F;
    /** 后退刹车按到水平速度降到这么小才松开({@link #committed})。 */
    double BRAKING = 0.15;
    /** 推演里刹车一程,水平速度降到这么小就松开后退键。 */
    double BRAKED = 0.01;

    /** 两组候选差几个键(前后、疾跑、跳、潜行、烟花各算一个):换键的代价按它算,不在相邻两组之间来回抖。 */
    static int difference(Action a, Action b) {
        Keys x = a.keys();
        Keys y = b.keys();
        return (x.forward() != y.forward() ? 1 : 0) + (x.sprint() != y.sprint() ? 1 : 0) + (x.jump() != y.jump() ? 1 : 0)
                + (x.sneak() != y.sneak() ? 1 : 0) + (x.rocket() != y.rocket() ? 1 : 0);
    }

    /** 创造飞行:前后(疾飞、平飞、松开、后退)乘上跳、松开、潜行,朝着路线飞。松开键飞着就慢慢停下,所以滑出去的后半程是松开。 */
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
                int[][] drive = {{0, 0}, {1, 0}, {1, 1}, {-1, 0}};
                List<Action> all = new ArrayList<>();
                for (int lift : new int[] {0, 1, -1}) {
                    for (int[] d : drive) {
                        all.add(new Action(new Keys(d[0], 0, lift > 0, lift < 0, d[1] > 0, yaw, pitch, false), yaw, pitch));
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
            public boolean committed(Action held, Motion state) {
                return held.keys().forward() < 0 && Math.hypot(state.velocity().x, state.velocity().z) > BRAKING;
            }

            @Override
            public double cost(Action action) {
                return 0;
            }

            @Override
            public boolean softLanding(Motion state) {
                return true;
            }
        };
    }
}
