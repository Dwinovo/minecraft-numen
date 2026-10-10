package com.dwinovo.numen.pathing.drive;

import net.minecraft.world.phys.Vec3;

/**
 * 卡没卡住,全模块只在这里判。两件事,量尺都是游戏刻,不随机器快慢与刻速率变:
 * <ul>
 *   <li><b>一步超时</b>:一步开始时按它几何上的最短耗时(运动学算的走法耗时 {@link com.dwinovo.numen.pathing.world.Kinematics},
 *       加上挖掘的刻数)给一个期限——最短耗时的两倍再加三秒;超过了这一步就算卡住。倍数的依据:实际走起来比几何最短慢在起步加速、
 *       转身、等落地、爬坡跳的时机,靶场量到各走法实际是最短耗时的 1 到 2 倍出头,两倍留足余量;三秒是起步与被推一下的绝对余量。
 *       慢慢挖一块硬方块,期限跟着挖掘的刻数长,不会被误判;</li>
 *   <li><b>在推进</b>:身体挪动过、或这一刻真的干了活(挖掘有进度、门开了、块放下了)、或在等规划的结论(有界,总会回来),
 *       就算在推进。连续一秒没有,就不在推进。这是对外交出的信号,宿主的脱困反射读的就是它。</li>
 * </ul>
 */
public final class Watchdog {

    /** 期限:最短耗时的倍数与另加的刻数。 */
    private static final double SLACK_FACTOR = 2.0;
    private static final int SLACK_TICKS = 60;
    /** 连续这么多刻没推进,就不算在推进。 */
    private static final int STALL = 20;
    /** 身体离上一个推进点这么远,就算挪动过。 */
    private static final double MOVED = 0.1;

    private int stepTicks;
    private double allowance = Double.POSITIVE_INFINITY;
    private Vec3 anchor;
    private int idle;

    /** 一步开始:按它的最短耗时 {@code minimumTicks} 定期限。 */
    void begin(double minimumTicks, Vec3 body) {
        stepTicks = 0;
        allowance = minimumTicks * SLACK_FACTOR + SLACK_TICKS;
        anchor = body;
    }

    /** 这一刻身体在 {@code body};{@code worked} 是这一刻真的干了活。 */
    void observe(Vec3 body, boolean worked) {
        stepTicks++;
        if (anchor == null || worked || body.distanceToSqr(anchor) > MOVED * MOVED) {
            anchor = body;
            idle = 0;
        } else {
            idle++;
        }
    }

    /** 这一刻在等规划的结论:算在推进,不占这一步的期限。 */
    void waiting(Vec3 body) {
        anchor = body;
        idle = 0;
    }

    /** 这一步超过了期限。 */
    boolean overran() {
        return stepTicks > allowance;
    }

    /** 这一步做了几刻。 */
    int stepTicks() {
        return stepTicks;
    }

    /** 这一步的期限(刻)。 */
    double allowance() {
        return allowance;
    }

    /** 在推进。 */
    public boolean progressing() {
        return idle < STALL;
    }
}
