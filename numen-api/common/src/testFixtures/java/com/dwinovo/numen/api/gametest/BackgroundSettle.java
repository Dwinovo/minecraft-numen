package com.dwinovo.numen.api.gametest;

import com.dwinovo.numen.api.task.CompanionTickDispatcher;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * 测试服务器每一刻开头先等后台工作告一段落再往下走。
 *
 * <p>结果本身是确定的(寻路的预算是节点数,程序的每一步由服务端的调用决定),什么时候到却取决于后台线程:寻路在线程池里算,
 * 程序在自己的虚拟线程上跑,而 GameTest 的时限按刻数算、测试服务器又尽可能快地推进刻,机器一忙时限就先到了。每刻开头等
 * 到这些都停下,一件活要几刻就是几刻,和机器快慢无关。
 *
 * <p>等待有上限({@link #LIMIT_SECONDS}):到了还有没停下的,说明那一头卡住了,不是慢,抛出并点名是谁,服务器带着这句话停。
 * 挂在每刻开头({@link CompanionTickDispatcher#beforeTick}),三个平台的测试服务器都走那一处。
 */
public final class BackgroundSettle {

    private static final Logger LOG = LogUtils.getLogger();
    /** 等多久算卡住:远大于任何正常的一刻。 */
    static final long LIMIT_SECONDS = 60;
    private static final long POLL_NANOS = TimeUnit.MICROSECONDS.toNanos(200);

    /** 用例故意压着后台不放的期限(纳秒钟);0 是没有。 */
    private static volatile long heldUntil;

    private BackgroundSettle() {}

    /**
     * 用例故意让后台工作停着不动(例如把寻路线程池占满、等某个时机再放),它自己管那段时间的先后,这段时间里每刻开头不等。
     * 最多这么久,用例没来得及 {@link #release} 就失败了也不会一直不等。
     */
    public static void hold(long seconds) {
        heldUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    }

    /** 故意压着的后台放行了,每刻开头照常等。 */
    public static void release() {
        heldUntil = 0;
    }

    /** @param sources 每一项答"此刻还没停下的是什么",停下了是 {@code null} */
    static void install(List<Supplier<String>> sources) {
        CompanionTickDispatcher.beforeTick(server -> settle(sources));
    }

    private static void settle(List<Supplier<String>> sources) {
        if (System.nanoTime() < heldUntil) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LIMIT_SECONDS);
        for (String unsettled = firstUnsettled(sources); unsettled != null; unsettled = firstUnsettled(sources)) {
            if (System.nanoTime() > deadline) {
                String why = "background work did not settle within " + LIMIT_SECONDS + " s: " + unsettled;
                LOG.error("[numen-gametest] {}", why);
                throw new IllegalStateException(why);
            }
            LockSupport.parkNanos(POLL_NANOS);
        }
    }

    private static String firstUnsettled(List<Supplier<String>> sources) {
        for (Supplier<String> source : sources) {
            String unsettled = source.get();
            if (unsettled != null) {
                return unsettled;
            }
        }
        return null;
    }
}
