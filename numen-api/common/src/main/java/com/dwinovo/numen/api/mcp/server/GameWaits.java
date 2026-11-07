package com.dwinovo.numen.api.mcp.server;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * MCP 请求此刻停在"等游戏"上的那些:等她的事件(get_events 的长等待)、等程序收尾(跑她的工具)、等她的回话(渠道的 get_replies)。
 * 等的东西由游戏这一头交来,一到({@code arrived} 为真)这次请求就又在算了,不再算作等游戏。
 *
 * <p>只读查询 {@link #count} 回答"有几次请求在等游戏还没等到":请求在算(解析、去主线程办事、写回执)与在等游戏,对外面的人是两回事——
 * 前者几毫秒就完,后者要游戏往前走才完。
 *
 * <p>线程:任何线程。
 */
final class GameWaits {

    private static final Set<BooleanSupplier> PARKED = ConcurrentHashMap.newKeySet();

    private GameWaits() {}

    /**
     * 停下等游戏:{@code wait} 阻塞着等,{@code arrived} 回答等的东西到了没有(游戏那一头交来的那一刻就为真,不等这条线程醒来)。
     */
    static <T> T await(BooleanSupplier arrived, Callable<T> wait) throws Exception {
        // 每次一个新的对象:同一个判据并发等两次也各记一笔
        BooleanSupplier parked = arrived::getAsBoolean;
        PARKED.add(parked);
        try {
            return wait.call();
        } finally {
            PARKED.remove(parked);
        }
    }

    /** 端点关了:还停着的请求随它作废,不再算作在等游戏(它们的线程随后自己散)。 */
    static void clear() {
        PARKED.clear();
    }

    /** 此刻在等游戏、等的东西还没到的请求有几次。 */
    static int count() {
        int n = 0;
        for (BooleanSupplier parked : PARKED) {
            if (!parked.getAsBoolean()) {
                n++;
            }
        }
        return n;
    }
}
