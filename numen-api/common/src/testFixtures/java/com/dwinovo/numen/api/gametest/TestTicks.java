package com.dwinovo.numen.api.gametest;

import com.mojang.logging.LogUtils;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.TestReporter;
import org.slf4j.Logger;

/**
 * 每条用例收场时记一行"实际用了多少刻 / 时限多少刻":定超时有据可依(超时收到实测的合理倍数),也看得出谁在逼近自己的时限。
 * 起始刻由 {@link GameTestSuites} 在函数体前记下(框架自己的计刻不对外);收场的记录再交给原来的汇报方式。
 */
final class TestTicks implements TestReporter {

    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<String, Long> STARTS = new ConcurrentHashMap<>();

    private final TestReporter delegate;

    TestTicks(TestReporter delegate) {
        this.delegate = delegate;
    }

    static void started(String test, long gameTime) {
        STARTS.put(test, gameTime);
    }

    @Override
    public void onTestSuccess(GameTestInfo info) {
        record("passed", info);
        delegate.onTestSuccess(info);
    }

    @Override
    public void onTestFailed(GameTestInfo info) {
        record("failed", info);
        delegate.onTestFailed(info);
    }

    @Override
    public void finish() {
        delegate.finish();
    }

    private static void record(String outcome, GameTestInfo info) {
        Long start = STARTS.remove(info.getTestName());
        long ticks = start == null ? -1 : info.getLevel().getGameTime() - start;
        LOG.info("[numen-gametest-ticks] {} {}/{} {}ms {}", outcome, ticks, info.getTimeoutTicks(), info.getRunTime(), info.getTestName());
    }
}
