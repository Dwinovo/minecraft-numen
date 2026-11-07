package com.dwinovo.numen.api.gametest;

import com.mojang.logging.LogUtils;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.TestReporter;
import org.slf4j.Logger;

/**
 * 每条用例收场时记一行"实际用了多少刻 / 时限多少刻":定超时有据可依(超时收到实测的合理倍数),也看得出谁在逼近自己的时限。
 * 失败的另记一行场地起点与世界种子({@link GameTestOrigin}),照着开服就能原样重放。记完让这条用例收场({@link TestCleanup})。
 * 起始刻由 {@link GameTestSuites} 在函数体前记下(框架自己的计刻不对外),墙钟也在那一刻记下(原版的用例计时器从不启动,
 * {@code GameTestInfo.getRunTime} 总是 0);收场的记录再交给原来的汇报方式。
 */
final class TestTicks implements TestReporter {

    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<String, Start> STARTS = new ConcurrentHashMap<>();

    /** 用例开始的那一刻:游戏刻与墙钟(纳秒)。 */
    private record Start(long gameTime, long nanos) {}

    private final TestReporter delegate;

    TestTicks(TestReporter delegate) {
        this.delegate = delegate;
    }

    static void started(String test, long gameTime) {
        STARTS.put(test, new Start(gameTime, System.nanoTime()));
    }

    @Override
    public void onTestSuccess(GameTestInfo info) {
        record("passed", info);
        TestCleanup.finished(info);
        delegate.onTestSuccess(info);
    }

    @Override
    public void onTestFailed(GameTestInfo info) {
        record("failed", info);
        TestCleanup.finished(info);
        delegate.onTestFailed(info);
    }

    @Override
    public void finish() {
        delegate.finish();
    }

    private static void record(String outcome, GameTestInfo info) {
        Start start = STARTS.remove(NumenTestInstance.name(info));
        long ticks = start == null ? -1 : info.getLevel().getGameTime() - start.gameTime();
        long millis = start == null ? -1 : (System.nanoTime() - start.nanos()) / 1_000_000;
        LOG.info("[numen-gametest-ticks] {} {}/{} {}ms {}", outcome, ticks, info.getTimeoutTicks(), millis, NumenTestInstance.name(info));
        if (outcome.equals("failed")) {
            // 照着这两样(-PgametestOrigin、-PgametestSeed)开服就是原样的场地与世界
            LOG.info("[numen-gametest] {} failed at {}", NumenTestInstance.name(info),
                    GameTestOrigin.describe(info.getLevel().getSeed()));
        }
    }
}
