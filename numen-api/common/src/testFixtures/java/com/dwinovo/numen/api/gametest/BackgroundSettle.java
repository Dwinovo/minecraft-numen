package com.dwinovo.numen.api.gametest;

import com.dwinovo.numen.api.task.CompanionTickDispatcher;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 测试服务器每一刻开头先等后台工作告一段落再往下走。
 *
 * <p>结果本身是确定的(寻路的预算是节点数,程序的每一步由服务端的调用决定),什么时候到却取决于后台线程:寻路在线程池里算,
 * 程序在自己的虚拟线程上跑,而 GameTest 的时限按刻数算、测试服务器又尽可能快地推进刻,机器一忙时限就先到了。每刻开头等
 * 到这些都停下,一件活要几刻就是几刻,和机器快慢无关。
 *
 * <p>等两处:每刻开头({@link CompanionTickDispatcher#beforeTick},三个平台的测试服务器都走那一处),和每刻收尾、服务端执行别的线程
 * 排给它的任务之前({@link #afterTick}:模组加载器经 {@code GameTestServerTickMixin},Paper 经测试插件的刻尾事件)。这一刻里起的
 * 后台活,做完之后排给主线程的回调(签好的皮肤、MCP 请求要主线程做的那一步)就总在这一刻的收尾执行,不看后台线程赶没赶上。
 *
 * <p>等的时候主线程照常执行排给它的任务:后台那一头可能正等着主线程替它做一步(MCP 的请求列工具、读背包),不执行就互相等死。
 *
 * <p>等待有上限({@link #LIMIT_SECONDS}):到了还有没停下的,说明那一头卡住了,不是慢,抛出并点名是谁,服务器带着这句话停。
 */
public final class BackgroundSettle {

    private static final Logger LOG = LogUtils.getLogger();
    /** 等多久算卡住:远大于任何正常的一刻。 */
    static final long LIMIT_SECONDS = 60;

    private BackgroundSettle() {}

    /** 登记了的后台来源:每一项答"此刻还没停下的是什么",停下了是 {@code null}。 */
    private static final List<Supplier<String>> SOURCES = new CopyOnWriteArrayList<>();

    /** @param sources 每一项答"此刻还没停下的是什么",停下了是 {@code null} */
    static void install(List<Supplier<String>> sources) {
        SOURCES.addAll(sources);
        CompanionTickDispatcher.beforeTick(BackgroundSettle::settle);
    }

    /** 每刻收尾、服务端执行排给它的任务之前调:等这一刻里起的后台活做完。 */
    public static void afterTick(MinecraftServer server) {
        settle(server);
    }

    /**
     * 等的时候用原版"主线程等一件事"的写法({@code managedBlock}):排给主线程的任务不论这一刻还剩不剩时间都执行。直接调
     * {@code pollTask} 不行——原版只在这一刻还没超时、或任务已经排了三刻以上时才执行它,这一刻一超时,后台等主线程、主线程等后台,
     * 直到后台那一头自己的时限到了才散。
     */
    private static void settle(MinecraftServer server) {
        if (firstUnsettled() == null) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LIMIT_SECONDS);
        // 停下来的判断与报错用同一次读数:读完之后后台又动了是下一次等的事,不是卡住
        String[] unsettled = new String[1];
        server.managedBlock(() -> (unsettled[0] = firstUnsettled()) == null || System.nanoTime() > deadline);
        if (unsettled[0] != null) {
            String why = "background work did not settle within " + LIMIT_SECONDS + " s: " + unsettled[0];
            LOG.error("[numen-gametest] {}", why);
            throw new IllegalStateException(why);
        }
    }

    private static String firstUnsettled() {
        for (Supplier<String> source : SOURCES) {
            String unsettled = source.get();
            if (unsettled != null) {
                return unsettled;
            }
        }
        return null;
    }
}
